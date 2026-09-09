package eu.wohlben.qits.cli.bootstrap.api;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * qits-deployments: the environments it owns and the deployment rows it records. One
 * component now — the merge-back of qits-cd and qits-serviceregistry — so an environment write is
 * a row here rather than a proxied call to a second service.
 * <p>
 * Every route sits under {@code /platform-deployments/api}, which is what the edge projects
 * verbatim; the base this is built with carries the segment and nothing repeats it.
 */
public class PdApi {

    /** Identity asserted on the private qits-net hop to the deployer's authorized read API. */
    private static final Map<String, String> ADMIN_HEADERS = Map.of(
            "X-Qits-User", "qits-bootstrap",
            "X-Qits-Roles", "qits:admin");

    private final Http http;
    private final String base;

    public PdApi(Http http, String platformDeploymentsUrl) {
        this.http = http;
        this.base = platformDeploymentsUrl;
    }

    public Http.Response health() {
        return http.get(base + "/q/health/ready", ADMIN_HEADERS);
    }

    public boolean ready() {
        return health().ok();
    }

    /** The id of the environment with this name, if there is one. */
    public Optional<String> environmentId(String name) {
        return environments().stream()
                .filter(environment -> name.equals(Json.text(environment, "name")))
                .map(environment -> Json.text(environment, "id"))
                .findFirst();
    }

    /**
     * The environment the platform plane deploys from, if one is designated. Exactly one row
     * carries the flag — the deployer holds that, by moving it rather than setting it.
     * <p>
     * The bootstrap asks so it can REFUSE rather than rename. A platform whose environment is
     * called something else is not this one under a new name: every running container holds
     * {@code <old>-qits-*} aliases and the recorded idp secrets are keyed by the old name, so a
     * rename would leave the platform answering to two at once.
     */
    public Optional<JsonNode> platformEnvironment() {
        return environments().stream()
                .filter(environment -> environment.path("platform").asBoolean(false))
                .findFirst();
    }

    private java.util.List<JsonNode> environments() {
        Http.Response response = http.get(base + "/api/environments", ADMIN_HEADERS);
        if (!response.ok()) {
            return java.util.List.of();
        }
        java.util.List<JsonNode> environments = new java.util.ArrayList<>();
        Json.parse(response.body()).path("environments").forEach(environments::add);
        return environments;
    }


    /** The writes are machine-guarded on the merged deployer (cd's ancestors were not). */
    private static Map<String, String> bearer(String token) {
        return token == null || token.isBlank() ? Map.of()
                : Map.of("Authorization", "Bearer " + token);
    }
    /**
     * The standing environment, created as THE PLATFORM ENVIRONMENT. That flag is the whole of what
     * makes it deploy anything: the deployer's entry tier is the one row carrying it, and an
     * environment created without it is a row nothing is ever deployed into.
     * <p>
     * <b>It carries no branch, and one must never be sent again.</b> The column is gone —
     * {@code pd_environment} lost it, and the create and update requests lost the field with it —
     * because a deployment is entered by a {@code SoftwareRelease} rather than matched against a
     * ref. An older sender's {@code branch} is ignored by the deserializer rather than refused,
     * which is exactly why sending one would be invisible: the boot would look green and the value
     * would mean nothing.
     */
    public Http.Response createEnvironment(String name, String network, String token) {
        return http.postJson(base + "/api/environments",
                Json.object("name", name, "network", network,
                        "platform", Json.verbatim("true")),
                bearer(token));
    }

    /**
     * RECONCILE, NEVER RECREATE. A DELETE tears down every container of the environment, which
     * here is the whole platform, the deployer included.
     */
    public Http.Response patchEnvironment(String id, String json, String token) {
        return http.patchJson(base + "/api/environments/" + id, json, bearer(token));
    }

    /** The newest deployment row of an application in an environment. */
    public Optional<JsonNode> newestDeployment(String environmentId, String applicationName) {
        Http.Response response = http.get(base + "/api/deployments?environmentId=" + environmentId,
                ADMIN_HEADERS);
        if (!response.ok()) {
            return Optional.empty();
        }
        for (JsonNode deployment : Json.parse(response.body()).path("deployments")) {
            if (applicationName.equals(Json.text(deployment, "applicationName"))) {
                return Optional.of(deployment);
            }
        }
        return Optional.empty();
    }
}
