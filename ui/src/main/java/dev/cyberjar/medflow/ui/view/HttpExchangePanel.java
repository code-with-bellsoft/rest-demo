package dev.cyberjar.medflow.ui.view;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.Pre;
import dev.cyberjar.medflow.ui.client.HttpExchange;
import java.util.Locale;
import org.springframework.http.HttpHeaders;
import org.springframework.http.InvalidMediaTypeException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

public class HttpExchangePanel extends Div {

    private final Pre request = new Pre("Waiting for an API request.");
    private final Pre response = new Pre("Waiting for an API response.");
    private final JsonMapper mapper;

    public HttpExchangePanel(JsonMapper mapper) {
        this.mapper = mapper;
        addClassName("http-inspector");
        var sections = new Div(section("Request", request), section("Response", response));
        sections.addClassName("http-sections");
        add(new H2("HTTP inspector"), sections);
    }

    public void show(HttpExchange exchange) {
        var outgoing = exchange.request();
        request.setText(outgoing == null ? "Request could not be sent."
                : format(outgoing.method() + " " + outgoing.uri(), outgoing.headers(), outgoing.body()));
        var incoming = exchange.response();
        response.setText(incoming == null ? "Connection failure · No HTTP response."
                : format(incoming.statusLine(), incoming.headers(), incoming.body()));
    }

    private Div section(String title, Pre content) {
        content.getElement().setAttribute("tabindex", "0");
        content.getElement().setAttribute("aria-label", title + " details");
        var copy = new Button("Copy " + title.toLowerCase(Locale.ROOT));
        copy.getElement().executeJs("""
                this.addEventListener('click', async () => {
                  try {
                    await navigator.clipboard.writeText($0.textContent);
                  } catch {
                    const range = document.createRange();
                    range.selectNodeContents($0);
                    const selection = window.getSelection();
                    selection.removeAllRanges();
                    selection.addRange(range);
                  }
                });
                """, content.getElement());
        var heading = new Div(new H3(title), copy);
        heading.addClassName("http-section-heading");
        return new Div(heading, content);
    }

    private String format(String firstLine, HttpHeaders headers, String body) {
        var text = new StringBuilder(firstLine).append('\n');
        headers.headerSet().forEach(header -> header.getValue().forEach(value ->
                text.append(header.getKey()).append(": ").append(value).append('\n')));
        return text.append('\n').append(displayBody(headers, body)).toString();
    }

    private String displayBody(HttpHeaders headers, String body) {
        if (body == null) {
            return "(Response body could not be read.)";
        }
        if (body.isEmpty()) {
            return "(No body)";
        }
        try {
            var contentType = headers.getContentType();
            if (contentType != null) {
                var subtype = contentType.getSubtype().toLowerCase(Locale.ROOT);
                if (subtype.equals("json") || subtype.endsWith("+json")) {
                    var json = mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(body);
                    return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(json);
                }
            }
        } catch (JacksonException | InvalidMediaTypeException e) {
            // Show the original text if a response advertises JSON but cannot be formatted.
        }
        return body;
    }
}
