package dev.cyberjar.medflow.slot;

import dev.cyberjar.medflow.enums.BookingStatus;
import dev.cyberjar.medflow.slot.dto.SlotRequest;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static dev.cyberjar.medflow.Tables.APPOINTMENT_SLOT;
import static dev.cyberjar.medflow.Tables.BOOKING;
import static dev.cyberjar.medflow.Tables.FACILITY;
import static dev.cyberjar.medflow.Tables.SPECIALTY;
import static dev.cyberjar.medflow.enums.BookingStatus.CANCELLED;
import static dev.cyberjar.medflow.slot.AppointmentSlotValidationTest.problem;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.is;
import static org.jooq.impl.DSL.max;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class AppointmentSlotApiTest {

    private static final String SLOTS = "/api/slots";

    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("medflow_rest_test");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    DSLContext db;

    @Autowired
    AppointmentSlotRepository repository;

    @Test
    void listsAllSeededSlotsInOrderIncludingTheFullyBookedSlot() throws Exception {
        Long empty = slotId("Harbor Community Clinic", "General Medicine");
        Long partial = slotId("Harbor Community Clinic", "Cardiology");
        Long full = slotId("Downtown General Hospital", "Cardiology");
        Long cancelled = slotId("Downtown General Hospital", "General Medicine");

        mvc.perform(get(SLOTS))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(4))
                .andExpect(jsonPath("$[0].id").value(is(empty), Long.class))
                .andExpect(jsonPath("$[1].id").value(is(partial), Long.class))
                .andExpect(jsonPath("$[2].id").value(is(full), Long.class))
                .andExpect(jsonPath("$[3].id").value(is(cancelled), Long.class))
                .andExpectAll(availability("$[0]", 0, 3))
                .andExpectAll(availability("$[1]", 2, 1))
                .andExpectAll(availability("$[2]", 1, 0))
                .andExpectAll(availability("$[3]", 0, 2));
        mvc.perform(get(SLOTS + "/{id}", full))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(is(full), Long.class))
                .andExpect(jsonPath("$.capacity").value(1))
                .andExpectAll(availability("$", 1, 0));
        assertThat(db.fetchCount(BOOKING, BOOKING.APPOINTMENT_SLOT_ID.eq(full))).isEqualTo(1);
    }

    @Test
    void filtersByEitherReferenceOrBoth() throws Exception {
        String harborId = facilityId("Harbor Community Clinic").toString();
        String cardiologyId = specialtyId("Cardiology").toString();

        mvc.perform(get(SLOTS).param("facilityId", harborId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].facilityName").value("Harbor Community Clinic"))
                .andExpect(jsonPath("$[1].facilityName").value("Harbor Community Clinic"))
                .andExpectAll(availability("$[1]", 2, 1));
        mvc.perform(get(SLOTS).param("specialtyId", cardiologyId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].specialtyName").value("Cardiology"))
                .andExpect(jsonPath("$[1].specialtyName").value("Cardiology"))
                .andExpectAll(availability("$[1]", 1, 0));
        mvc.perform(get(SLOTS).param("facilityId", harborId).param("specialtyId", cardiologyId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(is(slotId("Harbor Community Clinic", "Cardiology")), Long.class))
                .andExpectAll(availability("$[0]", 2, 1));
    }

    @Test
    void returnsEmptyCollectionForUnknownPositiveFilters() throws Exception {
        mvc.perform(get(SLOTS).param("facilityId", missingFacilityId().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get(SLOTS).param("specialtyId", missingSpecialtyId().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void returnsReferenceIdsAndNames() throws Exception {
        mvc.perform(get("/api/facilities"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].name").value("Downtown General Hospital"))
                .andExpect(jsonPath("$[0].id").value(is(facilityId("Downtown General Hospital")), Long.class));
        mvc.perform(get("/api/specialties"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].name").value("Cardiology"))
                .andExpect(jsonPath("$[0].id").value(is(specialtyId("Cardiology")), Long.class));
    }

    @Test
    @Transactional
    void createsUpdatesAndDeletesAPersistedSlot() throws Exception {
        OffsetDateTime startsAt = OffsetDateTime.now().plusDays(14).withNano(0);
        Long facilityId = facilityId("Harbor Community Clinic");
        Long specialtyId = specialtyId("Cardiology");
        String location = mvc.perform(post(SLOTS).contentType(MediaType.APPLICATION_JSON).content("""
                        {"facilityId": %d, "specialtyId": %d, "startsAt": "%s", "endsAt": "%s", "capacity": 2}
                        """.formatted(facilityId, specialtyId, startsAt, startsAt.plusMinutes(30))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.capacity").value(2))
                .andExpectAll(availability("$", 0, 2))
                .andReturn().getResponse().getHeader("Location");

        Long id = db.select(APPOINTMENT_SLOT.ID).from(APPOINTMENT_SLOT)
                .where(APPOINTMENT_SLOT.STARTS_AT.eq(startsAt)).fetchSingle().value1();
        assertThat(location).isEqualTo(SLOTS + "/" + id);
        mvc.perform(get(location))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(is(id), Long.class))
                .andExpect(jsonPath("$.capacity").value(2));
        mvc.perform(patch(location).contentType(MediaType.APPLICATION_JSON).content("{\"capacity\":3}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.capacity").value(3))
                .andExpectAll(availability("$", 0, 3))
                .andExpect(jsonPath("$.facilityId").value(is(facilityId), Long.class))
                .andExpect(jsonPath("$.specialtyId").value(is(specialtyId), Long.class));
        var updated = db.fetchSingle(APPOINTMENT_SLOT, APPOINTMENT_SLOT.ID.eq(id));
        assertThat(updated.getCapacity()).isEqualTo(3);
        assertThat(updated.getStartsAt().toInstant()).isEqualTo(startsAt.toInstant());
        assertThat(updated.getEndsAt().toInstant()).isEqualTo(startsAt.plusMinutes(30).toInstant());

        mvc.perform(delete(location)).andExpect(status().isNoContent()).andExpect(content().string(""));
        mvc.perform(get(location)).andExpect(status().isNotFound());
        assertThat(db.fetchExists(APPOINTMENT_SLOT, APPOINTMENT_SLOT.ID.eq(id))).isFalse();
    }

    @ParameterizedTest
    @CsvSource({"RESERVED, 1", "CONFIRMED, 1", "CANCELLED, 0", "NO_SHOW, 0"})
    @Transactional
    void countsEachBookingStatusUsingPersistedData(BookingStatus bookingStatus, int active) throws Exception {
        Long id = slotId("Harbor Community Clinic", "General Medicine");
        db.insertInto(BOOKING).set(BOOKING.APPOINTMENT_SLOT_ID, id).set(BOOKING.STATUS, bookingStatus).execute();

        mvc.perform(get(SLOTS + "/{id}", id))
                .andExpect(status().isOk())
                .andExpectAll(availability("$", active, 3 - active));
        mvc.perform(patch(SLOTS + "/{id}", id).contentType(MediaType.APPLICATION_JSON).content("{\"capacity\":2}"))
                .andExpect(status().isOk())
                .andExpectAll(availability("$", active, 2 - active));
    }

    @Test
    void returnsNotFoundForMissingSlots() throws Exception {
        Long missing = db.select(max(APPOINTMENT_SLOT.ID))
                .from(APPOINTMENT_SLOT).fetchSingle().value1() + 1;
        String path = SLOTS + "/" + missing;

        for (var request : List.of(get(path), delete(path),
                patch(path).contentType(MediaType.APPLICATION_JSON).content("{\"capacity\":3}"))) {
            problem(mvc.perform(request), 404, "Not Found", path)
                    .andExpect(jsonPath("$.detail").value("Slot with id " + missing + " not found"));
        }
    }

    @Test
    void refusesToReduceCapacityBelowActiveBookingsOrDeleteABookedSlot() throws Exception {
        Long booked = slotId("Harbor Community Clinic", "Cardiology");
        String path = SLOTS + "/" + booked;
        problem(mvc.perform(patch(path).contentType(MediaType.APPLICATION_JSON).content("{\"capacity\":1}")),
                409, "Conflict", path)
                .andExpect(jsonPath("$.detail").value("Cannot set capacity below the 2 active bookings."));
        problem(mvc.perform(delete(path)), 409, "Conflict", path)
                .andExpect(jsonPath("$.detail").value("Cannot delete a slot with active bookings."));
        assertThat(db.fetchSingle(APPOINTMENT_SLOT, APPOINTMENT_SLOT.ID.eq(booked)).getCapacity()).isEqualTo(3);
        assertThat(db.fetchCount(BOOKING, BOOKING.APPOINTMENT_SLOT_ID.eq(booked))).isEqualTo(2);
    }

    @Test
    void capacityUpdateWaitsForTheSlotLockAndCountsTheCommittedBookings() throws Exception {
        var start = OffsetDateTime.now().plusDays(14);
        Long id = repository.createSlot(new SlotRequest(
                facilityId("Harbor Community Clinic"), specialtyId("Cardiology"),
                start, start.plusMinutes(30), 3)).id();
        try (var executor = Executors.newSingleThreadExecutor()) {
            var update = db.transactionResult(configuration -> {
                DSLContext holder = DSL.using(configuration);
                int holderPid = holder.fetchOne("select pg_backend_pid()").get(0, Integer.class);
                holder.select(APPOINTMENT_SLOT.ID).from(APPOINTMENT_SLOT)
                        .where(APPOINTMENT_SLOT.ID.eq(id)).forUpdate().fetchSingle();
                var competingUpdate = executor.submit(() ->
                        mvc.perform(patch(SLOTS + "/{id}", id).contentType(MediaType.APPLICATION_JSON)
                                        .content("{\"capacity\":1}"))
                                .andReturn().getResponse().getStatus());
                await().atMost(5, TimeUnit.SECONDS).until(() ->
                        db.fetchOne("""
                                select exists (
                                    select 1 from pg_locks
                                    where not granted and ? = any(pg_blocking_pids(pid))
                                )
                                """, holderPid).get(0, Boolean.class));
                holder.insertInto(BOOKING, BOOKING.APPOINTMENT_SLOT_ID, BOOKING.STATUS)
                        .values(id, BookingStatus.RESERVED)
                        .values(id, BookingStatus.CONFIRMED)
                        .execute();
                return competingUpdate;
            });
            assertThat(update.get(5, TimeUnit.SECONDS)).isEqualTo(409);
            assertThat(repository.findSlotById(id).orElseThrow().capacity()).isEqualTo(3);
        } finally {
            db.deleteFrom(APPOINTMENT_SLOT).where(APPOINTMENT_SLOT.ID.eq(id)).execute();
        }
    }

    @Test
    @Transactional
    void permitsCapacityEqualToActiveBookings() throws Exception {
        Long booked = slotId("Harbor Community Clinic", "Cardiology");
        mvc.perform(patch(SLOTS + "/{id}", booked).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"capacity\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.capacity").value(2))
                .andExpectAll(availability("$", 2, 0));
        assertThat(db.fetchSingle(APPOINTMENT_SLOT, APPOINTMENT_SLOT.ID.eq(booked)).getCapacity()).isEqualTo(2);
    }

    @Test
    @Transactional
    void permitsRemovingASlotWithOnlyACancelledBooking() throws Exception {
        Long cancelled = slotId("Downtown General Hospital", "General Medicine");
        assertThat(db.fetchSingle(BOOKING, BOOKING.APPOINTMENT_SLOT_ID.eq(cancelled)).getStatus())
                .isEqualTo(CANCELLED);
        String path = SLOTS + "/" + cancelled;
        mvc.perform(patch(path).contentType(MediaType.APPLICATION_JSON).content("{\"capacity\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.capacity").value(1));
        mvc.perform(delete(path)).andExpect(status().isNoContent());
        assertThat(db.fetchExists(BOOKING, BOOKING.APPOINTMENT_SLOT_ID.eq(cancelled))).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"time order", "facility", "specialty", "capacity"})
    void rejectsInvalidCreationWithoutPersistingASlot(String invalidField) throws Exception {
        var request = invalidSlotRequest(invalidField);
        int before = db.fetchCount(APPOINTMENT_SLOT);
        mvc.perform(post(SLOTS).contentType(MediaType.APPLICATION_JSON).content("""
                        {"facilityId": %d, "specialtyId": %d, "startsAt": "%s", "endsAt": "%s", "capacity": %d}
                        """.formatted(request.facilityId(), request.specialtyId(), request.startsAt(),
                        request.endsAt(), request.capacity())))
                .andExpect(status().isBadRequest());
        assertThat(db.fetchCount(APPOINTMENT_SLOT)).isEqualTo(before);
    }

    @ParameterizedTest
    @CsvSource({"time order, 23514", "capacity, 23514", "facility, 23503", "specialty, 23503"})
    void postgresEnforcesChecksAndForeignKeysWhenRequestValidationIsBypassed(String invalidField, String sqlState) {
        var request = invalidSlotRequest(invalidField);
        int before = db.fetchCount(APPOINTMENT_SLOT);

        assertThatThrownBy(() -> repository.createSlot(request))
                .isInstanceOf(DataIntegrityViolationException.class)
                .rootCause().isInstanceOfSatisfying(SQLException.class,
                        cause -> assertThat(cause.getSQLState()).isEqualTo(sqlState));
        assertThat(db.fetchCount(APPOINTMENT_SLOT)).isEqualTo(before);
    }

    private SlotRequest invalidSlotRequest(String invalidField) {
        var start = OffsetDateTime.now().plusDays(14);
        return new SlotRequest(
                invalidField.equals("facility") ? missingFacilityId() : facilityId("Harbor Community Clinic"),
                invalidField.equals("specialty") ? missingSpecialtyId() : specialtyId("Cardiology"),
                start, invalidField.equals("time order") ? start.minusMinutes(30) : start.plusMinutes(30),
                invalidField.equals("capacity") ? 0 : 2);
    }

    private Long missingFacilityId() {
        return db.select(max(FACILITY.ID)).from(FACILITY).fetchSingle().value1() + 1;
    }

    private Long missingSpecialtyId() {
        return db.select(max(SPECIALTY.ID)).from(SPECIALTY).fetchSingle().value1() + 1;
    }

    private ResultMatcher[] availability(String path, int activeBookings, int remainingCapacity) {
        return new ResultMatcher[] {
                jsonPath(path + ".activeBookings").value(activeBookings),
                jsonPath(path + ".remainingCapacity").value(remainingCapacity)
        };
    }

    private Long slotId(String facility, String specialty) {
        return db.select(APPOINTMENT_SLOT.ID).from(APPOINTMENT_SLOT)
                .where(APPOINTMENT_SLOT.FACILITY_ID.eq(facilityId(facility)))
                .and(APPOINTMENT_SLOT.SPECIALTY_ID.eq(specialtyId(specialty)))
                .fetchSingle().value1();
    }

    private Long facilityId(String name) {
        return db.select(FACILITY.ID).from(FACILITY).where(FACILITY.NAME.eq(name)).fetchSingle().value1();
    }

    private Long specialtyId(String name) {
        return db.select(SPECIALTY.ID).from(SPECIALTY).where(SPECIALTY.NAME.eq(name)).fetchSingle().value1();
    }
}
