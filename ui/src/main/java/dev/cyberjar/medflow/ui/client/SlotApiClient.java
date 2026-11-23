package dev.cyberjar.medflow.ui.client;

import dev.cyberjar.medflow.ui.dto.CreateSlotRequest;
import dev.cyberjar.medflow.ui.dto.ReferenceDto;
import dev.cyberjar.medflow.ui.dto.SlotDto;
import dev.cyberjar.medflow.ui.dto.UpdateCapacityRequest;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpHeaders;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriBuilder;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.ObjectReader;
import tools.jackson.databind.json.JsonMapper;

@Component
public class SlotApiClient {

    private static final String SLOTS = "/api/slots";
    private final RestClient client;
    private final ObjectReader slotsReader;
    private final ObjectReader slotReader;
    private final ObjectReader referenceReader;
    private final ObjectReader problemReader;

    public SlotApiClient(RestClient client, JsonMapper mapper) {
        this.client = client;
        var reader = mapper.reader().without(DateTimeFeature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE);
        slotsReader = reader.forType(new TypeReference<List<SlotDto>>() {});
        slotReader = reader.forType(SlotDto.class);
        problemReader = mapper.reader();
        referenceReader = reader.forType(new TypeReference<List<ReferenceDto>>() {});
    }

    public Result<List<SlotDto>> slots(Long facilityId, Long specialtyId) {
        return send(HttpMethod.GET, uri -> uri.path(SLOTS)
                .queryParamIfPresent("facilityId", Optional.ofNullable(facilityId))
                .queryParamIfPresent("specialtyId", Optional.ofNullable(specialtyId)).build(), null, slotsReader);
    }

    public Result<List<ReferenceDto>> facilities() {
        return send(HttpMethod.GET, uri -> uri.path("/api/facilities").build(), null, referenceReader);
    }

    public Result<List<ReferenceDto>> specialties() {
        return send(HttpMethod.GET, uri -> uri.path("/api/specialties").build(), null, referenceReader);
    }

    public Result<SlotDto> create(CreateSlotRequest request) {
        return send(HttpMethod.POST, uri -> uri.path(SLOTS).build(), request, slotReader);
    }

    public Result<SlotDto> updateCapacity(Long id, UpdateCapacityRequest request) {
        return send(HttpMethod.PATCH, uri -> uri.path(SLOTS + "/{id}").build(id), request, slotReader);
    }

    public Result<Void> delete(Long id) {
        return send(HttpMethod.DELETE, uri -> uri.path(SLOTS + "/{id}").build(id), null, null);
    }

    private <T> Result<T> send(HttpMethod method, Function<UriBuilder, URI> uri,
                               Object body, ObjectReader reader) {
        // The interceptor belongs to this call, so even a failed connection retains the attempted request.
        var captured = new AtomicReference<HttpExchange.Request>();
        var request = client.mutate().requestInterceptor((outgoing, bytes, execution) -> {
            captured.set(new HttpExchange.Request(outgoing.getMethod(), outgoing.getURI(),
                    HttpExchange.visibleHeaders(outgoing.getHeaders()), new String(bytes, StandardCharsets.UTF_8)));
            return execution.execute(outgoing, bytes);
        }).build().method(method).uri(uri).accept(MediaType.APPLICATION_JSON, MediaType.APPLICATION_PROBLEM_JSON);
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).body(body);
        }

        try {
            return request.exchange((outgoing, incoming) -> {
                var status = incoming.getStatusCode();
                var headers = HttpExchange.visibleHeaders(incoming.getHeaders());
                String text;
                try {
                    text = new String(incoming.getBody().readAllBytes(), responseCharset(incoming.getHeaders()));
                } catch (IOException e) {
                    var exchange = new HttpExchange(captured.get(), new HttpExchange.Response(status, headers, null));
                    return new Result<>(null, exchange, "HTTP " + exchange.response().statusLine()
                            + " · Response body could not be read.");
                }
                var exchange = new HttpExchange(captured.get(), new HttpExchange.Response(status, headers, text));
                if (!status.is2xxSuccessful()) {
                    return new Result<>(null, exchange, errorMessage(exchange.response()));
                }
                if (reader == null) {
                    return new Result<>(null, exchange, null);
                }
                try {
                    T value = reader.readValue(text);
                    if (value != null) {
                        return new Result<>(value, exchange, null);
                    }
                } catch (JacksonException e) {
                    // Keep the actual body available to diagnose invalid JSON or an unexpected shape.
                }
                return new Result<>(null, exchange,
                        "HTTP " + exchange.response().statusLine() + " · Unexpected response body.");
            });
        } catch (ResourceAccessException e) {
            return new Result<>(null, new HttpExchange(captured.get(), null),
                    "Connection failure. Check that the MedFlow REST API is reachable.");
        }
    }

    private String errorMessage(HttpExchange.Response response) {
        var message = new StringBuilder("HTTP ").append(response.statusLine());
        try {
            if (MediaType.APPLICATION_PROBLEM_JSON.isCompatibleWith(response.headers().getContentType())) {
                var problem = problemReader.readTree(response.body());
                if (problem.path("detail").isTextual()) {
                    message.append(" · ").append(problem.path("detail").asString());
                }
                for (var error : problem.path("errors")) {
                    if (error.path("field").isTextual() && error.path("message").isTextual()) {
                        message.append(" · ").append(error.path("field").asString())
                                .append(": ").append(error.path("message").asString());
                    }
                }
            }
        } catch (JacksonException | InvalidMediaTypeException e) {
            // Keep the HTTP status and the original inspector response for malformed problems.
        }
        return message.toString();
    }

    private Charset responseCharset(HttpHeaders headers) {
        try {
            var contentType = headers.getContentType();
            if (contentType != null && contentType.getCharset() != null) {
                return contentType.getCharset();
            }
        } catch (InvalidMediaTypeException e) {
            // Keep the raw header in the inspector and use the default encoding.
        }
        return StandardCharsets.UTF_8;
    }

    public record Result<T>(T value, HttpExchange exchange, String error) {
        public boolean succeeded() {
            return error == null;
        }
    }
}
