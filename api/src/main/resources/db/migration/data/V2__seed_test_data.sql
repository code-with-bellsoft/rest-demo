INSERT INTO facility(name) VALUES ('Harbor Community Clinic'), ('Downtown General Hospital');
INSERT INTO specialty(name) VALUES ('General Medicine'), ('Cardiology');

WITH fixture(facility_name, specialty_name, starts_in, capacity) AS (
    VALUES
        ('Harbor Community Clinic', 'General Medicine', INTERVAL '7 days', 3),
        ('Harbor Community Clinic', 'Cardiology', INTERVAL '8 days', 3),
        ('Downtown General Hospital', 'Cardiology', INTERVAL '9 days', 1),
        ('Downtown General Hospital', 'General Medicine', INTERVAL '10 days', 2)
), slots AS (
    INSERT INTO appointment_slot(facility_id, specialty_id, starts_at, ends_at, capacity)
    SELECT f.id, s.id, now() + fixture.starts_in,
           now() + fixture.starts_in + INTERVAL '30 minutes', fixture.capacity
    FROM fixture
    JOIN facility f ON f.name = fixture.facility_name
    JOIN specialty s ON s.name = fixture.specialty_name
    RETURNING id, starts_at
)
INSERT INTO booking(appointment_slot_id, status)
SELECT slots.id, fixture.status::booking_status
FROM slots
JOIN (VALUES
    (INTERVAL '8 days', 'RESERVED'),
    (INTERVAL '8 days', 'CONFIRMED'),
    (INTERVAL '9 days', 'CONFIRMED'),
    (INTERVAL '10 days', 'CANCELLED')
) AS fixture(starts_in, status) ON slots.starts_at = now() + fixture.starts_in;
