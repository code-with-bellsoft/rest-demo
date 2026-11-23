package dev.cyberjar.medflow.ui.client;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;

public record HttpExchange(Request request, Response response) {

    // Show protocol metadata and omit credential-bearing and unknown custom headers.
    private static final Set<String> VISIBLE_HEADERS = Set.of("accept", "content-type", "content-length",
            "content-encoding", "transfer-encoding", "date", "cache-control", "location", "vary", "allow");

    static HttpHeaders visibleHeaders(HttpHeaders source) {
        var headers = new HttpHeaders();
        source.headerSet().forEach(entry -> {
            if (VISIBLE_HEADERS.contains(entry.getKey().toLowerCase(Locale.ROOT))) {
                headers.put(entry.getKey(), List.copyOf(entry.getValue()));
            }
        });
        return HttpHeaders.readOnlyHttpHeaders(headers);
    }

    public record Request(HttpMethod method, URI uri, HttpHeaders headers, String body) {
    }

    public record Response(HttpStatusCode status, HttpHeaders headers, String body) {

        public String statusLine() {
            HttpStatus known = HttpStatus.resolve(status.value());
            return known == null ? Integer.toString(status.value()) : status.value() + " " + known.getReasonPhrase();
        }
    }
}
