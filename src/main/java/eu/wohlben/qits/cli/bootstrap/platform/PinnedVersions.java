package eu.wohlben.qits.cli.bootstrap.platform;

import com.fasterxml.jackson.databind.JsonNode;
import eu.wohlben.qits.cli.bootstrap.api.Json;

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
 * <b>An image pin is the same defect in another file.</b> A Dockerfile pins the image it is built
 * FROM — {@code ARG WORKSPACE_BASE=…/qits/workspace-base:2026.904.223651} — and those pins lag for
 * the same reason poms do. A boot that replayed only each publisher's newest tag left
 * qits-workspace-daemon's release run failing at its first line with
 * {@code qits/workspace-base:2026.904.223651: not found}, measured on 2026-09-05. So the closure
 * reads {@code Dockerfile*} at each checkout's root and under {@code docker/} as well, resolves the
 * ARG references a {@code FROM} names, and follows a pinned image tag into the producer's own
 * Dockerfiles at that tag.
 * <p>
 * <b>An npm pin is the same defect in a lockfile.</b> A restore stands a deployable at its release
 * tag, and that tag's FRONTEND gitlink carries a {@code package-lock.json} pinning
 * {@code @qits/ui-components} exactly — {@code npm ci} obeys the lock, not the caret beside it — so
 * a release build installs whatever the frontend was pinned to when the release was cut. Nine
 * deployables wanted 2026.902.204627 on 2026-09-05 while the registry held only the publisher's
 * newest tag, and every one of their release builds died in {@code npm ci} with a 404. So the
 * closure reads those locks too, at the gitlink each ref records.
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

    /** What the closure reads: the checkouts, by (repository, ref). */
    public interface Sources {

        /** One file out of one ref, or null when the ref or the file is not there. */
        String at(String name, String ref, String path);

        /** The Dockerfiles of one ref — the root's and {@code docker/}'s — as paths. */
        List<String> dockerfiles(String name, String ref);

        /**
         * The newest release tag reachable from main, or null. It is what an IMAGE or NPM
         * producer's checkout publishes: those repositories carry no pom version to read, and the
         * tag IS the published version.
         */
        String releaseVersion(String name);

        /** The commit one submodule path is recorded at in a ref, or null when there is none. */
        String gitlink(String name, String ref, String path);
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

    /** {@code ARG NAME=value} and {@code ARG NAME}, in Dockerfile syntax. */
    private static final Pattern DOCKER_ARG =
            Pattern.compile("^\\s*ARG\\s+([A-Za-z_][A-Za-z0-9_]*)(?:=(.*))?$");

    /** {@code FROM <ref>} with whatever follows it — a stage name, a platform flag. */
    private static final Pattern DOCKER_FROM =
            Pattern.compile("^\\s*FROM\\s+(?:--\\S+\\s+)*(\\S+)");

    /** {@code ${NAME}} or {@code $NAME}, which a Dockerfile resolves from the ARGs above it. */
    private static final Pattern DOCKER_REFERENCE =
            Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)}|\\$([A-Za-z_][A-Za-z0-9_]*)");

    /** A version this platform mints: CalVer, and never {@code latest}, {@code native} or a stage. */
    private static final Pattern CALVER = Pattern.compile("\\d{4}\\.\\d{1,4}\\.\\d+");

    /** The lockfile {@code npm ci} obeys — the caret in package.json loses to it. */
    static final String LOCK_FILE = "package-lock.json";

    private static final Pattern SUBMODULE_SECTION =
            Pattern.compile("^\\s*\\[submodule\\s+\"([^\"]+)\"\\]");

    private static final Pattern SUBMODULE_PATH =
            Pattern.compile("^\\s*path\\s*=\\s*(\\S+)\\s*$");

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
    public static PinnedVersions read(List<String> repositories, Sources sources) {
        Map<String, String> head = new LinkedHashMap<>();
        for (String producer : new LinkedHashSet<>(PRODUCERS.values())) {
            String pom = sources.at(producer, HEAD, ROOT_POM);
            String version = pom == null ? null : versionIn(pom);
            if (version != null) {
                head.put(producer, version);
            }
        }

        // AN IMAGE PRODUCER HAS NO POM VERSION TO READ. Its checkout publishes whatever its newest
        // release tag says, and that tag IS the image tag — see PlatformModel.releasePackages.
        Map<String, String> imageHead = new LinkedHashMap<>();
        Set<String> tagPublishers = new LinkedHashSet<>(IMAGE_PRODUCERS.values());
        tagPublishers.addAll(NPM_PRODUCERS.values());
        for (String producer : tagPublishers) {
            String version = sources.releaseVersion(producer);
            if (version != null && !version.isBlank()) {
                imageHead.put(producer, version);
            }
        }

        Map<String, TreeSet<String>> found = new LinkedHashMap<>();
        List<String> warnings = new ArrayList<>();
        Set<String> unknown = new LinkedHashSet<>();
        // Breadth first, so a pin's own pins are read once each however many poms reach them.
        Deque<String[]> pending = new ArrayDeque<>();

        for (String repository : repositories) {
            String pom = sources.at(repository, HEAD, ROOT_POM);
            if (pom != null) {
                // A CONSUMER AT HEAD IS BUILT WHOLE — that is what ci does with it — so every pin
                // its root pom carries is a coordinate some module of it resolves.
                queue(pinsIn(pom), head, found, warnings, unknown, pending, sources);
            }
            queueImages(imagePins(repository, HEAD, sources), imageHead, found, warnings, unknown,
                    pending, sources);
            queueNpm(npmPins(repository, HEAD, sources, warnings), imageHead, found, warnings,
                    unknown, sources);
        }
        while (!pending.isEmpty()) {
            String[] at = pending.removeFirst();
            String pom = sources.at(at[0], at[1], ROOT_POM);
            if (pom != null) {
                queue(pinsUsed(at[0], at[1], pom, sources), head, found, warnings, unknown, pending,
                        sources);
            }
            // A pinned image build reads its OWN Dockerfiles at that tag, so what they pin is the
            // next hop: qits/workspace at 2026.904.223250 is FROM qits/workspace-base
            // 2026.902.143920, which nothing newer names any more.
            queueImages(imagePins(at[0], at[1], sources), imageHead, found, warnings, unknown,
                    pending, sources);
            queueNpm(npmPins(at[0], at[1], sources, warnings), imageHead, found, warnings, unknown,
                    sources);
        }

        Map<String, List<String>> extra = new LinkedHashMap<>();
        found.forEach((producer, versions) -> extra.put(producer, List.copyOf(versions)));
        for (String name : unknown) {
            warnings.add(name.startsWith("qits/") || name.startsWith("@qits/")
                    ? "no release publisher publishes " + name + " — pins of it are ignored"
                    : "no repository publishes qits-" + name + " — pins of it are ignored");
        }
        return new PinnedVersions(extra, warnings);
    }

    private static void queue(Map<String, String> pins, Map<String, String> head,
                              Map<String, TreeSet<String>> found, List<String> warnings,
                              Set<String> unknown, Deque<String[]> pending, Sources sources) {
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
            if (sources.at(producer, version, ROOT_POM) == null) {
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
    static Map<String, String> pinsUsed(String producer, String version, String rootPom, Sources sources) {
        Map<String, String> pins = pinsIn(rootPom);
        String modules = PlatformModel.mavenModule(producer);
        if (modules.isEmpty()) {
            return pins;
        }
        Map<String, String> managed = managedByProperty(rootPom);
        Set<String> declared = new LinkedHashSet<>();
        StringBuilder texts = new StringBuilder();
        for (String module : modules.split(",")) {
            String pom = sources.at(producer, version, module.strip() + "/" + ROOT_POM);
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

    /**
     * <b>Which repository publishes each qits IMAGE</b> — the inverse of
     * {@link PlatformModel#releasePackages}' OCI half, so the two cannot disagree. It is derived
     * rather than written: a publisher that grows an image gets an entry by saying so once, in the
     * place that already says what its release publishes.
     */
    static final Map<String, String> IMAGE_PRODUCERS = imageProducers();

    private static Map<String, String> imageProducers() {
        Map<String, String> byImage = new LinkedHashMap<>();
        for (String publisher : PlatformModel.RELEASE_PUBLISHERS) {
            for (PlatformModel.ReleasePackage published : PlatformModel.releasePackages(publisher)) {
                if (published.kind() == PlatformModel.ReleasePackage.Kind.OCI) {
                    byImage.put(published.coordinate(), publisher);
                }
            }
        }
        return Map.copyOf(byImage);
    }

    /**
     * <b>Which repository publishes each {@code @qits} npm package</b>, derived from
     * {@link PlatformModel#releasePackages} exactly as the image map is.
     */
    static final Map<String, String> NPM_PRODUCERS =
            producersOfKind(PlatformModel.ReleasePackage.Kind.NPM);

    private static Map<String, String> producersOfKind(PlatformModel.ReleasePackage.Kind kind) {
        Map<String, String> byCoordinate = new LinkedHashMap<>();
        for (String publisher : PlatformModel.RELEASE_PUBLISHERS) {
            for (PlatformModel.ReleasePackage published : PlatformModel.releasePackages(publisher)) {
                if (published.kind() == kind) {
                    byCoordinate.put(published.coordinate(), publisher);
                }
            }
        }
        return Map.copyOf(byCoordinate);
    }

    /**
     * <b>Every {@code @qits} version one ref's lockfiles pin</b> — the checkout's own, and each
     * frontend submodule's lock AT THE GITLINK the ref records.
     * <p>
     * The gitlink is the point. A restore stands a deployable at its release tag, and that tag's
     * frontend commit carries a lock that pins {@code @qits/ui-components} EXACTLY — {@code npm ci}
     * reads the lock, not the caret in package.json — so the version its release build installs is
     * whatever the frontend was pinned to when the release was cut, which is weeks behind the
     * publisher's newest tag.
     */
    static Map<String, String> npmPins(String repository, String ref, Sources sources,
                                       List<String> warnings) {
        Map<String, String> pins = new LinkedHashMap<>(
                npmPinsIn(sources.at(repository, ref, LOCK_FILE), warnings));
        String gitmodules = sources.at(repository, ref, ".gitmodules");
        if (gitmodules == null) {
            return pins;
        }
        submodulesIn(gitmodules).forEach((submodule, path) -> {
            String producer = PlatformModel.nameOf(submodule);
            if (producer == null) {
                return;
            }
            String commit = sources.gitlink(repository, ref, path);
            if (commit == null || commit.isBlank()) {
                return;
            }
            String lock = sources.at(producer, commit, LOCK_FILE);
            if (lock == null) {
                // The frontend clone is shallow-ish and follows main; a gitlink an old release tag
                // records can be a commit it does not hold. Said once, and the boot goes on: the
                // release build that needs it will fail with the version in the message.
                warnings.add(PlatformModel.repo(producer) + " has no " + commit.substring(0,
                        Math.min(8, commit.length())) + " in its checkout — the npm versions "
                        + PlatformModel.repo(repository) + " " + ref + " pins through it are not "
                        + "in the closure");
                return;
            }
            pins.putAll(npmPinsIn(lock, warnings));
        });
        return pins;
    }

    /** {@code path} by submodule name, out of a {@code .gitmodules}. */
    static Map<String, String> submodulesIn(String gitmodules) {
        Map<String, String> paths = new LinkedHashMap<>();
        String name = null;
        for (String line : gitmodules.split("\n")) {
            Matcher section = SUBMODULE_SECTION.matcher(line);
            if (section.find()) {
                name = section.group(1);
                continue;
            }
            Matcher path = SUBMODULE_PATH.matcher(line);
            if (name != null && path.find()) {
                paths.put(name, path.group(1).strip());
            }
        }
        return paths;
    }

    /**
     * The {@code @qits} versions one lockfile pins. A version that is not a plain CalVer — the
     * {@code <calver>-main.g<sha>} prereleases the registry also holds — is WARNED and skipped: no
     * release tag names one, so there is nothing to replay.
     */
    static Map<String, String> npmPinsIn(String lock, List<String> warnings) {
        Map<String, String> pins = new LinkedHashMap<>();
        if (lock == null || lock.isBlank()) {
            return pins;
        }
        JsonNode root = Json.parse(lock);
        for (String section : List.of("packages", "dependencies")) {
            root.path(section).properties().forEach(entry -> {
                String key = entry.getKey();
                String name = key.startsWith("node_modules/")
                        ? key.substring("node_modules/".length()) : key;
                if (!NPM_PRODUCERS.containsKey(name)) {
                    return;
                }
                String version = entry.getValue().path("version").asText("");
                if (version.isBlank()) {
                    return;
                }
                if (!CALVER.matcher(version).matches()) {
                    warnings.add(name + " is pinned at " + version + ", which no release tag names "
                            + "— it is skipped, and a build that installs it needs the registry to "
                            + "hold it already");
                    return;
                }
                pins.put(name, version);
            });
        }
        return pins;
    }

    /** Every qits image pinned at a CalVer by one ref's Dockerfiles: image repository to version. */
    static Map<String, String> imagePins(String repository, String ref, Sources sources) {
        Map<String, String> pins = new LinkedHashMap<>();
        for (String path : sources.dockerfiles(repository, ref)) {
            String dockerfile = sources.at(repository, ref, path);
            if (dockerfile != null) {
                pins.putAll(imagePinsIn(dockerfile));
            }
        }
        return pins;
    }

    /**
     * <b>The qits images one Dockerfile pins at a version this platform mints.</b>
     * <p>
     * Two places name one: an {@code ARG} default, which is the override seam every image build of
     * this estate uses, and a {@code FROM} line, which is usually a reference to such an ARG. So
     * the ARGs are resolved as the file goes — {@code FROM ${WORKSPACE_BASE}} means whatever the
     * ARG above it said — and every value either place produces is tested for a coordinate.
     * <p>
     * <b>Only CalVer counts.</b> {@code qits/workspace:latest}, {@code :native}, {@code :local},
     * {@code :<version>} and an unresolved {@code ${…}} are not versions this boot can replay, and
     * a pin whose ARG resolves to {@code latest} is a build that follows the newest tag by design.
     * A registry host in front of the repository is stripped: {@code registry.dev.localhost:8080/}
     * is where a running platform serves the same image, and this run's own address is a different
     * string for the same coordinate.
     */
    static Map<String, String> imagePinsIn(String dockerfile) {
        Map<String, String> pins = new LinkedHashMap<>();
        Map<String, String> args = new LinkedHashMap<>();
        for (String line : dockerfile.split("\n")) {
            Matcher arg = DOCKER_ARG.matcher(line);
            if (arg.find()) {
                String value = arg.group(2) == null ? "" : resolve(arg.group(2).strip(), args);
                args.put(arg.group(1), value);
                pin(value, pins);
                continue;
            }
            Matcher from = DOCKER_FROM.matcher(line);
            if (from.find()) {
                pin(resolve(from.group(1).strip(), args), pins);
            }
        }
        return pins;
    }

    /** {@code ${NAME}} and {@code $NAME} from the ARGs declared above this line, once. */
    private static String resolve(String value, Map<String, String> args) {
        Matcher matcher = DOCKER_REFERENCE.matcher(value);
        StringBuilder resolved = new StringBuilder();
        while (matcher.find()) {
            String name = matcher.group(1) == null ? matcher.group(2) : matcher.group(1);
            matcher.appendReplacement(resolved,
                    Matcher.quoteReplacement(args.getOrDefault(name, matcher.group())));
        }
        matcher.appendTail(resolved);
        return resolved.toString();
    }

    /** One image reference, kept only when it is a qits image at a CalVer. */
    private static void pin(String reference, Map<String, String> pins) {
        String value = reference.replaceAll("^[\"']|[\"']$", "");
        int slash = value.lastIndexOf('/');
        int colon = value.indexOf(':', slash + 1);
        if (colon < 0) {
            return;
        }
        String repository = value.substring(0, colon);
        String version = value.substring(colon + 1);
        int host = repository.indexOf('/');
        if (host > 0) {
            String first = repository.substring(0, host);
            // Docker's own rule for "is this first segment a registry": it has a dot or a port.
            if (first.indexOf('.') >= 0 || first.indexOf(':') >= 0) {
                repository = repository.substring(host + 1);
            }
        }
        if (repository.startsWith("qits/") && CALVER.matcher(version).matches()) {
            pins.put(repository, version);
        }
    }

    private static void queueImages(Map<String, String> pins, Map<String, String> head,
                                    Map<String, TreeSet<String>> found, List<String> warnings,
                                    Set<String> unknown, Deque<String[]> pending, Sources sources) {
        pins.forEach((image, version) -> {
            String producer = IMAGE_PRODUCERS.get(image);
            if (producer == null) {
                unknown.add(image);
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
            // The producer's own checkout has to hold the tag, exactly as for a jar: the replay
            // pushes that tag, and a tag nothing has is a version nobody can build.
            if (sources.at(producer, version, ROOT_POM) == null
                    && sources.dockerfiles(producer, version).isEmpty()) {
                warnings.add(PlatformModel.repo(producer) + " has no " + version + " in its "
                        + "checkout — the " + image + " pin is skipped, and a build that pulls it "
                        + "will fail");
                return;
            }
            versions.add(version);
            pending.addLast(new String[]{producer, version});
        });
    }

    /**
     * <b>An npm pin needs no recursion.</b> A published package version is a tarball, not a build:
     * nothing inside it names another {@code @qits} version this boot would have to publish first,
     * so a pinned version is added and the walk ends there.
     */
    private static void queueNpm(Map<String, String> pins, Map<String, String> head,
                                 Map<String, TreeSet<String>> found, List<String> warnings,
                                 Set<String> unknown, Sources sources) {
        pins.forEach((packageName, version) -> {
            String producer = NPM_PRODUCERS.get(packageName);
            if (producer == null) {
                unknown.add(packageName);
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
            // The tag has to be in the producer's checkout: the replay pushes it, and a tag nothing
            // has is a version nobody can build.
            if (sources.at(producer, version, "package.json") == null) {
                warnings.add(PlatformModel.repo(producer) + " has no " + version + " in its "
                        + "checkout — the " + packageName + " pin is skipped, and a build that "
                        + "installs it will fail");
                return;
            }
            versions.add(version);
        });
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
