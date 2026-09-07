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
    private final String envName;

    /**
     * @param configurationUrl scheme, host and port with no path — the deployer is told the same
     * @param envName which environment this boot's import asserts values FOR. It is not derivable
     *                from the url any more: the service moved to the platform plane on 2026-09-07,
     *                so one instance holds every tier's rows and the address says nothing about
     *                which tier a caller means.
     */
    public ConfigurationApi(Http http, String configurationUrl, String envName) {
        this.http = http;
        this.base = configurationUrl.endsWith("/")
                ? configurationUrl.substring(0, configurationUrl.length() - 1)
                : configurationUrl;
        this.envName = envName;
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
     * <p>
     * <b>THE IMPORT NAMES THE ENV IT ASSERTS.</b> One store holds every tier's rows since the plane
     * move, so which environment a properties file is for is a fact of the CALLER and not of the
     * address it dialled. The query parameter is what states it. An absent one means the store's
     * own legacy environment, which is right for a caller that predates the parameter and wrong for
     * this one: a bootstrap is bringing ONE tier up and knows which.
     */
    public Http.Response importProperties(String properties) {
        return http.postText(base + "/configuration/api/import?env=" + envName, properties,
                ADMIN_HEADERS);
    }

    /**
     * One application as the DEPLOYER will read it — the same document, at the same url.
     * <p>
     * Still the env-less route, like {@link #health()}: this is the read the deployer makes, and
     * asking it differently would stop it proving what it is here to prove. It moves to the
     * env-addressed route in the epic's cutover feature, with the deployer's own read.
     */
    public Http.Response resolved(String application) {
        return http.get(base + "/configuration/api/applications/" + application + "/resolved",
                ADMIN_HEADERS);
    }
}
