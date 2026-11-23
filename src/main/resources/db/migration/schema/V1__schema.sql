CREATE TYPE booking_status AS ENUM ('RESERVED', 'CONFIRMED', 'CANCELLED', 'NO_SHOW');

CREATE TABLE facility (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(200) NOT NULL UNIQUE
);

CREATE TABLE specialty (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(100) NOT NULL UNIQUE
);

CREATE TABLE appointment_slot (
    id BIGSERIAL PRIMARY KEY,
    facility_id BIGINT NOT NULL REFERENCES facility(id) ON DELETE CASCADE,
    specialty_id BIGINT NOT NULL REFERENCES specialty(id),
    starts_at TIMESTAMPTZ NOT NULL,
    ends_at TIMESTAMPTZ NOT NULL,
    capacity INT NOT NULL CHECK (capacity > 0),
    CHECK (ends_at > starts_at)
);

CREATE INDEX idx_slot_fac_spec_time ON appointment_slot(facility_id, specialty_id, starts_at);

CREATE TABLE booking (
    id BIGSERIAL PRIMARY KEY,
    appointment_slot_id BIGINT NOT NULL REFERENCES appointment_slot(id) ON DELETE CASCADE,
    status booking_status NOT NULL DEFAULT 'RESERVED',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_booking_slot_status ON booking(appointment_slot_id, status);
