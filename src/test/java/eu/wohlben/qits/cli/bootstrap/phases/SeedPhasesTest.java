package eu.wohlben.qits.cli.bootstrap.phases;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The phases shell docker and git, so a real bootstrap is their test. This one question is pure and
 * decides whether a COLD start gets past its third phase: a wrapper cloned without its submodules
 * has an empty directory at every gitlink, and the sources phase has to read that as "no local
 * checkout" rather than as "something is standing in its place".
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

    @Test
    void aPathThatIsNotThereIsNotAnEmptyDirectory() throws IOException {
        // Absent is its own answer — the sources phase clones from the org for both, but the two
        // must not be confused: only a directory can be read for what is in it.
        assertThat(SeedPhases.isEmptyDirectory(temp.resolve("nothing"))).isFalse();
        Files.writeString(temp.resolve("a-file"), "x", StandardCharsets.UTF_8);
        assertThat(SeedPhases.isEmptyDirectory(temp.resolve("a-file"))).isFalse();
    }
}
