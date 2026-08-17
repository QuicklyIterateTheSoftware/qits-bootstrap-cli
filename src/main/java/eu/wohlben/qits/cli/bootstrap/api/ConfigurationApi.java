package eu.wohlben.qits.cli.bootstrap.api;

import java.util.Map;

/**
 * qits-configuration: deployment configuration as platform state, and the service the deployer
 * reads an application's extras out of once this boot has flipped it.
 * <p>
 * <b>Every route is guarded and there is no anonymous surface</b> — {@code qits:admin} for a person,
 * {@code qits:system} for a machine — so this class asserts an identity on every call. It is the
 * same assertion {@link PdApi} makes and for the same reason: the hop is private, the bootstrap is
 * inside it, and the edge is deliberately not on this path because it strips client-supplied
 * identity headers from what it proxies.
 */
public class ConfigurationApi {

    /**
     * Who the bootstrap says it is when it seeds this service. {@code qits:admin} rather than
     * {@code qits:system}: what is written here is the platform's configuration, which is an
     * operator's act, and the actor is recorded on every revision the import writes.
     */
    private static final Map<String, String> ADMIN_HEADERS = Map.of(
            "X-Qits-User", "qits-bootstrap",
            "X-Qits-Roles", "qits:admin");

    private final Http http;
    private final String base;

    /** @param configurationUrl scheme, host and port with no path — the deployer is told the same */
    public ConfigurationApi(Http http, String configurationUrl) {
        this.http = http;
        this.base = configurationUrl.endsWith("/")
                ? configurationUrl.substring(0, configurationUrl.length() - 1)
                : configurationUrl;
    }

    /**
     * Readiness, at the path this service's own deployments.yml names as its health gate. Under
     * {@code quarkus.http.non-application-root-path}, so it is {@code /configuration/q} and not the
     * bare {@code /q} a prefix-routing edge could never reach.
     */
    public Http.Response health() {
        return http.get(base + "/configuration/q/health/ready", ADMIN_HEADERS);
    }

    /**
     * The bulk import, idempotent by construction: a line whose value is already stored writes no
     * revision, so a boot that re-imports the file it just rendered costs one request and leaves the
     * history a record of changes rather than of runs.
     */
    public Http.Response importProperties(String properties) {
        return http.postText(base + "/configuration/api/import", properties, ADMIN_HEADERS);
    }

    /** One application as the DEPLOYER will read it — the same document, at the same url. */
    public Http.Response resolved(String application) {
        return http.get(base + "/configuration/api/applications/" + application + "/resolved",
                ADMIN_HEADERS);
    }
}
