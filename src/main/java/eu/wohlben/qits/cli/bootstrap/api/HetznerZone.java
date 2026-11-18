package eu.wohlben.qits.cli.bootstrap.api;

import com.fasterxml.jackson.databind.JsonNode;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * <b>The domain's zone at Hetzner, READ and never written</b> (qits-2293): the RRSets the edge
 * labelled {@code qits-exposure=<public|private>}, every page. The edge is the zone's one writer
 * (qits-2292's {@code HostRecords}); this run only asks whether its records are there.
 * <p>
 * <b>Why the zone is asked rather than a resolver.</b> A lookup of a name before its record exists
 * caches NXDOMAIN for the zone's negative TTL — an hour on wohlben.eu — in the host's resolver and
 * dockerd's alike, so a correct record arriving a minute later still stalls the boot. The API
 * answers what the zone holds and caches nothing.
 * <p>
 * Same endpoint and labels as qits-edge-service's {@code acme/HetznerAddressRecords}. Hetzner is
 * no qits service, so there is no pact row for it, and the class is named off the {@code *Api}
 * pattern {@code BootstrapCallGuardTest} runs. <b>The token is a header and nothing else</b>: no
 * failure text carries it.
 */
public class HetznerZone {

    /** Hetzner Cloud's API root. */
    public static final String API = "https://api.hetzner.cloud/v1";

    /** The edge's ownership label, and its two values. */
    public static final String OWNER_LABEL = "qits-exposure";
    public static final String PUBLIC = "public";
    public static final String PRIVATE = "private";

    private static final int PAGE_SIZE = 100;

    /** One RRSet: its name relative to the zone ({@code @} for the apex), its type, its values. */
    public record RrSet(String name, String type, List<String> values) {
    }

    /** What one read found, or why it found nothing: {@code failure} is null on a good read. */
    public record Listing(List<RrSet> rrsets, String failure) {
        public boolean ok() {
            return failure == null;
        }
    }

    private final Http http;
    private final String api;
    private final String token;
    private final String zone;

    public HetznerZone(Http http, String token, String zone) {
        this(http, API, token, zone);
    }

    public HetznerZone(Http http, String api, String token, String zone) {
        this.http = http;
        this.api = api;
        this.token = token;
        this.zone = zone.toLowerCase(Locale.ROOT);
    }

    /** The url of one page of the RRSets labelled {@code qits-exposure=<exposure>}. */
    String page(String exposure, int page) {
        return api + "/zones/" + encode(zone) + "/rrsets?label_selector="
                + encode(OWNER_LABEL + "=" + exposure) + "&per_page=" + PAGE_SIZE + "&page=" + page;
    }

    /**
     * Every RRSet labelled {@code qits-exposure=<exposure>}, following
     * {@code meta.pagination.next_page} to the end. A refused or unreadable page is the whole
     * read's failure: half a zone would read as records missing.
     */
    public Listing labelled(String exposure) {
        List<RrSet> found = new ArrayList<>();
        int page = 1;
        while (true) {
            Http.Response answer = http.get(page(exposure, page),
                    Map.of("Authorization", "Bearer " + token, "Accept", "application/json"));
            if (!answer.ok()) {
                return new Listing(List.of(), "zones/" + zone + "/rrsets answered "
                        + answer.describe());
            }
            JsonNode body = Json.parse(answer.body());
            if (!body.path("rrsets").isArray()) {
                return new Listing(List.of(), "zones/" + zone + "/rrsets answered no rrsets list");
            }
            for (JsonNode rrset : body.path("rrsets")) {
                List<String> values = new ArrayList<>();
                rrset.path("records").forEach(record -> values.add(record.path("value").asText()));
                found.add(new RrSet(rrset.path("name").asText(), rrset.path("type").asText(),
                        List.copyOf(values)));
            }
            JsonNode next = body.path("meta").path("pagination").path("next_page");
            if (!next.isInt() || next.asInt() <= page) {
                return new Listing(List.copyOf(found), null);
            }
            page = next.asInt();
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
