package eu.wohlben.qits.cli.bootstrap.platform;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.fail;

/**
 * <b>ONE FILE PER APPLICATION, AND THE FILE IS THE EXPECTATION.</b> The extras a deployment is
 * started with are content, not behaviour: forty assertions spelling one block's lines back at it
 * prove nothing a whole-block comparison does not, and they cost a test edit every time a value
 * moves. So each application's block is checked in under
 * {@code src/test/resources/compose-golden/extras/<app>.properties} and compared whole.
 * <p>
 * <b>What that buys is the migration wave.</b> The epic moves per-service defaults out of
 * {@code ComposeTemplate.EXTRAS} and into each repository's own
 * {@code .config/qits/configuration.yml}, batch by batch. With the block goldened, one batch is one
 * diff a reader can see whole — and an application that loses its last key is a golden file
 * DELETED, which is a decision somebody made rather than a test that quietly stopped asserting.
 * <p>
 * <b>The application set is discovered, never listed.</b> A new application with no golden fails
 * naming itself, and a golden with no application fails the same way — the two halves are what stop
 * this from becoming a directory nobody prunes. {@link ExtrasWiringGuardTest} is the other half of
 * the pair: a golden says what a block HOLDS, the guard says what a block is ALLOWED to hold.
 * <p>
 * <b>What a golden cannot do</b> is render a second platform. The fixture is one environment with
 * no domain, so everything that varies with the tokens — the domain fragments, a second environment
 * name, a two-build host — stays an assertion in {@link ComposeTemplateTest}, and so does every
 * cross-check between the seed stack and the extras, because a golden of one file cannot say the
 * two files agree.
 */
class ComposeTemplateGoldenTest {

    private static final String EXTRAS = "qits.platform.deployments.extras.";

    /**
     * The flag that rewrites the expectations. It always FAILS afterwards: a run that repaired its
     * own goldens and went green would prove only that the generator agrees with itself.
     */
    private static final String REGENERATE = "qits.golden.regenerate";

    private static final String REGENERATE_COMMAND =
            "./mvnw test -Dtest=ComposeTemplateGoldenTest -D" + REGENERATE + "=true";

    @Test
    void everyApplicationsExtrasBlockIsItsGoldenFile() throws IOException {
        Map<String, List<String>> rendered = renderedBlocks();
        if (Boolean.getBoolean(REGENERATE)) {
            regenerate(rendered);
            return;
        }

        Map<String, List<String>> goldens = goldenBlocks();
        List<String> problems = new ArrayList<>();
        for (String application : rendered.keySet()) {
            if (!goldens.containsKey(application)) {
                problems.add("no golden for " + application + " — the extras render a block for it "
                        + "and " + relative(goldenFile(application)) + " does not exist");
            }
        }
        for (String application : goldens.keySet()) {
            if (!rendered.containsKey(application)) {
                problems.add("orphaned golden " + relative(goldenFile(application))
                        + " — the extras render no block for " + application + " any more");
            }
        }
        for (Map.Entry<String, List<String>> entry : rendered.entrySet()) {
            List<String> golden = goldens.get(entry.getKey());
            if (golden != null && !golden.equals(entry.getValue())) {
                problems.add(diff(entry.getKey(), golden, entry.getValue()));
            }
        }

        if (!problems.isEmpty()) {
            fail(String.join("\n\n", problems)
                    + "\n\nRegenerate with:  " + REGENERATE_COMMAND
                    + "\nThen read the diff: a golden is reviewed, never trusted.");
        }
    }

    // --- what the generator says -------------------------------------------------------------------

    /**
     * Every application in the rendered extras, with its own lines in the order they are generated.
     * <p>
     * The order is part of the expectation. Lines of one application are written together and a
     * block that suddenly interleaves with another's is a template edit that landed in the wrong
     * place — which a sorted comparison would hide.
     */
    private static Map<String, List<String>> renderedBlocks() {
        Map<String, List<String>> blocks = new LinkedHashMap<>();
        ComposeTemplate.extras(ComposeTemplateTest.tokens()).lines()
                .filter(line -> line.startsWith(EXTRAS))
                .forEach(line -> blocks
                        .computeIfAbsent(application(line), application -> new ArrayList<>())
                        .add(line));
        return blocks;
    }

    /** The application name of one extras line — everything up to the first dot or bracket. */
    private static String application(String line) {
        String element = line.substring(EXTRAS.length());
        int end = element.length();
        for (int index = 0; index < element.length(); index++) {
            if (element.charAt(index) == '.' || element.charAt(index) == '[') {
                end = index;
                break;
            }
        }
        return element.substring(0, end);
    }

    // --- what is checked in ------------------------------------------------------------------------

    /**
     * The applications the golden directory names, which is the checked-in list of what this file
     * configures. {@link ComposeTemplateTest} asks it rather than carrying an array of its own.
     */
    static List<String> goldenApplications() {
        return List.copyOf(goldenBlocks().keySet());
    }

    private static Map<String, List<String>> goldenBlocks() {
        Path directory = goldenDirectory();
        if (!Files.isDirectory(directory)) {
            return Map.of();
        }
        Map<String, List<String>> blocks = new TreeMap<>();
        try (Stream<Path> files = Files.list(directory)) {
            for (Path file : files.filter(path -> path.getFileName().toString()
                    .endsWith(".properties")).toList()) {
                String name = file.getFileName().toString();
                blocks.put(name.substring(0, name.length() - ".properties".length()),
                        Files.readString(file).stripTrailing().lines().toList());
            }
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
        return blocks;
    }

    private static Path goldenFile(String application) {
        return goldenDirectory().resolve(application + ".properties");
    }

    /**
     * <b>The directory is resolved from {@code user.dir}, and the resolution is checked rather than
     * assumed.</b> Surefire and every IDE runner start a module's tests in the module's own
     * directory, but a runner that does not would write nineteen files somewhere nobody looks — so
     * the sanity check is this test's own source tree, which exists at exactly one path.
     * <p>
     * The goldens are read from {@code src/} rather than from the classpath copy under
     * {@code target/}: regeneration writes to {@code src/}, and a comparison against a stale copy
     * would report a difference the working tree does not have.
     */
    private static Path goldenDirectory() {
        Path root = Path.of(System.getProperty("user.dir", "."));
        if (!Files.isRegularFile(root.resolve("src/test/java/eu/wohlben/qits/cli/bootstrap"
                + "/platform/ComposeTemplateGoldenTest.java"))) {
            throw new IllegalStateException("the working directory is " + root.toAbsolutePath()
                    + ", which is not this module's root — the goldens live at "
                    + "<module>/src/test/resources/compose-golden and cannot be found from here");
        }
        return root.resolve("src/test/resources/compose-golden/extras");
    }

    private static String relative(Path file) {
        return Path.of(System.getProperty("user.dir", ".")).relativize(file).toString();
    }

    // --- the two things a failure has to say -------------------------------------------------------

    /**
     * What moved, in the shape a reader already knows. Whole-line adds and removes first, because
     * that is what a key gained or lost looks like; a pure reordering says so instead, since two
     * sets that match line for line would otherwise print nothing at all.
     */
    private static String diff(String application, List<String> golden, List<String> rendered) {
        StringBuilder report = new StringBuilder(application + " does not match its golden\n"
                + "--- " + relative(goldenFile(application)) + "\n"
                + "+++ rendered by ComposeTemplate.extras\n");
        List<String> removed = golden.stream().filter(line -> !rendered.contains(line)).toList();
        List<String> added = rendered.stream().filter(line -> !golden.contains(line)).toList();
        removed.forEach(line -> report.append('-').append(line).append('\n'));
        added.forEach(line -> report.append('+').append(line).append('\n'));
        if (removed.isEmpty() && added.isEmpty()) {
            for (int index = 0; index < Math.min(golden.size(), rendered.size()); index++) {
                if (!golden.get(index).equals(rendered.get(index))) {
                    report.append(" same keys, different order — line ").append(index + 1)
                            .append(" is\n-").append(golden.get(index))
                            .append("\n+").append(rendered.get(index)).append('\n');
                    break;
                }
            }
        }
        return report.toString().stripTrailing();
    }

    /**
     * Rewrite every golden and remove the ones no application answers to, then fail. Regenerating
     * is a step in an edit, not a way of passing.
     */
    private static void regenerate(Map<String, List<String>> rendered) throws IOException {
        Path directory = goldenDirectory();
        Files.createDirectories(directory);
        int changed = 0;
        for (Map.Entry<String, List<String>> entry : rendered.entrySet()) {
            Path file = goldenFile(entry.getKey());
            String text = String.join("\n", entry.getValue()) + "\n";
            if (!Files.exists(file) || !Files.readString(file).equals(text)) {
                Files.writeString(file, text);
                changed++;
            }
        }
        int removed = 0;
        for (String application : goldenBlocks().keySet()) {
            if (!rendered.containsKey(application)) {
                Files.delete(goldenFile(application));
                removed++;
            }
        }
        fail("regenerated " + rendered.size() + " files (" + changed + " rewritten, " + removed
                + " removed) — rerun without -D" + REGENERATE + "=true and review the diff");
    }
}
