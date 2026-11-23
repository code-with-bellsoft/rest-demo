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

Install Java 25, Node.js 24 LTS for Vaadin's frontend tooling, and Docker with Compose. Run the commands from the repository root. In one terminal, start PostgreSQL and the API.

```sh
export MEDFLOW_DB_PASSWORD=medpass
docker compose up -d --wait postgres
./mvnw -pl api spring-boot:run
```

The API connects to `localhost:5434/medflow_rest` as `meduser`. Flyway applies the schema and seed migrations at startup, and Maven generates the jOOQ classes from the schema SQL during the build. Compose persists PostgreSQL data in `medflow-rest-demo_rest_pgdata`.

In a second terminal, start the UI.

```sh
./mvnw -pl ui package
java -jar ui/target/ui-0.0.1-SNAPSHOT.jar
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

[requests.http](requests.http) contains runnable examples for the JetBrains HTTP Client. Run them in order on a fresh database. With curl and jq, you can start by fetching the reference IDs and creating a slot.

```sh
facility_id=$(curl -fsS localhost:8080/api/facilities | jq '.[0].id')
specialty_id=$(curl -fsS localhost:8080/api/specialties | jq '.[0].id')

curl -i -H 'Content-Type: application/json' \
  -d "{\"facilityId\":${facility_id},\"specialtyId\":${specialty_id},\"startsAt\":\"2030-05-01T10:00:00Z\",\"endsAt\":\"2030-05-01T10:30:00Z\",\"capacity\":2}" \
  localhost:8080/api/slots
```

For example, a fresh database can return:

```http
HTTP/1.1 201
Location: /api/slots/5
Content-Type: application/json

{"id":5,"facilityId":2,"facilityName":"Downtown General Hospital","specialtyId":2,"specialtyName":"Cardiology","startsAt":"2030-05-01T10:00:00Z","endsAt":"2030-05-01T10:30:00Z","capacity":2,"activeBookings":0,"remainingCapacity":2}
```

Slot responses include `activeBookings` and `remainingCapacity`. Remaining capacity is total capacity minus the count of reserved and confirmed bookings. The Grid displays these API values as Capacity, Booked, and Remaining.

Follow the returned `Location` to retrieve the slot. Send a PATCH to the same path with `{"capacity":3}` to change capacity.

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

The reactor regenerates the jOOQ classes, runs both test suites, and packages both Spring Boot applications, including Vaadin's browser bundle. API tests use MockMvc and a fresh PostgreSQL Testcontainer with its own credentials. UI tests use a local HTTP test server and can run independently of PostgreSQL and Docker.

```sh
./mvnw -pl api test
./mvnw -pl ui test
```
