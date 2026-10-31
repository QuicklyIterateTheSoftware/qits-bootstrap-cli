package eu.wohlben.qits.cli.bootstrap.platform;

import java.util.ArrayList;
import java.util.List;

/**
 * <b>What this installation tells the deployer that no image can ship</b> (qits-765): the
 * per-application environment variables the bootstrap writes into qits-deployments' settings
 * store, {@code PUT /deployments/api/settings/{application}/{environment}/{key}}.
 * <p>
 * <b>The deployer reads nothing else.</b> Every deployment takes its environment from the
 * {@code pd_setting} rows of its (application, environment), and its shape — volumes, binds,
 * publishes, aliases — from the repository's own {@code .config/qits/deployments.yml}. What used to
 * be the generated extras file, imported into qits-configuration, is gone with both: the values that
 * are the same on every platform became each image's code defaults, and what is left here is what
 * only this run knows — a domain, a password it generated, the port it was told, the ACME account.
 * <p>
 * <b>Only what applies is stated.</b> {@code QITS_PORT} names only when the port is not the
 * images' default of 8080, the daemon override only when this run built one, the DNS token only
 * when there is one, the additional certificate names only when there are any. A key a run has
 * nothing to say about is left unwritten rather than written empty.
 * <p>
 * <b>Values are never printed.</b> {@link Setting#toString} names the place and the key alone,
 * because a setting is the one object here that holds a password in clear.
 */
public final class DeploymentSettings {

    /** The deployer's own default for {@code QITS_PORT}, which a setting need not repeat. */
    public static final int DEFAULT_PORT = 8080;

    private DeploymentSettings() {
    }

    /**
     * One row of the settings store.
     *
     * @param application the APPLICATION name ({@code qits-edge}), never the repository's
     * @param key         an environment variable name
     * @param value       the value, in clear — never logged
     * @param secret      whether the deployer masks it on every read
     */
    public record Setting(String application, String key, String value, boolean secret) {
        @Override
        public String toString() {
            return application + " " + key + (secret ? " (secret)" : "");
        }
    }

    /** What this run knows that the settings are made of. */
    public record Inputs(String envName, String domain, int port, boolean acmeEnabled,
            String acmeMode, String acmeEmail, String hetznerToken, List<String> extraSans,
            String postgresPassword, String pushToken, String daemonSha) {
    }

    /**
     * Every setting this installation states, in the order they are written: the deployer first,
     * because its self-update is what reads its own rows, then each application in the order the
     * seed deploys meet them.
     */
    public static List<Setting> of(Inputs in) {
        List<Setting> settings = new ArrayList<>();
        String deployments = PlatformModel.application("deployments");
        // NOTHING PROPAGATES TO THE PROPAGATOR. The deployer writes QITS_DOMAIN into every container
        // it deploys, so this is the one application whose own rows must carry it: its successor
        // is started from them.
        settings.add(plain(deployments, "QITS_DOMAIN", in.domain()));
        // The credential it provisions every other application's database with. The seed stack
        // spells the same value, and qits-oci-postgresql's row below is the other half of it.
        settings.add(secret(deployments, "QITS_PLATFORM_DEPLOYMENTS_POSTGRES_ADMIN_PASSWORD",
                in.postgresPassword()));
        // Every pull carries a bearer since the registry flip; false is a deployer that can pull
        // nothing.
        settings.add(plain(deployments, "QITS_PLATFORM_DEPLOYMENTS_REGISTRY_AUTH", "true"));
        // The host side of the edge's `${QITS_PORT}:8080` publish, resolved by the deployer from
        // its own QITS_PORT. Stated only when it is not the default the deployer already has.
        // NO registry-host row beside it any more: the deployer DERIVES the registry host from
        // QITS_DOMAIN above (registry.qits.<domain>) rather than reading one stated here.
        if (in.port() != DEFAULT_PORT) {
            settings.add(plain(deployments, "QITS_PORT", String.valueOf(in.port())));
        }

        String edge = PlatformModel.application("edge");
        // THE TLS WIRING, which wakes the image's certificate support: inert until a keystore
        // names files. The files are on the letsencrypt volume the edge's deployments.yml
        // declares, which holds the placeholder this boot writes until the edge's own DNS-01 order
        // replaces it.
        settings.add(plain(edge, "QUARKUS_TLS_KEY_STORE_PEM_ACME_CERT",
                "/work/.letsencrypt/current/lets-encrypt.crt"));
        settings.add(plain(edge, "QUARKUS_TLS_KEY_STORE_PEM_ACME_KEY",
                "/work/.letsencrypt/current/lets-encrypt.key"));
        settings.add(plain(edge, "QUARKUS_TLS_RELOAD_PERIOD", "1m"));
        settings.add(plain(edge, "QITS_EDGE_ACME_ENABLED", String.valueOf(in.acmeEnabled())));
        if (in.acmeEnabled()) {
            settings.add(plain(edge, "QITS_EDGE_ACME_MODE", in.acmeMode()));
            settings.add(plain(edge, "QITS_EDGE_ACME_EMAIL", in.acmeEmail()));
            // A VALUE, never the _FILE form the seed edge reads: the deployer mounts no swarm
            // secrets, and the file form wins when both are set.
            if (present(in.hetznerToken())) {
                settings.add(secret(edge, "QITS_EDGE_ACME_HETZNER_TOKEN", in.hetznerToken()));
            }
            // Names the derived wildcards cannot reach; usually none, and then no key.
            if (in.extraSans() != null && !in.extraSans().isEmpty()) {
                settings.add(plain(edge, "QITS_EDGE_ACME_ADDITIONAL_NAMES",
                        String.join(",", in.extraSans())));
            }
        }

        String ci = PlatformModel.application("ci");
        // THE DAEMON EVERY STEP CONTAINER DOWNLOADS, as the override: a freshly bootstrapped
        // platform has released no daemon, and its only binary is the digest this run uploaded and
        // named nothing else — while qits-ci's ordinary answer is the version its protocol jar
        // pins, which no store here holds. Deleting this row once a released daemon's version is
        // pinned hands the decision back to the pin.
        if (present(in.daemonSha())) {
            settings.add(plain(ci, "QITS_CI_DAEMON_VERSION_OVERRIDE", in.daemonSha()));
        }
        // ONE PUSH TOKEN, TWO HOLDERS: the git host checks it and qits-projects presents it.
        settings.add(secret(PlatformModel.application("githost"),
                "QITS_REPOSITORIES_GIT_PUSH_TOKEN", in.pushToken()));
        settings.add(secret(PlatformModel.application("projects"),
                "QITS_REPOSITORIES_GIT_PUSH_TOKEN", in.pushToken()));

        // The superuser the deployer's admin password above logs in as. No phase of this boot
        // deploys postgres — it stays the seed service — so this row is for a deployer that ever
        // redeploys it, which must then not initdb with the image's default password.
        settings.add(secret(PlatformModel.application("oci-postgresql"), "POSTGRES_PASSWORD",
                in.postgresPassword()));
        return List.copyOf(settings);
    }

    private static Setting plain(String application, String key, String value) {
        return new Setting(application, key, required(application, key, value), false);
    }

    private static Setting secret(String application, String key, String value) {
        return new Setting(application, key, required(application, key, value), true);
    }

    /**
     * A blank where a value is owed is a bug in the run, not something to write: an empty
     * password row would deploy a database nobody can log in to.
     */
    private static String required(String application, String key, String value) {
        if (!present(value)) {
            throw new IllegalStateException("no value for " + application + " " + key
                    + " — the phase that resolves it has not run");
        }
        return value;
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }
}
