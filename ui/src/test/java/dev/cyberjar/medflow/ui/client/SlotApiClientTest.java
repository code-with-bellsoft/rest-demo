package dev.cyberjar.medflow.ui.client;

import com.sun.net.httpserver.HttpServer;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.Pre;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.select.Select;
import com.vaadin.flow.component.textfield.IntegerField;
import com.vaadin.flow.server.VaadinSession;
import com.vaadin.flow.dom.Element;
import dev.cyberjar.medflow.ui.dto.CreateSlotRequest;
import dev.cyberjar.medflow.ui.dto.ReferenceDto;
import dev.cyberjar.medflow.ui.dto.SlotDto;
import dev.cyberjar.medflow.ui.dto.UpdateCapacityRequest;
import dev.cyberjar.medflow.ui.view.SlotView;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class SlotApiClientTest {

    private static final String SLOT = """
            {"id":42,"facilityId":2,"facilityName":"Harbor","specialtyId":3,"specialtyName":"Cardiology",
             "startsAt":"2030-05-01T10:00:00+03:00","endsAt":"2030-05-01T10:30:00+03:00","capacity":2,"activeBookings":2,"remainingCapacity":0}""";
    private static final String CREATED_SLOT = SLOT.replace("\"activeBookings\":2,\"remainingCapacity\":0",
            "\"activeBookings\":0,\"remainingCapacity\":2");
    private static final String FACILITIES = "[{\"id\":2,\"name\":\"Harbor\"}]";
    private static final String SPECIALTIES = "[{\"id\":3,\"name\":\"Cardiology\"}]";

    private static final String ACCEPT = "application/json, application/problem+json";
    private static final String VALIDATION_PROBLEM = """
            {"type":"about:blank","title":"Bad Request","status":400,
             "detail":"Request validation failed","instance":"/api/slots/42",
             "errors":[{"field":"capacity","message":"must be greater than 0"}]}""";
    private static final String PROBLEM_JSON = "application/problem+json";

    private final Queue<Reply> replies = new ConcurrentLinkedQueue<>();
    private final Queue<SeenRequest> requests = new ConcurrentLinkedQueue<>();
    private final JsonMapper mapper = JsonMapper.builder().build();
    private HttpServer server;
    private URI baseUrl;
    private SlotApiClient client;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try (exchange) {
                requests.add(new SeenRequest(exchange.getRequestMethod(), exchange.getRequestURI().toString(),
                        exchange.getRequestHeaders().getFirst("Content-Type"),
                        exchange.getRequestHeaders().getFirst("Accept"),
                        exchange.getRequestHeaders().getFirst("API-Version"),
                        new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
                var reply = replies.remove();
                if (reply.contentType() != null) {
                    exchange.getResponseHeaders().set("Content-Type", reply.contentType());
                }
                if (reply.location() != null) {
                    exchange.getResponseHeaders().set("Location", reply.location());
                }
                exchange.getResponseHeaders().set("Set-Cookie", "demo=secret");
                if (exchange.getRequestMethod().equals("GET") && exchange.getRequestURI().getPath().equals("/api/slots")) {
                    exchange.getResponseHeaders().set("Vary", "API-Version");
                }
                var bytes = reply.body().getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(reply.status(), bytes.length == 0 ? -1 : bytes.length);
                exchange.getResponseBody().write(bytes);
            }
        });
        server.start();
        baseUrl = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        var builder = RestClient.builder().configureMessageConverters(
                converters -> converters.withJsonConverter(new JacksonJsonHttpMessageConverter(mapper)));
        client = new SlotApiClient(new ApiClientConfig().medFlowRestClient(builder, baseUrl), mapper);
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void readsSlotCollectionAndSendsEachFilterCombination() {
        for (int i = 0; i < 4; i++) {
            replies.add(Reply.json(200, "[" + SLOT + "]"));
        }

        var result = client.slots(ApiVersion.V1, null, null);
        assertThat(result.succeeded()).isTrue();
        assertThat(result.value()).containsExactly(new SlotDto(42L, 2L, "Harbor", 3L, "Cardiology",
                OffsetDateTime.parse("2030-05-01T10:00:00+03:00"),
                OffsetDateTime.parse("2030-05-01T10:30:00+03:00"), 2, 2, 0));
        assertThat(result.exchange().response().body()).isEqualTo("[" + SLOT + "]");
        assertThat(result.exchange().response().status().value()).isEqualTo(200);
        client.slots(ApiVersion.V1, 2L, null);
        client.slots(ApiVersion.V1, null, 3L);
        client.slots(ApiVersion.V1, 2L, 3L);

        assertThat(requests).extracting(SeenRequest::path).containsExactly(
                "/api/slots", "/api/slots?facilityId=2", "/api/slots?specialtyId=3",
                "/api/slots?facilityId=2&specialtyId=3");
    }

    @Test
    void readsFacilitiesAndSpecialties() {
        replies.add(Reply.json(200, FACILITIES));
        replies.add(Reply.json(200, SPECIALTIES));

        assertThat(client.facilities().value()).containsExactly(new ReferenceDto(2L, "Harbor"));
        assertThat(client.specialties().value()).containsExactly(new ReferenceDto(3L, "Cardiology"));
        assertThat(requests).extracting(SeenRequest::path).containsExactly("/api/facilities", "/api/specialties");
        assertThat(requests).extracting(SeenRequest::method).containsOnly("GET");
    }

    @Test
    void postsJsonAndCapturesTheActualExchangeIncludingLocation() {
        replies.add(new Reply(201, "application/json", "/api/slots/42", CREATED_SLOT));
        var start = OffsetDateTime.parse("2030-05-01T07:00:00Z");
        var result = client.create(ApiVersion.V1, new CreateSlotRequest(2L, 3L, start, start.plusMinutes(30), 2));
        var seen = requests.remove();
        assertThat(seen.method()).isEqualTo("POST");
        assertThat(seen.path()).isEqualTo("/api/slots");
        assertThat(seen.contentType()).isEqualTo("application/json");
        assertThat(mapper.readTree(seen.body())).isEqualTo(mapper.readTree("""
                {"facilityId":2,"specialtyId":3,"startsAt":"2030-05-01T07:00:00Z",
                 "endsAt":"2030-05-01T07:30:00Z","capacity":2}"""));

        assertThat(result.succeeded()).isTrue();
        assertThat(result.value().id()).isEqualTo(42L);
        var request = result.exchange().request();
        assertThat(request.method()).isEqualTo(HttpMethod.POST);
        assertThat(request.uri()).isEqualTo(baseUrl.resolve(seen.path()));
        assertThat(request.headers().getFirst("Content-Type")).isEqualTo(seen.contentType());
        assertThat(request.headers().getFirst("Accept")).isEqualTo(seen.accept());
        assertThat(request.body()).isEqualTo(seen.body());

        var response = result.exchange().response();
        assertThat(response.statusLine()).isEqualTo("201 Created");
        assertThat(response.headers().getLocation()).isEqualTo(URI.create("/api/slots/42"));
        assertThat(response.headers().getFirst("Content-Type")).isEqualTo("application/json");
        assertThat(response.headers().containsHeader("Set-Cookie")).isFalse();
        assertThat(response.body()).isEqualTo(CREATED_SLOT);
    }

    @Test
    void patchesOnlyCapacityAndLetsTheApiRejectZero() {
        replies.add(Reply.json(200, SLOT.replace("\"capacity\":2", "\"capacity\":3")
                .replace("\"remainingCapacity\":0", "\"remainingCapacity\":1")));
        replies.add(new Reply(400, PROBLEM_JSON, null, VALIDATION_PROBLEM));

        var updated = client.updateCapacity(ApiVersion.V1, 42L, new UpdateCapacityRequest(3));
        assertThat(updated.succeeded()).isTrue();
        assertThat(updated.value().capacity()).isEqualTo(3);
        assertThat(updated.value().activeBookings()).isEqualTo(2);
        assertThat(updated.value().remainingCapacity()).isEqualTo(1);
        assertThat(requests.remove()).isEqualTo(new SeenRequest("PATCH", "/api/slots/42",
                "application/json", ACCEPT, "1.0", "{\"capacity\":3}"));

        var rejected = client.updateCapacity(ApiVersion.V1, 42L, new UpdateCapacityRequest(0));
        assertThat(requests.remove().body()).isEqualTo("{\"capacity\":0}");
        assertThat(rejected.error()).isEqualTo("HTTP 400 Bad Request · Request validation failed · capacity: must be greater than 0");
        assertThat(rejected.exchange().response().body()).isEqualTo(VALIDATION_PROBLEM);
    }

    @Test
    void deletesTheResourceAndHandlesNoContent() {
        replies.add(new Reply(204, null, null, ""));
        var result = client.delete(ApiVersion.V1, 42L);

        assertThat(requests.remove()).isEqualTo(new SeenRequest("DELETE", "/api/slots/42",
                null, ACCEPT, "1.0", ""));
        assertThat(result.succeeded()).isTrue();
        assertThat(result.exchange().response().statusLine()).isEqualTo("204 No Content");
        assertThat(result.exchange().response().body()).isEmpty();
    }

    @Test
    void preservesErrorBodiesIncludingPlainTextServerErrors() {
        String missingBody = "{\"status\":404,\"detail\":\"Slot with id 42 not found\"}";
        String conflictBody = "{\"status\":409,\"detail\":\"Cannot delete a slot with active bookings.\"}";
        replies.add(new Reply(404, PROBLEM_JSON, null, missingBody));
        replies.add(new Reply(409, PROBLEM_JSON, null, conflictBody));
        replies.add(new Reply(500, PROBLEM_JSON, null,
                "{\"status\":500,\"detail\":\"The server could not complete the request\"}"));
        replies.add(new Reply(503, "text/plain", null, "Service temporarily unavailable"));
        replies.add(new Reply(502, PROBLEM_JSON, null, "{"));

        var missing = client.delete(ApiVersion.V1, 42L);
        assertThat(missing.error()).isEqualTo("HTTP 404 Not Found · Slot with id 42 not found");
        assertThat(missing.exchange().response().body()).isEqualTo(missingBody);
        var conflict = client.delete(ApiVersion.V1, 42L);
        assertThat(conflict.error()).isEqualTo("HTTP 409 Conflict · Cannot delete a slot with active bookings.");
        assertThat(conflict.exchange().response().body()).isEqualTo(conflictBody);
        assertThat(client.slots(ApiVersion.V1, null, null).error())
                .isEqualTo("HTTP 500 Internal Server Error · The server could not complete the request");
        var unavailable = client.slots(ApiVersion.V1, null, null);
        assertThat(unavailable.error()).isEqualTo("HTTP 503 Service Unavailable");
        assertThat(unavailable.exchange().response().body()).isEqualTo("Service temporarily unavailable");
        var malformed = client.slots(ApiVersion.V1, null, null);
        assertThat(malformed.error()).isEqualTo("HTTP 502 Bad Gateway");
        assertThat(malformed.exchange().response().body()).isEqualTo("{");
    }

    @Test
    void preservesAttemptedReadAndWriteWhenTheServerStops() {
        server.stop(0);
        var read = client.slots(ApiVersion.V1, null, null);
        var write = client.delete(ApiVersion.V1, 42L);

        assertThat(read.error()).startsWith("Connection failure");
        assertThat(read.exchange().request().uri()).isEqualTo(baseUrl.resolve("/api/slots"));
        assertThat(read.exchange().response()).isNull();
        assertThat(write.error()).startsWith("Connection failure");
        assertThat(write.exchange().request().uri()).isEqualTo(baseUrl.resolve("/api/slots/42"));
        assertThat(write.exchange().response()).isNull();
    }

    @Test
    void readsVersionTwoWriteResponsesAndKeepsTheWireBody() {
        var start = OffsetDateTime.parse("2030-05-01T10:00:00+03:00");
        String createdBody = v2(CREATED_SLOT);
        String updatedBody = v2(CREATED_SLOT.replace("\"capacity\":2", "\"capacity\":3")
                .replace("\"remainingCapacity\":2", "\"remainingCapacity\":3"));
        replies.add(new Reply(201, "application/json", "/api/slots/42", createdBody));
        replies.add(Reply.json(200, updatedBody));

        var created = client.create(ApiVersion.V2,
                new CreateSlotRequest(2L, 3L, start, start.plusMinutes(30), 2));
        assertThat(created.value().capacity()).isEqualTo(2);
        assertThat(created.value().startsAt()).isEqualTo(start);
        assertThat(created.exchange().response().body()).isEqualTo(createdBody);
        assertThat(created.exchange().response().headers().getFirst("Location")).isEqualTo("/api/slots/42");
        assertThat(created.exchange().request().headers().getFirst("API-Version")).isEqualTo("2.0");

        var updated = client.updateCapacity(ApiVersion.V2, 42L, new UpdateCapacityRequest(3));
        assertThat(updated.value().remainingCapacity()).isEqualTo(3);
        assertThat(updated.exchange().response().body()).isEqualTo(updatedBody);
        assertThat(requests).extracting(SeenRequest::version).containsExactly("2.0", "2.0");
        assertThat(requests).extracting(SeenRequest::method).containsExactly("POST", "PATCH");
    }

    @Test
    void vaadinViewKeepsTheWriteInTheInspectorAfterRefreshingTheGrid() {
        replies.add(Reply.json(200, FACILITIES));
        replies.add(Reply.json(200, SPECIALTIES));
        replies.add(Reply.json(200, "[" + SLOT + "]"));
        var view = new SlotView(client, mapper);
        var ui = new UI();
        ui.getInternals().setSession(mock(VaadinSession.class));
        UI.setCurrent(ui);
        try {
            ui.add(view);
            view.afterNavigation(null);

            assertThat(requests).extracting(SeenRequest::path)
                    .containsExactly("/api/facilities", "/api/specialties", "/api/slots");
            @SuppressWarnings("unchecked")
            var grid = (Grid<SlotDto>) descendants(view, Grid.class).findFirst().orElseThrow();
            var slot = grid.getGenericDataView().getItems().findFirst().orElseThrow();
            assertThat(renderColumn(grid, slot, "Capacity")).isEqualTo(2);
            assertThat(renderColumn(grid, slot, "Booked")).isEqualTo(2);
            assertThat(renderColumn(grid, slot, "Remaining")).isZero();
            grid.select(slot);
            assertThat(descendants(view, Button.class)
                    .filter(button -> button.getText().equals("Edit capacity") || button.getText().equals("Delete"))
                    .filter(Button::isEnabled).count()).isEqualTo(2);
            assertThat(descendants(view, Pre.class).map(Pre::getText))
                    .anyMatch(text -> text.startsWith("200 OK") && text.contains("Cardiology")
                            && text.contains("\"activeBookings\" : 2") && text.contains("\"remainingCapacity\" : 0"));

            @SuppressWarnings("unchecked")
            var version = (Select<ApiVersion>) descendants(view, Select.class).findFirst().orElseThrow();
            assertThat(version.getValue()).isEqualTo(ApiVersion.V1);
            assertThat(requests.stream().toList().getLast().version()).isEqualTo("1.0");

            @SuppressWarnings("unchecked")
            var facility = (ComboBox<ReferenceDto>) descendants(view, ComboBox.class)
                    .filter(box -> "Facility".equals(box.getLabel())).findFirst().orElseThrow();
            @SuppressWarnings("unchecked")
            var specialty = (ComboBox<ReferenceDto>) descendants(view, ComboBox.class)
                    .filter(box -> "Specialty".equals(box.getLabel())).findFirst().orElseThrow();
            replies.add(Reply.json(200, "[" + SLOT + "]"));
            facility.setValue(new ReferenceDto(2L, "Harbor"));
            replies.add(Reply.json(200, "[" + SLOT + "]"));
            specialty.setValue(new ReferenceDto(3L, "Cardiology"));
            String filteredPath = "/api/slots?facilityId=2&specialtyId=3";
            requests.clear();

            replies.add(Reply.json(200, "[" + v2(SLOT) + "]"));
            version.setValue(ApiVersion.V2);
            assertThat(requests).singleElement().satisfies(sent -> {
                assertThat(sent.version()).isEqualTo("2.0");
                assertThat(sent.path()).isEqualTo(filteredPath);
            });
            assertThat(grid.getGenericDataView().getItems()).containsExactly(slot);
            assertThat(renderColumn(grid, slot, "Capacity")).isEqualTo(2);
            assertThat(renderColumn(grid, slot, "Booked")).isEqualTo(2);
            assertThat(renderColumn(grid, slot, "Remaining")).isZero();
            assertThat(descendants(view, Pre.class).map(Pre::getText))
                    .anyMatch(text -> text.startsWith("GET ") && text.contains("API-Version: 2.0"))
                    .anyMatch(text -> text.contains("\"availability\"") && text.toLowerCase(Locale.ROOT).contains("vary: api-version"));

            replies.add(Reply.json(200, "[" + SLOT + "]"));
            version.setValue(ApiVersion.V1);
            assertThat(requests.stream().toList().getLast().path()).isEqualTo(filteredPath);
            assertThat(requests.stream().toList().getLast().version()).isEqualTo("1.0");
            assertThat(descendants(view, Pre.class).map(Pre::getText))
                    .anyMatch(text -> text.startsWith("200 OK") && text.contains("\"activeBookings\"")
                            && !text.contains("\"availability\""));

            replies.add(Reply.json(200, "[" + v2(SLOT) + "]"));
            version.setValue(ApiVersion.V2);
            grid.select(grid.getGenericDataView().getItems().findFirst().orElseThrow());
            requests.clear();

            String updatedSlot = SLOT.replace("\"capacity\":2", "\"capacity\":3")
                    .replace("\"remainingCapacity\":0", "\"remainingCapacity\":1");
            replies.add(new Reply(400, PROBLEM_JSON, null, VALIDATION_PROBLEM));
            replies.add(Reply.json(200, v2(updatedSlot)));
            replies.add(Reply.json(200, "[" + v2(updatedSlot) + "]"));
            descendants(view, Button.class).filter(button -> button.getText().equals("Edit capacity"))
                    .findFirst().orElseThrow().click();
            ui.getInternals().getStateTree().runExecutionsBeforeClientResponse();
            var dialog = descendants(ui, Dialog.class).findFirst().orElseThrow();
            var capacity = descendants(dialog, IntegerField.class).findFirst().orElseThrow();
            var save = dialog.getFooter().getElement().getChildren().flatMap(element -> element.getComponent().stream())
                    .filter(Button.class::isInstance).map(Button.class::cast)
                    .filter(button -> button.getText().equals("Save capacity"))
                    .findFirst().orElseThrow();

            capacity.setValue(0);
            save.click();
            assertThat(dialog.isOpened()).isTrue();
            assertThat(requests).extracting(SeenRequest::method).containsExactly("PATCH");
            assertThat(descendants(view, Pre.class).map(Pre::getText)).anyMatch(text ->
                    text.startsWith("400 Bad Request") && text.contains(PROBLEM_JSON)
                            && text.endsWith(VALIDATION_PROBLEM));
            ui.getInternals().getStateTree().runExecutionsBeforeClientResponse();
            assertThat(descendants(ui, Notification.class).map(notification ->
                    notification.getElement().getProperty("text", "")))
                    .anyMatch(text -> text.contains("capacity: must be greater than 0"));

            capacity.setValue(3);
            save.click();
            assertThat(requests).extracting(SeenRequest::method)
                    .containsExactly("PATCH", "PATCH", "GET");
            assertThat(requests).extracting(SeenRequest::version).containsOnly("2.0");
            assertThat(grid.getGenericDataView().getItems()).singleElement()
                    .satisfies(updated -> assertThat(updated.capacity()).isEqualTo(3));
            assertThat(descendants(view, Pre.class).map(Pre::getText)).anyMatch(text ->
                    text.startsWith("PATCH ") && text.contains("/api/slots/42") && text.contains("API-Version: 2.0"));
            assertThat(descendants(view, Pre.class).map(Pre::getText)).anyMatch(text ->
                    text.startsWith("200 OK") && text.contains("\"availability\""));

            server.stop(0);
            descendants(view, Button.class).filter(button -> button.getText().equals("Refresh"))
                    .findFirst().orElseThrow().click();
            assertThat(grid.getGenericDataView().getItems().count()).isZero();
            assertThat(descendants(view, Pre.class).map(Pre::getText))
                    .contains("Connection failure · No HTTP response.");
        } finally {
            UI.setCurrent(null);
        }
    }

    private String v2(String flat) {
        var slot = (ObjectNode) mapper.readTree(flat);
        var availability = slot.putObject("availability");
        for (String field : List.of("capacity", "activeBookings", "remainingCapacity")) {
            availability.set(field, slot.remove(field));
        }
        availability.put("status", availability.required("remainingCapacity").asInt() > 0 ? "AVAILABLE" : "FULL");
        return slot.toString();
    }

    private int renderColumn(Grid<SlotDto> grid, SlotDto slot, String header) {
        var column = grid.getColumns().stream().filter(candidate -> header.equals(candidate.getHeaderText()))
                .findFirst().orElseThrow();
        var element = new Element("vaadin-grid-column");
        var data = mapper.createObjectNode();
        column.getRenderer().render(element, grid.getDataCommunicator().getKeyMapper(), null)
                .getDataGenerator().orElseThrow().generateData(slot, data);
        return data.required(element.getProperty("path")).asInt();
    }

    private static <T extends Component> Stream<T> descendants(Component root, Class<T> type) {
        return Stream.concat(Stream.of(root),
                root.getChildren().flatMap(child -> descendants(child, Component.class)))
                .filter(type::isInstance).map(type::cast);
    }

    private record Reply(int status, String contentType, String location, String body) {
        static Reply json(int status, String body) {
            return new Reply(status, "application/json", null, body);
        }
    }

    private record SeenRequest(String method, String path, String contentType, String accept, String version, String body) {
    }
}
