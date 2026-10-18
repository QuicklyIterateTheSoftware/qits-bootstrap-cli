package eu.wohlben.qits.cli.bootstrap.contracts;

import au.com.dius.pact.core.model.DefaultPactWriter;
import au.com.dius.pact.core.model.PactSpecVersion;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * <b>The committed consumer pacts, {@code pacts/qits-bootstrap-cli_<provider>.json}</b> — one per
 * provider with at least one recorded row of {@link BootstrapContract}, and no other file.
 * <p>
 * <b>Compare by default.</b> pact-jvm writes the pact; the test normalises it — interactions sorted
 * by description then provider state, pact-jvm's own version stripped from {@code metadata} (a
 * library bump is not a contract change, and a changed file is what republishes the pacts jar),
 * 2-space indentation and a trailing newline — and compares it byte for byte to the committed file.
 * {@code -Dgolden.update=true} (or {@code QITS_GOLDEN_UPDATE=true}) rewrites the files instead, and
 * removes the file of a provider left with no recorded row.
 * <p>
 * The release.yml {@code contracts:} section publishes each file as
 * {@code eu.wohlben.qits:qits-bootstrap-cli-pacts-<provider>}; its {@code pacts:} keys must name
 * exactly the providers that have a file here.
 */
class BootstrapPactFileTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void theCommittedPactsAreWhatTheContractWrites() throws IOException {
        Path dir = repositoryRoot().resolve("pacts");
        Set<String> expected = new TreeSet<>();
        List<String> failures = new ArrayList<>();
        for (BootstrapContract.Provider provider : BootstrapContract.PROVIDERS) {
            if (BootstrapContract.recorded(provider).isEmpty()) {
                continue;
            }
            String file = fileOf(provider);
            expected.add(file);
            String actual = normalise(written(provider));
            Path golden = dir.resolve(file);
            if (updating()) {
                Files.createDirectories(dir);
                Files.writeString(golden, actual);
            } else if (!Files.exists(golden)) {
                failures.add("no " + golden + " — run with -Dgolden.update=true and review it");
            } else if (!Files.readString(golden).equals(actual)) {
                failures.add(golden + " differs from what the contract writes:\n"
                        + firstDifference(Files.readString(golden), actual)
                        + "\nIf the change is intended, rerun with -Dgolden.update=true.");
            }
        }
        Set<String> present = new TreeSet<>();
        if (Files.isDirectory(dir)) {
            try (Stream<Path> files = Files.list(dir)) {
                files.map(p -> p.getFileName().toString()).forEach(present::add);
            }
        }
        for (String stale : present) {
            if (expected.contains(stale)) {
                continue;
            }
            if (updating()) {
                Files.delete(dir.resolve(stale));
            } else {
                failures.add("pacts/" + stale + " is written by no recorded row");
            }
        }
        if (!failures.isEmpty()) {
            fail(String.join("\n\n", failures));
        }
    }

    /**
     * {@code release.yml}'s {@code contracts.pacts} names exactly the providers with a pact file: a
     * file it leaves out is never published, and a key with no file fails the release postlude.
     */
    @Test
    void releaseYmlPublishesExactlyThePactFiles() throws IOException {
        Set<String> declared = new TreeSet<>();
        boolean inPacts = false;
        for (String line : Files.readAllLines(repositoryRoot().resolve(".config/qits/release.yml"))) {
            if (line.equals("  pacts:")) {
                inPacts = true;
            } else if (inPacts && line.startsWith("    ") && line.contains(":")) {
                declared.add(line.strip().substring(0, line.strip().indexOf(':')));
            } else if (inPacts && !line.isBlank()) {
                inPacts = false;
            }
        }
        Set<String> withRows = new TreeSet<>();
        for (BootstrapContract.Provider provider : BootstrapContract.PROVIDERS) {
            if (!BootstrapContract.recorded(provider).isEmpty()) {
                withRows.add(provider.repository());
            }
        }
        assertEquals(withRows, declared, "release.yml contracts.pacts");
    }

    /** Both references on every interaction, as the platform reads them. */
    @Test
    void everyInteractionCarriesBothReferences() throws IOException {
        for (BootstrapContract.Provider provider : BootstrapContract.PROVIDERS) {
            if (BootstrapContract.recorded(provider).isEmpty()) {
                continue;
            }
            JsonNode pact = MAPPER.readTree(normalise(written(provider)));
            assertEquals(BootstrapContract.CONSUMER, pact.path("consumer").path("name").asText());
            assertEquals(provider.repository(), pact.path("provider").path("name").asText());
            assertEquals("4.0", pact.path("metadata").path("pactSpecification").path("version").asText());
            assertEquals(BootstrapContract.recorded(provider).size(), pact.path("interactions").size());
            Set<String> unique = new HashSet<>();
            for (JsonNode interaction : pact.path("interactions")) {
                String description = interaction.path("description").asText();
                JsonNode references = interaction.path("comments").path("references");
                JsonNode call = references.path("qits-call");
                assertEquals(provider.repository(), call.path("app").asText(), description);
                assertTrue(!call.path("operationId").asText().isBlank(), description);
                JsonNode trigger = references.path("qits-trigger");
                assertEquals("command", trigger.path("kind").asText(), description);
                assertEquals(BootstrapContract.CONSUMER, trigger.path("app").asText(), description);
                assertTrue(description.startsWith(trigger.path("command").asText() + ": "),
                        description + ": the description leads with the trigger");
                String state = interaction.path("providerStates").path(0).path("name").asText();
                assertTrue(unique.add(description + "\u0000" + state),
                        "(description, state) repeats: " + description);
            }
        }
    }

    /**
     * Every row's description and state are unique, pending rows included, and every row says what
     * its provider must record — a live row goes pending the day its recording moves.
     */
    @Test
    void everyRowIsDistinct() {
        Set<String> unique = new HashSet<>();
        for (BootstrapContract.Case row : BootstrapContract.CASES) {
            assertTrue(unique.add(row.provider().repository() + "\u0000" + row.description()
                    + "\u0000" + row.state()), "repeated row: " + row.description());
            assertTrue(row.needs() != null && !row.needs().isBlank(),
                    row.description() + ": a row says what its provider must record");
        }
    }

    static String fileOf(BootstrapContract.Provider provider) {
        return BootstrapContract.CONSUMER + "_" + provider.repository() + ".json";
    }

    private static boolean updating() {
        return "true".equalsIgnoreCase(System.getenv("QITS_GOLDEN_UPDATE"))
                || Boolean.getBoolean("golden.update");
    }

    private static Path repositoryRoot() {
        for (Path dir = Path.of("").toAbsolutePath(); dir != null; dir = dir.getParent()) {
            if (Files.isRegularFile(dir.resolve("pom.xml"))
                    && Files.isDirectory(dir.resolve(".config/qits"))) {
                return dir;
            }
        }
        throw new IllegalStateException("no repository root above " + Path.of("").toAbsolutePath());
    }

    private static String firstDifference(String expected, String actual) {
        String[] a = expected.split("\n", -1);
        String[] b = actual.split("\n", -1);
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            String left = i < a.length ? a[i] : "<end>";
            String right = i < b.length ? b[i] : "<end>";
            if (!left.equals(right)) {
                return "line " + (i + 1) + ":\n- " + left + "\n+ " + right;
            }
        }
        return "(no line differs)";
    }

    /** The pact as pact-jvm's own writer serialises it. */
    static String written(BootstrapContract.Provider provider) {
        StringWriter out = new StringWriter();
        try (PrintWriter writer = new PrintWriter(out)) {
            DefaultPactWriter.INSTANCE.writePact(BootstrapContract.pact(provider), writer,
                    PactSpecVersion.V4);
        }
        return out.toString();
    }

    /** Sorted, version-stripped, 2-space indented, one trailing newline. */
    static String normalise(String raw) throws IOException {
        ObjectNode pact = (ObjectNode) MAPPER.readTree(raw);
        if (pact.path("metadata") instanceof ObjectNode meta) {
            meta.remove("pact-jvm");
        }
        if (pact.path("interactions") instanceof ArrayNode interactions) {
            List<JsonNode> sorted = new ArrayList<>();
            interactions.forEach(sorted::add);
            sorted.sort(Comparator.comparing((JsonNode i) -> i.path("description").asText())
                    .thenComparing(i -> i.path("providerStates").path(0).path("name").asText()));
            interactions.removeAll();
            sorted.forEach(interactions::add);
        }
        StringBuilder out = new StringBuilder();
        print(pact, "", out);
        return out.append('\n').toString();
    }

    /** {@code JSON.stringify(value, null, 2)}: no space before a colon, empty containers as {} / []. */
    private static void print(JsonNode node, String indent, StringBuilder out) throws IOException {
        String inner = indent + "  ";
        if (node.isObject()) {
            if (node.isEmpty()) {
                out.append("{}");
                return;
            }
            out.append("{\n");
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                out.append(inner).append(MAPPER.writeValueAsString(field.getKey())).append(": ");
                print(field.getValue(), inner, out);
                out.append(fields.hasNext() ? ",\n" : "\n");
            }
            out.append(indent).append('}');
        } else if (node.isArray()) {
            if (node.isEmpty()) {
                out.append("[]");
                return;
            }
            out.append("[\n");
            for (int i = 0; i < node.size(); i++) {
                out.append(inner);
                print(node.get(i), inner, out);
                out.append(i < node.size() - 1 ? ",\n" : "\n");
            }
            out.append(indent).append(']');
        } else {
            out.append(MAPPER.writeValueAsString(node));
        }
    }
}
