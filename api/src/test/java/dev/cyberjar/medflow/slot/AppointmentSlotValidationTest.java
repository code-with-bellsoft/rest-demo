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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

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

        var result = rejectsCreation(body);
        if (input.equals("invalid")) {
            unreadableBody(result);
        } else {
            String message = switch (input) {
                case "missing", "null" -> "must not be null";
                case "past" -> "must be a future date";
                case "reversed", "equal" -> "End time must be later than start time";
                default -> "must be greater than 0";
            };
            validationError(result, field, message);
        }
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
        validationError(rejectsCreation(validBody().put("facilityId", UNKNOWN)),
                "facilityId", "Facility does not exist");
        verify(references).existsFacilityById(UNKNOWN);
    }

    @Test
    void rejectsUnknownSpecialtyBeforeCallingService() throws Exception {
        when(references.existsFacilityById(FACILITY)).thenReturn(true);
        validationError(rejectsCreation(validBody().put("specialtyId", UNKNOWN)),
                "specialtyId", "Specialty does not exist");
        verify(references).existsSpecialtyById(UNKNOWN);
    }

    @Test
    void rejectsMultipleViolationsInOneRequest() throws Exception {
        var result = rejectsCreation(validBody().put("facilityId", -1).put("specialtyId", UNKNOWN)
                .put("capacity", 0).put("endsAt", start.minusMinutes(30).toString()));
        result.andExpect(jsonPath("$.errors", hasSize(4)));
        validationError(result, "facilityId", "must be greater than 0");
        validationError(result, "specialtyId", "Specialty does not exist");
        validationError(result, "capacity", "must be greater than 0");
        validationError(result, "endsAt", "End time must be later than start time");
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
            parameterError(mvc.perform(request), SLOTS + "/" + id, "id", id);
        }
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @CsvSource({"facilityId, 0", "specialtyId, -1", "facilityId, abc"})
    void rejectsInvalidFilters(String name, String value) throws Exception {
        parameterError(mvc.perform(get(SLOTS).param(name, value)), SLOTS, name, value);
        verifyNoInteractions(service, references);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"capacity\":0}", "{\"capacity\":-1}", "{\"capacity\":null}",
            "{}", "{\"capacity\":\"abc\"}"})
    void rejectsInvalidCapacityUpdates(String body) throws Exception {
        var result = problem(mvc.perform(patch(SLOTS + "/1").contentType(MediaType.APPLICATION_JSON)
                .content(body)), 400, "Bad Request", SLOTS + "/1");
        if (body.contains("abc")) {
            unreadableBody(result);
        } else {
            validationError(result, "capacity", body.equals("{}") || body.contains("null")
                    ? "must not be null" : "must be greater than 0");
        }
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "{"})
    void rejectsMissingBodyOrMalformedJson(String body) throws Exception {
        unreadableBody(problem(mvc.perform(post(SLOTS).contentType(MediaType.APPLICATION_JSON).content(body)),
                400, "Bad Request", SLOTS));
        verifyNoInteractions(service, references);
    }

    @Test
    void preservesHttpProtocolErrorsAndHeaders() throws Exception {
        problem(mvc.perform(put(SLOTS)), 405, "Method Not Allowed", SLOTS)
                .andExpect(header().string(HttpHeaders.ALLOW, allOf(containsString("GET"), containsString("POST"))));
        problem(mvc.perform(post(SLOTS).contentType(MediaType.TEXT_PLAIN).content("{}")),
                415, "Unsupported Media Type", SLOTS)
                .andExpect(header().string(HttpHeaders.ACCEPT, containsString("application/json")));
        when(service.findSlots(null, null)).thenReturn(List.of());
        mvc.perform(get(SLOTS).accept(MediaType.IMAGE_PNG))
                .andExpect(status().isNotAcceptable())
                .andExpect(header().doesNotExist(HttpHeaders.CONTENT_TYPE))
                .andExpect(content().string(""));
        when(service.findSlots(null, null)).thenAnswer(invocation -> {
            throw new HttpMediaTypeNotAcceptableException(List.of(MediaType.APPLICATION_JSON));
        });
        problem(mvc.perform(get(SLOTS).accept(MediaType.APPLICATION_PROBLEM_JSON)), 406, "Not Acceptable", SLOTS)
                .andExpect(header().string(HttpHeaders.ACCEPT, "application/json"));
    }

    @Test
    void returnsSafeProblemForUnexpectedDatabaseFailures() throws Exception {
        String internal = "SELECT secret FROM internal_table with password: demo-secret";
        when(service.findSlots(null, null)).thenThrow(new DataIntegrityViolationException(internal));
        problem(mvc.perform(get(SLOTS)), 500, "Internal Server Error", SLOTS)
                .andExpect(jsonPath("$.detail").value("The server could not complete the request"))
                .andExpect(jsonPath("$.errors").doesNotExist())
                .andExpect(jsonPath("$.trace").doesNotExist())
                .andExpect(content().string(not(anyOf(containsString("SELECT"), containsString("internal_table"),
                        containsString("demo-secret"), containsString("DataIntegrityViolationException")))));
    }

    static ResultActions problem(ResultActions result, int statusCode, String title, String path) throws Exception {
        return result.andExpect(status().is(statusCode))
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("about:blank"))
                .andExpect(jsonPath("$.title").value(title))
                .andExpect(jsonPath("$.status").value(statusCode))
                .andExpect(jsonPath("$.detail").isNotEmpty())
                .andExpect(jsonPath("$.instance").value(path));
    }

    private void parameterError(ResultActions result, String path, String field, String value) throws Exception {
        problem(result, 400, "Bad Request", path);
        if (value.equals("abc")) {
            result.andExpect(jsonPath("$.detail").value("A request parameter has an invalid format"))
                    .andExpect(jsonPath("$.errors").doesNotExist());
        } else {
            validationError(result, field, "must be greater than 0");
        }
    }

    private void validationError(ResultActions result, String field, String message) throws Exception {
        result.andExpect(jsonPath("$.detail").value("Request validation failed"))
                .andExpect(jsonPath("$.errors[?(@.field == '%s' && @.message == '%s')]", field, message)
                        .value(hasSize(1)));
    }

    private void unreadableBody(ResultActions result) throws Exception {
        result.andExpect(jsonPath("$.detail").value("Failed to read request"))
                .andExpect(jsonPath("$.errors").doesNotExist());
    }

    private ObjectNode validBody() {
        return json.createObjectNode().put("facilityId", FACILITY).put("specialtyId", SPECIALTY)
                .put("startsAt", start.toString()).put("endsAt", end.toString()).put("capacity", 2);
    }

    private ResultActions rejectsCreation(ObjectNode body) throws Exception {
        var result = problem(mvc.perform(post(SLOTS).contentType(MediaType.APPLICATION_JSON).content(body.toString())),
                400, "Bad Request", SLOTS);
        verifyNoInteractions(service);
        return result;
    }
}
