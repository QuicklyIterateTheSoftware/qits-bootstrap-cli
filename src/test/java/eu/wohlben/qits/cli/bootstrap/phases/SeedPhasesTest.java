package eu.wohlben.qits.cli.bootstrap.phases;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

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

    @Test
    void aPathThatIsNotThereIsNotAnEmptyDirectory() throws IOException {
        // Absent is its own answer — the sources phase clones from the org for both, but the two
        // must not be confused: only a directory can be read for what is in it.
        assertThat(SeedPhases.isEmptyDirectory(temp.resolve("nothing"))).isFalse();
        Files.writeString(temp.resolve("a-file"), "x", StandardCharsets.UTF_8);
        assertThat(SeedPhases.isEmptyDirectory(temp.resolve("a-file"))).isFalse();
    }
}
