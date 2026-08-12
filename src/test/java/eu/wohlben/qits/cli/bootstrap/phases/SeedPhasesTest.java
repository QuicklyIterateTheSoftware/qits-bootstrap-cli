package eu.wohlben.qits.cli.bootstrap.phases;

import eu.wohlben.qits.cli.bootstrap.config.TestConfig;
import eu.wohlben.qits.cli.bootstrap.proc.RunLog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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

    /**
     * The seed set and its order. A jar missing here fails a seed image build minutes in, naming a
     * version nobody ever pushed; a jar in the wrong place fails the deploy that needs it.
     */
    @Test
    void theSeedLibrariesAreEveryJarASeedImageResolves() {
        assertThat(SeedPhases.SEED_LIBRARIES).containsExactly(
                "blobstore", "registries", "integrations-quarkus", "eventstream", "githost",
                "containers");
        // Dependency order, and every pair is forced by a pom: qits-registries is written against
        // qits-blobstore's entities, qits-eventstream against qits-db-core (a module of
        // qits-integrations-quarkus), qits-githost-events against qits-eventstream, and the
        // orchestrator's two libraries against qits-db-core and qits-eventstream both.
        assertThat(SeedPhases.SEED_LIBRARIES).containsSubsequence("blobstore", "registries");
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

    @Test
    void aPathThatIsNotThereIsNotAnEmptyDirectory() throws IOException {
        // Absent is its own answer — the sources phase clones from the org for both, but the two
        // must not be confused: only a directory can be read for what is in it.
        assertThat(SeedPhases.isEmptyDirectory(temp.resolve("nothing"))).isFalse();
        Files.writeString(temp.resolve("a-file"), "x", StandardCharsets.UTF_8);
        assertThat(SeedPhases.isEmptyDirectory(temp.resolve("a-file"))).isFalse();
    }
}
