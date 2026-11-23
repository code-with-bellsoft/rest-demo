#!/usr/bin/env bash
set -Eeuo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
if [[ "${VERIFY_VERSIONING:-0}" != 1 || -z "${DB_CONTAINER:-}" ]]; then
  # Use the existing disposable database and API lifecycle for safe conflict checks.
  VERIFY_VERSIONING=1 RUN_MAVEN=0 CHECK_DB=1 START_DB=1 START_API=1 API_BASE= \
    exec "$root/verify-medflow-validation.sh"
fi

work="$(mktemp -d)"
slots="$API_BASE/api/slots"
slot_id=
booked=0
sql() {
  docker exec "$DB_CONTAINER" psql -X -v ON_ERROR_STOP=1 -U "$DB_USER" -d "$DB_NAME" -c "$1"
}
cleanup() {
  local status=$?
  trap - EXIT
  if [[ -n "$slot_id" ]]; then
    if (( booked )); then
      sql "DELETE FROM booking WHERE appointment_slot_id = $slot_id" >/dev/null || status=1
    fi
    curl -fsS --max-time 10 -X DELETE "$slots/$slot_id" >/dev/null || status=1
  fi
  rm -f "$work"/{body,headers,baseline,all}
  rmdir "$work"
  exit "$status"
}
trap cleanup EXIT
trap 'printf "FAIL %s\n" "$check" >&2' ERR

request() {
  local expected="$1" version="$2"
  shift 2
  if [[ -n "$version" ]]; then
    set -- -H "API-Version: $version" "$@"
  fi
  local actual
  actual=$(curl -sS --max-time 10 -D "$work/headers" -o "$work/body" -w '%{http_code}' "$@") || return
  if [[ "$actual" != "$expected" ]]; then
    printf 'Expected HTTP %s, got %s\n' "$expected" "$actual" >&2
    cat "$work/body" >&2
    return 1
  fi
}

header() {
  awk -v name="$1" 'tolower($1) == tolower(name) ":" {sub(/^[^:]*:[[:space:]]*/, ""); sub(/\r$/, ""); print}' \
    "$work/headers"
}

vary() {
  header Vary | tr ',' '\n' | grep -iqE '^[[:space:]]*API-Version[[:space:]]*$'
}

problem() {
  local status="$1" path="$2"
  [[ "$(header Content-Type)" == application/problem+json* ]]
  jq -e --argjson status "$status" --arg path "$path" \
    '.status == $status and .type == "about:blank" and .instance == $path
     and (.title | length > 0) and (.detail | length > 0)' "$work/body" >/dev/null
}

check='API availability and reference data'
command -v jq >/dev/null
request 200 '' "$API_BASE/api/facilities"
facility="$(jq -er '.[0].id' "$work/body")"
request 200 '' "$API_BASE/api/specialties"
specialty="$(jq -er '.[0].id' "$work/body")"
echo "PASS $check"

for query in '' "?facilityId=$facility" "?specialtyId=$specialty" "?facilityId=$facility&specialtyId=$specialty"; do
  check="default, v1 and v2 slot contracts for /api/slots$query"
  request 200 '' "$slots$query"
  vary
  cp "$work/body" "$work/baseline"
  if [[ -z "$query" ]]; then
    cp "$work/baseline" "$work/all"
  else
    jq -e --slurpfile all "$work/all" --arg query "$query" \
      --argjson facility "$facility" --argjson specialty "$specialty" '
      . == ($all[0] | map(select(
        (if $query | contains("facilityId=") then .facilityId == $facility else true end)
        and (if $query | contains("specialtyId=") then .specialtyId == $specialty else true end))))
    ' "$work/baseline" >/dev/null
  fi
  request 200 1.0 "$slots$query"
  vary
  jq -e --slurpfile baseline "$work/baseline" '
    . == $baseline[0] and all(.[];
      keys == ["activeBookings","capacity","endsAt","facilityId","facilityName","id",
               "remainingCapacity","specialtyId","specialtyName","startsAt"])
  ' "$work/body" >/dev/null
  request 200 2.0 "$slots$query"
  vary
  jq -e --slurpfile baseline "$work/baseline" '
    all(.[];
      (has("capacity") or has("activeBookings") or has("remainingCapacity") | not)
      and (.availability | keys == ["activeBookings","capacity","remainingCapacity","status"])
      and (.availability | .remainingCapacity == (.capacity - .activeBookings)
           and .status == (if .remainingCapacity > 0 then "AVAILABLE" else "FULL" end)))
    and (map(del(.availability) + (.availability | del(.status))) == $baseline[0])
  ' "$work/body" >/dev/null
  echo "PASS $check"
done

for version in 99.0 malformed; do
  check="reject API-Version $version with Problem Details"
  request 400 "$version" "$slots"
  problem 400 /api/slots
  jq -e --arg version "$version" '.detail | contains($version) | not' "$work/body" >/dev/null
  echo "PASS $check"
done

check='create a temporary v1 slot and read it in v2'
payload="$(python3 - "$facility" "$specialty" <<'PY'
import json
import sys
from datetime import datetime, timedelta, timezone

start = datetime.now(timezone.utc) + timedelta(days=14)
print(json.dumps(dict(facilityId=int(sys.argv[1]), specialtyId=int(sys.argv[2]), capacity=2,
                      startsAt=start.isoformat(), endsAt=(start + timedelta(minutes=30)).isoformat())))
PY
)"
request 201 1.0 -H 'Content-Type: application/json' --data "$payload" "$slots"
slot_id="$(jq -er '.id | select(type == "number" and . > 0 and . == floor)' "$work/body")"
path="/api/slots/$slot_id"
[[ "$(header Location)" == "$path" ]]
jq -e '.capacity == 2 and .activeBookings == 0 and .remainingCapacity == 2' "$work/body" >/dev/null
request 200 2.0 "$slots/$slot_id"
vary
jq -e --argjson id "$slot_id" '.id == $id and .availability ==
  {capacity:2,activeBookings:0,remainingCapacity:2,status:"AVAILABLE"}' "$work/body" >/dev/null
echo "PASS $check"

check='patch in v2 and read the same capacity in v1'
request 200 2.0 -X PATCH -H 'Content-Type: application/json' --data '{"capacity":3}' "$slots/$slot_id"
jq -e '.availability == {capacity:3,activeBookings:0,remainingCapacity:3,status:"AVAILABLE"}
  and (has("capacity") | not)' "$work/body" >/dev/null
request 200 1.0 "$slots/$slot_id"
vary
jq -e '.capacity == 3 and .activeBookings == 0 and .remainingCapacity == 3' "$work/body" >/dev/null
echo "PASS $check"

booked=1
check='validation and booking conflicts keep the existing Problem Details status codes'
sql "INSERT INTO booking (appointment_slot_id, status) VALUES ($slot_id, 'RESERVED'), ($slot_id, 'CONFIRMED')" >/dev/null
for version in 1.0 2.0; do
  request 400 "$version" -X PATCH -H 'Content-Type: application/json' --data '{"capacity":0}' "$slots/$slot_id"
  problem 400 "$path"
  jq -e 'any(.errors[]; .field == "capacity")' "$work/body" >/dev/null
  request 409 "$version" -X PATCH -H 'Content-Type: application/json' --data '{"capacity":1}' "$slots/$slot_id"
  problem 409 "$path"
  request 409 "$version" -X DELETE "$slots/$slot_id"
  problem 409 "$path"
done
sql "DELETE FROM booking WHERE appointment_slot_id = $slot_id" >/dev/null
booked=0
echo "PASS $check"

check='delete the temporary slot and receive 404 in both versions'
request 204 2.0 -X DELETE "$slots/$slot_id"
slot_id=
[[ ! -s "$work/body" ]]
for version in 1.0 2.0; do
  request 404 "$version" "$API_BASE$path"
  problem 404 "$path"
done
echo "PASS $check"
