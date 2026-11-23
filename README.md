# MedFlow REST Demo

MedFlow manages appointment slots through a small REST API. It uses Java 25, Spring Boot 4.1.1 with Spring MVC, PostgreSQL 16, Flyway, jOOQ, and the Maven Wrapper. Maven generates the jOOQ classes from the Flyway schema SQL.

Each appointment slot belongs to one facility and one specialty. A slot has a start time, an end time, and a positive capacity. Bookings are read-only fixtures. Only `RESERVED` and `CONFIRMED` bookings count toward capacity. A slot can be deleted when it has zero active bookings. PostgreSQL cascades that deletion to any inactive booking rows for the slot.

## Run locally

Install Java 25 and start Docker. Export a database password:
```shell
export MEDFLOW_DB_PASSWORD=medpass
```

Spin up a database instance:

```shell
docker compose up -d postgres
```

For the backend service, run Flyway migrations to create a schema and seed test data:

```shell
mvn flyway:migrate
```

Then, run code generation with jOOQ:

```shell
mvn jooq-codegen:generate
```

After that, you can run the application:

```shell
mvn spring-boot:run
```

Compose starts only PostgreSQL, using project `medflow-rest-demo` and volume `medflow-rest-demo_rest_pgdata`. The app connects to `localhost:5434/medflow_rest` as `meduser` and listens on port 8080. Flyway creates and seeds the schema on first startup.

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

{"id":5,"facilityId":2,"facilityName":"Downtown General Hospital","specialtyId":2,"specialtyName":"Cardiology","startsAt":"2030-05-01T10:00:00Z","endsAt":"2030-05-01T10:30:00Z","capacity":2}
```

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

The build regenerates the jOOQ classes. The tests run Spring MVC requests through MockMvc against a fresh PostgreSQL Testcontainer. They use Docker and create their own database and credentials.
