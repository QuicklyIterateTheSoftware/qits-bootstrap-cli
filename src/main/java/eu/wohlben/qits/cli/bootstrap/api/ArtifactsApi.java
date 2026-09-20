package eu.wohlben.qits.cli.bootstrap.api;

import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

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
    private final Supplier<String> readAuthorization;

    /** A store this run reads without presenting anything — the shape the tests use. */
    public ArtifactsApi(Http http, String artifactsUrl) {
        this(http, artifactsUrl, () -> null);
    }

    /**
     * @param readAuthorization the header value this run's READS present, or null where there is
     *                          nothing to present yet. Every method here but {@link #health()}
     *                          asks it per call rather than holding one: a boot's first probes
     *                          happen before any idp exists, and the store they meet is the
     *                          ungated seed. A PUBLISH takes its credential as an argument instead
     *                          — it is a different identity, with a different lifetime
     */
    public ArtifactsApi(Http http, String artifactsUrl, Supplier<String> readAuthorization) {
        this.http = http;
        this.base = artifactsUrl;
        this.readAuthorization = readAuthorization;
    }

    /**
     * What a read carries: the run's own machine token where it has one, and nothing where it has
     * not. Never the publishing credential — that one is commissioned for the publish phase and
     * handed back at the end of it, while reads go on for the rest of the boot.
     */
    private Map<String, String> readHeaders() {
        String authorization = readAuthorization.get();
        return authorization == null || authorization.isBlank() ? Map.of()
                : Map.of("Authorization", authorization);
    }

    /** The same, with one more header beside it. */
    private Map<String, String> readHeaders(String name, String value) {
        Map<String, String> headers = new LinkedHashMap<>(readHeaders());
        headers.put(name, value);
        return headers;
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
     * caller did not ask for. Carries this run's own token where there is one: reads at the store
     * are behind the machine gate too.
     */
    public boolean imagePublished(String repository, String tag) {
        return http.get(manifestUrl(registryBase(), repository, tag),
                readHeaders("Accept", MANIFEST_TYPES)).ok();
    }

    /**
     * <b>Where one npm version's tarball lives</b> — which is the path {@code npm ci} fetches, and
     * the one the 404 in a failing install names. The file name drops the scope:
     * {@code @qits/ui-components} at 1.2.3 is {@code @qits/ui-components/-/ui-components-1.2.3.tgz}.
     */
    public static String npmTarballUrl(String base, String packageName, String version) {
        String unscoped = packageName.substring(packageName.indexOf('/') + 1);
        return base + "/npm/npm/" + packageName + "/-/" + unscoped + "-" + version + ".tgz";
    }

    /**
     * Does the registry hold this npm version? Asked at the TARBALL rather than at
     * {@code <package>/<version>}, which this registry does not serve — measured, 404 for a version
     * its own packument lists. HEAD, so a probe moves no bytes.
     */
    public boolean npmPublished(String packageName, String version) {
        return http.head(npmTarballUrl(base, packageName, version), readHeaders()).ok();
    }

    /** Is this maven coordinate already published? */
    public boolean mavenPublished(String groupPath, String artifactId, String version, String extension) {
        String url = base + "/maven/maven/" + groupPath + "/" + artifactId + "/" + version + "/"
                + artifactId + "-" + version + "." + extension;
        return http.get(url, readHeaders()).ok();
    }

    /**
     * Re-publishing a version is 409 by design, so this probe is not optional: a blind re-PUT
     * would kill a rerun. HEAD on the version-addressed spelling answers without moving 43 MB.
     * <p>
     * Credentialed like every other read here, and it has to be: a gated store answers an
     * anonymous HEAD with a 401, which is not a 404 — the probe would read "already published"
     * from a refusal and skip the publish the boot needs.
     */
    public boolean daemonPublished(String name, String version) {
        return http.head(base + "/daemons/" + name + "/" + version, readHeaders()).ok();
    }

    /**
     * <b>The cold-start publish of the ci-daemon binary, and the one publish this class makes.</b>
     * <p>
     * It presents the credential it is GIVEN rather than the one the reads use, and the difference
     * is the whole of the "only CI may publish" ruling (2026-09-13): the store's anonymous
     * publishing door is closed, {@code qits:ci-run} is the only role that opens it, and the
     * bootstrap commissions itself one of kind {@code bootstrap-publish} for its publish phase and
     * hands it back when that phase ends. See {@link BootstrapPublishCredential}.
     *
     * @param authorization the header value, or null where the gate is off and there is nothing to
     *                      present — the seed store that has not been handed a gate yet
     */
    public Http.Response publishDaemon(String name, String version, Path binary,
                                       String authorization) {
        Map<String, String> headers = authorization == null || authorization.isBlank() ? Map.of()
                : Map.of("Authorization", authorization);
        return http.putFile(base + "/daemons/" + name + "/" + version, binary,
                Duration.ofMinutes(15), headers);
    }
}
