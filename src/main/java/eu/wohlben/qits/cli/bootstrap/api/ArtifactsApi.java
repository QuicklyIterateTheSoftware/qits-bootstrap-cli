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

    /**
     * The Distribution API's own root, which is the SERVICE root and not under {@code /artifacts}.
     * A docker client demands {@code /v2/} at the host it was given, so the store serves it there —
     * measured on the live platform, where {@code /artifacts/v2/} answers 404 and {@code /v2/}
     * answers 200.
     */
    public String registryBase() {
        return base.endsWith("/artifacts")
                ? base.substring(0, base.length() - "/artifacts".length()) : base;
    }

    /** Every manifest media type a qits image could have been pushed as. */
    static final String MANIFEST_TYPES = String.join(",",
            "application/vnd.oci.image.index.v1+json",
            "application/vnd.oci.image.manifest.v1+json",
            "application/vnd.docker.distribution.manifest.list.v2+json",
            "application/vnd.docker.distribution.manifest.v2+json");

    /** Where one image tag's manifest lives, kept pure so the shape is provable without a store. */
    public static String manifestUrl(String registryBase, String repository, String tag) {
        return registryBase + "/v2/" + repository + "/manifests/" + tag;
    }

    /**
     * <b>Does the registry hold this image at this tag?</b> 200 or 404, and the {@code Accept}
     * header is not decoration: a registry may answer 404 for a manifest whose media type the
     * caller did not ask for. Read anonymously, like every other read this bootstrap makes at the
     * store's own alias.
     */
    public boolean imagePublished(String repository, String tag) {
        return http.get(manifestUrl(registryBase(), repository, tag),
                Map.of("Accept", MANIFEST_TYPES)).ok();
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
