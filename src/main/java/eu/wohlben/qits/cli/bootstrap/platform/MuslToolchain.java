package eu.wohlben.qits.cli.bootstrap.platform;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * <b>The two tarballs the ci-daemon's musl builder image is made of, read out of the Dockerfile
 * that consumes them.</b>
 * <p>
 * qits-ci-daemon's {@code docker/Dockerfile.musl-builder} stopped fetching its GraalVM musl
 * toolchain and its zlib source from the internet on 2026-09-05: a community host that is slow on a
 * good day was down on a bad one, and a QA run reddened on it the first time the docker layer cache
 * had been collected out from under the repository. Both tarballs are uploaded once, unmodified,
 * under {@code eu.wohlben.qits.toolchain} in the platform's own Maven store, and the Dockerfile's
 * two {@code ARG} defaults name them there.
 * <p>
 * <b>Which is a coordinate a cold platform's store does not hold.</b> Nothing in the estate seeds
 * them — they were uploaded by hand on the live platform — so the first bootstrap of a fresh host
 * reaches phase 37 and dies in the {@code ADD}, and the default ARG host makes the failure read as
 * a network fault rather than a missing artifact: {@code registry.dev.localhost:8080} is the
 * domainless local vhost, and no edge exists that early in a boot. Measured on 2026-09-05:
 * {@code dial tcp [::1]:8080: connection refused}.
 * <p>
 * <b>The pins are the consuming repository's, and they are read rather than copied.</b> The
 * Dockerfile records each tarball's size and sha256 beside its upstream address, because those
 * facts belong to the image that is built from them — a CLI holding its own copy would be a second
 * place to update and a silent way to disagree. So this reads them, and a Dockerfile whose comment
 * block has been reformatted past recognition fails loudly here rather than seeding unverified
 * bytes.
 */
public final class MuslToolchain {

    /** Where the store's Maven repository begins in an artifact URL. */
    private static final String MAVEN_ROOT = "/artifacts/maven/maven/";

    /** {@code ARG MUSL_URL=<default>}: real Dockerfile syntax, and the coordinate's home. */
    private static final Pattern ARG =
            Pattern.compile("(?m)^ARG\\s+([A-Z0-9_]+_URL)=(\\S+)\\s*$");

    /** The override example in the comment, which is where the upstream address is written. */
    private static final Pattern UPSTREAM_OF =
            Pattern.compile("--build-arg\\s+%s=(https://\\S+?)\\s*\\\\?\\s*$");

    /** {@code <n> B  sha256 <hex>} on the line that names the tarball. */
    private static final Pattern PIN =
            Pattern.compile("(\\d+)\\s*B\\s+sha256\\s+([0-9a-f]{64})");

    /**
     * One tarball: where the store keeps it, where it came from, and what it has to hash to.
     *
     * @param arg        the Dockerfile ARG whose value points at it — the seed build overrides this
     * @param groupPath  the Maven group as a path, {@code eu/wohlben/qits/toolchain}
     * @param extension  the packaging, which is also the file's extension: {@code tgz}, {@code tar.gz}
     * @param upstream   the address the bytes are fetched from when the store has none
     */
    public record Tarball(String arg, String groupPath, String artifactId, String version,
                          String fileName, String extension, String upstream, String sha256,
                          long bytes) {

        /** The path under the store's Maven repository, which is also the ARG default's tail. */
        public String storePath() {
            return groupPath + "/" + artifactId + "/" + version + "/" + fileName;
        }

        /** The Maven group id the deploy is made under. */
        public String groupId() {
            return groupPath.replace('/', '.');
        }
    }

    private MuslToolchain() {
    }

    /**
     * Every store-hosted tarball the Dockerfile declares. An {@code ARG *_URL} whose default does
     * not point into the store is ignored — a dev machine's override lives in a comment, not in a
     * default — and one that does but carries no pin stops the caller.
     */
    public static List<Tarball> read(String dockerfile) {
        List<Tarball> tarballs = new ArrayList<>();
        Matcher arg = ARG.matcher(dockerfile);
        while (arg.find()) {
            String name = arg.group(1);
            String url = arg.group(2);
            int root = url.indexOf(MAVEN_ROOT);
            if (root < 0) {
                continue;
            }
            String[] segments = url.substring(root + MAVEN_ROOT.length()).split("/");
            if (segments.length < 4) {
                throw new IllegalStateException("the " + name + " default in "
                        + "docker/Dockerfile.musl-builder is not a maven coordinate: " + url);
            }
            String fileName = segments[segments.length - 1];
            String version = segments[segments.length - 2];
            String artifactId = segments[segments.length - 3];
            String groupPath = String.join("/",
                    List.of(segments).subList(0, segments.length - 3));
            String prefix = artifactId + "-" + version + ".";
            if (!fileName.startsWith(prefix)) {
                throw new IllegalStateException("the " + name + " default in "
                        + "docker/Dockerfile.musl-builder names " + fileName + ", which is not "
                        + prefix + "<extension>");
            }
            tarballs.add(new Tarball(name, groupPath, artifactId, version, fileName,
                    fileName.substring(prefix.length()), upstream(dockerfile, name),
                    pin(dockerfile, name, artifactId), bytes(dockerfile, name, artifactId)));
        }
        if (tarballs.isEmpty()) {
            throw new IllegalStateException("docker/Dockerfile.musl-builder declares no ARG "
                    + "pointing into " + MAVEN_ROOT + " — the toolchain seed has nothing to seed, "
                    + "and the builder image would fetch from wherever the ARG now points");
        }
        return List.copyOf(tarballs);
    }

    private static String upstream(String dockerfile, String arg) {
        Matcher matcher = Pattern.compile(UPSTREAM_OF.pattern().formatted(Pattern.quote(arg)),
                Pattern.MULTILINE).matcher(dockerfile);
        if (!matcher.find()) {
            throw new IllegalStateException("docker/Dockerfile.musl-builder records no upstream "
                    + "address for " + arg + ". It is the `--build-arg " + arg + "=https://…` in "
                    + "that file's own override example, and the seed needs it to fill an empty "
                    + "store");
        }
        return matcher.group(1);
    }

    private static String pin(String dockerfile, String arg, String artifactId) {
        return pinLine(dockerfile, arg, artifactId).group(2);
    }

    private static long bytes(String dockerfile, String arg, String artifactId) {
        return Long.parseLong(pinLine(dockerfile, arg, artifactId).group(1));
    }

    /**
     * The comment line that carries this artifact's size and hash. Matched by ARTIFACT ID rather
     * than by file name: the comment names the upstream tarball
     * ({@code x86_64-linux-musl-native.tgz}) and the store names the versioned one
     * ({@code x86_64-linux-musl-native-11.2.1.tgz}), and the artifact id is the half both spell.
     */
    private static Matcher pinLine(String dockerfile, String arg, String artifactId) {
        for (String line : dockerfile.split("\n")) {
            if (!line.startsWith("#") || !line.contains(artifactId)) {
                continue;
            }
            Matcher pin = PIN.matcher(line);
            if (pin.find()) {
                return pin;
            }
        }
        throw new IllegalStateException("docker/Dockerfile.musl-builder records no `<size> B "
                + "sha256 <hash>` for " + artifactId + " (" + arg + "). The pin belongs to that "
                + "repository, and the seed refuses to upload bytes it cannot check");
    }
}
