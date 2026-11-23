package dev.cyberjar.medflow.slot;

import dev.cyberjar.medflow.reference.ReferenceDataRepository;
import dev.cyberjar.medflow.slot.dto.SlotRequest;
import dev.cyberjar.medflow.slot.dto.SlotResponse;
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
import org.junit.jupiter.params.provider.NullSource;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
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
    @ValueSource(strings = {"99.0", "malformed", "1.0.0.0", "999999999999999.0", "<untrusted>"})
    void rejectsInvalidVersionsWithSafeProblemDetails(String version) throws Exception {
        problem(mvc.perform(get(SLOTS).header("API-Version", version).accept(MediaType.APPLICATION_PROBLEM_JSON)),
                400, "Bad Request", SLOTS)
                .andExpect(jsonPath("$.detail").value("Invalid API version. Supported versions are 1.0 and 2.0."))
                .andExpect(content().string(not(containsString(version))));
        verifyNoInteractions(service, references);
    }

    @ParameterizedTest
    @ValueSource(strings = {"v2.0", "-2.0", "garbage2.0", "2", "2.0.0"})
    void usesSpringSemanticVersionParsing(String version) throws Exception {
        when(service.findSlotById(1L)).thenReturn(
                new SlotResponse(1L, FACILITY, "Harbor", SPECIALTY, "Cardiology", start, end, 2, 0, 2));

        mvc.perform(get(SLOTS + "/1").header("API-Version", version))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.availability.capacity").value(2))
                .andExpect(jsonPath("$.capacity").doesNotExist());
    }

    @Test
    void doesNotReportInconsistentAvailabilityAsFull() throws Exception {
        when(service.findSlotById(1L)).thenReturn(
                new SlotResponse(1L, FACILITY, "Harbor", SPECIALTY, "Cardiology", start, end, 2, 3, -1));
        String path = SLOTS + "/1";
        mvc.perform(get(path)).andExpect(status().isOk()).andExpect(jsonPath("$.remainingCapacity").value(-1));
        problem(mvc.perform(get(path).header("API-Version", "2.0")), 500, "Internal Server Error", path)
                .andExpect(jsonPath("$.detail").value("The server could not complete the request"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"1.0", "2.0"})
    void callsServiceExactlyOncePerVersionedWrite(String version) throws Exception {
        when(references.existsFacilityById(FACILITY)).thenReturn(true);
        when(references.existsSpecialtyById(SPECIALTY)).thenReturn(true);
        var created = new SlotResponse(1L, FACILITY, "Harbor", SPECIALTY, "Cardiology", start, end, 2, 0, 2);
        var updated = new SlotResponse(1L, FACILITY, "Harbor", SPECIALTY, "Cardiology", start, end, 3, 0, 3);
        when(service.createSlot(any())).thenReturn(created);
        when(service.updateSlot(1L, 3)).thenReturn(updated);

        mvc.perform(post(SLOTS).header("API-Version", version)
                        .contentType(MediaType.APPLICATION_JSON).content(validBody().toString()))
                .andExpect(status().isCreated());
        mvc.perform(patch(SLOTS + "/1").header("API-Version", version)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"capacity\":3}"))
                .andExpect(status().isOk());

        verify(service).createSlot(any());
        verify(service).updateSlot(1L, 3);
        verifyNoMoreInteractions(service);
    }

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

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"1.0", "2.0"})
    void rejectsUnknownFacilityBeforeCallingService(String version) throws Exception {
        when(references.existsSpecialtyById(SPECIALTY)).thenReturn(true);
        validationError(rejectsCreation(validBody().put("facilityId", UNKNOWN), version),
                "facilityId", "Facility does not exist");
        verify(references).existsFacilityById(UNKNOWN);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"1.0", "2.0"})
    void rejectsUnknownSpecialtyBeforeCallingService(String version) throws Exception {
        when(references.existsFacilityById(FACILITY)).thenReturn(true);
        validationError(rejectsCreation(validBody().put("specialtyId", UNKNOWN), version),
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
        for (String version : Arrays.asList(null, "1.0", "2.0")) {
            for (var method : List.of(HttpMethod.GET, HttpMethod.PATCH, HttpMethod.DELETE)) {
                var request = versioned(request(method, SLOTS + "/{id}", id), version);
                if (method.equals(HttpMethod.PATCH)) {
                    request.contentType(MediaType.APPLICATION_JSON).content("{\"capacity\":3}");
                }
                parameterError(mvc.perform(request), SLOTS + "/" + id, "id", id);
            }
        }
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @CsvSource({"facilityId, 0", "specialtyId, -1", "facilityId, abc"})
    void rejectsInvalidFilters(String name, String value) throws Exception {
        parameterError(mvc.perform(get(SLOTS).param(name, value)), SLOTS, name, value);
        for (String version : List.of("1.0", "2.0")) {
            parameterError(mvc.perform(get(SLOTS).header("API-Version", version).param(name, value)),
                    SLOTS, name, value);
        }
        verifyNoInteractions(service, references);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"capacity\":0}", "{\"capacity\":-1}", "{\"capacity\":null}",
            "{}", "{\"capacity\":\"abc\"}"})
    void rejectsInvalidCapacityUpdates(String body) throws Exception {
        for (String version : Arrays.asList(null, "1.0", "2.0")) {
            var result = problem(mvc.perform(versioned(patch(SLOTS + "/1"), version)
                    .contentType(MediaType.APPLICATION_JSON).content(body)), 400, "Bad Request", SLOTS + "/1");
            if (body.contains("abc")) {
                unreadableBody(result);
            } else {
                validationError(result, "capacity", body.equals("{}") || body.contains("null")
                        ? "must not be null" : "must be greater than 0");
            }
        }
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "{"})
    void rejectsMissingBodyOrMalformedJson(String body) throws Exception {
        for (String version : Arrays.asList(null, "1.0", "2.0")) {
            unreadableBody(problem(mvc.perform(versioned(post(SLOTS), version)
                    .contentType(MediaType.APPLICATION_JSON).content(body)), 400, "Bad Request", SLOTS));
        }
        verifyNoInteractions(service, references);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"1.0", "2.0"})
    void preservesHttpProtocolErrorsAndHeaders(String version) throws Exception {
        problem(mvc.perform(versioned(put(SLOTS), version)), 405, "Method Not Allowed", SLOTS)
                .andExpect(header().string(HttpHeaders.ALLOW, allOf(containsString("GET"), containsString("POST"))));
        problem(mvc.perform(versioned(post(SLOTS), version).contentType(MediaType.TEXT_PLAIN).content("{}")),
                415, "Unsupported Media Type", SLOTS)
                .andExpect(header().string(HttpHeaders.ACCEPT, containsString("application/json")));
        when(service.findSlots(null, null)).thenReturn(List.of());
        mvc.perform(versioned(get(SLOTS), version).accept(MediaType.IMAGE_PNG))
                .andExpect(status().isNotAcceptable())
                .andExpect(header().doesNotExist(HttpHeaders.CONTENT_TYPE))
                .andExpect(content().string(""));
        when(service.findSlots(null, null)).thenAnswer(invocation -> {
            throw new HttpMediaTypeNotAcceptableException(List.of(MediaType.APPLICATION_JSON));
        });
        problem(mvc.perform(versioned(get(SLOTS), version).accept(MediaType.APPLICATION_PROBLEM_JSON)), 406, "Not Acceptable", SLOTS)
                .andExpect(header().string(HttpHeaders.ACCEPT, "application/json"));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"1.0", "2.0"})
    void returnsSafeProblemForUnexpectedDatabaseFailures(String version) throws Exception {
        String internal = "SELECT secret FROM internal_table with password: demo-secret";
        when(service.findSlots(null, null)).thenThrow(new DataIntegrityViolationException(internal));
        problem(mvc.perform(versioned(get(SLOTS), version)), 500, "Internal Server Error", SLOTS)
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
        return rejectsCreation(body, null);
    }

    private ResultActions rejectsCreation(ObjectNode body, String version) throws Exception {
        var result = problem(mvc.perform(versioned(post(SLOTS), version)
                        .contentType(MediaType.APPLICATION_JSON).content(body.toString())),
                400, "Bad Request", SLOTS);
        verifyNoInteractions(service);
        return result;
    }

    private MockHttpServletRequestBuilder versioned(MockHttpServletRequestBuilder request, String version) {
        return version == null ? request : request.header("API-Version", version);
    }
}
