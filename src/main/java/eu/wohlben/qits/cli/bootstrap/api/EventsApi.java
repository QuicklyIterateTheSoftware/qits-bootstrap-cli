package eu.wohlben.qits.cli.bootstrap.api;

import com.fasterxml.jackson.databind.JsonNode;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;

/**
 * qits-events: the platform's own account of what it is doing, read one page at a time.
 * <p>
 * <b>Polling, because the bus's push door is not this program's to take.</b> The service does serve
 * a websocket, and a subscriber on it would be a durable consumer with a name and a subscription —
 * a peer of the platform's services. This run is a spectator that comes and goes, and the query API
 * answers every read a spectator has with no state on the far side at all.
 * <p>
 * <b>The cursor is composite and the ascending order is what makes it a feed.</b>
 * {@code ?cursor=<occurredAt>,<id>} resumes after one exact row — {@code occurredAt} ties by
 * construction, so a scalar cursor would drop or repeat a release fork's siblings — and
 * {@code ?order=asc} reads forward from it, which is the one direction a catch-up consumer can
 * express.
 * <p>
 * Failures are answers here as everywhere in {@link Http}: for most of a bootstrap neither the edge
 * nor this service exists, and an empty answer is the ordinary state of the first half hour.
 */
public class EventsApi {

    private final Http http;
    private final String base;

    public EventsApi(Http http, String eventsUrl) {
        this.http = http;
        this.base = eventsUrl;
    }

    public Http.Response health() {
        return http.get(base + "/q/health/ready", Map.of());
    }

    /**
     * One page of the log: {@code {"events":[…],"nextCursor":…}}, or empty when the read did not
     * answer.
     * <p>
     * {@code cursor} is null for "from the end this order starts at" — the head of the log when
     * descending, the very beginning when ascending.
     */
    public Optional<JsonNode> page(String cursor, boolean ascending, int limit) {
        StringBuilder url = new StringBuilder(base)
                .append("/api/events?order=").append(ascending ? "asc" : "desc")
                .append("&limit=").append(limit);
        if (cursor != null && !cursor.isBlank()) {
            // The cursor holds an ISO instant's colons and the comma that splits the pair. Both are
            // legal in a query string and both are encoded anyway, because the far side reads the
            // parameter after decoding and a proxy in between may not agree about what is legal.
            url.append("&cursor=").append(URLEncoder.encode(cursor, StandardCharsets.UTF_8));
        }
        Http.Response response = http.get(url.toString(), Map.of());
        return response.ok() ? Optional.of(Json.parse(response.body())) : Optional.empty();
    }
}
