#!/bin/sh
set -eu

base=${MEDFLOW_API_BASE_URL:-http://localhost:8080}
slots=$base/api/slots
work=$(mktemp -d)
created_id=
cleanup() {
    if [ -n "$created_id" ]; then
        curl -fsS -X DELETE "$slots/$created_id" >/dev/null || true
    fi
    rm -f "$work/response"
    rmdir "$work"
}
trap cleanup EXIT

check() {
    expected=$1
    label=$2
    shift 2
    actual=$(curl -sS -o "$work/response" -w '%{http_code}' "$@")
    if [ "$actual" != "$expected" ]; then
        printf '%s returned %s, expected %s\n' "$label" "$actual" "$expected" >&2
        cat "$work/response" >&2
        exit 1
    fi
    printf '%s returned %s\n' "$label" "$actual"
}

post_slot() {
    check "$1" "$2" -H 'Content-Type: application/json' --data "$3" "$slots"
}

check 200 'List facilities' "$base/api/facilities"
facility=$(jq -er '.[0].id' "$work/response")
unknown_facility=$(jq -er 'map(.id) | max + 1' "$work/response")
check 200 'List specialties' "$base/api/specialties"
specialty=$(jq -er '.[0].id' "$work/response")
unknown_specialty=$(jq -er 'map(.id) | max + 1' "$work/response")
check 200 'Filter by both references' "$slots?facilityId=$facility&specialtyId=$specialty"
jq -e --argjson facility "$facility" --argjson specialty "$specialty" \
    'all(.[]; .facilityId == $facility and .specialtyId == $specialty)' "$work/response" >/dev/null

timestamps=$(python3 - <<'PY'
from datetime import datetime, timedelta, timezone

now = datetime.now(timezone.utc).replace(microsecond=0)
start = now + timedelta(days=2)
print(start.isoformat(), (start + timedelta(minutes=30)).isoformat(),
      (now - timedelta(days=2)).isoformat())
PY
)
read -r start end past <<EOF_TIMES
$timestamps
EOF_TIMES

body=$(jq -nc --argjson facility "$facility" --argjson specialty "$specialty" \
    --arg start "$start" --arg end "$end" \
    '{facilityId: $facility, specialtyId: $specialty, startsAt: $start, endsAt: $end, capacity: 2}')
post_slot 201 'Create valid slot' "$body"
created_id=$(jq -er '.id' "$work/response")
check 200 'Read created slot' "$slots/$created_id"
check 200 'Update capacity' -X PATCH -H 'Content-Type: application/json' \
    --data '{"capacity":3}' "$slots/$created_id"
jq -e '.capacity == 3' "$work/response" >/dev/null

reject_slot() {
    label=$1
    shift
    invalid_body=$(printf '%s\n' "$body" | jq -ce "$@")
    post_slot 400 "$label" "$invalid_body"
}

reject_slot 'Zero capacity' '.capacity = 0'
reject_slot 'Missing capacity' 'del(.capacity)'
reject_slot 'Past start' --arg past "$past" '.startsAt = $past'
reject_slot 'End before start' '. as $slot | .startsAt = $slot.endsAt | .endsAt = $slot.startsAt'
reject_slot 'Unknown facility' --argjson id "$unknown_facility" '.facilityId = $id'
reject_slot 'Unknown specialty' --argjson id "$unknown_specialty" '.specialtyId = $id'
check 400 'Invalid slot ID' "$slots/0"
check 400 'Invalid facility filter' "$slots?facilityId=0"

check 204 'Delete created slot' -X DELETE "$slots/$created_id"
deleted_id=$created_id
created_id=
check 404 'Read deleted slot' "$slots/$deleted_id"
