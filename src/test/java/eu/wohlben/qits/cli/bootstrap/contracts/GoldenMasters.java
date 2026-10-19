package eu.wohlben.qits.cli.bootstrap.contracts;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * <b>One provider's recorded answers</b>, as this CLI's consumer pacts read them (qits-1149, after
 * epic qits-546).
 * <p>
 * A provider records what it answers for each provider state — {@code golden-masters/index.json}
 * plus one JSON per (state, operation) — and publishes the tree as
 * {@code eu.wohlben.qits:<app>-golden-masters}, a test-scoped pin in the pom. Unlike the copies in
 * qits-maintenance-service and qits-platform-access-cli, this CLI reads SEVERAL providers' jars,
 * and every jar puts its index at the same path. So the index is found with
 * {@link ClassLoader#getResources} and picked by its {@code provider} field, and each recording is
 * read relative to the index it came from — never through the class path, where another
 * provider's file of the same name could answer.
 * <p>
 * <b>A provider with no jar on the class path is a provider with no recordings</b>, not an error:
 * {@link #holds} answers false, and the rows that need it wait (see {@link BootstrapContract}).
 */
public final class GoldenMasters {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String INDEX = "golden-masters/index.json";

    private static final Map<String, GoldenMasters> LOADED = new ConcurrentHashMap<>();

    /** The application name the index names, e.g. {@code qits-projects}. */
    private final String application;

    /** Where the index was read from, or null when no jar on the class path is this provider's. */
    private final URL indexUrl;

    private final JsonNode index;

    private GoldenMasters(String application, URL indexUrl, JsonNode index) {
        this.application = application;
        this.indexUrl = indexUrl;
        this.index = index;
    }

    /** The recordings of the provider whose index names {@code application}. */
    public static GoldenMasters of(String application) {
        return LOADED.computeIfAbsent(application, GoldenMasters::load);
    }

    private static GoldenMasters load(String application) {
        try {
            ClassLoader loader = GoldenMasters.class.getClassLoader();
            Enumeration<URL> found = loader.getResources(INDEX);
            for (URL url : Collections.list(found)) {
                JsonNode index = MAPPER.readTree(read(url));
                if (!application.equals(index.path("provider").asText())) {
                    continue;
                }
                if (index.path("formatVersion").asInt() != 1) {
                    throw new IllegalStateException(url + " is formatVersion "
                            + index.path("formatVersion") + "; GoldenMasters reads formatVersion 1");
                }
                return new GoldenMasters(application, url, index);
            }
            return new GoldenMasters(application, null, MAPPER.createObjectNode());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public String application() {
        return application;
    }

    /** Whether a jar of this provider's recordings is on the class path at all. */
    public boolean published() {
        return indexUrl != null;
    }

    /**
     * One recorded (state, operation), as the index describes it — the request the provider was
     * sent as well as what it answered. {@code query} values and {@code body} may name a state
     * param as {@code {name}}; {@code body} is null for a request without one, a JSON tree for a
     * JSON request and a text node for any other. {@code authorization} is the {@code
     * Authorization} header the recording was sent — typically {@code {authorization}}, the
     * state's caller credential — or null when it was sent none.
     */
    public record Operation(String state, String operationId, String method, String path,
                            Map<String, List<String>> query, JsonNode body, String contentType,
                            String authorization, int status, String file, Set<String> ids,
                            Set<String> instants) {
    }

    /** Whether the index records this operation in this state. */
    public boolean holds(String state, String operationId) {
        return find(state, operationId).isPresent();
    }

    /** The index entry for one (state, operation); fails naming both when there is none. */
    public Operation operation(String state, String operationId) {
        return find(state, operationId).orElseThrow(() -> new IllegalArgumentException(
                application + "'s golden masters record no operation " + operationId
                        + " in state '" + state + "'"));
    }

    private Optional<Operation> find(String state, String operationId) {
        for (JsonNode node : index.path("states")) {
            if (!state.equals(node.path("name").asText())) {
                continue;
            }
            for (JsonNode op : node.path("operations")) {
                if (operationId.equals(op.path("operationId").asText())) {
                    JsonNode frozen = op.path("frozen");
                    JsonNode body = op.get("body");
                    return Optional.of(new Operation(state, operationId,
                            op.path("method").asText(), op.path("path").asText(),
                            query(op.path("query")),
                            body == null || body.isNull() ? null : body,
                            op.hasNonNull("contentType") ? op.get("contentType").asText() : null,
                            authorization(op.path("headers")), op.path("status").asInt(),
                            op.path("file").asText(),
                            strings(frozen.path("ids")), strings(frozen.path("instants"))));
                }
            }
        }
        return Optional.empty();
    }

    /** The provider state's frozen example params, e.g. {@code projectId}. */
    public Map<String, String> params(String state) {
        Map<String, String> params = new LinkedHashMap<>();
        for (JsonNode node : index.path("states")) {
            if (state.equals(node.path("name").asText())) {
                node.path("params").fields()
                        .forEachRemaining(e -> params.put(e.getKey(), e.getValue().asText()));
            }
        }
        return params;
    }

    /** The recorded answer for one (state, operation), parsed — a fresh tree each call. */
    @SuppressWarnings("deprecation") // URL(URL, String): a jar: URL is opaque to URI.resolve
    public JsonNode json(String state, String operationId) {
        Operation op = operation(state, operationId);
        try {
            return MAPPER.readTree(read(new URL(indexUrl, op.file())));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * The recorded {@code Authorization} header, matched without regard to case. Only that one is
     * read: it is the one request header a door judges, and some providers' {@code headers} hold
     * what they ANSWERED (a manifest's digest, a tarball's length) rather than what they were sent.
     */
    private static String authorization(JsonNode headers) {
        var names = headers.fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            if ("authorization".equalsIgnoreCase(name)) {
                return headers.get(name).asText();
            }
        }
        return null;
    }

    /** A recorded query: each name with its values in order, a lone value as a list of one. */
    private static Map<String, List<String>> query(JsonNode query) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        query.fields().forEachRemaining(e -> {
            List<String> values = new ArrayList<>();
            if (e.getValue().isArray()) {
                e.getValue().forEach(v -> values.add(v.asText()));
            } else {
                values.add(e.getValue().asText());
            }
            out.put(e.getKey(), List.copyOf(values));
        });
        return out;
    }

    private static Set<String> strings(JsonNode array) {
        Set<String> out = new LinkedHashSet<>();
        array.forEach(e -> out.add(e.asText()));
        return Set.copyOf(out);
    }

    private static String read(URL url) throws IOException {
        try (InputStream in = url.openStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
