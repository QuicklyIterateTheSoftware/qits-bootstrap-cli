package eu.wohlben.qits.cli.bootstrap.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The CLI lives inside the wrapper, so it can find the wrapper. These are the walk's claims; the
 * old default was {@code .}, which was right from exactly one directory.
 */
class WrapperDirTest {

    @TempDir
    Path temp;

    /** A wrapper checkout, as the marker sees it: submodules named in .gitmodules. */
    private Path wrapper(String name) throws IOException {
        Path root = Files.createDirectories(temp.resolve(name));
        Files.writeString(root.resolve(".gitmodules"), """
                [submodule "qits-platform-artifacts"]
                	path = services/qits-platform-artifacts
                	url = https://example.invalid/qits-platform-artifacts.git
                """, StandardCharsets.UTF_8);
        return root;
    }

    @Test
    void theCliFindsTheWrapperItLivesIn() throws IOException {
        Path root = wrapper("qits-qits");
        Path here = Files.createDirectories(root.resolve("cli/qits-cli-bootstrap"));

        assertThat(WrapperDir.detect(here)).contains(root);
    }

    @Test
    void aServicesDirectoryIsMarkerEnoughOnItsOwn() throws IOException {
        // A checkout whose .gitmodules is missing or unreadable is still a wrapper if the
        // submodule is on disk — which is what a `git submodule update --init` leaves behind.
        Path root = Files.createDirectories(temp.resolve("no-gitmodules"));
        Files.createDirectories(root.resolve("services/qits-platform-artifacts"));

        assertThat(WrapperDir.detect(root.resolve("services/qits-platform-artifacts")))
                .contains(root);
    }

    @Test
    void aGitmodulesNamingSomethingElseIsNotAWrapper() throws IOException {
        Path other = Files.createDirectories(temp.resolve("someone-elses-repo"));
        Files.writeString(other.resolve(".gitmodules"), """
                [submodule "vendor/thing"]
                	path = vendor/thing
                	url = https://example.invalid/thing.git
                """, StandardCharsets.UTF_8);

        assertThat(WrapperDir.isWrapper(other)).isFalse();
        assertThat(WrapperDir.detect(other)).isEmpty();
    }

    @Test
    void theFirstHitWinsWhenAWrapperSitsInsideAnother() throws IOException {
        Path outer = wrapper("outer");
        Path inner = Files.createDirectories(outer.resolve("nested/inner"));
        Files.writeString(inner.resolve(".gitmodules"),
                "[submodule \"a\"]\n\tpath = services/qits-platform-artifacts\n",
                StandardCharsets.UTF_8);

        assertThat(WrapperDir.detect(inner.resolve("cli"))).contains(inner);
    }

    @Test
    void anExplicitValueWinsAndIsNotSecondGuessed() throws IOException {
        Path root = wrapper("qits-qits");
        Path elsewhere = Files.createDirectories(temp.resolve("elsewhere"));

        WrapperDir.Resolved resolved =
                WrapperDir.resolve(Optional.of(elsewhere.toString()), root);

        assertThat(resolved.path()).isEqualTo(elsewhere);
        assertThat(resolved.how()).isEqualTo("configured");
    }

    @Test
    void aBlankValueIsNoValue() throws IOException {
        Path root = wrapper("qits-qits");

        WrapperDir.Resolved resolved =
                WrapperDir.resolve(Optional.of("   "), root.resolve("cli/qits-cli-bootstrap"));

        assertThat(resolved.path()).isEqualTo(root);
        assertThat(resolved.how()).startsWith("detected from ");
    }

    @Test
    void howItWasFoundIsPartOfTheAnswer() throws IOException {
        Path root = wrapper("qits-qits");
        Path here = Files.createDirectories(root.resolve("cli/qits-cli-bootstrap"));

        WrapperDir.Resolved resolved = WrapperDir.resolve(Optional.empty(), here);

        assertThat(resolved.path()).isEqualTo(root);
        assertThat(resolved.how()).isEqualTo("detected from " + here);
    }

    @Test
    void nothingFoundNamesBothWaysOut() throws IOException {
        Path nowhere = Files.createDirectories(temp.resolve("nowhere/deep"));

        assertThatThrownBy(() -> WrapperDir.resolve(Optional.empty(), nowhere))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("run this from inside the qits-qits checkout")
                .hasMessageContaining("QITS_WRAPPER_DIR");
    }

    @Test
    void aBareMachineIsAnsweredWithWhereTheWrapperWillBeCloned() throws IOException {
        // The cold start. Nothing to walk to and nothing configured, so the answer is the working
        // directory's own qits-qits — a path that does not exist yet, which the boot's `wrapper`
        // phase clones into.
        Path bare = Files.createDirectories(temp.resolve("bare"));

        WrapperDir.Resolved resolved = WrapperDir.resolveOrClone(Optional.empty(), bare);

        assertThat(resolved.path()).isEqualTo(bare.resolve("qits-qits"));
        assertThat(resolved.path()).doesNotExist();
        assertThat(resolved.how()).isEqualTo("not on this machine yet");
    }

    @Test
    void aCheckoutThatIsThereBeatsTheColdAnswer() throws IOException {
        Path root = wrapper("qits-qits");
        Path here = Files.createDirectories(root.resolve("cli/qits-cli-bootstrap"));

        assertThat(WrapperDir.resolveOrClone(Optional.empty(), here).path()).isEqualTo(root);
        assertThat(WrapperDir.resolveOrClone(Optional.of(root.toString()), temp).path())
                .isEqualTo(root);
    }
}
