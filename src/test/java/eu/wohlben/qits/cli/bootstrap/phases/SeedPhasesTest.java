package eu.wohlben.qits.cli.bootstrap.phases;

import eu.wohlben.qits.cli.bootstrap.config.TestConfig;
import eu.wohlben.qits.cli.bootstrap.proc.Cmd;
import eu.wohlben.qits.cli.bootstrap.proc.RunLog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The phases shell docker and git, so a real bootstrap is their test. What is pure is tested here:
 * whether a COLD start gets past its third phase — a wrapper cloned without its submodules has an
 * empty directory at every gitlink, and the sources phase has to read that as "no local checkout"
 * rather than as "something is standing in its place" — and the seed library set, which decides
 * what the first image builds can resolve.
 */
class SeedPhasesTest {

    @TempDir
    Path temp;

    @Test
    void aGitlinkWithNoSubmoduleCheckedOutIsEmpty() throws IOException {
        Path gitlink = Files.createDirectories(temp.resolve("services/qits-ci"));

        assertThat(SeedPhases.isEmptyDirectory(gitlink)).isTrue();
    }

    @Test
    void anythingAtAllInItIsNotEmptyAndStopsTheBoot() throws IOException {
        Path occupied = Files.createDirectories(temp.resolve("services/qits-events"));
        Files.writeString(occupied.resolve("README.md"), "not a checkout\n", StandardCharsets.UTF_8);

        assertThat(SeedPhases.isEmptyDirectory(occupied)).isFalse();
    }

    @Test
    void aCheckoutIsNotEmptyEither() throws IOException {
        Path checkout = Files.createDirectories(temp.resolve("services/qits-gateway"));
        Files.createDirectories(checkout.resolve(".git"));

        assertThat(SeedPhases.isEmptyDirectory(checkout)).isFalse();
    }

    // --- the records the domain needs -------------------------------------------------------------

    /**
     * <b>Three A records, and the shapes are the edge's rather than a list of today's names.</b> The
     * edge reads at most the first two labels of a Host header, so one wildcard per DEPTH answers
     * every environment and every application vhost there will ever be — adding either is then a
     * deploy with no dns step. The apex is written out because no wildcard matches it.
     * <p>
     * The nameserver's own record went with qits-platform-dns: this platform serves no dns, so there
     * is nothing to delegate to and no glue record to answer for.
     */
    @Test
    void theDomainNeedsAWildcardPerDepthPlusTheApex() {
        List<SeedPhases.ZoneRecord> records =
                SeedPhases.zoneRecords("qits-dev.eu", "203.0.113.7");

        assertThat(records).extracting(SeedPhases.ZoneRecord::name)
                .containsExactly("@", "*", "*.*");
        // Every name is this one host: the edge is a single front door and routes by Host behind it.
        assertThat(records).extracting(SeedPhases.ZoneRecord::value)
                .containsOnly("203.0.113.7");
    }

    /**
     * Record names are RELATIVE to the apex, which is how a provider's own record editor asks for
     * them — a name with the domain spelled into it lands as {@code <domain>.<domain>}.
     */
    @Test
    void noRecordNameCarriesTheDomain() {
        assertThat(SeedPhases.zoneRecords("qits-dev.eu", "203.0.113.7"))
                .extracting(SeedPhases.ZoneRecord::name)
                .noneMatch(name -> name.contains("qits-dev.eu"));
    }

    // --- the ACME run -----------------------------------------------------------------------------

    /**
     * <b>The two field names the whole issuance hangs off.</b> certbot hands the hook
     * {@code CERTBOT_TOKEN} and {@code CERTBOT_VALIDATION}; the edge's challenge slot is filled with
     * {@code challenge-resource} and {@code challenge-content}. Getting either pair wrong fails
     * silently — the slot is filled with nothing useful and the CA reads a 404 — so the mapping is
     * pinned here rather than discovered on a live domain.
     */
    @Test
    void theAuthHookMapsCertbotsVariablesOntoTheEdgesChallengeFields() {
        assertThat(SeedPhases.ACME_SCRIPT)
                .contains("challenge-resource=$CERTBOT_TOKEN")
                .contains("challenge-content=$CERTBOT_VALIDATION");
    }

    /**
     * The slot holds ONE challenge and answers 400 to a second, so a run killed between filling it
     * and cleaning it up would block every run after it. The script clears it before it orders.
     */
    @Test
    void theChallengeSlotIsClearedBeforeTheOrderAndByTheCleanupHook() {
        assertThat(SeedPhases.ACME_SCRIPT).contains("-X DELETE \"$EDGE_URL/challenge\"");
        // Before certbot is invoked, not after it.
        assertThat(SeedPhases.ACME_SCRIPT.indexOf("-X DELETE \"$EDGE_URL/challenge\""))
                .isLessThan(SeedPhases.ACME_SCRIPT.indexOf("certbot certonly"));
    }

    /**
     * <b>A working public site is never taken down by a rerun that forgot the mode.</b> A production
     * certificate on the volume ends the script whatever was asked for; a staging one only ends it
     * when staging is what was asked for, so staging → production still issues.
     */
    @Test
    void aProductionCertificateIsNeverReplacedByAStagingOne() {
        String script = SeedPhases.ACME_SCRIPT;

        // The production arm skips unconditionally; the staging arm asks what the mode is.
        assertThat(script).contains("production)").contains("staging)")
                .contains("if [ \"$MODE\" = staging ]");
        assertThat(script).contains(SeedPhases.SKIPPED);
    }

    /**
     * The edge runs as uid 1001 and reads these two files at startup and at every reload. A key it
     * cannot read is a reload that fails and a certificate nobody ever sees.
     */
    @Test
    void thePemsLandUnderTheNamesTheKeystoreExpectsAndAreReadableByTheEdge() {
        assertThat(SeedPhases.ACME_SCRIPT)
                .contains("/cert/lets-encrypt.crt")
                .contains("/cert/lets-encrypt.key")
                .contains("chown 1001:0")
                .contains("chmod 640 /cert/lets-encrypt.key");
    }

    /**
     * Staging and production are two certbot lineages, named per mode: a flip from one to the other
     * is then a fresh order rather than a renewal against a different ACME server, which certbot
     * refuses without being told twice.
     */
    @Test
    void theCertbotLineageIsNamedPerMode() {
        assertThat(SeedPhases.ACME_SCRIPT).contains("--cert-name \"qits-edge-$MODE\"")
                .contains("/acme/live/qits-edge-$MODE/fullchain.pem");
    }

    /**
     * The seed set and its order. A jar missing here fails a seed image build minutes in, naming a
     * version nobody ever pushed; a jar in the wrong place fails the deploy that needs it.
     */
    @Test
    void theSeedLibrariesAreEveryJarASeedImageResolves() {
        assertThat(SeedPhases.SEED_LIBRARIES).containsExactly(
                "integrations-quarkus", "blobstore", "registries", "eventstream", "githost",
                "containers");
        // Dependency order, and every pair is forced by a pom: qits-blobstore is written against
        // qits-db-core (a module of qits-integrations-quarkus) since its DbRetry release,
        // qits-registries against qits-blobstore's entities, qits-eventstream against qits-db-core
        // too, qits-githost-events against qits-eventstream, and the orchestrator's two libraries
        // against qits-db-core and qits-eventstream both.
        assertThat(SeedPhases.SEED_LIBRARIES)
                .containsSubsequence("integrations-quarkus", "blobstore", "registries");
        assertThat(SeedPhases.SEED_LIBRARIES)
                .containsSubsequence("integrations-quarkus", "eventstream", "githost");
        assertThat(SeedPhases.SEED_LIBRARIES)
                .containsSubsequence("integrations-quarkus", "containers");
        assertThat(SeedPhases.SEED_LIBRARIES).containsSubsequence("eventstream", "containers");
        // containers is last, because the probe that skips the whole phase asks for
        // qits-containers-client — the last thing this phase publishes, so its presence is the one
        // honest answer for the whole set.
        assertThat(SeedPhases.SEED_LIBRARIES).endsWith("containers");
    }

    /**
     * The git host and the orchestrator are seeded by MODULE. Publishing either repository whole
     * would build its service to hand over a library; {@code -am} is what carries the root pom with
     * it, without which the jar resolves nowhere.
     */
    @Test
    void theGitHostPublishesItsEventVocabularyAndNothingElse() {
        assertThat(SeedPhases.mavenModuleArgs("githost")).isEqualTo(" -pl githost-events -am");
        assertThat(SeedPhases.mavenModuleArgs("containers")).isEqualTo(" -pl core,client -am");
        assertThat(SeedPhases.mavenModuleArgs("eventstream")).isEmpty();
        assertThat(SeedPhases.mavenModuleArgs("blobstore")).isEmpty();
        assertThat(SeedPhases.mavenModuleArgs("integrations-quarkus")).isEmpty();
    }

    /**
     * <b>The seed script reads Maven Central through a cache that outlives the run.</b> Without it
     * every bootstrap re-downloaded the whole dependency world with a cold local repository, and
     * four cold runs in one evening got this host throttled: the 2026-08-11 attempt 4 died at the
     * qits-blobstore publish on a 502 from the mirror's central proxy.
     */
    @Test
    void everySeedBuildResolvesThroughTheSharedCache() {
        String script = SeedPhases.seedScript();

        assertThat(script.lines().filter(line -> line.startsWith("cd /src-")))
                .hasSize(SeedPhases.SEED_LIBRARIES.size())
                .allSatisfy(line ->
                        assertThat(line).contains("-Dmaven.repo.local=/cache/repository"));
        assertThat(SeedPhases.MAVEN_CACHE_MOUNT).isEqualTo("qits-maven-cache:/cache");
    }

    /**
     * <b>The cache keeps third-party bytes and never this platform's.</b> A seed build stamps a
     * calver that a later checkout reuses, so a remembered qits jar could satisfy a resolution that
     * this run's source would answer differently — under the same version, silently. Only released
     * artifacts are unique per bytes, and these are not released.
     */
    @Test
    void theQitsGroupIsPurgedBeforeAnythingIsBuilt() {
        String script = SeedPhases.seedScript();

        assertThat(script).startsWith("set -eu\nrm -rf /cache/repository/eu/wohlben/qits\n");
        // Only our group: the third-party half of the cache is the whole point of having one.
        assertThat(SeedPhases.MAVEN_PURGE_QITS)
                .isEqualTo("rm -rf /cache/repository/eu/wohlben/qits\n");
    }

    /** The publish half of the same script, which is what phase 18 was killed in. */
    @Test
    void aPublishBuildCachesAndPurgesTheSameWay() {
        SeedPhases phases = new SeedPhases(
                new Boot(TestConfig.from(Map.of()), new RunLog(temp.resolve("run.log"))));

        String script = phases.publishScript("githost");

        assertThat(script).contains("rm -rf /cache/repository/eu/wohlben/qits\n");
        assertThat(script).contains("-Dmaven.repo.local=/cache/repository");
        // The purge is before the build, and the settings before both — a mirror written after the
        // mvn line configures nothing.
        assertThat(script.indexOf("SETTINGS"))
                .isLessThan(script.indexOf("rm -rf /cache/repository"));
        assertThat(script.indexOf("rm -rf /cache/repository")).isLessThan(script.indexOf("mvn "));
        // Still by module, and still deploying to the store.
        assertThat(script).contains(" -pl githost-events -am")
                .contains("-DaltDeploymentRepository=qits::default::");
    }

    /**
     * <b>ONE VALUE, TWO SPELLINGS, and the pair is the whole credential.</b> The state file records
     * a secret under the CLIENT ID — {@code IDP_SECRET_PROD_QITS_EDGE} — while both generated files
     * read it under the APPLICATION, because a placeholder cannot be spelled with an environment
     * name the template does not know yet. A pair that drifted apart is an idp holding a secret
     * that nothing on this platform presents.
     */
    @Test
    void theEdgesSecretIsRecordedByIdAndReadByApplication() {
        Boot boot = new Boot(TestConfig.from(Map.of("QITS_ENV_NAME", "prod")),
                new RunLog(temp.resolve("run.log")));
        boot.state.secrets.put("prod-qits-edge", "s3cr3t");

        Map<String, String> tokens = new SeedPhases(boot).tokens();

        assertThat(tokens).containsEntry("IDP_SECRET_EDGE", "s3cr3t");
        assertThat(tokens.get("IDP_CLIENTS")).contains("prod-qits-edge");
        // The passkey binding travels in the same map. The rp id is the ENVIRONMENT's label, and
        // the ceremony's origin is the idp's own host — a child of it, so the binding holds.
        assertThat(tokens).containsEntry("WEBAUTHN_RP_ID", "prod.localhost")
                .containsEntry("WEBAUTHN_ORIGINS", "http://idp.prod.localhost:8080");
        // And the session travels with it: the door, the login host, one label under the door, and
        // the shared parent. The door and the login host are NOT the same name.
        assertThat(tokens).containsEntry("PUBLIC_ORIGIN", "http://prod.localhost:8080")
                .containsEntry("IDP_ORIGIN", "http://idp.prod.localhost:8080")
                .containsEntry("BROWSER_HOSTS", "prod.localhost:8080,*.prod.localhost:8080")
                .containsEntry("SESSION_COOKIE_DOMAIN", "prod.localhost");
    }

    /**
     * A domain platform's login host is {@code idp.<domain>} — {@code idp.} of the APEX, not of the
     * environment authority, because the environment label is optional for the default tier.
     */
    @Test
    void aDomainPlatformsLoginHostIsIdpOfTheApex() {
        Boot boot = new Boot(TestConfig.from(Map.of("QITS_ENV_NAME", "dev",
                "QITS_DOMAIN", "qits-dev.eu", "QITS_PUBLIC_IP", "203.0.113.7")),
                new RunLog(temp.resolve("run.log")));

        Map<String, String> tokens = new SeedPhases(boot).tokens();

        assertThat(tokens).containsEntry("PUBLIC_ORIGIN", "https://qits-dev.eu")
                .containsEntry("IDP_ORIGIN", "https://idp.qits-dev.eu")
                .containsEntry("WEBAUTHN_ORIGINS", "https://idp.qits-dev.eu")
                // The rp id stays the apex: a credential asserts on it and every label under it.
                .containsEntry("WEBAUTHN_RP_ID", "qits-dev.eu");
        // No entry of its own is needed for the idp host — the wildcards already admit it.
        assertThat(tokens.get("BROWSER_HOSTS")).contains("*.qits-dev.eu");
    }

    @Test
    void aPublicBootstrapEdgeKeepsSeedTlsPortsOutOfComposeButNotDeploymentExtras() {
        Boot boot = new Boot(TestConfig.from(Map.of(
                "QITS_DOMAIN", "wohlben.eu",
                "QITS_BOOTSTRAP_INGRESS_PUBLIC", "true")),
                new RunLog(temp.resolve("run.log")));

        Map<String, String> tokens = new SeedPhases(boot).tokens();

        assertThat(tokens).containsEntry("EDGE_SEED_TLS_PORTS", "");
        assertThat(tokens.get("EDGE_TLS_ARGS")).contains("publishes[1]=443:8443")
                .doesNotContain("80:8080");
    }

    private static final List<String> TAGS = List.of("2026.812.153438", "2026.811.090000");

    /**
     * THE BOOT'S IDENTITY for a DEPLOYABLE, and the reason it exists: a seed built from main
     * applies main's migrations, and the released successor the train deploys minutes later
     * refuses to start against a schema ahead of it. qits-ci is both seeded and deployed, and it
     * is the one this cost. Same answer as the deploy ref's, by construction — both go through
     * PlatformModel.newestRelease.
     */
    @Test
    void aDeployableStandsAtItsNewestRelease() {
        assertThat(SeedPhases.bootIdentity(false, "ci", TAGS)).isEqualTo("2026.812.153438");
    }

    /** A release publisher's output is a coordinate consumers pin, so it follows its tag too. */
    @Test
    void aReleasePublisherStandsAtItsNewestRelease() {
        assertThat(SeedPhases.bootIdentity(false, "eventstream", TAGS))
                .isEqualTo("2026.812.153438");
    }

    /**
     * SEEDED AND NOTHING ELSE: main, however many tags it has. qits-oci's step images are consumed
     * by bare local tag and rebuilt every boot, so nothing pins a version of them and their tags go
     * stale unnoticed — the one whose newest tag predated the `build` user the step sandbox needs.
     */
    @Test
    void aSeededSourceWithNoVersionIdentityStaysOnMain() {
        assertThat(SeedPhases.bootIdentity(false, "oci", TAGS)).isEmpty();
        // The SPA seed sources are the same shape: a placeholder bundle, then the real client from
        // the pipeline.
        assertThat(SeedPhases.bootIdentity(false, "spa-home", TAGS)).isEmpty();
    }

    /** In scope but never released: main, which is what an empty answer means to the caller. */
    @Test
    void aRepositoryWithNoReleaseStaysOnMain() {
        assertThat(SeedPhases.bootIdentity(false, "ci", List.of())).isEmpty();
        // A stray tag is not a release.
        assertThat(SeedPhases.bootIdentity(false, "ci", List.of("latest"))).isEmpty();
    }

    /** {@code --ship-mains}: every checkout stays on main and the tags are not even read. */
    @Test
    void shipMainsLeavesEveryCheckoutOnMain() {
        assertThat(SeedPhases.bootIdentity(true, "ci", TAGS)).isEmpty();
        assertThat(SeedPhases.bootIdentity(true, "eventstream", TAGS)).isEmpty();
        assertThat(SeedPhases.bootIdentity(true, "oci", TAGS)).isEmpty();
    }

    // --- the pullers' docker credentials -----------------------------------------------------------

    /**
     * The docker CLI's own file: four fixed keys around one base64 of {@code <id>:<secret>}, which
     * is the same document {@code docker login} writes. The registry host is the KEY inside it —
     * docker matches the credential by the name it is dialling — so a file keyed by anything but
     * the vhost the deployer and the orchestrator pull from is a credential never offered.
     */
    @Test
    void aPullersConfigJsonIsWhatDockerLoginWouldHaveWritten() {
        String json = SeedPhases.dockerConfigJson(
                List.of("registry.prod.localhost:8080"), "qits-deployments", "s3cr3t");

        String auth = Base64.getEncoder().encodeToString(
                "qits-deployments:s3cr3t".getBytes(StandardCharsets.UTF_8));
        assertThat(json).isEqualTo(
                "{\"auths\":{\"registry.prod.localhost:8080\":{\"auth\":\"" + auth + "\"}}}\n");
        // The raw secret is not in the file: what travels is the base64, which is an encoding and
        // not protection — and that is exactly why the write masks both.
        assertThat(json).doesNotContain("s3cr3t");
    }

    /**
     * <b>The base system panels pull an image the MIRROR holds, so their file names two hosts.</b>
     * docker matches a credential by the host it is dialling and by nothing else, so a file keyed
     * only by the registry would be an anonymous glances pull — which the edge has refused since
     * 2026-08-14. One client and one secret behind both entries: it is the same identity at the
     * same door.
     */
    @Test
    void theSystemConsolesConfigJsonNamesTheRegistryAndTheMirror() {
        String json = SeedPhases.dockerConfigJson(
                List.of("registry.prod.localhost:8080", "mirror.prod.localhost:8080"),
                "qits-platform-system", "s3cr3t");

        String auth = Base64.getEncoder().encodeToString(
                "qits-platform-system:s3cr3t".getBytes(StandardCharsets.UTF_8));
        assertThat(json).isEqualTo("{\"auths\":{"
                + "\"registry.prod.localhost:8080\":{\"auth\":\"" + auth + "\"},"
                + "\"mirror.prod.localhost:8080\":{\"auth\":\"" + auth + "\"}}}\n");
        assertThat(json).doesNotContain("s3cr3t");
    }

    /**
     * <b>Both halves of the credential are masked, and the second is not covered by the first.</b>
     * The base64 does not contain the secret as a substring, so a mask on the secret alone would
     * leave the whole credential on the screen and in the run log in one token.
     */
    @Test
    void theWriteHidesTheSecretAndTheBase64OfIt() {
        String json = SeedPhases.dockerConfigJson(
                List.of("registry.prod.localhost:8080"), "prod-qits-containers", "s3cr3t");
        Cmd write = SeedPhases.dockerConfigWrite(
                "qits-containers-config", json, "prod-qits-containers", "s3cr3t");

        assertThat(write.maskText(json + " s3cr3t")).doesNotContain("s3cr3t")
                .doesNotContain(Base64.getEncoder().encodeToString(
                        "prod-qits-containers:s3cr3t".getBytes(StandardCharsets.UTF_8)));
        // The file lands on the volume named, under the one name docker looks for, owned by the
        // image's uid and readable by nobody else on that volume.
        assertThat(write.command()).contains("qits-containers-config:/cfg");
        assertThat(String.join(" ", write.command()))
                .contains("cat > /cfg/config.json")
                .contains("chown 1001:0 /cfg/config.json")
                .contains("chmod 600 /cfg/config.json");
        // The document travels on stdin: a credential on a command line is a credential in ps.
        assertThat(write.stdinText()).isEqualTo(json);
    }

    @Test
    void aPathThatIsNotThereIsNotAnEmptyDirectory() throws IOException {
        // Absent is its own answer — the sources phase clones from the org for both, but the two
        // must not be confused: only a directory can be read for what is in it.
        assertThat(SeedPhases.isEmptyDirectory(temp.resolve("nothing"))).isFalse();
        Files.writeString(temp.resolve("a-file"), "x", StandardCharsets.UTF_8);
        assertThat(SeedPhases.isEmptyDirectory(temp.resolve("a-file"))).isFalse();
    }

    // --- who already serves ------------------------------------------------------------------------

    private static final String ARTIFACTS = "prod-qits-artifacts";

    private static final String PD_ARTIFACTS = "qits-pd-prod-qits-artifacts-";

    /** This run's own hand-started seed, or an earlier run's: a container under the wire alias. */
    @Test
    void aContainerUnderTheWireAliasIsServing() {
        assertThat(SeedPhases.alreadyServing(ARTIFACTS, PD_ARTIFACTS,
                List.of(ARTIFACTS), List.of())).contains(ARTIFACTS);
    }

    /** A deployed store reads the same database and answers the same API: strictly better. */
    @Test
    void aDeployedContainerIsServingToo() {
        assertThat(SeedPhases.alreadyServing(ARTIFACTS, PD_ARTIFACTS,
                List.of(PD_ARTIFACTS + "a1b2c3d4"), List.of()))
                .contains(PD_ARTIFACTS + "a1b2c3d4");
    }

    /**
     * THE ONE THE STACK ADDED. A seed service's task container is named
     * {@code qits_prod-qits-artifacts.1.<taskid>}, so the container list answers nothing — and a
     * phase that then starts a container by hand puts a second store under one wire alias.
     */
    @Test
    void aSeedStackServiceIsServingAlthoughNoContainerCarriesTheName() {
        assertThat(SeedPhases.alreadyServing(ARTIFACTS, PD_ARTIFACTS,
                List.of("qits_prod-qits-artifacts.1.k3n1x9"), List.of("qits_" + ARTIFACTS)))
                .contains("qits_" + ARTIFACTS);
    }

    /** Both names a stack service answers to, because both are addresses on the network. */
    @Test
    void theBareServiceNameCountsAsWell() {
        assertThat(SeedPhases.alreadyServing("qits-platform-idp", "qits-pd-qits-platform-idp-",
                List.of(), List.of("qits-platform-idp"))).contains("qits-platform-idp");
    }

    /** Nothing of this application anywhere: the phase starts its own. */
    @Test
    void anotherApplicationsServiceIsNotThisOne() {
        assertThat(SeedPhases.alreadyServing(ARTIFACTS, PD_ARTIFACTS,
                List.of("prod-qits-ci"), List.of("qits_prod-qits-ci"))).isEmpty();
    }
}
