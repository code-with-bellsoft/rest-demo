# MedFlow REST Demo

MedFlow manages appointment slots through a small REST API. It uses Java 25, Spring Boot 4.1.1 with Spring MVC, PostgreSQL 16, Flyway, jOOQ, and the Maven Wrapper. Maven generates the jOOQ classes from the Flyway schema SQL.

Each appointment slot belongs to one facility and one specialty. A slot has a start time, an end time, and a positive capacity. Bookings are read-only fixtures. Only `RESERVED` and `CONFIRMED` bookings count toward capacity. A slot can be deleted when it has zero active bookings. PostgreSQL cascades that deletion to any inactive booking rows for the slot.

## Modules and architecture

| Module | Port | Role |
| --- | --- | --- |
| `api/` | 8080 | Spring MVC REST API, business rules, Flyway migrations, jOOQ, and PostgreSQL access |
| `ui/` | 8081 | Vaadin Flow 25.3 appointment-slot manager and HTTP inspector |

The root Maven project builds both modules. Each module starts its own Spring Boot process. The browser talks to Vaadin on port 8081. The Vaadin server uses Spring `RestClient` to call the REST API on port 8080, and the API talks to PostgreSQL. The UI has its own DTOs for the public JSON contract.

## Run locally

Install Java 25  and Docker with Compose. Run the commands from the repository root. In one terminal, start PostgreSQL and the API.

```sh
export MEDFLOW_DB_PASSWORD=medpass
docker compose up -d --wait postgres
./mvnw -pl api spring-boot:run
```

The API connects to `localhost:5434/medflow_rest` as `meduser`. Flyway applies the schema and seed migrations at startup, and Maven generates the jOOQ classes from the schema SQL during the build. Compose persists PostgreSQL data in `medflow-rest-demo_rest_pgdata`.

In a second terminal, start the UI.

```sh
./mvnw -pl ui spring-boot:run
```

Open [MedFlow on port 8081](http://localhost:8081). The UI reads its API address from `medflow.api.base-url`, which defaults to `http://localhost:8080`. Override it with `MEDFLOW_API_BASE_URL` when needed.

## HTTP inspector

The inspector shows the latest exchange for the current view. It captures the method, full URI, displayable HTTP headers, and body at the `RestClient` boundary. Sensitive headers are excluded. It shows the API's status and body on errors, the `Location` header on creation, and an empty-body marker for `204 No Content`. Each pane has a copy button. After a successful write, the grid reloads and the inspector keeps the write response visible. When the backend is unreachable, the inspector shows the attempted request and reports that no HTTP response arrived.

The create form uses UTC, and the grid displays the offset from the API response. Try deleting the fully booked fixture to see a real `409 Conflict` from the API.

## API

| Method | Path | Successful response |
| --- | --- | --- |
| GET | `/api/slots` | 200 with all matching slots, including fully booked slots, ordered by start time and then ID |
| GET | `/api/slots/{id}` | 200 with the slot |
| POST | `/api/slots` | 201 with the created slot and a `Location` header |
| PATCH | `/api/slots/{id}` | 200 with the slot after updating its capacity |
| DELETE | `/api/slots/{id}` | 204 with an empty body |
| GET | `/api/facilities` | 200 with facility IDs and names |
| GET | `/api/specialties` | 200 with specialty IDs and names |

The collection accepts optional `facilityId` and `specialtyId` query parameters. Requests for a missing slot return 404. A capacity reduction below the active booking count or deletion of a slot with active bookings returns 409.

[requests.http](requests.http) contains optional examples for the JetBrains HTTP Client.

Slot responses report `activeBookings` and `remainingCapacity`. Remaining capacity is total capacity minus the count of reserved and confirmed bookings. The grid displays these API values as Capacity, Booked, and Remaining. Follow the `Location` header after creating a slot to retrieve it.

## API Versioning

API versioning is a compatibility strategy. It gives clients time to adopt a breaking change on their own schedule. For example, a client that ignores unfamiliar properties can usually handle a new optional response field. Moving a required field changes the path a client reads. In this demo, clients that look for `slot.capacity` need to read `slot.availability.capacity` in v2.

Spring Boot configures Spring Framework 7's native MVC version resolution with `spring.mvc.apiversion.use.header`, `spring.mvc.apiversion.default`, and `spring.mvc.apiversion.supported`. Spring resolves the `API-Version` request header, parses and validates the version, and selects a handler using the mapping annotation's `version` attribute. The header keeps the existing resource URLs and `Location` values usable across versions. Requests with no header default to v1, so existing clients can keep using their original JSON contract. Use `1.0` and `2.0` in client requests. Spring's semantic parser determines which inputs are valid. In Spring Framework 7.0.9 it ignores leading non-digit characters and treats omitted minor or patch components as zero. For example, `v2.0`, `-2.0`, `2`, and `2.0.0` all select v2. Versions that Spring cannot parse or that resolve to an unsupported version receive a 400 Problem Details response. [Spring MVC API versioning](https://docs.spring.io/spring-framework/reference/web/webmvc-versioning.html)

GET collection, GET by ID, POST, and PATCH return the selected representation. Both sets of mappings call the same service and jOOQ repository, with the same validation, bookings, transactions, and database. POST and PATCH accept the original request bodies. DELETE and the facility and specialty endpoints have one unversioned mapping each. Slot GET responses include `Vary: API-Version`, so a cache can distinguish versions of the same URL, including requests with no header.

**Version 1.0**

```json
{
  "id": 42,
  "facilityId": 1,
  "facilityName": "Harbor Community Clinic",
  "specialtyId": 2,
  "specialtyName": "Cardiology",
  "startsAt": "2027-01-15T09:00:00Z",
  "endsAt": "2027-01-15T09:30:00Z",
  "capacity": 3,
  "activeBookings": 2,
  "remainingCapacity": 1
}
```

**Version 2.0**

```json
{
  "id": 42,
  "facilityId": 1,
  "facilityName": "Harbor Community Clinic",
  "specialtyId": 2,
  "specialtyName": "Cardiology",
  "startsAt": "2027-01-15T09:00:00Z",
  "endsAt": "2027-01-15T09:30:00Z",
  "availability": {
    "capacity": 3,
    "activeBookings": 2,
    "remainingCapacity": 1,
    "status": "AVAILABLE"
  }
}
```

IDs and capacity counts are JSON integers. Names and timestamps are strings. The timestamps keep their existing ISO 8601 offset format. In v2, all three counts live exclusively inside `availability`. The `status` is `AVAILABLE` when remaining capacity is positive and `FULL` when it is zero.

```bash
curl -i http://localhost:8080/api/slots

curl -i -H 'API-Version: 1.0' http://localhost:8080/api/slots

curl -i -H 'API-Version: 2.0' http://localhost:8080/api/slots

curl -i -H 'API-Version: 99.0' http://localhost:8080/api/slots
```

With the demo running, these Bash commands discover reference IDs, create a temporary slot in v1, update it in v2, and remove it. They use jq and Python 3.

```bash
base=http://localhost:8080
facility=$(curl -fsS "$base/api/facilities" | jq -er '.[0].id')
specialty=$(curl -fsS "$base/api/specialties" | jq -er '.[0].id')
payload=$(python3 - "$facility" "$specialty" <<'PYTHON'
import json
import sys
from datetime import datetime, timedelta, timezone
start = datetime.now(timezone.utc) + timedelta(days=7)
print(json.dumps(dict(facilityId=int(sys.argv[1]), specialtyId=int(sys.argv[2]), capacity=2,
                      startsAt=start.isoformat(), endsAt=(start + timedelta(minutes=30)).isoformat())))
PYTHON
)
id=$(curl -fsS -H 'API-Version: 1.0' -H 'Content-Type: application/json' \
  --data "$payload" "$base/api/slots" | jq -er '.id')

curl -i -X PATCH -H 'API-Version: 2.0' -H 'Content-Type: application/json' \
  --data '{"capacity":3}' "$base/api/slots/$id"

curl -i -X DELETE -H 'API-Version: 2.0' "$base/api/slots/$id"
```

The Vaadin **API version** selector starts at **Version 1.0**. Switching it reloads the grid with the current filters. The client reads the selected contract and displays the same columns. The HTTP inspector shows the outgoing version header and the response JSON captured from the API. After a create or capacity update, the inspector keeps that exchange visible while the grid refreshes.

After building the API JAR with Maven, run `./scripts/verify-versioning.sh`. It uses Bash, curl, jq, Python 3, Java, and Docker to verify both contracts and the shared booking rules against a disposable PostgreSQL database and API.

## Validation

The API uses Jakarta Bean Validation. `@Valid` tells Spring MVC to validate the deserialized request body before invoking the controller. Creating a slot requires positive facility and specialty IDs, future start and end timestamps, and positive capacity. Capacity updates require a positive capacity. Slot path IDs and supplied collection filters must be positive. Positive filter values with no matches return an empty list.

`@EndAfterStart` compares the two timestamps as instants and reports a violation on `endsAt` when the end is equal to or earlier than the start. `@ExistingFacility` and `@ExistingSpecialty` run jOOQ existence queries for positive IDs. Spring's validator factory injects the reference repository into both validators through their constructors. `@NotNull` handles missing values, and `@Positive` handles nonpositive IDs, so the lookup validators skip them.

PostgreSQL foreign keys and CHECK constraints enforce integrity at write time, including when a reference changes after validation. The service delegates capacity updates and deletions to repository transactions that lock the slot before counting active bookings. This keeps the booking rule and the subsequent write in the same transaction. The Vaadin forms submit values via HTTP and the inspector displays the API's actual response.

Run the smoke test against the running API with the seed fixtures present. It requires curl, jq, and Python 3. It generates timestamps relative to the current time, discovers IDs from the API, and deletes the slot it creates.

```sh
./scripts/validation-smoke.sh
```

Run `./verify-medflow-validation.sh` for the full build and live API and SQL checks. It starts a disposable PostgreSQL container and a separate API on local ports selected at runtime. To run only the live HTTP checks against an existing demo API, use `RUN_MAVEN=0 CHECK_DB=0 API_BASE=http://localhost:8080 ./verify-medflow-validation.sh`.

## Fixtures

| Facility | Specialty | Capacity | Bookings |
| --- | --- | ---: | --- |
| Harbor Community Clinic | General Medicine | 3 | Empty |
| Harbor Community Clinic | Cardiology | 3 | One reserved and one confirmed |
| Downtown General Hospital | Cardiology | 1 | One confirmed, fully booked |
| Downtown General Hospital | General Medicine | 2 | One cancelled, zero active |

The 30-minute slots start seven through ten days after Flyway seeds the database, in table order.

## Tests

```sh
./mvnw clean verify
```

The reactor regenerates the jOOQ classes, runs both test suites, and packages both Spring Boot applications, including Vaadin's browser bundle. API tests use MockMvc for validation and a fresh PostgreSQL Testcontainer for persistence and transactional behavior. UI tests use a local HTTP test server and can run independently of PostgreSQL and Docker.

```sh
./mvnw -pl api test
./mvnw -pl ui test
```

## Error Handling

HTTP status codes tell clients how to respond. A 400 means the request needs a correction, a 404 means the requested slot is missing, and a 409 means the slot exists but its current bookings prevent the change. A valid filter with no matches returns 200 with an empty array. Successful creation still returns 201 with `Location`, and successful deletion returns 204 with an empty body.

[RFC 9457](https://www.rfc-editor.org/rfc/rfc9457.html) defines a standard error representation for HTTP APIs. MedFlow uses Spring's `ProblemDetail` to serialize `application/problem+json`. The standard fields are `type` (`about:blank` uses the HTTP status meaning), `title` (the status description), `status` (the numeric HTTP status), `detail` (a human-readable explanation), and `instance` (the request path, without the query string). Branch on the HTTP status and use the detail for display. A 406 can have an empty body when the client requests an unsupported representation.

A single `ResponseEntityExceptionHandler` advice adds the API's error details and lets Spring MVC handle response writing and protocol headers such as `Allow`. Body validation handles violations on the deserialized request, including the custom time and reference constraints. Method validation handles constraints on arguments such as the path ID and query filters. Both expose an `errors` array of `field` and `message` entries in arbitrary order. For a request-wide violation without a field, the entry uses `body` or `request`. Invalid JSON or parameter conversion failures return a short 400 detail.

```sh
curl -i -X POST http://localhost:8080/api/slots \
  -H 'Content-Type: application/json' -d '{"capacity":0}'
curl -i http://localhost:8080/api/slots/0
```

An invalid path ID produces:

```json
{
  "type": "about:blank",
  "title": "Bad Request",
  "status": 400,
  "detail": "Request validation failed",
  "instance": "/api/slots/0",
  "errors": [{"field": "id", "message": "must be greater than 0"}]
}
```

A POST with only `capacity: 0` also reports the missing fields:

```json
{
  "type": "about:blank",
  "title": "Bad Request",
  "status": 400,
  "detail": "Request validation failed",
  "instance": "/api/slots",
  "errors": [
    {"field": "facilityId", "message": "must not be null"},
    {"field": "specialtyId", "message": "must not be null"},
    {"field": "startsAt", "message": "must not be null"},
    {"field": "endsAt", "message": "must not be null"},
    {"field": "capacity", "message": "must be greater than 0"}
  ]
}
```

The HTTP boundary translates missing slots and booking conflicts into their public responses. Repository transactions, row locks, and database constraints continue to enforce the underlying rules. Unexpected failures return a generic 500 detail, while the server logs the failure type and location. The Vaadin client displays the detail and any field messages as plain text and keeps the original error body in the inspector.

After building with `./mvnw clean verify`, run `./scripts/verify-error-handling.sh`. It uses Bash, Java, Python 3, curl, and Docker to start a disposable PostgreSQL database and API, verify the HTTP contract, and clean up test fixtures.
