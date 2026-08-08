package eu.wohlben.qits.cli.bootstrap.api;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

/**
 * qits-platform-artifacts: the maven repository, the npm registry, the OCI registry, the daemon
 * store and
 * the git host, all on one published port.
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
     * Creates a repository on the git host. Idempotent by design: 201 when this call created it,
     * 200 when one was already there.
     */
    public Http.Response createRepository(String repoId) {
        return http.putJson(base + "/git/" + repoId, Json.object("defaultBranch", "main"), Map.of());
    }

    /** The clone and push URL of a repository on the platform git host. */
    public String gitUrl(String repoId) {
        return base + "/git/" + repoId;
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
