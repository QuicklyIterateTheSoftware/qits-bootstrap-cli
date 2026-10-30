package eu.wohlben.qits.cli.bootstrap.phases;

import eu.wohlben.qits.cli.bootstrap.config.TestConfig;
import eu.wohlben.qits.cli.bootstrap.engine.PhaseContext;
import eu.wohlben.qits.cli.bootstrap.engine.PhaseSkipped;
import eu.wohlben.qits.cli.bootstrap.platform.MuslToolchain;
import eu.wohlben.qits.cli.bootstrap.platform.PlatformModel;
import eu.wohlben.qits.cli.bootstrap.proc.Cmd;
import eu.wohlben.qits.cli.bootstrap.proc.RunLog;
import eu.wohlben.qits.cli.bootstrap.proc.ScriptedRunner;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    // --- which source one repository is built from ------------------------------------------------

    private static final String AT = "components/qits-ci/qits-ci";

    /** A checkout in the wrapper is the source, and there is nothing to refuse. */
    @Test
    void aCheckoutInTheWrapperIsAccepted() {
        assertThat(SeedPhases.localSourceRefusal("qits-ci", temp.resolve(AT), AT,
                true, true, true, true)).isNull();
    }

    /**
     * <b>The cold start, and it must stay silent.</b> The run clones the wrapper WITHOUT its
     * submodules on purpose — this phase clones every repository from the org anyway — so an empty
     * directory at every gitlink is the expected state and hides no local work.
     */
    @Test
    void aWrapperWithNoSubmoduleCheckedOutFallsBackToTheOrgWithoutASound() {
        assertThat(SeedPhases.localSourceRefusal("qits-ci", temp.resolve(AT), AT,
                true, false, false, false)).isNull();
    }

    /**
     * <b>The silence the layout flip would have turned into 48 wrong shas.</b> The wrapper declares
     * this repository, its directory holds no checkout, and its siblings do — a half-initialised
     * checkout. The org fallback would build last week's push and say so in one line among
     * thousands, so it stops the boot and names the fix.
     */
    @Test
    void aDeclaredModuleMissingFromAnInitialisedWrapperStopsTheBoot() {
        String refusal = SeedPhases.localSourceRefusal("qits-ci", temp.resolve(AT), AT,
                true, true, false, false);

        assertThat(refusal)
                .contains("qits-ci")
                .contains(AT)
                .contains("git submodule update --init " + AT);
    }

    /** A repository the wrapper does not declare has no local source to miss. */
    @Test
    void anUndeclaredRepositoryStillComesFromTheOrg() {
        assertThat(SeedPhases.localSourceRefusal("qits-stt", temp.resolve("services/qits-stt"),
                "services/qits-stt", false, true, false, false)).isNull();
    }

    /** Something that is not a checkout is standing in the directory — the older rule, unchanged. */
    @Test
    void somethingElseInTheDirectoryStopsTheBootWhoeverPutItThere() {
        assertThat(SeedPhases.localSourceRefusal("qits-ci", temp.resolve(AT), AT,
                false, false, false, true))
                .contains("is not a git checkout and is not empty");
    }

    // --- the records the domain needs -------------------------------------------------------------

    /**
     * <b>Four A records, and the shapes are the edge's rather than a list of today's names.</b> The
     * edge reads at most the first three labels of a Host header, so one wildcard per DEPTH answers
     * every environment, every application vhost and every PROJECT there will ever be — adding any
     * of them is then a platform act with no dns step. The apex is written out because no wildcard
     * matches it.
     * <p>
     * <b>{@code *.*.*} is the record this change is about.</b> An environment and an app arrive with
     * a deploy, which an operator is already present for; a project is created by a person on a
     * running platform, so a record per project would be a dns edit per project — at Hetzner, by
     * hand. The wildcard is what makes the project tier cost nobody anything.
     * <p>
     * The nameserver's own record went with qits-platform-dns: this platform serves no dns, so there
     * is nothing to delegate to and no glue record to answer for.
     */
    @Test
    void theDomainNeedsAWildcardPerDepthPlusTheApex() {
        List<SeedPhases.ZoneRecord> records =
                SeedPhases.zoneRecords("qits-dev.eu", "203.0.113.7");

        assertThat(records).extracting(SeedPhases.ZoneRecord::name)
                .containsExactly("@", "*", "*.*", "*.*.*");
        // Every name is this one host: the edge is a single front door and routes by Host behind it.
        assertThat(records).extracting(SeedPhases.ZoneRecord::value)
                .containsOnly("203.0.113.7");
        // And each one says which shape it is there for, because the person typing them in at the
        // provider is reading this list and nothing else.
        assertThat(records).extracting(SeedPhases.ZoneRecord::why)
                .anySatisfy(why -> assertThat(why).contains("apex"))
                .anySatisfy(why -> assertThat(why).contains("<project>.qits-dev.eu door"))
                .anySatisfy(why -> assertThat(why).contains("<env>.<project>.qits-dev.eu"))
                .anySatisfy(why -> assertThat(why)
                        .contains("<app>.<env>.<project>.qits-dev.eu")
                        .contains("wire routes"));
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
                "userflows", "coding-agents", "integrations-quarkus", "registries", "eventstream",
                "containers-driver", "githost", "containers");
        // Dependency order, and every pair is forced by a pom: the blob store is written against
        // qits-db-core (a module of qits-integrations-quarkus) since its DbRetry release,
        // qits-eventstream against qits-db-core too, qits-githost-events against qits-eventstream,
        // and the orchestrator's two libraries against qits-db-core and qits-eventstream both.
        // THE BLOB STORE HAS NO ENTRY, and needs none since 2026-08-30: it is a module of the
        // qits-registries reactor, which is published whole, so the registries entry is what puts
        // qits-blobstore in the registry — after the integrations, as it always was.
        assertThat(SeedPhases.SEED_LIBRARIES).doesNotContain("blobstore");
        assertThat(SeedPhases.SEED_LIBRARIES)
                .containsSubsequence("integrations-quarkus", "registries");
        assertThat(SeedPhases.SEED_LIBRARIES)
                .containsSubsequence("integrations-quarkus", "eventstream", "githost");
        assertThat(SeedPhases.SEED_LIBRARIES)
                .containsSubsequence("integrations-quarkus", "containers");
        assertThat(SeedPhases.SEED_LIBRARIES).containsSubsequence("eventstream", "containers");
        // The new library, beside eventstream and before the service that now depends on it.
        assertThat(SeedPhases.SEED_LIBRARIES)
                .containsSubsequence("eventstream", "containers-driver", "containers");
        // containers is last, because the probe that skips the whole phase asks for
        // qits-containers-client — the last thing this phase publishes, so its presence is the one
        // honest answer for the whole set.
        assertThat(SeedPhases.SEED_LIBRARIES).endsWith("containers");
        // <b>qits-userflows resolves nothing of ours and seventeen root poms resolve it.</b> It is
        // TEST-scoped in every one of them, which buys a store nothing: `mvn package -DskipTests`
        // resolves test-scoped dependencies too, so a cold boot's first seed image build died on
        // qits-userflows:pom (absent) while the repository was seeded but never published. First,
        // because the skip probe asks for the LAST entry.
        assertThat(SeedPhases.SEED_LIBRARIES).startsWith("userflows");
    }

    /**
     * The git host and the orchestrator are seeded by MODULE. Publishing either repository whole
     * would build its service to hand over a library; {@code -am} is what carries the root pom with
     * it, without which the jar resolves nowhere.
     */
    @Test
    void theGitHostPublishesItsEventVocabularyAndNothingElse() {
        assertThat(SeedPhases.mavenModuleArgs("githost")).isEqualTo(" -pl githost-events -am");
        assertThat(SeedPhases.mavenModuleArgs("containers")).isEqualTo(" -pl client -am");
        assertThat(SeedPhases.mavenModuleArgs("containers-driver")).isEmpty();
        assertThat(SeedPhases.mavenModuleArgs("eventstream")).isEmpty();
        // Whole, and that is what carries the blob store: it is a module of this reactor.
        assertThat(SeedPhases.mavenModuleArgs("registries")).isEmpty();
        assertThat(SeedPhases.mavenModuleArgs("integrations-quarkus")).isEmpty();
        // Whole too: a parent pom with one module, and consumers need both — the parent's pom is
        // what the module's own pom names.
        assertThat(SeedPhases.mavenModuleArgs("userflows")).isEmpty();
    }

    /**
     * <b>The seed script reads Maven Central through a cache that outlives the run.</b> Without it
     * every bootstrap re-downloaded the whole dependency world with a cold local repository, and
     * four cold runs in one evening got this host throttled: the 2026-08-11 attempt 4 died at the
     * qits-blobstore publish on a 502 from the mirror's central proxy.
     */
    @Test
    void everySeedBuildResolvesThroughTheSharedCache() {
        String script = SeedPhases.seedScript(Map.of());

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
        String script = SeedPhases.seedScript(Map.of());

        assertThat(script).startsWith("set -eu\nrm -rf /cache/repository/eu/wohlben/qits\n");
        // Only our group: the third-party half of the cache is the whole point of having one.
        assertThat(SeedPhases.MAVEN_PURGE_QITS)
                .isEqualTo("rm -rf /cache/repository/eu/wohlben/qits\n");
    }

    /** The other phase-1 builds, which is what phase 18 was killed in. */
    @Test
    void aProtocolBuildCachesAndPurgesTheSameWay() {
        String script = new SeedPhases(
                new Boot(TestConfig.from(Map.of()), new RunLog(temp.resolve("run.log"))))
                .protocolScript("githost", List.of("2026.1.1"));

        assertThat(script).contains("rm -rf /cache/repository/eu/wohlben/qits\n");
        assertThat(script).contains("-Dmaven.repo.local=/cache/repository");
        // The purge is before the build, and the settings before both — a mirror written after the
        // mvn line configures nothing.
        assertThat(script.indexOf("SETTINGS"))
                .isLessThan(script.indexOf("rm -rf /cache/repository"));
        assertThat(script.indexOf("rm -rf /cache/repository")).isLessThan(script.indexOf("mvn "));
        // Still by module, and into the file repository only.
        assertThat(script).contains(" -pl githost-events -am")
                .contains("-DaltDeploymentRepository=seed::default::file:///repo")
                .doesNotContain("qits::default::");
    }

    // --- the versions a lagging pin adds ----------------------------------------------------------

    /**
     * <b>The 2026-09-05 defect, in the script it produces.</b> A consumer pinned an eventstream
     * release four hours behind the checkout, the temporary registry held only what the seed built,
     * and the phase died on a version nobody had published. Each pinned version is now its own tree
     * and its own build, and the checkout's is LAST — everything else prefers it.
     */
    @Test
    void everyPinnedVersionOfALibraryIsSeededBesideTheCheckout() {
        String script = SeedPhases.seedScript(Map.of(
                "eventstream", List.of("2026.904.82646", "2026.905.1")));

        List<String> builds = script.lines().filter(line -> line.startsWith("cd ")).toList();
        assertThat(builds).hasSize(SeedPhases.SEED_LIBRARIES.size() + 2);
        assertThat(builds).containsSubsequence(
                "cd /src-eventstream@2026.904.82646 && mvn -B -ntp deploy -DskipTests"
                        + " -Dmaven.repo.local=/cache/repository"
                        + " -Dqits.maven.repository.url=file:///repo"
                        + " -DaltDeploymentRepository=seed::default::file:///repo",
                "cd /src-eventstream@2026.905.1 && mvn -B -ntp deploy -DskipTests"
                        + " -Dmaven.repo.local=/cache/repository"
                        + " -Dqits.maven.repository.url=file:///repo"
                        + " -DaltDeploymentRepository=seed::default::file:///repo",
                "cd /src-eventstream && mvn -B -ntp deploy -DskipTests"
                        + " -Dmaven.repo.local=/cache/repository"
                        + " -Dqits.maven.repository.url=file:///repo"
                        + " -DaltDeploymentRepository=seed::default::file:///repo");
    }

    /**
     * The library ORDER is untouched by the extra versions: an older version of one library
     * resolves only libraries above it, whose own pinned versions this container has already
     * deployed.
     */
    @Test
    void pinnedVersionsDoNotDisturbTheLibraryOrder() {
        String script = SeedPhases.seedScript(Map.of(
                "eventstream", List.of("2026.904.82646"),
                "integrations-quarkus", List.of("2026.902.53026")));

        assertThat(script.lines().filter(line -> line.startsWith("cd ")).toList())
                .containsSubsequence("cd /src-integrations-quarkus@2026.902.53026"
                                + " && mvn -B -ntp deploy -DskipTests"
                                + " -Dmaven.repo.local=/cache/repository"
                                + " -Dqits.maven.repository.url=file:///repo"
                                + " -DaltDeploymentRepository=seed::default::file:///repo",
                        "cd /src-eventstream@2026.904.82646 && mvn -B -ntp deploy -DskipTests"
                                + " -Dmaven.repo.local=/cache/repository"
                                + " -Dqits.maven.repository.url=file:///repo"
                                + " -DaltDeploymentRepository=seed::default::file:///repo");
    }

    /**
     * A library nothing lags behind is built once, exactly as it always was — the pin closure adds
     * work only where the estate asks for it.
     */
    @Test
    void withNothingLaggingTheSeedIsOneBuildPerLibrary() {
        assertThat(SeedPhases.seedScript(Map.of()).lines()
                .filter(line -> line.startsWith("cd ")).toList())
                .hasSize(SeedPhases.SEED_LIBRARIES.size());
        assertThat(SeedPhases.seedTree("eventstream", null)).isEqualTo("/src-eventstream");
    }

    // --- the wire contracts qits-ci and qits-system compile against ------------------------------

    private static final String CI_POM = """
            <project>
              <properties>
                <qits.ci-daemon-protocol.version>2026.929.103640</qits.ci-daemon-protocol.version>
                <qits.eventstream.version>2026.929.142419</qits.eventstream.version>
              </properties>
            </project>
            """;

    /** qits-system's root pom, which pins the target's runner — whitespace and all. */
    private static final String SYSTEM_POM = """
            <project>
              <properties>
                <qits.system-runner-protocol.version>
                    2026.930.132340
                </qits.system-runner-protocol.version>
              </properties>
            </project>
            """;

    /** The pin is the consumer checkout's own property, whitespace and all. */
    @Test
    void theProtocolVersionIsTheOneTheCiCheckoutPins() {
        assertThat(SeedPhases.protocolPin(CI_POM, "qits.ci-daemon-protocol.version"))
                .isEqualTo("2026.929.103640");
        assertThat(SeedPhases.protocolPin(SYSTEM_POM, "qits.system-runner-protocol.version"))
                .isEqualTo("2026.930.132340");
    }

    /**
     * <b>Anything that is not a released version stops the boot, and says which thing it is.</b> A
     * cold boot can only publish what a release tag holds: a snapshot has no tag, and a reference
     * to another property is a value this reader cannot follow.
     */
    @Test
    void aPinThatIsNotAReleaseStopsTheBootWithItsName() {
        assertThatThrownBy(() -> SeedPhases.protocolPin(
                "<qits.system-runner-protocol.version>1.0.0-SNAPSHOT</qits.system-runner-protocol.version>",
                "qits.system-runner-protocol.version"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("qits.system-runner-protocol.version")
                .hasMessageContaining("1.0.0-SNAPSHOT")
                .hasMessageContaining("not a released version");
        assertThatThrownBy(() -> SeedPhases.protocolPin(
                "<qits.system-runner-protocol.version>${revision}</qits.system-runner-protocol.version>",
                "qits.system-runner-protocol.version"))
                .hasMessageContaining("not a released version");
        assertThatThrownBy(() -> SeedPhases.protocolPin("<project/>",
                "qits.ci-daemon-protocol.version"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("carries no <qits.ci-daemon-protocol.version>");
    }

    /**
     * <b>One module per pinned version, from the tag's own tree, into the FILE repository and
     * nowhere else</b> (qits-889): the seed image builds resolve from it, and the store gets the
     * jar from {@code store-seed} once there is an idp to publish under.
     */
    @Test
    void aProtocolJarIsBuiltFromItsTagIntoTheFileRegistryOnly() {
        SeedPhases phases = new SeedPhases(
                new Boot(TestConfig.from(Map.of()), new RunLog(temp.resolve("run.log"))));

        String script = phases.protocolScript("system-runner-daemon", List.of("2026.930.132340"));

        assertThat(script).startsWith("set -eu\n");
        assertThat(script.lines().filter(line -> line.startsWith("cd ")).toList())
                .containsExactly(
                        "cd /src@2026.930.132340 && mvn -B -ntp -s /root/.m2/settings.xml deploy "
                                + "-DskipTests -pl system-runner-protocol -am"
                                + " -Dmaven.repo.local=/cache/repository"
                                + " -Dqits.maven.repository.url=file:///repo"
                                + " -DaltDeploymentRepository=seed::default::file:///repo");
        // No credential, no server, no store: the settings mirror the qits repository to the volume.
        assertThat(script).doesNotContain("<server>").doesNotContain("Authorization")
                .doesNotContain("qits-artifacts").doesNotContain("qits-platform-mirror")
                .contains("<mirrorOf>qits-maven</mirrorOf>").contains("<url>file:///repo</url>");
        // Never the working tree: the checkout's own /src is not in the script at all.
        assertThat(script).doesNotContain("cd /src &&");
        // And the daemon's contract is the daemon's module.
        assertThat(phases.protocolScript("ci-daemon", List.of("2026.929.103640")))
                .contains("-pl ci-daemon-protocol -am");
    }

    private static final String RUNNER_PROTOCOL_JAR = "eu/wohlben/qits/qits-system-runner-protocol/"
            + "2026.930.132340/qits-system-runner-protocol-2026.930.132340.jar";

    /**
     * A boot whose sources directory is this test's, holding a ci checkout and a system checkout
     * that pin the two contracts.
     */
    private Boot protocolBoot(ScriptedRunner runner, CannedHttp http) throws IOException {
        Boot boot = new Boot(TestConfig.from(Map.of("QITS_MACHINE_AUTH", "0",
                "QITS_DOMAIN", "qits-dev.eu",
                "QITS_SRC", temp.resolve("src").toString())),
                new RunLog(temp.resolve("run.log")), runner, http);
        boot.state.srcDir = temp.resolve("src");
        boot.state.wrapperDir = temp;
        Path ci = Files.createDirectories(boot.state.repoDir("ci"));
        Files.writeString(ci.resolve("pom.xml"), CI_POM, StandardCharsets.UTF_8);
        Path system = Files.createDirectories(boot.state.repoDir("system"));
        Files.writeString(system.resolve("pom.xml"), SYSTEM_POM, StandardCharsets.UTF_8);
        return boot;
    }

    private static void runProtocolPublish(Boot boot, PhaseContext ctx) throws Exception {
        new SeedPhases(boot).protocolSeed("system", "system-runner-daemon",
                "qits-system-runner-protocol", "qits.system-runner-protocol.version").action()
                .run(ctx);
    }

    /**
     * <b>A tag the clone does not hold stops the boot HERE, with the tag named.</b> The
     * alternative is a ci image build that fails minutes later on a coordinate nothing published.
     */
    @Test
    void aPinnedTagMissingFromTheDaemonCheckoutStopsTheBoot() throws Exception {
        ScriptedRunner runner = new ScriptedRunner(command ->
                command.contains("show") ? ScriptedRunner.failed("fatal: invalid object name")
                        : ScriptedRunner.ok());
        Boot boot = protocolBoot(runner, new CannedHttp());

        assertThatThrownBy(() -> runProtocolPublish(boot, new CiLogStreamTest.Recorder()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("qits-system-runner-protocol 2026.930.132340")
                .hasMessageContaining("qits-system-runner-daemon")
                .hasMessageContaining("no such tag");
        // And nothing was started for a jar there is no source for.
        assertThat(runner.lines()).noneMatch(line -> line.startsWith("docker create"));
    }

    /** The file registry holds it: nothing is built, which is every warm rerun. */
    @Test
    void aProtocolJarTheFileRegistryHoldsIsNotSeededAgain() throws Exception {
        ScriptedRunner runner = new ScriptedRunner(command ->
                command.contains("--rm") ? ScriptedRunner.ok(RUNNER_PROTOCOL_JAR)
                        : ScriptedRunner.ok("<project/>"));
        CannedHttp http = new CannedHttp();
        Boot boot = protocolBoot(runner, http);

        assertThatThrownBy(() -> runProtocolPublish(boot, new CiLogStreamTest.Recorder()))
                .isInstanceOf(PhaseSkipped.class)
                .hasMessageContaining("2026.930.132340 is in the file registry");
        // And the store was not asked: no store runs in phase 1.
        assertThat(http.calls).isEmpty();
        assertThat(runner.lines()).noneMatch(line -> line.startsWith("docker create"));
    }

    /**
     * <b>The file registry lacks it.</b> The tag's tree is what goes into the container, the volume
     * is the one mounted, and the container is on no network of the platform's.
     */
    @Test
    void aProtocolJarTheFileRegistryLacksIsSeededFromTheTag() throws Exception {
        ScriptedRunner runner = new ScriptedRunner(command -> {
            String line = String.join(" ", command);
            if (line.startsWith("docker create")) {
                return ScriptedRunner.ok("cid-1");
            }
            // The file registry answers with nothing: the jar is not there.
            return line.contains("--rm") ? ScriptedRunner.ok() : ScriptedRunner.ok("<project/>");
        });
        Boot boot = protocolBoot(runner, new CannedHttp());

        runProtocolPublish(boot, new CiLogStreamTest.Recorder());

        List<String> create = runner.argv.stream()
                .filter(command -> command.size() > 1 && command.get(1).equals("create"))
                .findFirst().orElseThrow();
        assertThat(create).doesNotContain("--network")
                .containsSubsequence("-v", "qits-maven-seed:/repo")
                .containsSubsequence("-v", "qits-maven-cache:/cache");
        assertThat(create.getLast()).contains("seed::default::file:///repo")
                .doesNotContain("qits::default::");
        // The tag is exported as a worktree and copied in beside nothing else.
        assertThat(runner.lines()).anyMatch(line -> line.contains("worktree add")
                && line.contains("2026.930.132340"));
        assertThat(runner.lines()).anyMatch(line -> line.startsWith("docker cp ")
                && line.endsWith("cid-1:/src@2026.930.132340"));
        assertThat(runner.lines()).contains("docker start -a cid-1");
    }

    /**
     * <b>A contract two seed services pin, at both of their versions, in one phase</b> (qits-889):
     * qits-projects and qits-system compile against qits-workspace-daemon-protocol and their pins
     * lag independently, so each version is built from its own tag into the registries it lacks.
     */
    @Test
    void aContractTwoServicesPinIsSeededAtBothPins() throws Exception {
        ScriptedRunner runner = new ScriptedRunner(command -> {
            String line = String.join(" ", command);
            if (line.startsWith("docker create")) {
                return ScriptedRunner.ok("cid-2");
            }
            return line.contains("--rm") ? ScriptedRunner.ok() : ScriptedRunner.ok("<project/>");
        });
        Boot boot = protocolBoot(runner, new CannedHttp());
        Files.writeString(Files.createDirectories(boot.state.repoDir("projects")).resolve("pom.xml"),
                "<qits.workspace-daemon-protocol.version>2026.1030.80103"
                        + "</qits.workspace-daemon-protocol.version>", StandardCharsets.UTF_8);
        Files.writeString(boot.state.repoDir("system").resolve("pom.xml"),
                "<qits.workspace-daemon-protocol.version>2026.1030.124906"
                        + "</qits.workspace-daemon-protocol.version>", StandardCharsets.UTF_8);

        new SeedPhases(boot).protocolSeed(List.of("projects", "system"), "workspace-daemon",
                "qits-workspace-daemon-protocol", "qits.workspace-daemon-protocol.version")
                .action().run(new CiLogStreamTest.Recorder());

        String script = runner.argv.stream()
                .filter(command -> command.size() > 1 && command.get(1).equals("create"))
                .findFirst().orElseThrow().getLast();
        assertThat(script.lines().filter(line -> line.startsWith("cd ")).toList())
                .anySatisfy(line -> assertThat(line).startsWith("cd /src@2026.1030.80103 &&")
                        .contains("-pl workspace-daemon-protocol -am")
                        .endsWith("seed::default::file:///repo"))
                .anySatisfy(line -> assertThat(line).startsWith("cd /src@2026.1030.124906 &&")
                        .endsWith("seed::default::file:///repo"));
        // One purge and one settings file for the container, not one per version.
        assertThat(script.lines().filter(SeedPhases.MAVEN_PURGE_QITS.strip()::equals)).hasSize(1);
        assertThat(runner.lines()).filteredOn(line -> line.contains("worktree add")).hasSize(2);
    }

    // --- the runner image -------------------------------------------------------------------------

    /** The runner's Dockerfile, cut to its shape: a global ARG, the compile stage, the image stage. */
    private static final String RUNNER_DOCKERFILE = """
            # HOW THE RUNNER IS BUILT
            ARG BUILDER_IMAGE=qits/graalvmce-musl-builder:jdk-25

            FROM ${BUILDER_IMAGE} AS build
            USER root
            WORKDIR /qits-build
            COPY . .
            RUN ./mvnw -B -ntp -pl system-runner -am package -Dnative -DskipTests

            FROM scratch AS binary
            COPY --from=build /qits-build/system-runner/target/qits-system-runner /qits-system-runner

            # The image: the binary and a docker CLI on a small alpine base.
            FROM docker:29.8.1-cli AS image
            COPY --from=build /qits-build/system-runner/target/qits-system-runner /usr/local/bin/qits-system-runner
            RUN mkdir -p /var/lib/qits-system-runner && chmod 700 /var/lib/qits-system-runner
            ENTRYPOINT ["/usr/local/bin/qits-system-runner"]
            CMD []
            """;

    /**
     * <b>The image stage alone, fed the binary this boot built.</b> The compile happens in a
     * container on the shared cache, so the only line of the stage that changes is the copy out of
     * the {@code build} stage — the base image, the state directory and the entrypoint are the
     * repository's and arrive untouched.
     */
    @Test
    void theRunnerImageIsTheDockerfilesLastStageWithTheBinaryCopiedFromTheContext() {
        String stage = SeedPhases.runnerImageStage(RUNNER_DOCKERFILE, "qits-system-runner");

        assertThat(stage.lines().toList()).containsExactly(
                "ARG BUILDER_IMAGE=qits/graalvmce-musl-builder:jdk-25",
                "FROM docker:29.8.1-cli AS image",
                "COPY qits-system-runner /usr/local/bin/qits-system-runner",
                "RUN mkdir -p /var/lib/qits-system-runner && chmod 700 /var/lib/qits-system-runner",
                "ENTRYPOINT [\"/usr/local/bin/qits-system-runner\"]",
                "CMD []");
        // No stage of it compiles anything: the other two are gone whole.
        assertThat(stage).doesNotContain("--from=build").doesNotContain("mvnw");
    }

    /** A base image named through the mirror is read from its upstream, like every seed image's. */
    @Test
    void theRunnerImagesBaseLosesTheMirrorPrefixLikeEverySeedImage() {
        String stage = SeedPhases.runnerImageStage(RUNNER_DOCKERFILE.replace(
                "FROM docker:29.8.1-cli", "FROM mirror.dev.localhost:8080/hub/library/docker:29.8.1-cli"),
                "qits-system-runner");

        assertThat(stage).contains("FROM docker.io/library/docker:29.8.1-cli AS image");
    }

    /** A Dockerfile that lost the stage, or the copy, stops the boot rather than being guessed at. */
    @Test
    void aRunnerDockerfileThisBootCannotReadStopsIt() {
        assertThatThrownBy(() -> SeedPhases.runnerImageStage(
                "FROM alpine AS build\nRUN true\n", "qits-system-runner"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AS image");
        assertThatThrownBy(() -> SeedPhases.runnerImageStage(
                "FROM alpine AS image\nRUN true\n", "qits-system-runner"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("copies nothing from its build stage");
    }

    private static void runRunnerImage(Boot boot, PhaseContext ctx) throws Exception {
        new SeedPhases(boot).systemRunnerImage().action().run(ctx);
    }

    /**
     * <b>The whole build, command for command.</b> The tag qits-system pins goes into the ci-daemon's
     * builder container, the binary comes out into a directory under the sources directory —
     * because the image build mounts it, and the HOST's daemon resolves that mount — and the image
     * is tagged under the bare name, under the registry host and under the public registry name.
     */
    @Test
    void theRunnerIsCompiledFromThePinnedTagAndTaggedUnderTheRegistryHost() throws Exception {
        ScriptedRunner runner = new ScriptedRunner(command -> {
            String line = String.join(" ", command);
            if (line.startsWith("docker image inspect")) {
                return ScriptedRunner.failed("Error: No such image");
            }
            if (line.contains(" show 2026.930.132340:docker/Dockerfile")) {
                return ScriptedRunner.ok(RUNNER_DOCKERFILE.split("\n"));
            }
            return line.startsWith("docker create") ? ScriptedRunner.ok("cid-7")
                    : ScriptedRunner.ok();
        });
        Boot boot = protocolBoot(runner, new CannedHttp());

        runRunnerImage(boot, new CiLogStreamTest.Recorder());

        List<String> create = runner.argv.stream()
                .filter(command -> command.size() > 1 && command.get(1).equals("create"))
                .findFirst().orElseThrow();
        assertThat(create).doesNotContain("--network")
                .containsSubsequence("--entrypoint", "bash")
                .containsSubsequence("--memory", "6g")
                .containsSubsequence("-v", "qits-maven-seed:/repo:ro")
                .containsSubsequence("-v", "qits-maven-cache:/cache",
                        "qits/graalvmce-musl-builder:jdk-25", "-c");
        // The qits jars it resolves are the file repository's, mounted read-only; no store runs.
        assertThat(create.getLast())
                .contains("<mirrorOf>qits-maven</mirrorOf>")
                .contains("<url>file:///repo</url>")
                .doesNotContain("qits-artifacts").doesNotContain("qits-platform-mirror")
                .endsWith("rm -rf /cache/repository/eu/wohlben/qits\n"
                        + "cd /qits-build && ./mvnw -B -ntp -s /root/.m2/settings.xml -pl"
                        + " system-runner -am package -Dnative -DskipTests"
                        + " -Dquarkus.native.container-build=false"
                        + " -Dmaven.repo.local=/cache/repository"
                        + " -Dqits.maven.repository.url=file:///repo");
        Path context = temp.resolve("src/.exports/qits-system-runner-image");
        assertThat(runner.lines()).containsSubsequence(
                "docker start -a cid-7",
                "docker cp cid-7:/qits-build/system-runner/target/qits-system-runner "
                        + context.resolve("qits-system-runner"),
                "docker rm -f cid-7",
                "docker tag qits/qits-system-runner:2026.930.132340 "
                        + "registry.prod.localhost:8080/qits/qits-system-runner:2026.930.132340",
                "docker tag qits/qits-system-runner:2026.930.132340 "
                        + "registry.qits.qits-dev.eu/qits/qits-system-runner:2026.930.132340");
        // The tag's tree is what was compiled, never the checkout.
        assertThat(runner.lines()).anyMatch(line -> line.startsWith("docker cp ")
                && line.endsWith("cid-7:/qits-build")
                && line.contains("qits-system-runner-daemon@2026.930.132340"));
        // And the image build's context is that one directory, under the sources directory.
        assertThat(runner.lines()).anyMatch(line -> line.contains("-v " + context + ":/ctx:ro")
                && line.contains("name=qits/qits-system-runner:2026.930.132340"));
        assertThat(context).doesNotExist();
    }

    /** An image this host already holds is tagged and left alone: a version names one commit. */
    @Test
    void aRunnerImageThisHostHoldsIsNotBuiltAgain() throws Exception {
        ScriptedRunner runner = new ScriptedRunner(command -> ScriptedRunner.ok("sha256:abc"));
        Boot boot = protocolBoot(runner, new CannedHttp());

        assertThatThrownBy(() -> runRunnerImage(boot, new CiLogStreamTest.Recorder()))
                .isInstanceOf(PhaseSkipped.class)
                .hasMessageContaining("qits/qits-system-runner:2026.930.132340 is already on this host");
        assertThat(runner.lines()).contains("docker tag qits/qits-system-runner:2026.930.132340 "
                + "registry.prod.localhost:8080/qits/qits-system-runner:2026.930.132340",
                "docker tag qits/qits-system-runner:2026.930.132340 "
                + "registry.qits.qits-dev.eu/qits/qits-system-runner:2026.930.132340");
        assertThat(runner.lines()).noneMatch(line -> line.startsWith("docker create"));
    }

    /** A pinned tag the clone lacks stops the boot before anything is compiled. */
    @Test
    void aRunnerTagMissingFromTheCheckoutStopsTheBoot() throws Exception {
        ScriptedRunner runner = new ScriptedRunner(command -> ScriptedRunner.failed("no"));
        Boot boot = protocolBoot(runner, new CannedHttp());

        assertThatThrownBy(() -> runRunnerImage(boot, new CiLogStreamTest.Recorder()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("pins the runner at 2026.930.132340")
                .hasMessageContaining("no such tag");
        assertThat(runner.lines()).noneMatch(line -> line.startsWith("docker create"));
    }

    // --- the contract jars: golden masters and pacts (qits-1547, qits-889) -----------------------

    private SeedPhases plainPhases() {
        return new SeedPhases(
                new Boot(TestConfig.from(Map.of()), new RunLog(temp.resolve("run.log"))));
    }

    private static SeedPhases.ContractJar goldenMasters() {
        return new SeedPhases.ContractJar("qits-idp-golden-masters", "2026.1027.165632", "idp",
                "2026.1027.165632", null, "qits-idp golden masters (contracts)");
    }

    private static SeedPhases.ContractJar edgePact() {
        return new SeedPhases.ContractJar("qits-ci-frontend-pacts-qits-edge-service",
                "2026.1021.191659", "spa-ci", "2026.1021.191659", "qits-edge-service",
                "qits-ci pacts with qits-edge-service (contracts)");
    }

    /**
     * <b>Into the FILE repository with no server and no settings</b>: the phase runs before the
     * mirror exists, so the deploy plugin comes from Maven Central direct, and the jar is packed in
     * the platform packager's layout — a golden-masters tree whole, under its kind's root.
     */
    @Test
    void aGoldenMastersJarIsPackedFromItsArchiveIntoTheFileRepository() {
        String script = SeedPhases.contractScript(List.of(goldenMasters()));

        assertThat(script).startsWith("set -eu\n")
                .contains("mkdir -p /contracts/qits-idp-golden-masters@2026.1027.165632"
                        + " && tar -xf /tmp/qits-idp-golden-masters@2026.1027.165632.tar"
                        + " -C /contracts/qits-idp-golden-masters@2026.1027.165632")
                .contains("test -d /contracts/qits-idp-golden-masters@2026.1027.165632/golden-masters")
                .contains("jar cf /tmp/qits-idp-golden-masters-2026.1027.165632.jar"
                        + " -C /contracts/qits-idp-golden-masters@2026.1027.165632 golden-masters")
                .contains("mvn -B -ntp org.apache.maven.plugins:maven-deploy-plugin:3.1.2:deploy-file"
                        + " -Dfile=/tmp/qits-idp-golden-masters-2026.1027.165632.jar"
                        + " -DgroupId=eu.wohlben.qits -DartifactId=qits-idp-golden-masters"
                        + " -Dversion=2026.1027.165632 -Dpackaging=jar"
                        + " '-DgeneratePom.description=qits-idp golden masters (contracts)'"
                        + " -Dmaven.repo.local=/cache/repository -DrepositoryId=seed"
                        + " -Durl=file:///repo");
        // No settings file, no mirror, no store: nothing of the platform is up yet.
        assertThat(script).doesNotContain("settings.xml").doesNotContain("qits::")
                .doesNotContain("prod-qits-artifacts");
    }

    /**
     * <b>A pact jar holds ONE provider's pacts</b>, the top-level {@code pacts/?*_<provider>.json}
     * of the consumer's release — {@code ContractPackager.pacts}' rule — under {@code pacts/}, and a
     * consumer with none for that provider stops the container with the coordinate named.
     */
    @Test
    void aPactJarHoldsOneProvidersPactsUnderPacts() {
        String script = SeedPhases.contractScript(List.of(edgePact()));
        String pack = "/tmp/pack/qits-ci-frontend-pacts-qits-edge-service@2026.1021.191659";

        assertThat(script)
                .contains("find /contracts/qits-ci-frontend-pacts-qits-edge-service@2026.1021.191659"
                        + "/pacts -maxdepth 1 -type f -name '?*_qits-edge-service.json'"
                        + " -exec cp {} " + pack + "/pacts/ \\;")
                .contains("ls " + pack + "/pacts | grep -q . || { echo "
                        + "'qits-ci-frontend-pacts-qits-edge-service 2026.1021.191659: no "
                        + "pacts/*_qits-edge-service.json to pack' >&2; exit 1; }")
                .contains("jar cf /tmp/qits-ci-frontend-pacts-qits-edge-service-2026.1021.191659.jar"
                        + " -C " + pack + " pacts")
                .contains(" -DartifactId=qits-ci-frontend-pacts-qits-edge-service"
                        + " -Dversion=2026.1021.191659 -Dpackaging=jar"
                        + " '-DgeneratePom.description=qits-ci pacts with qits-edge-service"
                        + " (contracts)'")
                .contains("-DrepositoryId=seed -Durl=file:///repo");
        assertThat(script).doesNotContain("golden-masters");
    }

    /** {@code ContractPackager.pacts}: a top-level {@code <consumer>_<provider>.json}, nothing else. */
    @Test
    void aPactFileIsAProvidersByItsNameAlone() {
        assertThat(SeedPhases.pactFor("qits-ci-frontend_qits-edge-service.json",
                "qits-edge-service")).isTrue();
        assertThat(SeedPhases.pactFor(
                "qits-ci-frontend_qits-ui-components-jslib_qits-ci-service.json",
                "qits-ci-service")).isTrue();
        assertThat(SeedPhases.pactFor("pacts/qits-ci-frontend_qits-ci-service.json",
                "qits-ci-service")).isTrue();
        assertThat(SeedPhases.pactFor("qits-ci-frontend_qits-ci-service.json",
                "qits-edge-service")).isFalse();
        // Nothing before the underscore is no consumer's pact.
        assertThat(SeedPhases.pactFor("_qits-edge-service.json", "qits-edge-service")).isFalse();
    }

    /** The description names {@code contracts.application}, as the platform's packager does. */
    @Test
    void thePactsDescriptionNamesTheConsumersApplication() {
        String yml = """
                # application: is not this one
                artifacts:
                  - name: x
                contracts:
                  # the application this SPA belongs to
                  application: qits-ci
                  pacts:
                    qits-ci-service: { packages: [maven] }
                """;

        assertThat(SeedPhases.contractApplication(yml, "qits-ci-frontend")).isEqualTo("qits-ci");
        assertThat(SeedPhases.contractApplication("artifacts: []\n", "qits-ci-frontend"))
                .isEqualTo("qits-ci-frontend");
        assertThat(SeedPhases.contractApplication(null, "qits-bootstrap-cli"))
                .isEqualTo("qits-bootstrap-cli");
    }

    /** The edge's root pom, cut to its contract pins and one library pin. */
    private static final String EDGE_POM = """
            <project>
              <groupId>eu.wohlben</groupId>
              <artifactId>qits</artifactId>
              <version>2026.1030.40907</version>
              <properties>
                <qits.idp-golden-masters.version>2026.1027.165632</qits.idp-golden-masters.version>
                <qits.ci-frontend-pacts-qits-edge-service.version>2026.1021.191659</qits.ci-frontend-pacts-qits-edge-service.version>
                <qits.projects-service-pacts-qits-edge-service.version>2026.1028.12758</qits.projects-service-pacts-qits-edge-service.version>
                <qits.nowhere-app-pacts-qits-edge-service.version>2026.1001.1</qits.nowhere-app-pacts-qits-edge-service.version>
                <qits.eventstream.version>2026.1018.151251</qits.eventstream.version>
              </properties>
            </project>
            """;

    /** qits-system's root pom with both pins the runner and golden-master closures read. */
    private static final String SYSTEM_PINS_POM = """
            <project>
              <properties>
                <qits.system-runner-protocol.version>2026.930.132340</qits.system-runner-protocol.version>
                <qits.runner.version>2026.1029.234609</qits.runner.version>
              </properties>
            </project>
            """;

    private ScriptedRunner contractRunner() {
        return new ScriptedRunner(command -> {
            String line = String.join(" ", command);
            if (line.contains("rev-parse -q --verify refs/tags/2026.1028.12758")) {
                // The tag is gone; the release commit is not.
                return ScriptedRunner.failed();
            }
            if (line.contains("rev-parse -q --verify refs/tags/")) {
                return ScriptedRunner.ok("deadbeef");
            }
            if (line.contains(" log --all ")) {
                return ScriptedRunner.ok(
                        "0123abcd bump(qits-1): quotes release(2026.1028.12758): in passing",
                        "6dbeb40a release(2026.1028.12758): qits-1690: a rolled-back deployment");
            }
            if (line.contains("ls-tree --name-only 2026.1021.191659:pacts")) {
                return ScriptedRunner.ok("qits-ci-frontend_qits-ci-service.json",
                        "qits-ci-frontend_qits-ui-components-jslib_qits-edge-service.json");
            }
            if (line.contains("ls-tree --name-only 6dbeb40a:pacts")) {
                return ScriptedRunner.ok("qits-projects-service_qits-edge-service.json");
            }
            if (line.contains("show 2026.1021.191659:.config/qits/release.yml")) {
                return ScriptedRunner.ok("contracts:", "  application: qits-ci");
            }
            if (line.contains("ls-tree --name-only 2026.1027.165632:golden-masters")) {
                return ScriptedRunner.ok("index.json");
            }
            if (line.startsWith("git") && line.contains(" show ")) {
                return ScriptedRunner.failed("fatal: invalid object name");
            }
            if (line.startsWith("docker create")) {
                return ScriptedRunner.ok("cid-7");
            }
            // The file repository holds nothing yet; everything else just works.
            return ScriptedRunner.ok();
        });
    }

    private Boot contractBoot(ScriptedRunner runner) throws IOException {
        Boot boot = new Boot(TestConfig.from(Map.of("QITS_MACHINE_AUTH", "0",
                "QITS_DOMAIN", "qits-dev.eu",
                "QITS_SRC", temp.resolve("src").toString())),
                new RunLog(temp.resolve("run.log")), runner, new CannedHttp());
        boot.state.srcDir = temp.resolve("src");
        boot.state.wrapperDir = temp;
        Path edge = Files.createDirectories(boot.state.repoDir("edge"));
        Files.writeString(edge.resolve("pom.xml"), EDGE_POM, StandardCharsets.UTF_8);
        Path system = Files.createDirectories(boot.state.repoDir("system"));
        Files.writeString(system.resolve("pom.xml"), SYSTEM_PINS_POM, StandardCharsets.UTF_8);
        return boot;
    }

    /**
     * <b>From the seed images' poms to a packed jar, with no server</b>: each pin the edge's poms
     * carry is found in the consumer's checkout — the tag, or the {@code release(<version>):}
     * commit when the tag is gone — its {@code pacts/} archived, copied in and deployed into the
     * file repository from a container on no network. A consumer this boot clones nothing of is
     * said, not guessed; golden masters are the other phase's.
     */
    @Test
    void thePactsTheSeedImagesPinArePackedFromTheConsumersReleases() throws Exception {
        ScriptedRunner runner = contractRunner();
        Boot boot = contractBoot(runner);
        CiLogStreamTest.Recorder ctx = new CiLogStreamTest.Recorder();

        new SeedPhases(boot).seedPacts().action().run(ctx);

        List<String> create = runner.argv.stream()
                .filter(command -> command.size() > 1 && command.get(1).equals("create"))
                .findFirst().orElseThrow();
        assertThat(create).doesNotContain("--network")
                .containsSubsequence("-v", "qits-maven-seed:/repo");
        String script = create.getLast();
        assertThat(script)
                .contains("-DartifactId=qits-ci-frontend-pacts-qits-edge-service"
                        + " -Dversion=2026.1021.191659")
                .contains("'-DgeneratePom.description=qits-ci pacts with qits-edge-service"
                        + " (contracts)'")
                .contains("-DartifactId=qits-projects-service-pacts-qits-edge-service"
                        + " -Dversion=2026.1028.12758")
                // No release.yml read for that one: the description falls back to the repository.
                .contains("'-DgeneratePom.description=qits-projects-service pacts with"
                        + " qits-edge-service (contracts)'")
                .doesNotContain("golden-masters").doesNotContain("nowhere");
        // The bytes are archives of the consumers' checkouts at the release — the commit, where
        // the tag is gone, and never the one that merely quotes it.
        assertThat(runner.lines()).anyMatch(line -> line.equals("git -C "
                + boot.state.repoDir("spa-ci") + " archive --format=tar -o "
                + boot.state.srcDir.resolve(".exports")
                        .resolve("qits-ci-frontend-pacts-qits-edge-service@2026.1021.191659.tar")
                + " 2026.1021.191659 -- pacts"));
        assertThat(runner.lines()).anyMatch(line -> line.startsWith("git -C "
                + boot.state.repoDir("projects") + " archive") && line.contains(" 6dbeb40a -- pacts"));
        assertThat(runner.lines()).anyMatch(line -> line.startsWith("docker cp ") && line.endsWith(
                "cid-7:/tmp/qits-ci-frontend-pacts-qits-edge-service@2026.1021.191659.tar"));
        assertThat(runner.lines()).contains("docker start -a cid-7");
        assertThat(ctx.logs).anyMatch(line -> line.contains("qits-nowhere-app-pacts-qits-edge-service")
                && line.contains("no repository this boot clones"));
    }

    /** The golden masters the same poms pin, by the same route, into the same file repository. */
    @Test
    void theGoldenMastersTheSeedImagesPinArePackedFromTheProvidersTags() throws Exception {
        ScriptedRunner runner = contractRunner();
        Boot boot = contractBoot(runner);

        new SeedPhases(boot).seedGoldenMasters().action().run(new CiLogStreamTest.Recorder());

        String script = runner.argv.stream()
                .filter(command -> command.size() > 1 && command.get(1).equals("create"))
                .findFirst().orElseThrow().getLast();
        assertThat(script).contains("-DartifactId=qits-idp-golden-masters"
                        + " -Dversion=2026.1027.165632")
                .contains("'-DgeneratePom.description=qits-idp golden masters (contracts)'")
                .contains("-Durl=file:///repo")
                .doesNotContain("-pacts-");
        assertThat(runner.lines()).anyMatch(line -> line.startsWith("git -C "
                + boot.state.repoDir("idp") + " archive")
                && line.endsWith(" 2026.1027.165632 -- golden-masters"));
    }

    // --- the seed image preflight (qits-889) -----------------------------------------------------

    private static final String EVENTSTREAM_JAR =
            "eu/wohlben/qits/qits-eventstream/2026.1018.151251/qits-eventstream-2026.1018.151251.jar";

    /** A two-module reactor: the root manages, the service module pins. */
    private Path edgeReactor() throws IOException {
        Path repo = Files.createDirectories(temp.resolve("src/qits-edge-service"));
        Files.writeString(repo.resolve("pom.xml"), """
                <project>
                  <groupId>eu.wohlben</groupId>
                  <artifactId>qits</artifactId>
                  <version>2026.1030.40907</version>
                  <properties>
                    <qits.eventstream.version>2026.1018.151251</qits.eventstream.version>
                    <qits.idp-golden-masters.version>2026.1027.165632</qits.idp-golden-masters.version>
                  </properties>
                  <dependencyManagement><dependencies>
                    <dependency>
                      <groupId>eu.wohlben.qits</groupId>
                      <artifactId>qits-idp-golden-masters</artifactId>
                      <version>${qits.idp-golden-masters.version}</version>
                      <scope>test</scope>
                    </dependency>
                  </dependencies></dependencyManagement>
                </project>
                """, StandardCharsets.UTF_8);
        Path service = Files.createDirectories(repo.resolve("service"));
        Files.writeString(service.resolve("pom.xml"), """
                <project>
                  <parent><groupId>eu.wohlben</groupId><artifactId>qits</artifactId>
                    <version>2026.1030.40907</version></parent>
                  <groupId>eu.wohlben.qits</groupId>
                  <artifactId>qits-edge-service</artifactId>
                  <dependencies>
                    <dependency>
                      <groupId>${project.groupId}</groupId>
                      <artifactId>qits-eventstream</artifactId>
                      <version>${qits.eventstream.version}</version>
                    </dependency>
                    <dependency>
                      <groupId>eu.wohlben.qits</groupId>
                      <artifactId>qits-idp-golden-masters</artifactId>
                    </dependency>
                  </dependencies>
                </project>
                """, StandardCharsets.UTF_8);
        // A fixture pom under src/ is no module, and a build directory is no source.
        Path fixture = Files.createDirectories(service.resolve("src/test/resources/fixture"));
        Files.writeString(fixture.resolve("pom.xml"), """
                <project><dependencies><dependency><groupId>eu.wohlben.qits</groupId>
                <artifactId>qits-fixture</artifactId><version>1</version></dependency>
                </dependencies></project>
                """, StandardCharsets.UTF_8);
        return repo;
    }

    /**
     * <b>Every coordinate the reactor pins, asked of the volume in ONE container, and every missing
     * one named in the refusal</b> — with its scope and the pom that pins it — before a native
     * build that would have died on the first of them.
     */
    @Test
    void aSeedImageWhoseCoordinatesTheVolumeLacksStopsWithTheWholeList() throws Exception {
        ScriptedRunner runner = new ScriptedRunner(command ->
                ScriptedRunner.ok(EVENTSTREAM_JAR));
        Boot boot = new Boot(TestConfig.from(Map.of()), new RunLog(temp.resolve("run.log")),
                runner, new CannedHttp());
        Path repo = edgeReactor();

        assertThatThrownBy(() -> new SeedPhases(boot).mavenPreflight(
                new CiLogStreamTest.Recorder(), "edge", repo))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("qits/edge cannot be built: 1 of the qits coordinates")
                .hasMessageContaining("qits-idp-golden-masters:2026.1027.165632 (test, service/pom.xml)")
                .hasMessageNotContaining("qits-eventstream:")
                .hasMessageNotContaining("qits-fixture");
        assertThat(runner.argv).singleElement().satisfies(command -> {
            assertThat(command).startsWith("docker", "run", "--rm", "-v", "qits-maven-seed:/repo:ro");
            assertThat(command.getLast()).contains("[ -f '/repo/" + EVENTSTREAM_JAR + "' ]")
                    .contains("qits-idp-golden-masters-2026.1027.165632.jar");
        });
    }

    /** All there: the phase goes on to its build, having said so. */
    @Test
    void aSeedImageWhoseCoordinatesAreAllThereGoesOn() throws Exception {
        String goldenMasters = "eu/wohlben/qits/qits-idp-golden-masters/2026.1027.165632/"
                + "qits-idp-golden-masters-2026.1027.165632.jar";
        ScriptedRunner runner = new ScriptedRunner(command ->
                ScriptedRunner.ok(EVENTSTREAM_JAR, goldenMasters));
        Boot boot = new Boot(TestConfig.from(Map.of()), new RunLog(temp.resolve("run.log")),
                runner, new CannedHttp());
        CiLogStreamTest.Recorder ctx = new CiLogStreamTest.Recorder();

        new SeedPhases(boot).mavenPreflight(ctx, "edge", edgeReactor());

        assertThat(ctx.logs).anyMatch(line -> line.contains("all 2 qits coordinates"));
    }

    /** A probe that could not run is a stop with docker's words, never "everything is missing". */
    @Test
    void aProbeThatCannotRunIsNotReadAsEveryJarMissing() throws Exception {
        ScriptedRunner runner = new ScriptedRunner(command ->
                ScriptedRunner.failed("Cannot connect to the Docker daemon"));
        Boot boot = new Boot(TestConfig.from(Map.of()), new RunLog(temp.resolve("run.log")),
                runner, new CannedHttp());
        Path repo = edgeReactor();

        assertThatThrownBy(() -> new SeedPhases(boot).mavenPreflight(
                new CiLogStreamTest.Recorder(), "edge", repo))
                .hasMessageContaining("asking the qits-maven-seed volume for 2 coordinates failed")
                .hasMessageNotContaining("cannot be built");
    }

    /** The refusal is null when nothing is missing, and names type and classifier when it is. */
    @Test
    void thePreflightRefusalNamesEveryMissingCoordinate() {
        eu.wohlben.qits.cli.bootstrap.platform.MavenPins.Coordinate jar =
                new eu.wohlben.qits.cli.bootstrap.platform.MavenPins.Coordinate(
                        "qits-eventstream", "1", "jar", "", "compile", "pom.xml");
        eu.wohlben.qits.cli.bootstrap.platform.MavenPins.Coordinate bom =
                new eu.wohlben.qits.cli.bootstrap.platform.MavenPins.Coordinate(
                        "qits-bom", "2", "pom", "", "import", "pom.xml");

        assertThat(SeedPhases.preflightRefusal("ci", List.of(jar),
                List.of(jar.repositoryPath()))).isNull();
        assertThat(SeedPhases.preflightRefusal("ci", List.of(jar, bom), List.of()))
                .contains("2 of the qits coordinates")
                .contains("  qits-eventstream:1 (compile, pom.xml)")
                .contains("  qits-bom:2:pom (import, pom.xml)");
    }

    /** Only the seed images that are Maven builds are checked: the database image is a FROM line. */
    @Test
    void theMavenSeedImagesAreTheCoreLessTheDatabase() {
        assertThat(SeedPhases.mavenSeedImages()).containsExactlyElementsOf(
                PlatformModel.CORE.stream().filter(name -> !name.equals("oci-postgresql")).toList());
    }

    /**
     * <b>maven-seed resolves what it did not build from the file repository</b> — the golden
     * masters the contracts kits compile against — never from the live registry its poms default
     * to; and builds qits-containers' contracts kit where the tree has one.
     */
    @Test
    void theMavenSeedResolvesFromTheFileRepositoryAndBuildsTheContainersContractsKit() {
        String script = SeedPhases.seedScript(Map.of());

        assertThat(script.lines().filter(line -> line.startsWith("cd /src-")))
                .allSatisfy(line -> assertThat(line)
                        .contains(" -Dqits.maven.repository.url=file:///repo"));
        assertThat(script).contains("cd /src-containers && mvn -B -ntp deploy -DskipTests"
                + " -pl client$([ -f client-contracts/pom.xml ] && echo ,client-contracts || true)"
                + " -am");
        // A protocol build keeps the module its consumers resolve.
        assertThat(SeedPhases.mavenModuleArgs("containers")).isEqualTo(" -pl client -am");
        assertThat(SeedPhases.seedModuleArgs("githost")).isEqualTo(" -pl githost-events -am");
    }

    // --- the runner library (qits-1547) ----------------------------------------------------------

    /**
     * The runner library from its tags, whole, its module poms' test scope stripped first — in
     * the container's copy — and one deploy per version into the file repository, resolving qits
     * jars from the same volume.
     */
    @Test
    void theRunnerLibraryIsBuiltFromItsTagsWithItsTestScopeStripped() {
        String script = SeedPhases.runnerScript(List.of("2026.1023.80958", "2026.1023.52004"));

        assertThat(script).startsWith("set -eu\n");
        assertThat(script.split("rm -rf /cache/repository", -1)).hasSize(2);
        assertThat(script.lines().filter(line -> line.startsWith("cd ")).toList()).containsExactly(
                "cd /src@2026.1023.80958 && mvn -B -ntp -s /root/.m2/settings.xml deploy"
                        + " -DskipTests -Dmaven.repo.local=/cache/repository"
                        + " -Dqits.maven.repository.url=file:///repo"
                        + " -DaltDeploymentRepository=seed::default::file:///repo",
                "cd /src@2026.1023.52004 && mvn -B -ntp -s /root/.m2/settings.xml deploy"
                        + " -DskipTests -Dmaven.repo.local=/cache/repository"
                        + " -Dqits.maven.repository.url=file:///repo"
                        + " -DaltDeploymentRepository=seed::default::file:///repo");
        assertThat(script).contains("<url>file:///repo</url>").doesNotContain("qits::default::")
                .doesNotContain("<server>");
        // The strip comes before each tree's build, and never touches the root pom.
        assertThat(script).containsSubsequence(SeedPhases.stripTestDependencies("/src@2026.1023.80958"),
                "cd /src@2026.1023.80958");
        assertThat(SeedPhases.stripTestDependencies("/src@v")).contains("-mindepth 2")
                .contains("scope>test<");
    }

    /** The strip removes a test dependency and keeps the compile one beside it. */
    @Test
    void theTestScopeStripRemovesOnlyTestDependencies() throws Exception {
        Path module = Files.createDirectories(temp.resolve("tree/runner-protocol"));
        Files.writeString(temp.resolve("tree/pom.xml"), "<project><dependencyManagement>"
                + "<dependency><artifactId>managed</artifactId><scope>test</scope></dependency>"
                + "</dependencyManagement></project>", StandardCharsets.UTF_8);
        Files.writeString(module.resolve("pom.xml"), "<project><dependencies>\n"
                + "<dependency>\n<artifactId>keep</artifactId>\n</dependency>\n"
                + "<dependency>\n<artifactId>qits-ci-runner-protocol</artifactId>\n"
                + "<scope>test</scope>\n</dependency>\n"
                + "<dependency>\n<artifactId>also-kept</artifactId>\n<scope>compile</scope>\n"
                + "</dependency>\n</dependencies></project>", StandardCharsets.UTF_8);

        Process strip = new ProcessBuilder("sh", "-c",
                SeedPhases.stripTestDependencies(temp.resolve("tree").toString()))
                .redirectErrorStream(true).start();
        assertThat(strip.waitFor()).isZero();

        assertThat(Files.readString(module.resolve("pom.xml"))).contains("keep", "also-kept")
                .doesNotContain("qits-ci-runner-protocol");
        assertThat(Files.readString(temp.resolve("tree/pom.xml"))).contains("managed");
    }

    // --- what phase 1 resolves through, and what store-seed presents (qits-889) -----------------

    /**
     * <b>The phase-1 settings resolve qits jars from the volume and nothing else of the
     * platform's</b>: one exact-id mirror of {@code qits-maven} to {@code file:///repo}, Central
     * left as it is, and no {@code <server>} — nothing in phase 1 deploys to a server.
     */
    @Test
    void thePhaseOneSettingsMirrorTheQitsRepositoryToTheVolume() {
        String settings = SeedPhases.seedSettings();

        assertThat(settings).contains("<id>qits-maven</id>")
                .contains("<mirrorOf>qits-maven</mirrorOf>")
                .contains("<url>file:///repo</url>")
                .doesNotContain("<servers>").doesNotContain("central")
                .doesNotContain("qits-artifacts").doesNotContain("qits-platform-mirror");
    }

    /** {@code bearerOf} is still what the container masking reads a header value through. */
    @Test
    void theBearerOfAHeaderIsItsTokenAlone() {
        assertThat(SeedPhases.bearerOf(null)).isEmpty();
        assertThat(SeedPhases.bearerOf("Bearer t")).isEqualTo("t");
        assertThat(SeedPhases.bearerOf("t")).isEqualTo("t");
    }

    private static final String STORE = "http://prod-qits-artifacts:8080/artifacts/maven/maven";

    /**
     * <b>store-seed: the whole {@code eu/wohlben/qits} subtree, one PUT per file, the bearer on
     * every request.</b> Asked for first with a HEAD, so a coordinate the store holds is never
     * written again (immutable release paths answer different bytes with a 403); metadata never
     * sent (the store derives it and discards a client's); checksums with their file; poms last.
     */
    @Test
    void theStoreSeedScriptUploadsTheSubtreeUnderTheBearer() {
        String script = SeedPhases.storeSeedScript(STORE, "Bearer publish-token");

        assertThat(script).startsWith("set -eu\n")
                .contains("base='" + STORE + "'\n")
                .contains("cat > /tmp/auth <<'AUTH'\nAuthorization: Bearer publish-token\nAUTH\n")
                .contains("cd /repo\n")
                .contains("find eu/wohlben/qits -type f ! -name 'maven-metadata*'")
                .contains("! -name '*.sha1'")
                .contains("find eu/wohlben/qits -type f -name '*.pom' | sort >> /tmp/files");
        // Every request presents the header file: the HEAD and each PUT.
        assertThat(script).contains("-I -H @/tmp/auth \"$base/$f\"")
                .contains("-T \"$1\" -H @/tmp/auth \"$base/$1\"");
        // Held is left alone, absent is uploaded, anything else stops the phase.
        assertThat(script).contains("200) held=$((held + 1)); continue ;;")
                .contains("404) ;;")
                .contains("asking the store for $f answered $code\" >&2; exit 1");
        assertThat(script).contains("for sum in md5 sha1 sha256 sha512; do");
        // The bearer is in the header file and nowhere on a command line.
        assertThat(script.lines().filter(line -> line.contains("publish-token"))).hasSize(1);
        // Non-poms before poms: the pom is what a presence check reads as published.
        assertThat(script.indexOf("! -name '*.pom' | sort > /tmp/files"))
                .isLessThan(script.indexOf("-name '*.pom' | sort >> /tmp/files"));
    }

    /** No credential is no script: the store is never published to bare. */
    @Test
    void theStoreSeedRefusesToBuildABareUpload() {
        assertThatThrownBy(() -> SeedPhases.storeSeedScript(STORE, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("never published to bare");
        assertThatThrownBy(() -> SeedPhases.storeSeedScript(STORE, " "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** The container: on qits-net to reach the store, the volume read-only. */
    @Test
    void theStoreSeedContainerReadsTheVolumeOnQitsNet() {
        List<String> create = SeedPhases.storeSeedCommand(STORE, "Bearer t");

        assertThat(create).containsSubsequence("docker", "create", "--network", "qits-net")
                .containsSubsequence("-v", "qits-maven-seed:/repo:ro")
                .doesNotContain("qits-maven-cache:/cache");
        assertThat(create.getLast()).isEqualTo(SeedPhases.storeSeedScript(STORE, "Bearer t"));
    }

    /**
     * <b>The script, run.</b> A fake curl answers HEAD 200 for one held jar and 404 for the rest:
     * the held one and its checksum are not PUT, the absent ones are — with their checksums,
     * poms last — and metadata is never sent.
     */
    @Test
    void theStoreSeedScriptSkipsHeldFilesAndUploadsTheRest() throws Exception {
        Path repo = Files.createDirectories(temp.resolve("repo/eu/wohlben/qits/qits-a/1"));
        Files.writeString(repo.resolve("qits-a-1.jar"), "a");
        Files.writeString(repo.resolve("qits-a-1.jar.sha1"), "s");
        Files.writeString(repo.resolve("qits-a-1.pom"), "p");
        Files.writeString(repo.resolve("qits-a-1.pom.md5"), "m");
        Files.writeString(repo.getParent().resolve("maven-metadata.xml"), "x");
        Path held = Files.createDirectories(temp.resolve("repo/eu/wohlben/qits/qits-b/2"));
        Files.writeString(held.resolve("qits-b-2.jar"), "b");
        Files.writeString(held.resolve("qits-b-2.jar.sha1"), "s");
        Path bin = Files.createDirectories(temp.resolve("bin"));
        Path log = temp.resolve("curl.log");
        Files.writeString(bin.resolve("curl"), """
                #!/bin/sh
                url=""; put=""; head=""
                for a in "$@"; do
                  case "$a" in http*) url="$a" ;; -I) head=1 ;; esac
                done
                prev=""
                for a in "$@"; do [ "$prev" = "-T" ] && put="$a"; prev="$a"; done
                if [ -n "$head" ]; then
                  echo "HEAD $url" >> %1$s
                  case "$url" in *qits-b-2.jar) printf 200 ;; *) printf 404 ;; esac
                else
                  echo "PUT $put" >> %1$s
                fi
                """.formatted(log));
        bin.resolve("curl").toFile().setExecutable(true);
        String script = SeedPhases.storeSeedScript(STORE, "Bearer t")
                .replace("/tmp/", temp + "/")
                .replace("cd /repo\n", "cd " + temp.resolve("repo") + "\n");

        ProcessBuilder builder = new ProcessBuilder("sh", "-c", script).redirectErrorStream(true);
        builder.environment().put("PATH", bin + ":" + System.getenv("PATH"));
        Process run = builder.start();
        String out = new String(run.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(run.waitFor()).as(out).isZero();

        assertThat(out).contains("store-seed: 2 uploaded, 1 already held");
        List<String> puts = Files.readAllLines(log).stream()
                .filter(line -> line.startsWith("PUT ")).toList();
        assertThat(puts).containsExactly(
                "PUT eu/wohlben/qits/qits-a/1/qits-a-1.jar",
                "PUT eu/wohlben/qits/qits-a/1/qits-a-1.jar.sha1",
                "PUT eu/wohlben/qits/qits-a/1/qits-a-1.pom",
                "PUT eu/wohlben/qits/qits-a/1/qits-a-1.pom.md5");
        assertThat(Files.readString(log)).doesNotContain("maven-metadata");
    }

    /** An empty volume is said, not failed: there is nothing to upload. */
    @Test
    void theStoreSeedScriptSaysSoWhenTheVolumeIsEmpty() throws Exception {
        Path empty = Files.createDirectories(temp.resolve("empty"));
        String script = SeedPhases.storeSeedScript(STORE, "Bearer t")
                .replace("/tmp/", temp + "/").replace("cd /repo\n", "cd " + empty + "\n");

        Process run = new ProcessBuilder("sh", "-c", script).redirectErrorStream(true).start();
        String out = new String(run.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        assertThat(run.waitFor()).isZero();
        assertThat(out).contains(SeedPhases.STORE_SEED_EMPTY);
    }

    // --- the toolchain the ci-daemon builder image is made of ------------------------------------

    private static final String MUSL_DOCKERFILE = """
            #   x86_64-linux-musl-native.tgz  89080066 B  sha256 eb1db6f0f3c2bdbdbfb993d7ef7e2eeef82ac1259f6a6e1757c33a97dbcef3ad
            #   zlib-1.3.1.tar.gz              1512791 B  sha256 9a93b2b7dfdac77ceba5a558a580e74667dd6fede4585b91eefb60f03b72df23
            #   docker build --build-arg MUSL_URL=https://more.musl.cc/11.2.1/x86_64-linux-musl/x86_64-linux-musl-native.tgz \\
            #                --build-arg ZLIB_URL=https://github.com/madler/zlib/releases/download/v1.3.1/zlib-1.3.1.tar.gz \\
            ARG MUSL_URL=http://registry.dev.localhost:8080/artifacts/maven/maven/eu/wohlben/qits/toolchain/x86_64-linux-musl-native/11.2.1/x86_64-linux-musl-native-11.2.1.tgz
            ARG ZLIB_URL=http://registry.dev.localhost:8080/artifacts/maven/maven/eu/wohlben/qits/toolchain/zlib/1.3.1/zlib-1.3.1.tar.gz
            """;

    private static List<MuslToolchain.Tarball> bothTarballs() {
        return MuslToolchain.read(MUSL_DOCKERFILE);
    }

    /**
     * <b>Downloaded, CHECKED, and only then published.</b> The pin comes from the repository that
     * consumes the bytes, and it is verified before the upload rather than after: a registry is
     * forever, and a truncated ninety-megabyte download from a community host would be a builder
     * image that fails for everyone afterwards.
     */
    @Test
    void theToolchainSeedVerifiesEachTarballBeforeItPublishesIt() {
        String script = SeedPhases.toolchainScript(bothTarballs());

        assertThat(script).startsWith("set -eu\n");
        assertThat(script).contains(
                "curl -fsSL --retry 3 --retry-delay 5 -o /tmp/x86_64-linux-musl-native-11.2.1.tgz"
                        + " 'https://more.musl.cc/11.2.1/x86_64-linux-musl/"
                        + "x86_64-linux-musl-native.tgz'");
        assertThat(script).contains(
                "echo \"eb1db6f0f3c2bdbdbfb993d7ef7e2eeef82ac1259f6a6e1757c33a97dbcef3ad  "
                        + "/tmp/x86_64-linux-musl-native-11.2.1.tgz\" | sha256sum -c -");
        // The check is BEFORE the deploy for every tarball, not once at the end.
        assertThat(script.indexOf("sha256sum -c -"))
                .isLessThan(script.indexOf("deploy-file"));
    }

    /**
     * <b>Into the FILE repository only (qits-889)</b>, with no settings file: the plugin comes from
     * Maven Central direct and the store gets the tarballs from {@code store-seed}.
     */
    @Test
    void theToolchainReachesTheFileRegistryAndNothingElse() {
        String script = SeedPhases.toolchainScript(bothTarballs());

        assertThat(script).contains("-DrepositoryId=seed -Durl=file:///repo")
                .doesNotContain("-DrepositoryId=qits").doesNotContain("settings.xml")
                .doesNotContain("prod-qits-artifacts");
        // And the mount that line needs is the one the library seed writes through.
        assertThat(SeedPhases.SEED_REPO_VOLUME).isEqualTo("qits-maven-seed");
        // Nothing missing is an empty script — the phase skips before it gets here.
        assertThat(SeedPhases.toolchainScript(List.of())).doesNotContain("curl");
    }

    /**
     * The coordinates are the ARG default's, so the file lands exactly where the Dockerfile's own
     * {@code ADD} would have looked for it — and the plugin is pinned rather than resolved by
     * prefix, because a cold boot's answer to "which version is newest" is whatever the mirror
     * happened to cache.
     */
    @Test
    void theToolchainIsDeployedAtTheCoordinateTheDockerfileReads() {
        String script = SeedPhases.toolchainScript(bothTarballs());

        assertThat(script).contains("org.apache.maven.plugins:maven-deploy-plugin:3.1.2:deploy-file")
                .contains("-DgroupId=eu.wohlben.qits.toolchain")
                .contains("-DartifactId=x86_64-linux-musl-native -Dversion=11.2.1 -Dpackaging=tgz")
                .contains("-DartifactId=zlib -Dversion=1.3.1 -Dpackaging=tar.gz")
                .contains("-Dmaven.repo.local=/cache/repository");
    }

    /**
     * <b>ONE VALUE, TWO SPELLINGS, and the pair is the whole credential.</b> The {@code idp-clients}
     * phase records a secret under the APPLICATION — {@code qits-edge}, which is the key
     * qits-deployments' {@code pd_resource} registry uses — and the generated files read it under
     * the same name, because a placeholder cannot be spelled with an environment name the template
     * does not know yet. A pair that drifted apart is an idp holding a secret that nothing on this
     * platform presents.
     */
    @Test
    void theEdgesSecretIsRecordedByApplicationAndReadByApplication() {
        Boot boot = new Boot(TestConfig.from(Map.of("QITS_ENV_NAME", "prod",
                "QITS_DOMAIN", "qits-dev.eu", "QITS_PUBLIC_IP", "203.0.113.7")),
                new RunLog(temp.resolve("run.log")));
        boot.state.serviceClientSecrets.put(PlatformModel.application("edge"), "s3cr3t");

        Map<String, String> tokens = new SeedPhases(boot).tokens();

        assertThat(tokens).containsEntry("IDP_CLIENT_SECRET_EDGE", "s3cr3t");
        // And the id beside it is the WIRE ALIAS, which the model already answers: there is no
        // second token for it, because a second spelling could not follow a plane change.
        assertThat(tokens).containsEntry("ALIAS_EDGE", "prod-qits-edge");
        // AND NO BROWSER NAME TRAVELS WITH IT ANY MORE. Six tokens did — the door, the login host,
        // the return-host allow-list, the cookie domain, the rp id and the ceremony's origins — and
        // they were the fan-out itself: six readings of the ONE domain this run is told, rendered
        // into two files where each could go stale on its own. Two did, and sign-in broke. The
        // domain is rendered once, for qits-deployments, which propagates it as QITS_DOMAIN.
        assertThat(tokens).doesNotContainKeys("WEBAUTHN_RP_ID", "WEBAUTHN_ORIGINS",
                "PUBLIC_ORIGIN", "IDP_ORIGIN", "BROWSER_HOSTS", "SESSION_COOKIE_DOMAIN");
    }

    /**
     * <b>A DOMAIN PLATFORM RENDERS ITS DOMAIN ONCE, AND AS THE BARE DOMAIN.</b> Every browser name
     * this platform serves is derived from it — the door {@code qits.<domain>}, the login host
     * {@code idp.<env>.qits.<domain>}, the return-host list, the cookie domain and the passkey rp
     * id — and the derivation belongs to the service that serves the name, not to this run. So the
     * one thing a token carries is the value the owner stated, for qits-deployments, which puts it
     * in every container it deploys.
     * <p>
     * <b>This is what the sign-in break bought.</b> The edge's allow-list and the idp's were
     * composed separately here and drifted apart; nothing composed separately can drift when there
     * is only one value.
     */
    @Test
    void aDomainPlatformStatesItsDomainOnceForTheDeployerToPropagate() {
        Boot boot = new Boot(TestConfig.from(Map.of("QITS_ENV_NAME", "dev",
                "QITS_DOMAIN", "qits-dev.eu", "QITS_PUBLIC_IP", "203.0.113.7")),
                new RunLog(temp.resolve("run.log")));

        Map<String, String> tokens = new SeedPhases(boot).tokens();

        // The BARE domain, in both files. Not a door, not an origin, not an authority with a port:
        // the derivations are the consuming services' and each needs the unqualified value.
        assertThat(tokens.get("SEED_DOMAIN")).contains("QITS_DOMAIN: qits-dev.eu");
        assertThat(tokens.get("DEPLOYMENTS_DOMAIN_ARGS")).contains(
                "qits.deployments.extras.qits-deployments.env.QITS_DOMAIN=qits-dev.eu");
        // And no composed spelling beside it.
        assertThat(tokens).doesNotContainKeys("WEBAUTHN_RP_ID", "WEBAUTHN_ORIGINS",
                "PUBLIC_ORIGIN", "IDP_ORIGIN", "BROWSER_HOSTS", "SESSION_COOKIE_DOMAIN");
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
     * is the one this cost. Same answer as the version the deploy phase asks for, by construction
     * — both go through PlatformModel.newestRelease over the version-sorted merged tags.
     */
    @Test
    void aDeployableStandsAtItsNewestRelease() {
        assertThat(SeedPhases.bootIdentity("ci", TAGS)).isEqualTo("2026.812.153438");
    }

    /** A release publisher's output is a coordinate consumers pin, so it follows its tag too. */
    @Test
    void aReleasePublisherStandsAtItsNewestRelease() {
        assertThat(SeedPhases.bootIdentity("eventstream", TAGS))
                .isEqualTo("2026.812.153438");
    }

    /**
     * SEEDED AND NOTHING ELSE: main, however many tags it has. qits-oci's step images are consumed
     * by bare local tag and rebuilt every boot, so nothing pins a version of them and their tags go
     * stale unnoticed — the one whose newest tag predated the `build` user the step sandbox needs.
     */
    @Test
    void aSeededSourceWithNoVersionIdentityStaysOnMain() {
        assertThat(SeedPhases.bootIdentity("oci", TAGS)).isEmpty();
        // The SPA seed sources are the same shape: a placeholder bundle, then the real client from
        // the pipeline.
        assertThat(SeedPhases.bootIdentity("spa-projects", TAGS)).isEmpty();
    }

    /** In scope but never released: main, which is what an empty answer means to the caller. */
    @Test
    void aRepositoryWithNoReleaseStaysOnMain() {
        assertThat(SeedPhases.bootIdentity("ci", List.of())).isEmpty();
        // A stray tag is not a release.
        assertThat(SeedPhases.bootIdentity("ci", List.of("latest"))).isEmpty();
    }

    /**
     * <b>{@code --ship-mains} cannot reach this any more, and that is the assertion.</b> The flag
     * used to leave every checkout on main AND point the deploy ref there, so seed and successor
     * still agreed. There is no deploy ref: the successor is the last release whatever the seed was
     * built from, and a seed from main beside it applies migrations that successor has never heard
     * of. The identity takes the name and the tags, and nothing else may be added to it.
     */
    @Test
    void nothingButTheNameAndTheTagsDecidesWhatACheckoutStandsAt() {
        assertThat(SeedPhases.bootIdentity("ci", TAGS)).isEqualTo("2026.812.153438");
        assertThat(SeedPhases.bootIdentity("eventstream", TAGS)).isEqualTo("2026.812.153438");
        assertThat(SeedPhases.bootIdentity("oci", TAGS)).isEmpty();
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
                "qits-system", "s3cr3t");

        String auth = Base64.getEncoder().encodeToString(
                "qits-system:s3cr3t".getBytes(StandardCharsets.UTF_8));
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
        assertThat(SeedPhases.alreadyServing("qits-idp", "qits-pd-qits-idp-",
                List.of(), List.of("qits-idp"))).contains("qits-idp");
    }

    /** Nothing of this application anywhere: the phase starts its own. */
    @Test
    void anotherApplicationsServiceIsNotThisOne() {
        assertThat(SeedPhases.alreadyServing(ARTIFACTS, PD_ARTIFACTS,
                List.of("prod-qits-ci"), List.of("qits_prod-qits-ci"))).isEmpty();
    }

    // --- the IPv6 loopback landmine ---------------------------------------------------------------

    /**
     * <b>The rule is one string in one place</b>: what the phase installs and what its failure
     * tells a person to type. The REJECT is what makes a client fall back to IPv4 at once; a DROP
     * would leave it hanging exactly as the ingress mesh's own accept does.
     */
    @Test
    void theIpv6LoopbackRuleRejectsWithAReset() {
        assertThat(SeedPhases.ip6tablesReject(8080))
                .containsExactly("ip6tables", "-I", "INPUT", "-i", "lo", "-p", "tcp",
                        "--dport", "8080", "-j", "REJECT", "--reject-with", "tcp-reset");
    }

    /**
     * <b>Idempotent by {@code -C}, and installed whatever the probe says.</b> The phase runs in the
     * plan's first minutes, forty phases before the swarm ingress binds that port — so nothing is
     * accepting v6 there yet, and a probe-and-repair would find nothing to do and leave the
     * landmine armed for the run that needs it. The probes are for the note.
     */
    @Test
    void theHelperChecksBeforeItInsertsAndInsertsWhateverTheProbeSaid() {
        String script = SeedPhases.ipv6LoopbackScript(8080);

        assertThat(script).contains("ip6tables -C INPUT -i lo -p tcp --dport 8080 -j REJECT "
                + "--reject-with tcp-reset");
        assertThat(script).contains("ip6tables -I INPUT -i lo -p tcp --dport 8080 -j REJECT "
                + "--reject-with tcp-reset || exit 1");
        // The check comes first, so a rerun stacks no duplicate rule.
        assertThat(script.indexOf("ip6tables -C")).isLessThan(script.indexOf("ip6tables -I"));
        // The insert is not inside any probe test: the probe only feeds the note.
        assertThat(script).contains("before=$(probe)").contains("after=$(probe)")
                .contains(SeedPhases.IPV6_MARKER);
        // The port is the edge's, everywhere in the script.
        assertThat(SeedPhases.ipv6LoopbackScript(9090)).contains("--dport 9090")
                .contains("nc -w 2 ::1 9090").doesNotContain("8080");
    }

    /**
     * <b>The whole certificate volume belongs to the edge, because the edge WRITES to it.</b> It
     * orders its real certificate itself and installs it as a new directory under
     * {@code versions/} — so a {@code versions/} left root-owned by {@code mkdir -p} is an
     * {@code AccessDeniedException} once a minute and a platform that never leaves the placeholder.
     * Chowning the two files was the half that only let it read.
     */
    @Test
    void theEdgeOwnsEveryDirectoryOfItsCertificateVolume() {
        String script = SeedPhases.placeholderCertificateScript("wohlben.dev");

        assertThat(script).contains("chown -R 1001:0 /cert\n");
        // The narrow pair that left versions/ behind is gone.
        assertThat(script).doesNotContain("chown 1001:0 /cert\n")
                .doesNotContain("chown -R 1001:0 /cert/versions/placeholder");
        // And the recursive chown is after the files exist, or it owns nothing.
        assertThat(script.indexOf("openssl req")).isLessThan(script.indexOf("chown -R"));
        assertThat(script).contains("subjectAltName=DNS:wohlben.dev,DNS:*.wohlben.dev");
    }

    // --- the bootstrap ingress, on a rerun ------------------------------------------------------

    /**
     * The deployer's edge is a service under the bare alias (the swarm driver's shape) or a
     * {@code qits-pd-} container (the docker driver's). The seed stack's own edge,
     * {@code qits_<alias>}, is not the deployer's: that is the cold boot's, and the ingress stands
     * beside it for the whole local-build step.
     */
    @Test
    void onlyADeployerManagedEdgeCountsAsLive() {
        assertThat(SeedPhases.deployerManagedEdge(List.of(), List.of("prod-qits-edge"), "prod"))
                .isTrue();
        assertThat(SeedPhases.deployerManagedEdge(List.of("qits-pd-prod-qits-edge-1a2b3c4d"),
                List.of(), "prod")).isTrue();
        assertThat(SeedPhases.deployerManagedEdge(List.of("qits_prod-qits-edge.1.xyz"),
                List.of("qits_prod-qits-edge"), "prod")).isFalse();
        // Another application's deployment is not the edge's.
        assertThat(SeedPhases.deployerManagedEdge(List.of(), List.of("prod-qits-ci"), "prod"))
                .isFalse();
    }

    /**
     * <b>A RERUN OVER A DEPLOYED EDGE STARTS NO INGRESS</b>: neither phase does anything but look,
     * because a second door would bind 80/443 against the platform's own. The edge's seed deploy is
     * in the middle of the boot now, so this is the common rerun.
     */
    @Test
    void aRerunOverADeployedEdgeStartsNoBootstrapIngress() throws Exception {
        ScriptedRunner runner = new ScriptedRunner(command ->
                String.join(" ", command).startsWith("docker service ls")
                        ? ScriptedRunner.ok("prod-qits-edge", "qits_prod-qits-githost")
                        : ScriptedRunner.ok());
        Boot boot = new Boot(TestConfig.from(Map.of()), new RunLog(temp.resolve("run.log")), runner);
        SeedPhases seed = new SeedPhases(boot);

        for (var phase : List.of(seed.bootstrapIngressPrepare(), seed.bootstrapIngressStart())) {
            assertThatThrownBy(() -> phase.action().run(new CiLogStreamTest.Recorder()))
                    .as(phase.id())
                    .isInstanceOf(PhaseSkipped.class)
                    .hasMessageContaining("prod-qits-edge is live");
        }
        assertThat(runner.lines()).allMatch(line -> line.startsWith("docker ps --format")
                || line.startsWith("docker service ls"));
        assertThat(boot.state.bootstrapIngressEnvFile).isNull();
    }

    // --- the workspace-side images (qits-889) ----------------------------------------------------

    private static String pom(String property, String version) {
        return "<project><properties><" + property + ">" + version + "</" + property
                + "></properties></project>";
    }

    /** A checkout that answers the release files the closure reads, and nothing else. */
    private static SeedPhases.ReleaseFiles releases(Map<String, String> files) {
        return (repository, version, path) -> files.get(repository + "@" + version + ":" + path);
    }

    /**
     * <b>Every version of every workspace-side image, closed over its bases</b>: qits/workspace at
     * each consumer's pin (two consumers sharing one version build it once), qits/project-agent at
     * qits-projects', the workspace bases their Dockerfiles name at those tags, and the browser
     * base and qits binary each base names.
     */
    @Test
    void theWorkspaceImagesAreClosedOverTheirBases() {
        Map<String, String> poms = Map.of(
                "workspaces", pom(SeedPhases.WORKSPACE_IMAGE_PIN, "10"),
                "projects", pom(SeedPhases.WORKSPACE_IMAGE_PIN, "10")
                        .replace("</properties>", "<" + SeedPhases.AGENT_IMAGE_PIN + ">20</"
                                + SeedPhases.AGENT_IMAGE_PIN + "></properties>"),
                "system", pom(SeedPhases.WORKSPACE_IMAGE_PIN, "11"));
        Map<String, String> files = new java.util.HashMap<>();
        files.put("workspace-daemon@10:docker/Dockerfile",
                "ARG WORKSPACE_BASE=registry.qits.${QITS_DOMAIN}/qits/workspace-base:1\n");
        files.put("workspace-daemon@11:docker/Dockerfile",
                "ARG WORKSPACE_BASE=registry.qits.${QITS_DOMAIN}/qits/workspace-base:2\n");
        files.put("projects-daemon@20:docker/Dockerfile.projects",
                "ARG BASE=qits/workspace-base:1\n");
        for (String base : List.of("1", "2")) {
            files.put("oci-workspace@" + base + ":Dockerfile", "ARG BROWSER_BASE=registry.qits."
                    + "${QITS_DOMAIN}/qits/build-images/node-browser-base:99\n");
            files.put("oci-workspace@" + base + ":pom.xml", pom(SeedPhases.CLI_PIN, "c" + base));
        }

        SeedPhases.WorkspaceImages closure = SeedPhases.workspaceImages(poms, releases(files));

        assertThat(closure.workspaces()).containsExactlyInAnyOrderEntriesOf(
                Map.of("10", "1", "11", "2"));
        assertThat(closure.agents()).containsExactlyInAnyOrderEntriesOf(Map.of("20", "1"));
        assertThat(closure.bases()).containsExactlyInAnyOrderEntriesOf(
                Map.of("1", "99", "2", "99"));
        assertThat(closure.browserBases()).containsExactly("99");
        assertThat(closure.cli()).containsExactly("c1", "c2");
    }

    /** A consumer that pins no workspace image is owed none; a tag that is not there stops the boot. */
    @Test
    void aMissingReleaseOfAPinnedImageStopsTheBoot() {
        assertThat(SeedPhases.workspaceImages(Map.of("system", "<project/>"),
                releases(Map.of())).workspaces()).isEmpty();
        assertThatThrownBy(() -> SeedPhases.workspaceImages(
                Map.of("workspaces", pom(SeedPhases.WORKSPACE_IMAGE_PIN, "10")),
                releases(Map.of())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("qits-workspace-daemon 10")
                .hasMessageContaining("git fetch --tags");
        assertThatThrownBy(() -> SeedPhases.workspaceImages(
                Map.of("workspaces", pom(SeedPhases.WORKSPACE_IMAGE_PIN, "10")),
                releases(Map.of("workspace-daemon@10:docker/Dockerfile",
                        "ARG WORKSPACE_BASE=${BASE}\n"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("names no versioned base");
    }

    /** A base's layout directory is named after its image, one per version. */
    @Test
    void theLayoutOfABaseIsNamedAfterItsImage() {
        assertThat(SeedPhases.layout("qits/build-images/node-browser-base", "9"))
                .isEqualTo("node-browser-base@9");
        assertThat(SeedPhases.layout("qits/workspace-base", "1")).isEqualTo("workspace-base@1");
        assertThat(SeedPhases.image("qits/workspace", "1")).isEqualTo("qits/workspace:1");
    }
}
