package eu.wohlben.qits.cli.bootstrap.platform;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The committed Dockerfiles FROM the platform's own pull-through image mirror
 * ({@code localhost:8082/{quay,redhat,hub}/…}), which IS qits-platform-mirror — one of the services
 * the seed phase hand-builds. A cold start cannot pull through the mirror it is starting, so a seed
 * build gets the same Dockerfile with the mirror prefixes rewritten back to the direct upstreams,
 * fed on stdin.
 * <p>
 * <b>THE MIRROR'S OWN SEED BUILD IS THE SHARPEST CASE OF THAT AND NEEDS NO SPECIAL ARM.</b> It goes
 * through this rewrite like every other seed build, so the one image that cannot pull through
 * itself does not: its base layers come straight from quay.io and the Red Hat registry. A build
 * that skipped the rewrite would be a service waiting on a port it is the process behind.
 * <p>
 * Nothing on disk changes: the context is untouched, and every steady-state build (a CI run on a
 * running platform) still goes through the mirror.
 */
public final class SeedDockerfile {

    /**
     * The mirror host as the committed Dockerfiles spell it. A literal, not a port knob: the knobs
     * move the host ports this bootstrap publishes, while the FROM lines in the repositories say
     * localhost:8082 whatever this run does.
     * <p>
     * It was localhost:8081 until the byte-plane split, when the caches left qits-artifacts for
     * qits-platform-mirror and took the {@code {quay,redhat,hub}} namespaces with them. 8081 is the
     * HOSTED registry now and serves none of those prefixes, so a Dockerfile still naming it would
     * fail on a running platform with a 404 rather than a name that does not resolve.
     */
    public static final String MIRROR_HOST = "localhost:8082";

    private SeedDockerfile() {
    }

    public static String rewrite(String dockerfile) {
        return rewrite(dockerfile, MIRROR_HOST);
    }

    public static String read(Path dockerfile) throws IOException {
        return read(dockerfile, MIRROR_HOST);
    }

    /**
     * @param mirrorHost the mirror host the committed Dockerfiles name, {@code localhost:8082} by
     *                   default and whatever the mirror port knob says otherwise
     */
    public static String rewrite(String dockerfile, String mirrorHost) {
        return dockerfile
                .replace(mirrorHost + "/quay/", "quay.io/")
                .replace(mirrorHost + "/redhat/", "registry.access.redhat.com/")
                .replace(mirrorHost + "/hub/", "docker.io/");
    }

    public static String read(Path dockerfile, String mirrorHost) throws IOException {
        return rewrite(Files.readString(dockerfile, StandardCharsets.UTF_8), mirrorHost);
    }
}
