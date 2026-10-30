package eu.wohlben.qits.cli.bootstrap.platform;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * <b>Every {@code eu.wohlben.qits} coordinate a Maven reactor's poms pin</b>, every scope, with
 * the {@code ${property}} versions resolved — the list a seed image build will ask the file
 * repository for.
 * <p>
 * <b>Why the boot reads it before a build rather than letting Maven find out.</b> A seed service
 * build is a GraalVM native image inside BuildKit, and it resolves the platform's jars from the
 * {@code qits-maven-seed} volume and nothing else (qits-889). A coordinate the run never put there
 * is a build that dies minutes in, deep in a buildkit log, naming one artifact — and the next run
 * names the next one. {@code mvn package -DskipTests} resolves TEST scope too, so a pact jar a
 * provider's verification suite reads is as fatal as a compile dependency. The preflight asks the
 * volume for the whole list at once and fails with all of it.
 * <p>
 * <b>Direct dependencies only.</b> What a pinned jar's own pom pulls in is the seed's business
 * where it produces the jar ({@code maven-seed} builds every library with its own pins resolved
 * against the same volume); this reader is about what the consumer's poms say. Regex, like every
 * other pom reader in this program: the poms are this platform's own, written to one shape.
 * <ul>
 *   <li>{@code ${project.groupId}} is the pom's own group (or its parent's), {@code ${x}} any
 *       property of that pom, then of the root pom, then of any pom in the reactor;
 *   <li>a dependency with no version takes the reactor's {@code <dependencyManagement>} entry for
 *       that artifactId; an {@code import}-scoped BOM there is itself a coordinate;
 *   <li>a coordinate the reactor itself builds — any pom's own artifactId — is no pin;
 *   <li>a version that does not resolve is reported, not guessed.
 * </ul>
 */
public final class MavenPins {

    /** The group every coordinate this platform releases is in. */
    public static final String QITS_GROUP = "eu.wohlben.qits";

    /** The reactor root. */
    public static final String ROOT_POM = "pom.xml";

    private static final Pattern COMMENT = Pattern.compile("(?s)<!--.*?-->");
    private static final Pattern PARENT = Pattern.compile("(?s)<parent>(.*?)</parent>");
    private static final Pattern EXCLUSIONS = Pattern.compile("(?s)<exclusions>.*?</exclusions>");
    private static final Pattern MANAGEMENT =
            Pattern.compile("(?s)<dependencyManagement>(.*?)</dependencyManagement>");
    private static final Pattern DEPENDENCY = Pattern.compile("(?s)<dependency>(.*?)</dependency>");
    private static final Pattern PROPERTIES = Pattern.compile("(?s)<properties>(.*?)</properties>");
    private static final Pattern PROPERTY =
            Pattern.compile("<([A-Za-z0-9_.\\-]+)>\\s*([^<]*?)\\s*</\\1>");
    private static final Pattern REFERENCE = Pattern.compile("\\$\\{([^}]+)}");
    /** What follows a project's own coordinates: nothing after it can be one of them. */
    private static final Pattern BODY = Pattern.compile(
            "(?s)<(?:dependencies|dependencyManagement|build|modules|properties|profiles"
                    + "|repositories|pluginRepositories|reporting)>.*");

    /**
     * One pinned coordinate.
     *
     * @param artifactId the artifact, in {@link #QITS_GROUP}
     * @param version    the resolved version
     * @param type       {@code jar} unless the pom says otherwise
     * @param classifier empty unless the pom names one
     * @param scope      {@code compile} unless the pom (or its managed entry) says otherwise
     * @param pom        the pom that pins it, relative to the reactor root
     */
    public record Coordinate(String artifactId, String version, String type, String classifier,
                             String scope, String pom) {

        /** Where a Maven repository holds it, relative to its root. */
        public String repositoryPath() {
            String extension = "pom".equals(type) ? "pom" : "jar";
            return QITS_GROUP.replace('.', '/') + "/" + artifactId + "/" + version + "/"
                    + artifactId + "-" + version
                    + (classifier.isEmpty() ? "" : "-" + classifier) + "." + extension;
        }

        /** {@code artifactId:version}, the short form a refusal names. */
        public String gav() {
            return artifactId + ":" + version;
        }
    }

    /**
     * What a reactor pins.
     *
     * @param dependencies every direct qits coordinate, once per artifactId:version:type:classifier
     *                     — the first pom that names it is the one recorded
     * @param managed      every qits coordinate a {@code <dependencyManagement>} section names,
     *                     used or not
     * @param unresolved   {@code artifactId (pom)} for each dependency whose version names nothing
     */
    public record Pins(List<Coordinate> dependencies, List<Coordinate> managed,
                       List<String> unresolved) {
    }

    private MavenPins() {
    }

    /**
     * Reads a reactor.
     *
     * @param poms each pom's text by its path relative to the reactor root, the root's being
     *             {@link #ROOT_POM}; the order is the order coordinates are reported in
     */
    public static Pins read(Map<String, String> poms) {
        Map<String, String> texts = new LinkedHashMap<>();
        poms.forEach((path, text) -> texts.put(path, COMMENT.matcher(text).replaceAll("")));

        Set<String> reactor = new LinkedHashSet<>();
        Map<String, Map<String, String>> ownProperties = new LinkedHashMap<>();
        Map<String, String> anyProperty = new LinkedHashMap<>();
        if (texts.containsKey(ROOT_POM)) {
            anyProperty.putAll(properties(texts.get(ROOT_POM)));
        }
        texts.forEach((path, text) -> {
            String own = own(text);
            String artifactId = field(own, "artifactId");
            if (!artifactId.isEmpty()) {
                reactor.add(artifactId);
            }
            Map<String, String> props = new LinkedHashMap<>(properties(text));
            String groupId = field(own, "groupId");
            String version = field(own, "version");
            Matcher parent = PARENT.matcher(text);
            if (parent.find()) {
                groupId = groupId.isEmpty() ? field(parent.group(1), "groupId") : groupId;
                version = version.isEmpty() ? field(parent.group(1), "version") : version;
            }
            props.put("project.groupId", groupId);
            props.put("project.version", version);
            props.put("project.artifactId", artifactId);
            ownProperties.put(path, props);
            properties(text).forEach(anyProperty::putIfAbsent);
        });

        // Managed entries first, from every pom, because a module's version-less dependency reads
        // whichever section manages it and a reactor puts that section in its root.
        Map<String, Coordinate> managedByArtifact = new LinkedHashMap<>();
        List<Coordinate> managed = new ArrayList<>();
        List<Coordinate> imports = new ArrayList<>();
        texts.forEach((path, text) -> {
            Matcher management = MANAGEMENT.matcher(text);
            while (management.find()) {
                for (String block : blocks(management.group(1))) {
                    Coordinate coordinate = coordinate(block, path, ownProperties.get(path),
                            anyProperty, Map.of());
                    if (coordinate == null) {
                        continue;
                    }
                    managedByArtifact.putIfAbsent(coordinate.artifactId(), coordinate);
                    managed.add(coordinate);
                    if ("import".equals(coordinate.scope())) {
                        imports.add(coordinate);
                    }
                }
            }
        });

        Map<String, Coordinate> dependencies = new LinkedHashMap<>();
        List<String> unresolved = new ArrayList<>();
        texts.forEach((path, text) -> {
            String body = MANAGEMENT.matcher(text).replaceAll("");
            for (String block : blocks(body)) {
                Coordinate coordinate = coordinate(block, path, ownProperties.get(path),
                        anyProperty, managedByArtifact);
                if (coordinate == null || reactor.contains(coordinate.artifactId())) {
                    continue;
                }
                if (coordinate.version().isEmpty() || coordinate.version().contains("${")) {
                    unresolved.add(coordinate.artifactId() + " (" + path + ")");
                    continue;
                }
                dependencies.putIfAbsent(key(coordinate), coordinate);
            }
        });
        for (Coordinate bom : imports) {
            if (!reactor.contains(bom.artifactId()) && !bom.version().contains("${")) {
                dependencies.putIfAbsent(key(bom), bom);
            }
        }
        return new Pins(List.copyOf(dependencies.values()), List.copyOf(managed),
                List.copyOf(unresolved));
    }

    private static String key(Coordinate coordinate) {
        return coordinate.artifactId() + ":" + coordinate.version() + ":" + coordinate.type() + ":"
                + coordinate.classifier();
    }

    /** One {@code <dependency>} block as a qits coordinate, or null for any other group. */
    private static Coordinate coordinate(String block, String path, Map<String, String> own,
                                         Map<String, String> any,
                                         Map<String, Coordinate> managed) {
        String groupId = resolve(field(block, "groupId"), own, any);
        if (!QITS_GROUP.equals(groupId)) {
            return null;
        }
        String artifactId = resolve(field(block, "artifactId"), own, any);
        String version = resolve(field(block, "version"), own, any);
        String scope = field(block, "scope");
        String type = field(block, "type");
        String classifier = resolve(field(block, "classifier"), own, any);
        Coordinate entry = managed.get(artifactId);
        if (version.isEmpty() && entry != null) {
            version = entry.version();
        }
        if (scope.isEmpty()) {
            scope = entry != null ? entry.scope() : "compile";
        }
        if (type.isEmpty()) {
            type = entry != null ? entry.type() : "jar";
        }
        return new Coordinate(artifactId, version, type, classifier, scope, path);
    }

    /** The {@code <dependency>} blocks of a text, each with its exclusions cut out. */
    private static List<String> blocks(String text) {
        List<String> blocks = new ArrayList<>();
        Matcher dependency = DEPENDENCY.matcher(EXCLUSIONS.matcher(text).replaceAll(""));
        while (dependency.find()) {
            blocks.add(dependency.group(1));
        }
        return blocks;
    }

    /** The project's own head: the text before its first section, its parent block cut out. */
    private static String own(String pom) {
        return BODY.matcher(PARENT.matcher(pom).replaceAll("")).replaceFirst("");
    }

    private static Map<String, String> properties(String pom) {
        Map<String, String> props = new LinkedHashMap<>();
        Matcher section = PROPERTIES.matcher(pom);
        while (section.find()) {
            Matcher property = PROPERTY.matcher(section.group(1));
            while (property.find()) {
                props.putIfAbsent(property.group(1), property.group(2));
            }
        }
        return props;
    }

    private static String field(String block, String name) {
        Matcher matcher = Pattern.compile("<" + name + ">\\s*([^<]*?)\\s*</" + name + ">")
                .matcher(block);
        return matcher.find() ? matcher.group(1) : "";
    }

    /** Every {@code ${x}} replaced, own properties first, a few levels deep; the rest left as is. */
    static String resolve(String value, Map<String, String> own, Map<String, String> any) {
        String current = value;
        for (int depth = 0; depth < 5 && current.contains("${"); depth++) {
            Matcher reference = REFERENCE.matcher(current);
            StringBuilder resolved = new StringBuilder();
            while (reference.find()) {
                String name = reference.group(1);
                String replacement = own.containsKey(name) ? own.get(name) : any.get(name);
                reference.appendReplacement(resolved, Matcher.quoteReplacement(
                        replacement == null ? reference.group() : replacement));
            }
            reference.appendTail(resolved);
            if (resolved.toString().equals(current)) {
                break;
            }
            current = resolved.toString();
        }
        return current;
    }

    // --- contract pins ---------------------------------------------------------------------------

    /**
     * {@code <qits.<key>.version>} where the key names a contract package — {@code
     * <app>-golden-masters} or {@code <consumer>-pacts-<provider>} — the property every consumer
     * pins one by. The artifact is {@code qits-<key>}: {@code qits.ci-frontend-pacts-qits-ci-service
     * .version} pins {@code qits-ci-frontend-pacts-qits-ci-service}.
     */
    private static final Pattern CONTRACT_PIN = Pattern.compile(
            "<qits\\.([a-z][a-z0-9-]*?(?:-golden-masters|-pacts-[a-z][a-z0-9-]*))\\.version>"
                    + "\\s*([^<\\s]+)\\s*</");

    /**
     * Every contract package some poms pin, as {@code artifactId -> versions} in reading order: the
     * {@code qits.*.version} properties of the texts, then the resolved coordinates of the given
     * reactors that are contracts. A pin that is no released version is passed by: nothing can
     * pack it.
     */
    public static Map<String, Set<String>> contractPins(List<String> poms, List<Pins> reactors) {
        Map<String, Set<String>> pins = new LinkedHashMap<>();
        for (String pom : poms) {
            if (pom == null) {
                continue;
            }
            Matcher pin = CONTRACT_PIN.matcher(COMMENT.matcher(pom).replaceAll(""));
            while (pin.find()) {
                add(pins, "qits-" + pin.group(1), pin.group(2));
            }
        }
        for (Pins reactor : reactors) {
            List<Coordinate> all = new ArrayList<>(reactor.dependencies());
            all.addAll(reactor.managed());
            for (Coordinate coordinate : all) {
                if (goldenMastersApp(coordinate.artifactId()) != null
                        || pactParties(coordinate.artifactId()) != null) {
                    add(pins, coordinate.artifactId(), coordinate.version());
                }
            }
        }
        return pins;
    }

    private static void add(Map<String, Set<String>> pins, String artifactId, String version) {
        if (version.isEmpty() || version.contains("$") || version.endsWith("-SNAPSHOT")) {
            return;
        }
        pins.computeIfAbsent(artifactId, a -> new LinkedHashSet<>()).add(version);
    }

    /** {@code qits-<app>-golden-masters} → {@code <app>}, or null for anything else. */
    public static String goldenMastersApp(String artifactId) {
        if (artifactId.startsWith("qits-") && artifactId.endsWith("-golden-masters")
                && artifactId.length() > "qits--golden-masters".length()) {
            return artifactId.substring("qits-".length(),
                    artifactId.length() - "-golden-masters".length());
        }
        return null;
    }

    /**
     * {@code <consumer repository>-pacts-<provider repository>} → both repository names, or null.
     * The consumer half is the repository the pact package's release ran for, so it is whatever
     * precedes the FIRST {@code -pacts-}.
     */
    public static String[] pactParties(String artifactId) {
        int at = artifactId.indexOf("-pacts-");
        if (at <= 0 || at + "-pacts-".length() >= artifactId.length()) {
            return null;
        }
        return new String[] {artifactId.substring(0, at),
                artifactId.substring(at + "-pacts-".length())};
    }
}
