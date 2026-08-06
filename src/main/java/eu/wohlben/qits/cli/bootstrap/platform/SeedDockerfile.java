package eu.wohlben.qits.cli.bootstrap.platform;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The committed Dockerfiles FROM the platform's own pull-through image mirror
 * ({@code localhost:8081/{quay,redhat,hub}/…}), which IS qits-artifacts — one of the services the
 * seed phase hand-builds. A cold start cannot pull through the registry it is starting, so a seed
 * build gets the same Dockerfile with the mirror prefixes rewritten back to the direct upstreams,
 * fed on stdin.
 * <p>
 * Nothing on disk changes: the context is untouched, and every steady-state build (CI's
 * post-receive, on a running platform) still goes through the mirror.
 */
public final class SeedDockerfile {

    /**
     * The mirror host as the committed Dockerfiles spell it. A literal, not the registry-port
     * knob: the knob moves the host port this bootstrap publishes, while the FROM lines in the
     * repositories say localhost:8081 whatever this run does.
     */
    public static final String MIRROR_HOST = "localhost:8081";

    private SeedDockerfile() {
    }

    public static String rewrite(String dockerfile) {
        return rewrite(dockerfile, MIRROR_HOST);
    }

    public static String read(Path dockerfile) throws IOException {
        return read(dockerfile, MIRROR_HOST);
    }

    /**
     * @param mirrorHost the registry host the committed Dockerfiles name, {@code localhost:8081}
     *                   by default and whatever the registry port knob says otherwise
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
