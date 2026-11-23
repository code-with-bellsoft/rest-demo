package dev.cyberjar.medflow.slot;

import dev.cyberjar.medflow.reference.ReferenceDataRepository;
import dev.cyberjar.medflow.slot.dto.SlotRequest;
import dev.cyberjar.medflow.slot.validation.EndAfterStart;
import dev.cyberjar.medflow.slot.validation.ExistingFacility;
import dev.cyberjar.medflow.slot.validation.ExistingSpecialty;
import jakarta.validation.ValidationException;
import jakarta.validation.Validator;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AppointmentSlotController.class)
class AppointmentSlotValidationTest {

    private static final String SLOTS = "/api/slots";
    private static final long FACILITY = 12L;
    private static final long SPECIALTY = 34L;
    private static final long UNKNOWN = 99L;

    @Autowired
    MockMvc mvc;

    @Autowired
    Validator validator;

    @Autowired
    JsonMapper json;

    @MockitoBean
    ReferenceDataRepository references;

    @MockitoBean
    AppointmentSlotService service;

    private final OffsetDateTime start = OffsetDateTime.now(ZoneOffset.UTC).plusDays(2);
    private final OffsetDateTime end = start.plusMinutes(30);

    @ParameterizedTest
    @CsvSource({
            "facilityId, missing", "facilityId, null", "facilityId, 0", "facilityId, -1",
            "specialtyId, missing", "specialtyId, null", "specialtyId, 0", "specialtyId, -1",
            "startsAt, missing", "endsAt, missing", "startsAt, past", "endsAt, past",
            "endsAt, reversed", "endsAt, equal",
            "capacity, missing", "capacity, null", "capacity, 0", "capacity, -1",
            "startsAt, invalid", "capacity, invalid"
    })
    void rejectsInvalidCreationBeforeCallingService(String field, String input) throws Exception {
        when(references.existsFacilityById(FACILITY)).thenReturn(true);
        when(references.existsSpecialtyById(SPECIALTY)).thenReturn(true);
        var body = validBody();
        switch (input) {
            case "missing" -> body.remove(field);
            case "null" -> body.putNull(field);
            case "past" -> body.put(field, start.minusDays(4).toString());
            case "reversed" -> body.put(field, start.minusMinutes(30).toString());
            case "equal" -> body.put(field, start.withOffsetSameInstant(ZoneOffset.ofHours(2)).toString());
            case "invalid" -> body.put(field, "abc");
            default -> body.put(field, Integer.parseInt(input));
        }

        rejectsCreation(body);
    }

    @ParameterizedTest
    @ValueSource(strings = {"startsAt", "endsAt"})
    void appliesFutureToEachTimestampIndependently(String field) {
        assertThat(validator.validateValue(SlotRequest.class, field, start.minusDays(4)))
                .singleElement().satisfies(violation ->
                        assertThat(violation.getConstraintDescriptor().getAnnotation().annotationType())
                                .isEqualTo(Future.class));
    }

    @Test
    void rejectsUnknownFacilityBeforeCallingService() throws Exception {
        when(references.existsSpecialtyById(SPECIALTY)).thenReturn(true);
        rejectsCreation(validBody().put("facilityId", UNKNOWN));
        verify(references).existsFacilityById(UNKNOWN);
    }

    @Test
    void rejectsUnknownSpecialtyBeforeCallingService() throws Exception {
        when(references.existsFacilityById(FACILITY)).thenReturn(true);
        rejectsCreation(validBody().put("specialtyId", UNKNOWN));
        verify(references).existsSpecialtyById(UNKNOWN);
    }

    @Test
    void rejectsMultipleViolationsInOneRequest() throws Exception {
        rejectsCreation(validBody().put("facilityId", -1).put("specialtyId", UNKNOWN)
                .put("capacity", 0).put("endsAt", start.minusMinutes(30).toString()));
        verify(references).existsSpecialtyById(UNKNOWN);
        verifyNoMoreInteractions(references);
    }

    @ParameterizedTest
    @CsvSource(value = {"null", "0", "-1"}, nullValues = "null")
    void referenceValidatorsDeferNullAndNonpositiveIdsWithoutLookingThemUp(Long id) {
        var request = new SlotRequest(id, id, start, end, 2);
        assertThat(validator.validate(request)).hasSize(2).allSatisfy(violation ->
                assertThat(violation.getConstraintDescriptor().getAnnotation().annotationType())
                        .isEqualTo(id == null ? NotNull.class : Positive.class));
        verifyNoInteractions(references);
    }

    @Test
    void springInjectsRepositoryIntoBothValidatorsAndFindsExistingReferences() {
        when(references.existsFacilityById(FACILITY)).thenReturn(true);
        when(references.existsSpecialtyById(SPECIALTY)).thenReturn(true);

        assertThat(validator.validate(new SlotRequest(FACILITY, SPECIALTY, start, end, 2))).isEmpty();
        verify(references).existsFacilityById(FACILITY);
        verify(references).existsSpecialtyById(SPECIALTY);
    }

    @Test
    void associatesEachCustomViolationWithItsField() {
        var violations = validator.validate(new SlotRequest(UNKNOWN, UNKNOWN, start,
                start.withOffsetSameInstant(ZoneOffset.ofHours(2)), 2));
        assertThat(violations).hasSize(3).allSatisfy(violation -> {
            var annotation = violation.getConstraintDescriptor().getAnnotation().annotationType();
            var field = violation.getPropertyPath().toString();
            switch (field) {
                case "facilityId" -> assertThat(annotation).isEqualTo(ExistingFacility.class);
                case "specialtyId" -> assertThat(annotation).isEqualTo(ExistingSpecialty.class);
                case "endsAt" -> assertThat(annotation).isEqualTo(EndAfterStart.class);
                default -> throw new AssertionError("Unexpected violation on " + field);
            }
        });
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void repositoryFailurePropagatesThroughSpringValidation(boolean facilityLookup) {
        var failure = new DataAccessResourceFailureException("Database unavailable");
        if (facilityLookup) {
            when(references.existsFacilityById(FACILITY)).thenThrow(failure);
        } else {
            when(references.existsSpecialtyById(SPECIALTY)).thenThrow(failure);
        }

        assertThatThrownBy(() -> validator.validate(new SlotRequest(FACILITY, SPECIALTY, start, end, 2)))
                .isInstanceOf(ValidationException.class)
                .hasRootCause(failure);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "abc"})
    void rejectsInvalidPathIdsForAllSlotOperations(String id) throws Exception {
        for (var method : new HttpMethod[]{HttpMethod.GET, HttpMethod.PATCH, HttpMethod.DELETE}) {
            var request = request(method, SLOTS + "/{id}", id);
            if (method.equals(HttpMethod.PATCH)) {
                request.contentType(MediaType.APPLICATION_JSON).content("{\"capacity\":3}");
            }
            mvc.perform(request).andExpect(status().isBadRequest());
        }
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @CsvSource({"facilityId, 0", "specialtyId, -1", "facilityId, abc"})
    void rejectsInvalidFilters(String name, String value) throws Exception {
        mvc.perform(get(SLOTS).param(name, value)).andExpect(status().isBadRequest());
        verifyNoInteractions(service, references);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"capacity\":0}", "{\"capacity\":-1}", "{\"capacity\":null}",
            "{}", "{\"capacity\":\"abc\"}"})
    void rejectsInvalidCapacityUpdates(String body) throws Exception {
        mvc.perform(patch(SLOTS + "/1").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    private ObjectNode validBody() {
        return json.createObjectNode().put("facilityId", FACILITY).put("specialtyId", SPECIALTY)
                .put("startsAt", start.toString()).put("endsAt", end.toString()).put("capacity", 2);
    }

    private void rejectsCreation(ObjectNode body) throws Exception {
        mvc.perform(post(SLOTS).contentType(MediaType.APPLICATION_JSON).content(body.toString()))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
}
