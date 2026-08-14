package eu.wohlben.qits.cli.bootstrap.platform;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The committed Dockerfiles FROM the platform's own pull-through image mirror
 * ({@code mirror.dev.localhost:8080/{quay,redhat,hub}/…}), which IS qits-platform-mirror — one of
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
 */
public final class SeedDockerfile {

    /**
     * The mirror host as the committed Dockerfiles spell it. <b>A literal, and it is derived from
     * nothing this run knows</b> — not from the mirror port knob, and not from the environment
     * name either. The FROM lines are TEXT IN OTHER REPOSITORIES: they say
     * {@code mirror.dev.localhost:8080} on every branch of the fleet, whatever tier this run
     * bootstraps and whatever ports it publishes. Deriving {@code mirror.<env>.localhost} would
     * match nothing on a platform called anything but {@code dev} and silently stop rewriting —
     * which is a seed build waiting on the mirror it is the process behind.
     * <p>
     * It has moved twice, and both moves were a fleet-wide edit of those FROM lines. It was
     * localhost:8081 until the byte-plane split, when the caches left qits-artifacts for
     * qits-platform-mirror and took the {@code {quay,redhat,hub}} namespaces with them; then
     * localhost:8082, the mirror's own host port, until unify-ingress closed that port and put the
     * cache behind the edge under a name. A Dockerfile still naming an older spelling fails on a
     * running platform — a 404 from the hosted registry, or a connection refused on a port nothing
     * holds — rather than as anything a seed build could work around.
     * <p>
     * Change this ONLY together with the fleet: the two spellings have to be the same string or
     * the rewrite is a no-op.
     */
    public static final String MIRROR_HOST = "mirror.dev.localhost:8080";

    private SeedDockerfile() {
    }

    public static String rewrite(String dockerfile) {
        return rewrite(dockerfile, MIRROR_HOST);
    }

    public static String read(Path dockerfile) throws IOException {
        return read(dockerfile, MIRROR_HOST);
    }

    /**
     * @param mirrorHost the mirror host the committed Dockerfiles name,
     *                   {@code mirror.dev.localhost:8080} unless a caller knows better
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
