package eu.wohlben.qits.cli.bootstrap.platform;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The committed Dockerfiles FROM the platform's own pull-through image mirror
 * ({@code mirror.qits.${QITS_DOMAIN}/{quay,redhat,hub}/…}, formerly
 * {@code mirror.dev.localhost:8080/…}), which IS qits-mirror — one of
 * the services the seed phase hand-builds. A cold start cannot pull through the mirror it is
 * starting, so a seed build gets the same Dockerfile with the mirror prefixes rewritten back to the
 * direct upstreams, fed on stdin.
 * <p>
 * <b>THE MIRROR'S OWN SEED BUILD IS THE SHARPEST CASE OF THAT AND NEEDS NO SPECIAL ARM.</b> It goes
 * through this rewrite like every other seed build, so the one image that cannot pull through
 * itself does not: its base layers come straight from quay.io and the Red Hat registry. A build
 * that skipped the rewrite would be a service waiting on a port it is the process behind.
 * <p>
 * Nothing on disk changes: the context is untouched, and every steady-state build (a CI run on a
 * running platform) still goes through the mirror.
 * <p>
 * <b>The same reasoning reaches past the {@code FROM} lines, into the build itself.</b> A service
 * Dockerfile resolves the platform's own jars from {@code https://registry.qits.$QITS_DOMAIN} and
 * Maven Central through {@code https://mirror.qits.$QITS_DOMAIN}, derived inside its build
 * {@code RUN}; the ci-daemon's musl builder {@code ADD}s its two tarballs from the same registry.
 * During a bootstrap that is the LIVE platform, or nothing at all. {@link #localMaven} and
 * {@link #localToolchain} point both at the {@code qits-maven-seed} volume instead, handed to the
 * build as the named context {@link #SEED_M2_CONTEXT}, and Maven Central at its upstream. Both
 * refuse loudly rather than build through a server: a Dockerfile they cannot match, or one that
 * still names {@code registry.qits.} or {@code mirror.qits.} in a {@code RUN} or {@code ADD}
 * afterwards, stops the phase with the file and the missing line named.
 */
public final class SeedDockerfile {

    /**
     * The mirror host as the committed Dockerfiles spell it. <b>A literal, and it is derived from
     * nothing this run knows</b> — not from the mirror port knob, not from the environment name,
     * and not from this run's domain either. The FROM lines are TEXT IN OTHER REPOSITORIES: they say
     * {@code mirror.qits.${QITS_DOMAIN}} on every branch of the fleet, the build-arg reference
     * UNEXPANDED, whatever tier this run bootstraps and whatever domain it serves. Expanding it to
     * {@code mirror.qits.<this run's domain>} would match nothing in the file and silently stop
     * rewriting — which is a seed build waiting on the mirror it is the process behind.
     * <p>
     * It has moved three times, and every move was a fleet-wide edit of those FROM lines. It was
     * localhost:8081 until the byte-plane split, when the caches left qits-artifacts for
     * qits-platform-mirror and took the {@code {quay,redhat,hub}} namespaces with them; then
     * localhost:8082, the mirror's own host port, until unify-ingress closed that port and put the
     * cache behind the edge under a name, {@code mirror.dev.localhost:8080}; then, with qits-1402,
     * the mirror is addressed by its public edge name, {@code mirror.qits.$QITS_DOMAIN}, spelled
     * with the build-arg — a global {@code ARG QITS_DOMAIN} above the first FROM — so the literal
     * here is the unexpanded text. A Dockerfile still naming a spelling older than
     * {@link #LEGACY_MIRROR_HOST} fails on a running platform — a 404 from the hosted registry, or
     * a connection refused on a port nothing holds — rather than as anything a seed build could
     * work around.
     * <p>
     * Change this ONLY together with the fleet: the two spellings have to be the same string or
     * the rewrite is a no-op.
     */
    public static final String MIRROR_HOST = "mirror.qits.${QITS_DOMAIN}";

    /**
     * <b>The spelling before qits-1402, still rewritten.</b> The fleet does not release in one
     * step: until every repository has released its renamed FROM lines, a cold start meets both
     * spellings — possibly in the same boot — and each has to come back to the direct upstreams.
     * Drop this once no released Dockerfile names it.
     */
    public static final String LEGACY_MIRROR_HOST = "mirror.dev.localhost:8080";

    private SeedDockerfile() {
    }

    /**
     * Both spellings the fleet's committed Dockerfiles may carry: {@link #MIRROR_HOST} and
     * {@link #LEGACY_MIRROR_HOST}.
     */
    public static String rewrite(String dockerfile) {
        return seedOnly(upstreams(upstreams(dockerfile, MIRROR_HOST), LEGACY_MIRROR_HOST));
    }

    public static String read(Path dockerfile) throws IOException {
        return rewrite(Files.readString(dockerfile, StandardCharsets.UTF_8));
    }

    /**
     * @param mirrorHost the one mirror host the committed Dockerfiles name, when a caller knows
     *                   better than {@link #MIRROR_HOST} and {@link #LEGACY_MIRROR_HOST}
     */
    public static String rewrite(String dockerfile, String mirrorHost) {
        return seedOnly(upstreams(dockerfile, mirrorHost));
    }

    private static String upstreams(String dockerfile, String mirrorHost) {
        return dockerfile
                .replace(mirrorHost + "/quay/", "quay.io/")
                .replace(mirrorHost + "/redhat/", "registry.access.redhat.com/")
                .replace(mirrorHost + "/hub/", "docker.io/");
    }

    private static String seedOnly(String dockerfile) {
        return dockerfile
                // Maven waits for native-image but otherwise kept a large ergonomic heap alive.
                // Bound the parent so the compiler can use its heap under the recovery cgroup.
                .replace("./mvnw", "MAVEN_OPTS=\"-Xmx384m\" ./mvnw")
                // The committed Dockerfiles cap native-image at 4 GB, which the smaller services
                // build under. The largest (qits-projects, qits-workspaces) need more — native-image
                // asks for >4.44 GB and is OOM-killed at 4 GB. A seed build runs one at a time and
                // sees the whole host (no build cgroup cap here), so raise the ceiling to 6 GB for
                // every seed image rather than editing each Dockerfile.
                .replace(
                    "-Dquarkus.native.native-image-xmx=4g",
                    "-Dquarkus.native.native-image-xmx=6g");
    }

    public static String read(Path dockerfile, String mirrorHost) throws IOException {
        return rewrite(Files.readString(dockerfile, StandardCharsets.UTF_8), mirrorHost);
    }

    // --- the local stores ---------------------------------------------------------------------

    /**
     * The named build context a seed build reads the {@code qits-maven-seed} volume through — the
     * file-layout Maven repository {@code maven-seed} and the seed publishes deploy into, and where
     * {@code seed-toolchain} puts the musl tarballs.
     */
    public static final String SEED_M2_CONTEXT = "qits-m2";

    /** Where a rewritten build {@code RUN} sees that context. */
    static final String SEED_M2_TARGET = "/qits-m2";

    /** The bind mount a rewritten build {@code RUN} carries. Read-only, BuildKit's default. */
    static final String SEED_M2_MOUNT =
            "--mount=type=bind,from=" + SEED_M2_CONTEXT + ",target=" + SEED_M2_TARGET;

    /**
     * What {@code QITS_MAVEN_REPOSITORY_URL} becomes. A {@code file://} url is an ordinary Maven
     * repository url, and every repository's {@code .qits-maven-settings.xml} mirrors the pom's
     * {@code qits-maven} id onto whatever the variable holds; the {@code <server>} credential beside
     * that mirror is simply never asked for.
     */
    static final String LOCAL_REPOSITORY_URL = "file://" + SEED_M2_TARGET;

    /**
     * What {@code QITS_MAVEN_CENTRAL_URL} becomes: Central itself. Third-party jars come from
     * upstream in a seed build, like the base layers do. Non-empty on purpose — the settings file's
     * central profile activates on a non-empty value and reroutes {@code qits-central} here.
     */
    static final String UPSTREAM_CENTRAL_URL = "https://repo.maven.apache.org/maven2";

    /**
     * The hosted Maven as the committed Dockerfiles derive it, with or without braces around the
     * build-arg — the export form, the inline-assignment form and the literal
     * {@code -Dqits.maven.repository.url=} all spell this.
     */
    private static final Pattern REGISTRY_URL = Pattern.compile(
            "https?://registry\\.qits\\.\\$\\{?QITS_DOMAIN\\}?/artifacts/maven/maven");

    /** The Maven Central cache, spelled the same way. qits-mirror's own Dockerfile has none. */
    private static final Pattern CENTRAL_URL = Pattern.compile(
            "https?://mirror\\.qits\\.\\$\\{?QITS_DOMAIN\\}?/mirror/maven/central");

    /** What may not survive in a {@code RUN} or {@code ADD} of a seed build. */
    private static final List<String> PLATFORM_HOSTS = List.of("registry.qits.", "mirror.qits.");

    /**
     * <b>A seed service's build {@code RUN}, resolving from the local file repository.</b>
     * <p>
     * Every {@code RUN} that names the hosted Maven gets {@link #SEED_M2_MOUNT} and has that url
     * replaced by {@link #LOCAL_REPOSITORY_URL} and the Central cache's by
     * {@link #UPSTREAM_CENTRAL_URL}. The values are replaced where they stand, so the
     * {@code export} form, the inline {@code VAR=value ./mvnw} form (qits-edge, qits-containers)
     * and a literal {@code -Dqits.maven.repository.url=} come out alike. The secret mounts stay:
     * a seed build passes no secret, and the {@code || true} each Dockerfile carries reads that as
     * an empty credential the file repository never asks for.
     *
     * @param origin what to name in a refusal — the Dockerfile's path
     * @throws IllegalStateException when no {@code RUN} names the hosted Maven, or when one still
     *                               names a platform host after the rewrite
     */
    public static String localMaven(String dockerfile, String origin) {
        List<String> out = new ArrayList<>();
        int rewritten = 0;
        for (String instruction : instructions(dockerfile)) {
            if (isInstruction(instruction, "RUN") && REGISTRY_URL.matcher(instruction).find()) {
                String local = CENTRAL_URL.matcher(
                        REGISTRY_URL.matcher(instruction)
                                .replaceAll(Matcher.quoteReplacement(LOCAL_REPOSITORY_URL)))
                        .replaceAll(Matcher.quoteReplacement(UPSTREAM_CENTRAL_URL));
                int run = keywordAt(local) + "RUN".length();
                out.add(local.substring(0, run) + " " + SEED_M2_MOUNT + " \\\n   "
                        + local.substring(run));
                rewritten++;
            } else {
                out.add(instruction);
            }
        }
        if (rewritten == 0) {
            throw new IllegalStateException(origin + " has no RUN that derives "
                    + "QITS_MAVEN_REPOSITORY_URL as https://registry.qits.$QITS_DOMAIN"
                    + "/artifacts/maven/maven, so the seed build cannot point it at the local "
                    + "repository in the qits-maven-seed volume. The Dockerfile changed shape: "
                    + "teach SeedDockerfile.localMaven the new one rather than let the build "
                    + "resolve the platform's jars from the live registry");
        }
        String result = String.join("", out);
        refuseServers(result, origin);
        return result;
    }

    /**
     * <b>The musl builder's two {@code ADD $<NAME>_URL <dest>}, as copies out of the local file
     * repository.</b>
     * <p>
     * The tarballs are where {@code seed-toolchain} deployed them, at their Maven path under the
     * {@link #SEED_M2_CONTEXT} context, so each {@code ADD} becomes
     * {@code COPY --from=qits-m2 /<store path> <dest>} and the {@code *_URL} build-args go unread.
     * Bytes that hashed to the Dockerfile's own pin when they were downloaded, then, and no server
     * in between.
     *
     * @param tarballs what {@link MuslToolchain#read} found in the same Dockerfile
     * @param origin   what to name in a refusal
     * @throws IllegalStateException when a tarball's {@code ADD} is not there, or when any
     *                               {@code ADD} of a {@code *_URL} or any platform host survives
     */
    public static String localToolchain(String dockerfile, String origin,
                                        List<MuslToolchain.Tarball> tarballs) {
        String result = dockerfile;
        for (MuslToolchain.Tarball tarball : tarballs) {
            Pattern add = Pattern.compile("(?m)^([ \\t]*)ADD[ \\t]+\\$\\{?"
                    + Pattern.quote(tarball.arg()) + "\\}?[ \\t]+(\\S+)[ \\t]*$");
            Matcher matcher = add.matcher(result);
            if (!matcher.find()) {
                throw new IllegalStateException(origin + " has no `ADD $" + tarball.arg()
                        + " <dest>` line, so the seed build cannot hand it " + tarball.storePath()
                        + " out of the qits-maven-seed volume. The Dockerfile changed shape: teach "
                        + "SeedDockerfile.localToolchain the new one rather than let the build "
                        + "fetch the tarball from a server");
            }
            result = matcher.replaceAll(Matcher.quoteReplacement(matcher.group(1)) + "COPY --from="
                    + SEED_M2_CONTEXT + " /" + Matcher.quoteReplacement(tarball.storePath()) + " "
                    + Matcher.quoteReplacement(matcher.group(2)));
        }
        for (String instruction : instructions(result)) {
            if (isInstruction(instruction, "ADD") && instruction.contains("_URL")) {
                throw new IllegalStateException(origin + " still ADDs a *_URL after the seed "
                        + "rewrite (" + instruction.strip() + "), which would fetch from whatever "
                        + "that build-arg names. Teach SeedDockerfile.localToolchain about it");
            }
        }
        refuseServers(result, origin);
        return result;
    }

    /**
     * The last word on both rewrites, and the whole check for a seed image that resolves no jars:
     * no {@code RUN} and no {@code ADD} of a seed build names a platform host. Comments,
     * {@code ARG} defaults and {@code FROM} lines are not fetched from by the build and are not
     * asked about — the {@code FROM} lines are {@link #rewrite}'s.
     *
     * @return the Dockerfile, unchanged
     */
    public static String refuseServers(String dockerfile, String origin) {
        for (String instruction : instructions(dockerfile)) {
            if (!isInstruction(instruction, "RUN") && !isInstruction(instruction, "ADD")) {
                continue;
            }
            for (String line : instruction.split("\n")) {
                if (line.strip().startsWith("#")) {
                    continue;
                }
                for (String host : PLATFORM_HOSTS) {
                    if (line.contains(host)) {
                        throw new IllegalStateException(origin + " still reaches " + host
                                + "… in a build step after the seed rewrite: `" + line.strip()
                                + "`. A seed build resolves from the qits-maven-seed volume and "
                                + "from upstream only; teach SeedDockerfile about this line");
                    }
                }
            }
        }
        return dockerfile;
    }

    // --- platform-built bases, from this run's own builds (qits-889) ------------------------------

    private static final Pattern GLOBAL_ARG = Pattern.compile("(?m)^ARG[ \\t]+([A-Za-z0-9_]+)=(\\S*)[ \\t]*$");

    /**
     * The committed default of a build argument — {@code ARG <name>=<value>} — or null when the file
     * declares none. The FIRST declaration is the answer, because that is the global one a
     * {@code FROM} reads; a stage's own {@code ARG <name>} redeclaration carries no default.
     */
    public static String argDefault(String dockerfile, String name) {
        Matcher matcher = GLOBAL_ARG.matcher(dockerfile);
        while (matcher.find()) {
            if (matcher.group(1).equals(name)) {
                return matcher.group(2);
            }
        }
        return null;
    }

    /** The tag of an image reference — what follows its last {@code :} after the last {@code /}. */
    public static String tagOf(String reference) {
        if (reference == null) {
            return null;
        }
        int slash = reference.lastIndexOf('/');
        int colon = reference.lastIndexOf(':');
        return colon > slash ? reference.substring(colon + 1) : null;
    }

    /**
     * <b>A base image this platform builds, taken from THIS RUN's build and from no registry.</b>
     * <p>
     * The image Dockerfiles name their platform-built base as a build argument whose default is the
     * live registry — {@code ARG WORKSPACE_BASE=registry.qits.${QITS_DOMAIN}/qits/workspace-base:<pin>}
     * — and {@code FROM ${WORKSPACE_BASE}}. This rewrites every default of that argument to the
     * LOCAL reference the bootstrap built the base under, which the build then resolves through a
     * named context ({@link Docker} {@code LocalBase}): qits-buildkitd is its own daemon and cannot
     * see the host's image store, so the reference alone would be a pull from Docker Hub.
     * <p>
     * <b>It refuses rather than guesses</b>: a file that declares no default for the argument
     * changed shape, and building it would reach for whatever its FROM now names.
     */
    public static String localBase(String dockerfile, String origin, String arg, String localRef) {
        Pattern declared = Pattern.compile("(?m)^(ARG[ \\t]+" + Pattern.quote(arg) + ")=\\S*[ \\t]*$");
        Matcher matcher = declared.matcher(dockerfile);
        if (!matcher.find()) {
            throw new IllegalStateException(origin + " declares no `ARG " + arg + "=<image>`, so "
                    + "the seed build cannot hand it " + localRef + " from this run's own build. The "
                    + "Dockerfile changed shape: teach SeedDockerfile.localBase the new one rather "
                    + "than let the build pull its base from a registry");
        }
        return matcher.replaceAll(Matcher.quoteReplacement(matcher.group(1) + "=" + localRef));
    }

    private static final Pattern FROM_ARG = Pattern.compile("\\$\\{?([A-Za-z0-9_]+)\\}?");

    /**
     * <b>No stage of the file starts from a platform registry</b> — neither a {@code FROM} that
     * names {@code registry.qits.}/{@code mirror.qits.} after {@link #rewrite}, nor a build argument
     * a {@code FROM} reads whose default still does. {@link #refuseServers} looks at the build
     * steps; this is the same refusal for the bases, which a platform-built base needs and an
     * upstream one never did.
     */
    public static String refusePlatformBases(String dockerfile, String origin) {
        for (String instruction : instructions(dockerfile)) {
            if (!isInstruction(instruction, "FROM")) {
                continue;
            }
            // The instruction's own lines: the comment paragraph above it rides in the piece.
            String from = instruction.lines().map(String::strip)
                    .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                    .collect(java.util.stream.Collectors.joining(" "));
            List<String> named = new ArrayList<>(List.of(from));
            Matcher arg = FROM_ARG.matcher(from);
            while (arg.find()) {
                String value = argDefault(dockerfile, arg.group(1));
                if (value != null) {
                    named.add(value);
                }
            }
            for (String text : named) {
                for (String host : PLATFORM_HOSTS) {
                    if (text.contains(host)) {
                        throw new IllegalStateException(origin + " still starts a stage from "
                                + host + "… after the seed rewrite: `" + from + "` (" + text
                                + "). A seed build takes a platform-built base from this run's "
                                + "own build; teach SeedDockerfile.localBase about this argument");
                    }
                }
            }
        }
        return dockerfile;
    }

    /**
     * The file cut into instructions, each with its continuation lines and the comment and blank
     * lines before it, so joining the pieces gives the file back byte for byte. A line ending in a
     * backslash continues the instruction; a comment line inside one does not end it, which is how
     * the Dockerfile parser reads it too.
     */
    static List<String> instructions(String dockerfile) {
        List<String> pieces = new ArrayList<>();
        StringBuilder piece = new StringBuilder();
        boolean continued = false;
        for (String line : dockerfile.split("(?<=\n)", -1)) {
            if (line.isEmpty()) {
                continue;
            }
            piece.append(line);
            String body = line.stripTrailing();
            if (body.isEmpty() || body.strip().startsWith("#")) {
                continue;
            }
            continued = body.endsWith("\\");
            if (!continued) {
                pieces.add(piece.toString());
                piece.setLength(0);
            }
        }
        if (!piece.isEmpty()) {
            pieces.add(piece.toString());
        }
        return pieces;
    }

    /** Whether a piece of {@link #instructions} is this instruction, comments before it aside. */
    private static boolean isInstruction(String piece, String keyword) {
        int at = keywordAt(piece);
        if (at < 0 || !piece.regionMatches(true, at, keyword, 0, keyword.length())) {
            return false;
        }
        int after = at + keyword.length();
        return after == piece.length() || Character.isWhitespace(piece.charAt(after));
    }

    /**
     * Where the instruction keyword of a piece starts: the first character of its first line that
     * is neither blank nor a comment, or -1 for a piece that is only comments.
     */
    private static int keywordAt(String piece) {
        int offset = 0;
        for (String line : piece.split("(?<=\n)")) {
            String body = line.strip();
            if (!body.isEmpty() && !body.startsWith("#")) {
                return offset + line.indexOf(body.charAt(0));
            }
            offset += line.length();
        }
        return -1;
    }
}
