#!/usr/bin/env bash
# MedFlow rest-01-validation verification: Maven, PostgreSQL constraints,
# API behavior, and regression checks. Does not test the Vaadin frontend.
set -Eeuo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT"

API_BASE="${API_BASE:-}"
API_BASE="${API_BASE%/}"
RUN_MAVEN="${RUN_MAVEN:-1}"
CHECK_DB="${CHECK_DB:-1}"
DB_USER=meduser
DB_NAME=medflow_validation

if [[ "$CHECK_DB" == 1 && ( -n "$API_BASE" || "${START_DB:-1}" != 1 || "${START_API:-1}" != 1 ) ]]; then
  echo "Direct SQL checks require the script's own database and API. Use CHECK_DB=0 with API_BASE." >&2
  exit 1
fi

WORK_DIR="$(mktemp -d "${TMPDIR:-/tmp}/medflow-validation.XXXXXX")"
API_PID=""
DB_CONTAINER=""
cleanup() {
  local exit_code=$?
  trap - EXIT
  if [[ -n "$API_PID" ]]; then
    echo "Stopping API process started by this script (PID $API_PID)..."
    kill "$API_PID" 2>/dev/null || true
    wait "$API_PID" 2>/dev/null || true
  fi
  if [[ -n "$DB_CONTAINER" ]]; then
    docker stop "$DB_CONTAINER" >/dev/null || exit_code=1
  fi
  if (( exit_code != 0 )) && [[ -s "$WORK_DIR/api.log" ]]; then
    echo "--- Last 50 lines of API log ---" >&2
    tail -50 "$WORK_DIR/api.log" >&2
  fi
  rm -rf "$WORK_DIR"
  exit "$exit_code"
}
trap cleanup EXIT

need() { command -v "$1" >/dev/null 2>&1 || { echo "Missing command: $1" >&2; exit 1; }; }
need curl
need python3

if [[ ! -f api/pom.xml || ! -f pom.xml ]]; then
  echo "Put this script in the medflow-rest-demo repository root (beside pom.xml and api/)." >&2
  exit 1
fi

printf '\n== 1/5: Git working-tree sanity ==\n'
if command -v git >/dev/null 2>&1 && git rev-parse --is-inside-work-tree >/dev/null 2>&1; then
  git diff --check
  git diff --cached --check
  echo "Git diff whitespace checks: OK"
else
  echo "Git checkout not detected; skipped git diff --check."
fi

if [[ "$RUN_MAVEN" == 1 ]]; then
  printf '\n== 2/5: Full Maven build and automated test suite ==\n'
  if [[ -x ./mvnw ]]; then
    ./mvnw clean verify
  else
    need mvn
    mvn clean verify
  fi
else
  echo "== 2/5: Maven verification skipped (RUN_MAVEN=0) =="
fi

if [[ "$CHECK_DB" == 1 ]]; then
  printf '\n== 3/5: Start disposable PostgreSQL ==\n'
  need docker
  db_password="$(python3 -c 'import secrets; print(secrets.token_hex(16))')"
  DB_CONTAINER="$(docker run --rm -d -p 127.0.0.1::5432 \
    -e POSTGRES_USER="$DB_USER" -e POSTGRES_DB="$DB_NAME" \
    -e POSTGRES_PASSWORD="$db_password" postgres:16-alpine)"
  db_address="$(docker port "$DB_CONTAINER" 5432/tcp)"
  for (( i=0; i<30; i++ )); do
    if docker exec "$DB_CONTAINER" pg_isready -h 127.0.0.1 -U "$DB_USER" -d "$DB_NAME" >/dev/null; then
      break
    fi
    sleep 1
  done
  if (( i == 30 )); then
    echo "Disposable PostgreSQL did not become ready." >&2
    exit 1
  fi

  printf '\n== 4/5: Start API against disposable PostgreSQL ==\n'
  need java
  api_jar=(api/target/api-*.jar)
  if [[ "${#api_jar[@]}" != 1 || ! -f "${api_jar[0]}" ]]; then
    echo "Build the API JAR first or run with RUN_MAVEN=1." >&2
    exit 1
  fi
  SPRING_DATASOURCE_PASSWORD="$db_password" java -jar "${api_jar[0]}" \
    --server.address=127.0.0.1 --server.port=0 \
    --spring.datasource.url="jdbc:postgresql://$db_address/$DB_NAME" \
    --spring.datasource.username="$DB_USER" >"$WORK_DIR/api.log" 2>&1 &
  API_PID=$!
  for (( i=0; i<90; i++ )); do
    api_port="$(sed -nE 's/.*Tomcat started on port ([0-9]+).*/\1/p' "$WORK_DIR/api.log")"
    if [[ -n "$api_port" ]]; then
      API_BASE="http://127.0.0.1:$api_port"
      if curl -fsS --max-time 2 "$API_BASE/api/facilities" >/dev/null 2>&1; then
        break
      fi
    fi
    if ! kill -0 "$API_PID" 2>/dev/null; then
      echo "API process stopped before becoming ready." >&2
      exit 1
    fi
    sleep 2
  done
  if (( i == 90 )); then
    echo "API did not become ready." >&2
    exit 1
  fi

  if [[ "${VERIFY_VERSIONING:-0}" == 1 ]]; then
    API_BASE="$API_BASE" DB_CONTAINER="$DB_CONTAINER" DB_USER="$DB_USER" DB_NAME="$DB_NAME" \
      "$ROOT/scripts/verify-versioning.sh"
    exit
  fi

  printf '\n== 4b/5: Verify actual PostgreSQL constraints and booking counts ==\n'
  # SQL blocks below deliberately cannot persist changes: they test invalid INSERTs.
  docker exec -i "$DB_CONTAINER" psql -X -v ON_ERROR_STOP=1 -U "$DB_USER" -d "$DB_NAME" <<'SQL'
DO $verify$
DECLARE
  facility_ref bigint;
  specialty_ref bigint;
  unknown_facility bigint;
BEGIN
  SELECT id INTO facility_ref FROM facility ORDER BY id LIMIT 1;
  SELECT id INTO specialty_ref FROM specialty ORDER BY id LIMIT 1;
  SELECT COALESCE(MAX(id), 0) + 1000000 INTO unknown_facility FROM facility;
  IF facility_ref IS NULL OR specialty_ref IS NULL THEN
    RAISE EXCEPTION 'Fixture database has no facility/specialty reference data';
  END IF;

  BEGIN
    INSERT INTO appointment_slot (facility_id, specialty_id, starts_at, ends_at, capacity)
    VALUES (facility_ref, specialty_ref, now() + interval '10 days', now() + interval '11 days', 0);
    RAISE EXCEPTION 'FAIL: database accepted capacity 0';
  EXCEPTION WHEN check_violation THEN
    RAISE NOTICE 'PASS: database rejects zero capacity (CHECK)';
  END;

  BEGIN
    INSERT INTO appointment_slot (facility_id, specialty_id, starts_at, ends_at, capacity)
    VALUES (facility_ref, specialty_ref, now() + interval '11 days', now() + interval '10 days', 1);
    RAISE EXCEPTION 'FAIL: database accepted end time before start time';
  EXCEPTION WHEN check_violation THEN
    RAISE NOTICE 'PASS: database rejects reversed times (CHECK)';
  END;

  BEGIN
    INSERT INTO appointment_slot (facility_id, specialty_id, starts_at, ends_at, capacity)
    VALUES (unknown_facility, specialty_ref, now() + interval '10 days', now() + interval '11 days', 1);
    RAISE EXCEPTION 'FAIL: database accepted nonexistent facility';
  EXCEPTION WHEN foreign_key_violation THEN
    RAISE NOTICE 'PASS: database rejects unknown facility (FOREIGN KEY)';
  END;
END
$verify$;
SQL

  docker exec -i "$DB_CONTAINER" psql -X -v ON_ERROR_STOP=1 -U "$DB_USER" -d "$DB_NAME" -A -t -F '|' \
    -c "SELECT s.id, COUNT(b.id) FILTER (WHERE b.status::text IN ('RESERVED','CONFIRMED')) FROM appointment_slot s LEFT JOIN booking b ON b.appointment_slot_id=s.id GROUP BY s.id ORDER BY s.id;" \
    > "$WORK_DIR/booking_counts.txt"
else
  API_BASE="${API_BASE:-http://localhost:8080}"
  echo "Running HTTP checks against $API_BASE (CHECK_DB=0)."
  : > "$WORK_DIR/booking_counts.txt"
fi

printf '\n== 5/5: Live HTTP validation and behavior checks ==\n'
VERIFY_PROBLEMS="${VERIFY_PROBLEMS:-0}" API_BASE="$API_BASE" BOOKING_COUNTS_FILE="$WORK_DIR/booking_counts.txt" CHECK_DB="$CHECK_DB" DB_CONTAINER="$DB_CONTAINER" DB_USER="$DB_USER" DB_NAME="$DB_NAME" python3 - <<'PY'
import copy
import json
import os
import subprocess
import sys
import urllib.error
import urllib.parse
import urllib.request
from http import HTTPStatus
from datetime import datetime, timedelta, timezone

BASE = os.environ['API_BASE']
VERIFY_PROBLEMS = os.environ['VERIFY_PROBLEMS'] == '1'
NO_BODY = object()
checks = 0
failures = []
created_paths = []


def call(method, path, data=NO_BODY, accept=None, content_type=None):
    url = urllib.parse.urljoin(BASE + '/', path.lstrip('/'))
    if VERIFY_PROBLEMS:
        args = ['curl', '--silent', '--show-error', '--include', '--max-time', '20',
                '--request', method, '--header', f'Accept: {accept or "application/json, application/problem+json"}']
        if data is not NO_BODY:
            args += ['--header', f'Content-Type: {content_type or "application/json"}',
                     '--data-binary', data if isinstance(data, str) else json.dumps(data)]
        raw = subprocess.check_output([*args, url])
        head, body = raw.split(b'\r\n\r\n', 1)
        status_line, *header_lines = head.decode('iso-8859-1').splitlines()
        headers = dict(line.split(':', 1) for line in header_lines)
        return int(status_line.split()[1]), {key: value.strip() for key, value in headers.items()}, body.decode()
    headers = {'Accept': 'application/json'}
    payload = None
    if data is not NO_BODY:
        headers['Content-Type'] = 'application/json'
        payload = json.dumps(data).encode('utf-8')
    req = urllib.request.Request(url, data=payload, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=20) as response:
            return response.status, dict(response.headers.items()), response.read().decode('utf-8', 'replace')
    except urllib.error.HTTPError as exc:
        return exc.code, dict(exc.headers.items()), exc.read().decode('utf-8', 'replace')


def run_sql(sql):
    command = ['docker', 'exec', os.environ['DB_CONTAINER'], 'psql',
               '-X', '-v', 'ON_ERROR_STOP=1',
               '-U', os.environ['DB_USER'], '-d', os.environ['DB_NAME'],
               '-A', '-t', '-c', sql]
    done = subprocess.run(command, capture_output=True, text=True, timeout=30)
    if done.returncode != 0:
        raise RuntimeError(f'PostgreSQL command failed: {done.stderr.strip()}')
    return done.stdout


def parse_json(body):
    try:
        return json.loads(body)
    except json.JSONDecodeError as exc:
        raise AssertionError(f'Expected JSON; got {body[:160]!r}') from exc


def get_header(headers, name):
    return next((v for k, v in headers.items() if k.lower() == name.lower()), None)


def problem_fields(*fields):
    def verify(_, __, body):
        errors = parse_json(body).get('errors', [])
        found = {error['field'] for error in errors if error.get('message')}
        if not set(fields) <= found:
            raise AssertionError(f'Expected validation errors for {fields}, got {errors}')
    return verify


def test(label, method, path, expected, data=NO_BODY, verify=None, **request_options):
    global checks
    checks += 1
    try:
        status, headers, body = call(method, path, data, **request_options)
        # If an invalid POST was accidentally accepted, remember it for cleanup.
        if method == 'POST' and status == 201:
            loc = get_header(headers, 'Location')
            if loc:
                candidate = urllib.parse.urlparse(urllib.parse.urljoin(BASE, loc)).path
                if candidate.startswith('/api/slots/'):
                    created_paths.append(candidate)
            else:
                try:
                    candidate_id = int(parse_json(body)['id'])
                    created_paths.append(f'/api/slots/{candidate_id}')
                except (ValueError, TypeError, KeyError, AssertionError):
                    pass
        if status != expected:
            raise AssertionError(f'expected HTTP {expected}, got {status}; body={body[:350]!r}')
        if VERIFY_PROBLEMS:
            if 400 <= status < 600 and status != 406:
                media = (get_header(headers, 'Content-Type') or '').split(';')[0]
                if media != 'application/problem+json':
                    raise AssertionError(f'Expected application/problem+json, got {media!r}')
                problem = parse_json(body)
                expected_fields = dict(type='about:blank', title=HTTPStatus(status).phrase,
                                       status=status, instance=path.split('?')[0])
                if any(problem.get(key) != value for key, value in expected_fields.items()):
                    raise AssertionError(f'Expected {expected_fields}, got {problem}')
                if not isinstance(problem.get('detail'), str) or not problem['detail'].strip():
                    raise AssertionError('Expected a nonempty problem detail')
                if status == 405 and not {'GET', 'POST'} <= set(
                        (get_header(headers, 'Allow') or '').replace(' ', '').split(',')):
                    raise AssertionError('Allow header must include GET and POST')
            if status in (204, 406) and body:
                raise AssertionError(f'Expected an empty HTTP {status} response')
        if verify:
            verify(status, headers, body)
        print(f'PASS {label} -> HTTP {status}')
        return status, headers, body
    except Exception as exc:
        print(f'FAIL {label}: {exc}')
        failures.append((label, str(exc)))
        return None


def require(label, condition, message):
    global checks
    checks += 1
    if condition:
        print(f'PASS {label}')
    else:
        print(f'FAIL {label}: {message}')
        failures.append((label, message))


def required_list(path):
    status, _, body = call('GET', path)
    if status != 200:
        raise AssertionError(f'{path} returned HTTP {status}: {body[:300]}')
    value = parse_json(body)
    if not isinstance(value, list) or not value:
        raise AssertionError(f'{path} must return a non-empty JSON array for these tests')
    return value


def json_list_200(_, __, body):
    if not isinstance(parse_json(body), list):
        raise AssertionError('Expected JSON array')


def main():
    facilities = required_list('/api/facilities')
    specialties = required_list('/api/specialties')
    initial_slots = required_list('/api/slots')
    facility_id = int(facilities[0]['id'])
    specialty_id = int(specialties[0]['id'])
    missing_facility = max(int(f['id']) for f in facilities) + 1_000_000
    missing_specialty = max(int(s['id']) for s in specialties) + 1_000_000
    missing_slot = max(int(s['id']) for s in initial_slots) + 1_000_000

    test('GET existing slot', 'GET', f'/api/slots/{initial_slots[0]["id"]}', 200)
    test('GET facility filter', 'GET', f'/api/slots?facilityId={facility_id}', 200, verify=json_list_200)
    test('GET specialty filter', 'GET', f'/api/slots?specialtyId={specialty_id}', 200, verify=json_list_200)
    test('GET combined filter', 'GET', f'/api/slots?facilityId={facility_id}&specialtyId={specialty_id}', 200, verify=json_list_200)
    def empty_list(_, __, body):
        if parse_json(body) != []:
            raise AssertionError('Positive nonexistent filter should return []')
    test('GET nonexistent positive filter', 'GET', f'/api/slots?facilityId={missing_facility}', 200, verify=empty_list)

    now = datetime.now(timezone.utc).replace(microsecond=0)
    start = now + timedelta(days=14)
    end = start + timedelta(minutes=30)
    iso = lambda d: d.isoformat()
    valid = {'facilityId': facility_id, 'specialtyId': specialty_id,
             'startsAt': iso(start), 'endsAt': iso(end), 'capacity': 3}

    # Each invalid request changes exactly one concern whenever possible.
    cases = {
        'missing facilityId': {'facilityId': NO_BODY},
        'null facilityId': {'facilityId': None},
        'zero facilityId': {'facilityId': 0},
        'negative facilityId': {'facilityId': -1},
        'unknown facilityId (@ExistingFacility)': {'facilityId': missing_facility},
        'missing specialtyId': {'specialtyId': NO_BODY},
        'null specialtyId': {'specialtyId': None},
        'zero specialtyId': {'specialtyId': 0},
        'negative specialtyId': {'specialtyId': -1},
        'unknown specialtyId (@ExistingSpecialty)': {'specialtyId': missing_specialty},
        'missing startsAt': {'startsAt': NO_BODY},
        'null startsAt': {'startsAt': None},
        'past startsAt': {'startsAt': iso(now - timedelta(days=1))},
        'missing endsAt': {'endsAt': NO_BODY},
        'null endsAt': {'endsAt': None},
        'past endsAt': {'endsAt': iso(now - timedelta(days=1))},
        'equal start/end (@EndAfterStart)': {'endsAt': iso(start)},
        'reversed start/end (@EndAfterStart)': {'endsAt': iso(start - timedelta(minutes=30))},
        'missing capacity': {'capacity': NO_BODY},
        'null capacity': {'capacity': None},
        'zero capacity': {'capacity': 0},
        'negative capacity': {'capacity': -3},
        'invalid timestamp format': {'startsAt': 'not-a-timestamp'},
        'wrong JSON value type': {'capacity': 'abc'},
    }
    for label, edits in cases.items():
        payload = copy.deepcopy(valid)
        for key, value in edits.items():
            if value is NO_BODY:
                payload.pop(key, None)
            else:
                payload[key] = value
        check = problem_fields(*edits) if VERIFY_PROBLEMS and label not in (
            'invalid timestamp format', 'wrong JSON value type') else None
        test(f'POST invalid: {label}', 'POST', '/api/slots', 400, payload, verify=check)

    if VERIFY_PROBLEMS:
        test('Multiple validation errors', 'POST', '/api/slots', 400,
             {**valid, 'facilityId': 0, 'capacity': 0}, verify=problem_fields('facilityId', 'capacity'))
        test('Malformed JSON', 'POST', '/api/slots', 400, '{')
        test('Missing body', 'POST', '/api/slots', 400, '')
        test('Unsupported method and Allow header', 'PUT', '/api/slots', 405)
        test('Unsupported media type', 'POST', '/api/slots', 415, valid, content_type='text/plain')
        test('Unacceptable response media type', 'GET', '/api/slots', 406, accept='image/png')

    # Correct/incorrect comparisons when textual local times have different offsets.
    from datetime import timedelta as td
    start_offset = start.astimezone(timezone(td(hours=5)))
    end_offset = end.astimezone(timezone(td(hours=-5)))
    earlier_offset = (start - td(minutes=30)).astimezone(timezone(td(hours=-5)))
    test('POST reversed instants across offsets', 'POST', '/api/slots', 400,
         {**valid, 'startsAt': iso(start_offset), 'endsAt': iso(earlier_offset)})

    after_invalid = required_list('/api/slots')
    before_state = {int(s['id']): s for s in initial_slots}
    after_state = {int(s['id']): s for s in after_invalid}
    require('Invalid POSTs did not change existing slots', before_state == after_state,
            'Slot collection changed after rejected POST requests')

    for path, label in [('/api/slots/0', 'zero path ID'),
                        ('/api/slots/-1', 'negative path ID'),
                        ('/api/slots/abc', 'nonnumeric path ID'),
                        ('/api/slots?facilityId=0', 'zero facility filter'),
                        ('/api/slots?specialtyId=-1', 'negative specialty filter'),
                        ('/api/slots?facilityId=abc', 'nonnumeric facility filter')]:
        field = path.split('?')[1].split('=')[0] if '?' in path else 'id'
        check = problem_fields(field) if VERIFY_PROBLEMS and 'nonnumeric' not in label else None
        test(f'GET invalid: {label}', 'GET', path, 400, verify=check)
    test('GET positive nonexistent slot', 'GET', f'/api/slots/{missing_slot}', 404)
    test('PATCH invalid path ID', 'PATCH', '/api/slots/0', 400, {'capacity': 3})
    test('DELETE invalid path ID', 'DELETE', '/api/slots/-1', 400)

    # Availability checks against real booking rows, when DB verification is enabled.
    db_counts = {}
    with open(os.environ['BOOKING_COUNTS_FILE'], encoding='utf-8') as stream:
        for line in stream:
            if not line.strip():
                continue
            slot_id, count = line.strip().split('|')
            db_counts[int(slot_id)] = int(count)
    for s in initial_slots:
        sid = int(s['id'])
        if 'activeBookings' not in s or 'remainingCapacity' not in s:
            require(f'Availability properties for slot {sid}', False,
                    'Response missing activeBookings or remainingCapacity')
            continue
        active = int(s['activeBookings'])
        capacity = int(s['capacity'])
        remaining = int(s['remainingCapacity'])
        require(f'Remaining capacity for slot {sid}', remaining == capacity - active,
                f'capacity={capacity}, activeBookings={active}, remainingCapacity={remaining}')
        if db_counts:
            require(f'Booking count matches PostgreSQL for slot {sid}', active == db_counts.get(sid),
                    f'API={active}, DB={db_counts.get(sid)}')

    # Use a temporary, disposable booked slot for destructive conflict tests.
    # Never attempt to delete the repository's seeded booked appointments.
    if os.environ['CHECK_DB'] == '1':
        def confirm_fixture(_, __, body):
            if not isinstance(parse_json(body).get('id'), int):
                raise AssertionError('Temporary slot has no numeric ID')
        temp_created = test('POST temporary slot for business-rule tests', 'POST',
                            '/api/slots', 201, valid, confirm_fixture)
        if temp_created:
            temp_id = int(parse_json(temp_created[2])['id'])
            temp_path = f'/api/slots/{temp_id}'
            try:
                run_sql(f"INSERT INTO booking (appointment_slot_id, status) VALUES "
                        f"({temp_id}, 'RESERVED'), ({temp_id}, 'CONFIRMED'), "
                        f"({temp_id}, 'CANCELLED'), ({temp_id}, 'NO_SHOW');")
                def assert_two_active(_, __, body):
                    slot = parse_json(body)
                    if int(slot['activeBookings']) != 2 or int(slot['remainingCapacity']) != 1:
                        raise AssertionError(f'Expected 2 active and 1 remaining; got {slot}')
                test('Reserved+confirmed count; cancelled+no-show excluded',
                     'GET', temp_path, 200, verify=assert_two_active)
                test('PATCH below booked count (409)', 'PATCH', temp_path, 409,
                     {'capacity': 1})
                test('409 PATCH did not mutate temporary slot', 'GET', temp_path, 200,
                     verify=lambda _, __, b: require('Capacity stays 3 after conflict',
                             int(parse_json(b)['capacity']) == 3, 'capacity changed'))
                test('DELETE booked temporary slot (409)', 'DELETE', temp_path, 409)
                test('409 DELETE leaves temporary slot intact', 'GET', temp_path, 200)

                # Leave CANCELLED and NO_SHOW in place to prove neither blocks deletion.
                run_sql(f"DELETE FROM booking WHERE appointment_slot_id = {temp_id} "
                        "AND status::text IN ('RESERVED', 'CONFIRMED');")
                def assert_zero_active(_, __, body):
                    slot = parse_json(body)
                    if int(slot['activeBookings']) != 0 or int(slot['remainingCapacity']) != 3:
                        raise AssertionError(f'Inactive-only bookings should leave 3 places: {slot}')
                test('Cancelled+no-show bookings do not count', 'GET', temp_path,
                     200, verify=assert_zero_active)
                deleted_temp = test('DELETE slot with inactive-only bookings (204)',
                                    'DELETE', temp_path, 204)
                if deleted_temp and temp_path in created_paths:
                    created_paths.remove(temp_path)
            except Exception as exc:
                failures.append(('Temporary booking fixture', str(exc)))
                print(f'FAIL Temporary booking fixture: {exc}')
    else:
        print('SKIP direct-DB booking fixture tests (CHECK_DB=0); these require PostgreSQL access.')

    # Valid create -> read -> update -> delete -> missing-resource behavior.
    def assert_created(_, headers, body):
        if not get_header(headers, 'Location'):
            raise AssertionError('201 response lacks Location header')
        if not isinstance(parse_json(body).get('id'), int):
            raise AssertionError('201 response lacks numeric JSON id')
    created = test('POST valid slot', 'POST', '/api/slots', 201, valid, assert_created)
    if created:
        _, headers, body = created
        slot_id = int(parse_json(body)['id'])
        path = f'/api/slots/{slot_id}'
        location = get_header(headers, 'Location')
        resolved_path = urllib.parse.urlparse(urllib.parse.urljoin(BASE, location)).path
        require('POST Location identifies created slot', resolved_path == path,
                f'Location={location!r}, expected path={path}')
        if path not in created_paths:
            created_paths.append(path)

        test('GET created slot', 'GET', path, 200)
        for label, payload in [('zero PATCH capacity', {'capacity': 0}),
                               ('negative PATCH capacity', {'capacity': -2}),
                               ('null PATCH capacity', {'capacity': None}),
                               ('missing PATCH capacity', {}),
                               ('wrong-type PATCH capacity', {'capacity': 'abc'})]:
            test(label, 'PATCH', path, 400, payload)
        _, _, state_body = call('GET', path)
        require('Invalid PATCHes leave capacity unchanged',
                int(parse_json(state_body)['capacity']) == 3,
                'Invalid PATCH changed the created slot capacity')
        def capacity_four(_, __, body):
            if int(parse_json(body)['capacity']) != 4:
                raise AssertionError('Updated response did not contain capacity 4')
        test('PATCH valid capacity', 'PATCH', path, 200, {'capacity': 4}, capacity_four)
        test('GET after PATCH', 'GET', path, 200,
             verify=lambda _, __, b: require('PATCH persisted capacity',
                       int(parse_json(b)['capacity']) == 4, 'GET did not return capacity 4'))
        deleted = test('DELETE created slot', 'DELETE', path, 204)
        if deleted:
            created_paths.remove(path)
        test('GET deleted slot (404)', 'GET', path, 404)
        test('PATCH deleted slot (404)', 'PATCH', path, 404, {'capacity': 3})
        test('DELETE deleted slot again (404)', 'DELETE', path, 404)

    test('PATCH positive nonexistent slot (404)', 'PATCH', f'/api/slots/{missing_slot}', 404, {'capacity': 3})
    test('DELETE positive nonexistent slot (404)', 'DELETE', f'/api/slots/{missing_slot}', 404)

    # Cross-offset valid request should pass (end instant later, local clock earlier).
    offset_valid = {**valid, 'startsAt': iso(start_offset), 'endsAt': iso(end_offset)}
    test('POST valid instants across different offsets', 'POST', '/api/slots', 201, offset_valid)

    final_slots = required_list('/api/slots')
    initial_ids = {int(s['id']) for s in initial_slots}
    final_ids = {int(s['id']) for s in final_slots}
    created_ids = {int(path.rsplit('/', 1)[1]) for path in created_paths}
    require('Only deliberately created test slots remain for cleanup',
            final_ids - created_ids == initial_ids,
            'Unexpected changes in existing slot IDs')


try:
    main()
except Exception as exc:
    failures.append(('Setup or unexpected test exception', str(exc)))
    print(f'FAIL Setup or unexpected test exception: {exc}')
finally:
    # Clean only resources created by this test run, even after a failure.
    for path in dict.fromkeys(created_paths):
        try:
            if os.environ['CHECK_DB'] == '1':
                # Only paths of resources created by this test run are tracked here.
                slot_id = int(path.rsplit('/', 1)[1])
                run_sql(f'DELETE FROM booking WHERE appointment_slot_id = {slot_id};')
            status, _, body = call('DELETE', path)
            if status not in (204, 404):
                failures.append(('Cleanup', f'{path} could not be deleted: HTTP {status} {body[:160]}'))
                print(f'FAIL Cleanup {path}: HTTP {status}')
        except Exception as exc:
            failures.append(('Cleanup', f'{path}: {exc}'))
            print(f'FAIL Cleanup {path}: {exc}')

print(f'\nRESULT: {checks - len([f for f in failures if f[0] not in ("Cleanup", "Setup or unexpected test exception")])}/{checks} HTTP/invariant checks passed, {len(failures)} failures in total.')
if failures:
    print('\nFailed checks:')
    for label, detail in failures:
        print(f'  - {label}: {detail}')
    sys.exit(1)
sys.exit(0)
PY

printf '\nAll requested automated and live API/database checks passed. Vaadin was not tested.\n'
