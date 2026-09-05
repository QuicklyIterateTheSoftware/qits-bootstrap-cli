package eu.wohlben.qits.cli.bootstrap.platform;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * <b>Every version of a qits library some pom still pins</b>, closed over the pins those versions
 * pin in turn.
 * <p>
 * <b>Why the seed cannot publish one version per library.</b> A consumer names the library version
 * it builds against in a root-pom property — {@code <qits.eventstream.version>} and its siblings —
 * and those properties LAG. On the live platform that is the normal state: the store holds every
 * version ever released, so a pom a week behind resolves fine. A cold boot has no store. Its
 * temporary file repository and the store that replaces it hold exactly what the seed publishes, so
 * one lagging pin anywhere in the estate ends the boot minutes into the maven phase, naming a
 * version nothing ever published. That is how the 2026-09-05 cold boot of a fresh host died:
 * qits-githost-events pinned qits-eventstream 2026.904.82646 while the eventstream checkout stood
 * at 2026.905.25646.
 * <p>
 * So the seed publishes a SET per library: the checkout's own version plus every version anything
 * still pins, and every version those pinned poms pin in turn — a pinned pom is a consumer too.
 * <p>
 * <b>Read from the checkouts, never from the network.</b> Each version is a release tag in the
 * producer's own clone ({@code git show <version>:pom.xml}), which the sources phase has already
 * fetched: clones are {@code --branch main --single-branch}, which brings the tags reachable from
 * main, and the refresh pulls {@code --tags}. A pin whose tag is not there is WARNED and dropped
 * rather than stopping the boot — it is a coordinate the estate cannot build either way, and the
 * boot has better things to fail on.
 */
public final class PinnedVersions {

    /** The ref a checkout's own version is read at. A restoring boot is detached at its tag. */
    public static final String HEAD = "HEAD";

    /** The root pom, relative to a checkout. */
    public static final String ROOT_POM = "pom.xml";

    /**
     * <b>Which repository publishes the jar behind each {@code <qits.*.version>} property.</b>
     * Keyed by the property, valued by the model name {@link PlatformModel#repo} resolves — so a
     * repository rename moves nothing here.
     * <p>
     * Several properties share a producer, and that is the reactor's doing rather than an
     * accident: {@code qits-integrations-quarkus} publishes six jars a consumer pins one at a time,
     * and {@code qits-registries} carries the blob store as a module since 2026-08-30. Two
     * properties name repositories that are SERVICES and publish one library module each — the git
     * host's event vocabulary and the orchestrator's client pair. {@link PlatformModel#mavenModule}
     * says which modules, and this reader uses that answer to decide which of a tag's own pins are
     * really needed.
     */
    static final Map<String, String> PRODUCERS = Map.ofEntries(
            Map.entry("eventstream", "eventstream"),
            Map.entry("blobstore", "registries"),
            Map.entry("registries", "registries"),
            Map.entry("integrations-quarkus", "integrations-quarkus"),
            Map.entry("db-core", "integrations-quarkus"),
            Map.entry("arch-rules", "integrations-quarkus"),
            Map.entry("service-mock", "integrations-quarkus"),
            Map.entry("auth-core", "integrations-quarkus"),
            Map.entry("environment-core", "integrations-quarkus"),
            Map.entry("githost-events", "githost"),
            Map.entry("containers-client", "containers"),
            Map.entry("containers-core", "containers"),
            Map.entry("userflows", "userflows"));

    /** One file out of one ref of one checkout, or null when the ref or the file is not there. */
    public interface Poms {
        String at(String name, String ref, String path);
    }

    /**
     * CalVer, compared field by field and numerically where both sides are numbers.
     * {@code 2026.904.82646} is 08:26:46 and {@code 2026.904.210416} is 21:04:16, so a plain string
     * compare would call the earlier one newer.
     */
    static final Comparator<String> VERSION_ORDER = PinnedVersions::compareVersions;

    private static final Pattern PIN = Pattern.compile(
            "<qits\\.([A-Za-z0-9-]+)\\.version>\\s*([^<$\\s][^<]*?)\\s*</qits\\.\\1\\.version>");

    /** A managed dependency whose version IS one of those properties: the artifact it versions. */
    private static final Pattern MANAGED = Pattern.compile(
            "<artifactId>\\s*([^<\\s]+)\\s*</artifactId>\\s*"
                    + "<version>\\s*\\$\\{qits\\.([A-Za-z0-9-]+)\\.version}\\s*</version>");

    private static final Pattern ARTIFACT = Pattern.compile("<artifactId>\\s*([^<\\s]+)\\s*</artifactId>");

    private final Map<String, List<String>> extra;
    private final List<String> warnings;

    private PinnedVersions(Map<String, List<String>> extra, List<String> warnings) {
        this.extra = Map.copyOf(extra);
        this.warnings = List.copyOf(warnings);
    }

    /**
     * The closure over every repository the boot has checked out.
     *
     * @param repositories the model names whose HEAD poms are the consumers to start from
     * @param poms         the checkouts, read by (name, ref, path)
     */
    public static PinnedVersions read(List<String> repositories, Poms poms) {
        Map<String, String> head = new LinkedHashMap<>();
        for (String producer : new LinkedHashSet<>(PRODUCERS.values())) {
            String pom = poms.at(producer, HEAD, ROOT_POM);
            String version = pom == null ? null : versionIn(pom);
            if (version != null) {
                head.put(producer, version);
            }
        }

        Map<String, TreeSet<String>> found = new LinkedHashMap<>();
        List<String> warnings = new ArrayList<>();
        Set<String> unknown = new LinkedHashSet<>();
        // Breadth first, so a pin's own pins are read once each however many poms reach them.
        Deque<String[]> pending = new ArrayDeque<>();

        for (String repository : repositories) {
            String pom = poms.at(repository, HEAD, ROOT_POM);
            if (pom != null) {
                // A CONSUMER AT HEAD IS BUILT WHOLE — that is what ci does with it — so every pin
                // its root pom carries is a coordinate some module of it resolves.
                queue(pinsIn(pom), head, found, warnings, unknown, pending, poms);
            }
        }
        while (!pending.isEmpty()) {
            String[] at = pending.removeFirst();
            String pom = poms.at(at[0], at[1], ROOT_POM);
            if (pom == null) {
                continue;
            }
            queue(pinsUsed(at[0], at[1], pom, poms), head, found, warnings, unknown, pending, poms);
        }

        Map<String, List<String>> extra = new LinkedHashMap<>();
        found.forEach((producer, versions) -> extra.put(producer, List.copyOf(versions)));
        for (String name : unknown) {
            warnings.add("no repository publishes qits-" + name + " — pins of it are ignored");
        }
        return new PinnedVersions(extra, warnings);
    }

    private static void queue(Map<String, String> pins, Map<String, String> head,
                              Map<String, TreeSet<String>> found, List<String> warnings,
                              Set<String> unknown, Deque<String[]> pending, Poms poms) {
        pins.forEach((key, version) -> {
            String producer = PRODUCERS.get(key);
            if (producer == null) {
                unknown.add(key);
                return;
            }
            if (version.equals(head.get(producer))) {
                return;
            }
            TreeSet<String> versions =
                    found.computeIfAbsent(producer, p -> new TreeSet<>(VERSION_ORDER));
            if (versions.contains(version)) {
                return;
            }
            if (poms.at(producer, version, ROOT_POM) == null) {
                // The over-approximation this reader is allowed to make. qits-githost's root pom
                // carries <qits.blobstore.version>2026.814.71936 at tag 2026.820.65553, and that
                // tag lives in the retired standalone blobstore repository — it is in no checkout
                // and never will be. The module filter drops that one before it gets here; a pin
                // that survives the filter and still has no tag is a real hole, and saying so
                // beats stopping a boot over a coordinate nothing can build.
                warnings.add(PlatformModel.repo(producer) + " has no " + version
                        + " in its checkout — the pin is skipped, and a build that resolves it "
                        + "will fail");
                return;
            }
            versions.add(version);
            pending.addLast(new String[]{producer, version});
        });
    }

    /**
     * <b>The pins of a producer at a pinned version that the seed's own build of it really
     * resolves.</b>
     * <p>
     * A repository the seed publishes WHOLE is built whole, so every root-pom pin counts. A
     * repository published by module ({@link PlatformModel#mavenModule}) is a different question:
     * qits-githost's root pom manages qits-blobstore, qits-db-core and qits-arch-rules, and its
     * {@code githost-events} module — the only one the seed builds — depends on qits-eventstream
     * and nothing else. Publishing the root pins of that tag would chase a blob store version that
     * exists in no repository any more.
     * <p>
     * So a pin survives when a built module names its property outright, or when the root's
     * dependency management maps the property to an artifact a built module depends on.
     */
    static Map<String, String> pinsUsed(String producer, String version, String rootPom, Poms poms) {
        Map<String, String> pins = pinsIn(rootPom);
        String modules = PlatformModel.mavenModule(producer);
        if (modules.isEmpty()) {
            return pins;
        }
        Map<String, String> managed = managedByProperty(rootPom);
        Set<String> declared = new LinkedHashSet<>();
        StringBuilder texts = new StringBuilder();
        for (String module : modules.split(",")) {
            String pom = poms.at(producer, version, module.strip() + "/" + ROOT_POM);
            if (pom == null) {
                // A module the seed names and the tag does not have: nothing to filter on, so keep
                // every pin rather than silently publishing too little.
                return pins;
            }
            texts.append(pom);
            Matcher artifact = ARTIFACT.matcher(pom);
            while (artifact.find()) {
                declared.add(artifact.group(1));
            }
        }
        Map<String, String> used = new LinkedHashMap<>();
        pins.forEach((key, pinned) -> {
            if (texts.indexOf("${qits." + key + ".version}") >= 0) {
                used.put(key, pinned);
                return;
            }
            managed.forEach((artifactId, property) -> {
                if (property.equals(key) && declared.contains(artifactId)) {
                    used.put(key, pinned);
                }
            });
        });
        return used;
    }

    /** {@code <qits.<key>.version>} properties with a literal value, by key. */
    static Map<String, String> pinsIn(String pom) {
        Map<String, String> pins = new LinkedHashMap<>();
        Matcher matcher = PIN.matcher(pom);
        while (matcher.find()) {
            pins.put(matcher.group(1), matcher.group(2));
        }
        return pins;
    }

    /** Managed artifacts whose version is one of those properties: artifactId to property key. */
    static Map<String, String> managedByProperty(String pom) {
        Map<String, String> managed = new LinkedHashMap<>();
        Matcher matcher = MANAGED.matcher(pom);
        while (matcher.find()) {
            managed.put(matcher.group(1), matcher.group(2));
        }
        return managed;
    }

    /** The version a root pom would publish: its first version element outside {@code <parent>}. */
    static String versionIn(String pom) {
        String withoutParent = pom.replaceAll("(?s)<parent>.*?</parent>", "");
        Matcher matcher = Pattern.compile("<version>([^<$]+)</version>").matcher(withoutParent);
        return matcher.find() ? matcher.group(1).trim() : null;
    }

    static int compareVersions(String left, String right) {
        String[] a = left.split("\\.");
        String[] b = right.split("\\.");
        for (int i = 0; i < Math.min(a.length, b.length); i++) {
            int order = a[i].matches("\\d+") && b[i].matches("\\d+")
                    ? Long.compare(Long.parseLong(a[i]), Long.parseLong(b[i]))
                    : a[i].compareTo(b[i]);
            if (order != 0) {
                return order;
            }
        }
        return Integer.compare(a.length, b.length);
    }

    /**
     * The versions of one producer the boot has to publish BESIDE the checkout's own — oldest
     * first, which is the order they are built in. Empty for a producer nothing lags behind.
     */
    public List<String> extraVersions(String producer) {
        return extra.getOrDefault(producer, List.of());
    }

    /** The same, for the whole set, so a caller can log the closure in one place. */
    public Map<String, List<String>> all() {
        return extra;
    }

    /** Pins this reader could not honour. Each one is a build that will fail if anything needs it. */
    public List<String> warnings() {
        return warnings;
    }
}
