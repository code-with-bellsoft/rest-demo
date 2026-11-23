package dev.cyberjar.medflow.slot;

import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.OffsetDateTime;

import static dev.cyberjar.medflow.Tables.APPOINTMENT_SLOT;
import static dev.cyberjar.medflow.Tables.BOOKING;
import static dev.cyberjar.medflow.Tables.FACILITY;
import static dev.cyberjar.medflow.Tables.SPECIALTY;
import static dev.cyberjar.medflow.enums.BookingStatus.CANCELLED;
import static org.assertj.core.api.Assertions.assertThat;
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
                .andExpect(jsonPath("$[3].id").value(is(cancelled), Long.class));
        mvc.perform(get(SLOTS + "/{id}", full))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(is(full), Long.class))
                .andExpect(jsonPath("$.capacity").value(1));
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
                .andExpect(jsonPath("$[1].facilityName").value("Harbor Community Clinic"));
        mvc.perform(get(SLOTS).param("specialtyId", cardiologyId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].specialtyName").value("Cardiology"))
                .andExpect(jsonPath("$[1].specialtyName").value("Cardiology"));
        mvc.perform(get(SLOTS).param("facilityId", harborId).param("specialtyId", cardiologyId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(is(slotId("Harbor Community Clinic", "Cardiology")), Long.class));
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

    @Test
    void returnsNotFoundForMissingSlots() throws Exception {
        Long missing = db.select(max(APPOINTMENT_SLOT.ID))
                .from(APPOINTMENT_SLOT).fetchSingle().value1() + 1;
        String path = SLOTS + "/" + missing;

        mvc.perform(get(path)).andExpect(status().isNotFound());
        mvc.perform(patch(path).contentType(MediaType.APPLICATION_JSON).content("{\"capacity\":3}"))
                .andExpect(status().isNotFound());
        mvc.perform(delete(path)).andExpect(status().isNotFound());
    }

    @Test
    void refusesToReduceCapacityBelowActiveBookingsOrDeleteABookedSlot() throws Exception {
        Long booked = slotId("Harbor Community Clinic", "Cardiology");
        String path = SLOTS + "/" + booked;
        mvc.perform(patch(path).contentType(MediaType.APPLICATION_JSON).content("{\"capacity\":1}"))
                .andExpect(status().isConflict());
        mvc.perform(delete(path)).andExpect(status().isConflict());
        assertThat(db.fetchSingle(APPOINTMENT_SLOT, APPOINTMENT_SLOT.ID.eq(booked)).getCapacity()).isEqualTo(3);
        assertThat(db.fetchCount(BOOKING, BOOKING.APPOINTMENT_SLOT_ID.eq(booked))).isEqualTo(2);
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

    @Test
    void rejectsAnInvalidTimeRangeAsABadRequest() throws Exception {
        OffsetDateTime start = OffsetDateTime.now().plusDays(14);
        mvc.perform(post(SLOTS).contentType(MediaType.APPLICATION_JSON).content("""
                        {"facilityId": %d, "specialtyId": %d, "startsAt": "%s", "endsAt": "%s", "capacity": 2}
                        """.formatted(facilityId("Harbor Community Clinic"), specialtyId("Cardiology"),
                        start, start.minusMinutes(30))))
                .andExpect(status().isBadRequest());
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
