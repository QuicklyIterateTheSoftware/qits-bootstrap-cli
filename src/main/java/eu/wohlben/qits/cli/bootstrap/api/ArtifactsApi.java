package eu.wohlben.qits.cli.bootstrap.api;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

/**
 * qits-artifacts: the platform's own packages — the hosted maven repository, the hosted npm
 * registry, the hosted OCI registry, the daemon binaries and the docs bundles, all on one published
 * port.
 * <p>
 * <b>Two things it no longer is.</b> The pull-through caches are qits-platform-mirror, so nothing
 * third-party is asked of this service; the git host is qits-githost, so {@link GitHostApi} owns
 * the repository lifecycle and the clone urls that used to hang off {@code /artifacts/git}.
 */
public class ArtifactsApi {

    private final Http http;
    private final String base;

    public ArtifactsApi(Http http, String artifactsUrl) {
        this.http = http;
        this.base = artifactsUrl;
    }

    public String base() {
        return base;
    }

    public Http.Response health() {
        return http.get(base + "/q/health/ready", Map.of());
    }

    public boolean ready() {
        return health().ok();
    }

    /** Is this maven coordinate already published? */
    public boolean mavenPublished(String groupPath, String artifactId, String version, String extension) {
        String url = base + "/maven/maven/" + groupPath + "/" + artifactId + "/" + version + "/"
                + artifactId + "-" + version + "." + extension;
        return http.get(url, Map.of()).ok();
    }

    /**
     * Re-publishing a version is 409 by design, so this probe is not optional: a blind re-PUT
     * would kill a rerun. HEAD on the version-addressed spelling answers without moving 43 MB.
     */
    public boolean daemonPublished(String name, String version) {
        return http.head(base + "/daemons/" + name + "/" + version).ok();
    }

    /**
     * The cold-start publish of the ci-daemon binary. Bare, like every other publish this
     * bootstrap makes: the daemon surface is tokenless, the same as the npm registry, the maven
     * repository and /v2. What keeps it honest is the immutable version the probe above relies on.
     */
    public Http.Response publishDaemon(String name, String version, Path binary) {
        return http.putFile(base + "/daemons/" + name + "/" + version, binary, Duration.ofMinutes(15));
    }
}
