package eu.wohlben.qits.cli.bootstrap.platform;

import eu.wohlben.qits.cli.bootstrap.phases.ImagePinKeys;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.fail;

/**
 * <b>THIS IS WHAT STOPS A PER-SERVICE DEFAULT FROM CREEPING BACK.</b>
 * <p>
 * {@code ComposeTemplate.EXTRAS} is where every application's configuration used to live, because
 * for a long time it was the only place a deployment could be configured from. The epic moves that
 * out: an application's own defaults belong in its repository's
 * {@code .config/qits/configuration.yml}, which ships with the image that reads them and changes in
 * the same commit as the code that reads them. What is left here is what a repository CANNOT know —
 * the wiring of the one platform it is being deployed into.
 * <p>
 * <b>A migration that only deletes is a migration that is undone by the next hurry.</b> Deleting
 * forty keys costs an afternoon; the next value somebody needs is one line in a template that is
 * already open, and a year later the file is back. So the wave leaves this test behind: every
 * generated key is classified into a PLATFORM-WIRING family, and a key that matches none fails
 * naming its application. <b>A key that fails here belongs in that application's own
 * {@code .config/qits/configuration.yml}</b>, not in a new family.
 * <p>
 * The families are deliberately narrow, and each one is a thing only the bootstrap knows:
 * <ul>
 *   <li><b>Indexed structure</b> — {@code mounts[]}, {@code publishes[]}, {@code groups[]},
 *       {@code aliases[]}. These are not configuration at all: they are what the deployer builds a
 *       container out of, and no image can declare its own socket mount or its own host port.
 *   <li><b>Secrets</b> — every value this run minted. A repository cannot ship a password.
 *       <b>Not an idp credential</b>, which is a resource the deployer creates and injects: see
 *       {@link #noExtrasBlockCarriesAnIdentity}, which fails the build on one.
 *   <li><b>The passkey binding</b> — the host a credential asserts on, which is this platform's own
 *       name and no image's to ship.
 *   <li><b>The machine gate and the issuer</b> — whether the gate is on and where the issuer is,
 *       which is one address per platform rather than one per service.
 *   <li><b>Tier names</b> — {@code QITS_ENVIRONMENT} and the edge's environment list. A service
 *       cannot ship the name of a tier it has not been deployed into yet.
 *   <li><b>Boot sequencing</b> — the deployer's own successor. Nothing configures the deployer but
 *       this file: the update argv {@code --env-rm}s what the extras do not state, so a setting of
 *       its own that is not here is gone at its first self-deploy, and the flip's extras url is the
 *       key that decides whether there is a configuration service to read at all.
 *   <li><b>The initial image pins</b> — asked of {@code PipelinePhases.IMAGE_PINS} through
 *       {@link ImagePinKeys}, because a pin is a release this boot cut and no shipped default can
 *       name it. The list is empty today, so the family admits nothing; it is asked rather than
 *       deleted because the next pin belongs to it without an edit here.
 * </ul>
 * <b>An audience-shaped key is deliberately not a family at all any more.</b> No service is told
 * which audience to validate — every image ships {@code quarkus.oidc.token.audience=qits-platform},
 * the one name the idp puts on every token it mints — and {@code QITS_GITHOST_AUDIENCE} and
 * {@code QITS_CI_CONTAINER_GIT_AUDIENCE}, which name a PEER rather than the service itself, keep
 * their application's exemption until the wave decides where they go.
 *
 * @see ComposeTemplateGoldenTest the other half of the pair — a golden says what a block HOLDS,
 *      this says what a block is ALLOWED to hold
 */
class ExtrasWiringGuardTest {

    private static final String EXTRAS = "qits.platform.deployments.extras.";

    /**
     * <b>THE TRANSITION, AND IT ONLY SHRINKS.</b> Every application whose block still holds keys
     * outside the families above, as of the day this guard landed — which is every application but
     * one. {@code qits-oci-postgresql} is not on the list because it is already at the end state:
     * its whole block is a volume and a password, and upstream postgres has no
     * {@code .config/qits/} to move anything into.
     * <p>
     * A name comes off this list when that application's wave lands, and the test below refuses an
     * entry that is no longer earning its place — so the set cannot quietly stop meaning anything.
     * <b>An empty set is the epic's end-state proof</b>, and the day it empties this constant and
     * the exemption arm go with it.
     */
    private static final Set<String> NOT_YET_MIGRATED = Set.of(
            "qits-artifacts",
            "qits-ci",
            "qits-configuration",
            "qits-containers",
            "qits-deployments",
            "qits-docs",
            "qits-events",
            "qits-githost",
            "qits-observability",
            "qits-platform-edge",
            "qits-platform-idp",
            "qits-platform-maintenance",
            "qits-platform-mirror",
            "qits-platform-orchestrator",
            "qits-platform-system",
            "qits-projects",
            "qits-stt",
            "qits-workspaces");

    /** The three endings that say a value is a credential. Derived from what is generated today. */
    private static final List<String> SECRET_SUFFIXES = List.of("_PASSWORD", "_SECRET", "_TOKEN");

    @Test
    void everyGeneratedKeyIsPlatformWiringOrItsApplicationIsStillExempt() {
        List<String> creeping = new ArrayList<>();
        outOfFamily().forEach((application, keys) -> {
            if (!NOT_YET_MIGRATED.contains(application)) {
                keys.forEach(key -> creeping.add("  " + application + "." + key));
            }
        });

        if (!creeping.isEmpty()) {
            fail("these keys are not platform wiring, and their application is not exempt:\n"
                    + String.join("\n", creeping)
                    + "\n\nA value that is the same on every platform belongs in that "
                    + "application's own .config/qits/configuration.yml, which ships with the "
                    + "image that reads it. Only wiring this bootstrap alone knows -- a mount, a "
                    + "secret, an audience, a tier name, the deployer's own successor -- belongs "
                    + "in ComposeTemplate.EXTRAS.");
        }
    }

    /**
     * <b>IDENTITY IS NOT CONFIGURATION, AND THIS IS WHERE THAT IS ENFORCED.</b> An application's idp
     * client is a RESOURCE it declares — {@code idp:client} in its own deployments.yml — which
     * qits-platform-deployments creates against the running idp, records in its {@code pd_resource}
     * registry and injects as {@code QITS_RESOURCE_IDP_URL} / {@code _CLIENT_ID} /
     * {@code _CLIENT_SECRET} before the container starts. Those variables are written BEFORE an
     * application's own extras and the last assignment of a key wins, so a credential stored here
     * would not configure the deployment: it would SHADOW the row the deployer keeps current and
     * survive every rotation of it.
     * <p>
     * Four prefixes, and one pair of them is deliberate. {@code QITS_OIDC_CLIENT_} is the spelling
     * the ruling used; {@code QUARKUS_OIDC_CLIENT_} is the spelling that actually existed in this
     * file. Both are named, so neither can come back under the other's name.
     */
    @Test
    void noExtrasBlockCarriesAnIdentity() {
        List<String> identities = new ArrayList<>();
        for (String line : extrasKeys()) {
            String key = key(line);
            if (!key.startsWith("env.")) {
                continue;
            }
            String name = key.substring("env.".length());
            if (name.startsWith("QITS_RESOURCE_IDP_") || name.startsWith("QITS_IDP_CLIENT")
                    || name.startsWith("QITS_OIDC_CLIENT_")
                    || name.startsWith("QUARKUS_OIDC_CLIENT_")) {
                identities.add("  " + application(line) + "." + name);
            }
        }

        if (!identities.isEmpty()) {
            fail("these extras keys are an identity:\n" + String.join("\n", identities)
                    + "\n\nAn idp client is created against the RUNNING idp -- by this bootstrap "
                    + "for the five seed applications, by qits-platform-deployments for every "
                    + "other -- and injected into the container as QITS_RESOURCE_IDP_URL, "
                    + "_CLIENT_ID and _CLIENT_SECRET off the pd_resource row. The deployer writes "
                    + "those BEFORE an application's own extras and the last assignment wins, so a "
                    + "credential stored here does not configure the deployment: it shadows the row "
                    + "that is kept current and outlives every rotation of it. Nothing "
                    + "identity-shaped belongs in ComposeTemplate.EXTRAS.");
        }
    }

    @Test
    void anExemptionIsRemovedTheDayItsApplicationIsMigrated() {
        Map<String, List<String>> remaining = outOfFamily();
        Set<String> applications = applications();
        List<String> stale = new ArrayList<>();
        for (String application : new TreeSet<>(NOT_YET_MIGRATED)) {
            if (!applications.contains(application)) {
                stale.add("  " + application + " has no extras block at all any more");
            } else if (!remaining.containsKey(application)) {
                stale.add("  " + application + " no longer needs its exemption -- remove it");
            }
        }

        if (!stale.isEmpty()) {
            fail("NOT_YET_MIGRATED has entries that are done:\n" + String.join("\n", stale)
                    + "\n\nThe set shrinks and never grows: an entry left standing after its wave "
                    + "is an application that could take a per-service default back without this "
                    + "guard saying a word. An empty set is what the epic is finished by.");
        }
    }

    // --- the classification ------------------------------------------------------------------------

    /** Per application, the keys of its block that match no family — in generated order. */
    private static Map<String, List<String>> outOfFamily() {
        Map<String, List<String>> remaining = new LinkedHashMap<>();
        for (String line : extrasKeys()) {
            String application = application(line);
            String key = key(line);
            if (!isPlatformWiring(application, key)) {
                remaining.computeIfAbsent(application, name -> new ArrayList<>()).add(key);
            }
        }
        return remaining;
    }

    private static Set<String> applications() {
        Set<String> applications = new TreeSet<>();
        extrasKeys().forEach(line -> applications.add(application(line)));
        return applications;
    }

    /**
     * One key, classified. The application is a parameter because boot sequencing is the one family
     * that is a property of WHOSE block a key is in: {@code QITS_PLATFORM_DEPLOYMENTS_*} on the
     * deployer is its own successor's settings, and the same prefix on anybody else is an address
     * like any other.
     */
    private static boolean isPlatformWiring(String application, String key) {
        // Indexed structure: what the deployer builds the container out of, not what the container
        // reads. An image cannot declare its own socket mount, its own host port or its own alias.
        if (key.startsWith("mounts[") || key.startsWith("publishes[")
                || key.startsWith("groups[") || key.startsWith("aliases[")) {
            return true;
        }
        if (!key.startsWith("env.")) {
            return false;
        }
        String name = key.substring("env.".length());

        // Secrets. POSTGRES_PASSWORD is the whole key rather than a suffixed one, which is why this
        // asks how a name ENDS rather than which prefixes it carries.
        if (SECRET_SUFFIXES.stream().anyMatch(name::endsWith)) {
            return true;
        }

        // The passkey binding: the host a credential asserts on, which is this platform's own
        // name. What used to stand beside it — the idp's client registry, every client's audience
        // list and every service's own audience — is gone from this file entirely, and
        // noExtrasBlockCarriesAnIdentity FAILS the build on those families rather than allowing
        // them.
        if (name.startsWith("QITS_IDP_WEBAUTHN_")) {
            return true;
        }

        // The machine gate and the issuer — whether a bearer is demanded at all, and where the one
        // issuer this platform has is. Neither is a credential: the gate is a platform posture, the
        // issuer is one address per platform rather than one per service.
        if (name.equals("QITS_AUTH_MACHINE_REQUIRED")
                || name.equals("QUARKUS_OIDC_AUTH_SERVER_URL")) {
            return true;
        }

        // Tier names. A platform service is handed no QITS_ENVIRONMENT at all, which is a rule of
        // its own in ComposeTemplateTest; what this says is only that the line is wiring when it
        // is rendered.
        if (name.equals("QITS_ENVIRONMENT") || name.equals("QITS_EDGE_ENVIRONMENTS")
                || name.equals("QITS_EDGE_DEFAULT_ENVIRONMENT")) {
            return true;
        }

        // Boot sequencing. The deployer reads its own extras to start its successor and the update
        // argv removes what they do not state, so its settings and the store its successor boots
        // from cannot be a default that ships in an image. QITS_PLATFORM_DEPLOYMENTS_EXTRAS_URL is
        // the one among them that decides whether there is a configuration service to read at all.
        if (application.equals("qits-deployments")
                && (name.startsWith("QITS_PLATFORM_DEPLOYMENTS_")
                        || name.startsWith("QITS_RESOURCE_DB_"))) {
            return true;
        }

        // The image pins this boot seeds, asked of the pin list rather than named here.
        return ImagePinKeys.keys().contains(name);
    }

    // --- reading the generated file ----------------------------------------------------------------

    /** Every generated key line, without the comments that explain them. */
    private static List<String> extrasKeys() {
        return ComposeTemplate.extras(ComposeTemplateTest.tokens()).lines()
                .filter(line -> line.startsWith(EXTRAS))
                .toList();
    }

    private static String application(String line) {
        String element = element(line);
        int dot = element.indexOf('.');
        int bracket = element.indexOf('[');
        int end = dot < 0 ? bracket : bracket < 0 ? dot : Math.min(dot, bracket);
        return end < 0 ? element : element.substring(0, end);
    }

    private static String key(String line) {
        String element = element(line);
        String application = application(line);
        String rest = element.substring(application.length());
        return rest.startsWith(".") ? rest.substring(1) : rest;
    }

    private static String element(String line) {
        return line.substring(EXTRAS.length(), line.indexOf('='));
    }
}
