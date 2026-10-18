package eu.wohlben.qits.cli.bootstrap.platform;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

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
}
