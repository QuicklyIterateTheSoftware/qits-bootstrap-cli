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
        Files.createDirectories(root.resolve("services/qits-artifacts"));

        assertThat(WrapperDir.detect(root.resolve("services/qits-artifacts"))).contains(root);
    }

    /**
     * <b>Both spellings of the store's directory are a wrapper.</b> The repository was
     * qits-artifacts, became qits-platform-artifacts in the 2026-08-08 rename, and went back when
     * the byte-plane split moved the caches out — so a working copy on either side of either rename
     * has to be recognised. Asking for one of them turns somebody's checkout into "no wrapper
     * repository at or above …".
     */
    @Test
    void thePreSplitSpellingIsStillAWrapper() throws IOException {
        Path root = Files.createDirectories(temp.resolve("pre-split"));
        Files.createDirectories(root.resolve("services/qits-platform-artifacts"));

        assertThat(WrapperDir.isWrapper(root)).isTrue();
    }

    /**
     * <b>The component layout is a wrapper too, and this is the test the flip would have broken.</b>
     * Detection asks which REPOSITORY the file declares, not which directory it sits in — the old
     * grep was for {@code services/qits-}, so a reorganised wrapper answered "no wrapper repository
     * at or above …" on every machine and every un-configured run died in preflight.
     */
    @Test
    void aComponentLayoutWrapperIsFoundJustTheSame() throws IOException {
        Path root = Files.createDirectories(temp.resolve("components-layout"));
        Files.writeString(root.resolve(".gitmodules"), """
                [submodule "qits-artifacts"]
                	path = components/qits-artifacts/qits-artifacts
                	url = ../qits-artifacts.git
                	ignore = all
                	branch = main
                	update = merge
                """, StandardCharsets.UTF_8);
        Path here = Files.createDirectories(
                root.resolve("components/qits-bootstrap/qits-cli-bootstrap"));

        assertThat(WrapperDir.isWrapper(root)).isTrue();
        assertThat(WrapperDir.detect(here)).contains(root);
    }

    /**
     * <b>The renamed store is a wrapper, and so is every checkout that predates the rename.</b>
     * qits-artifacts became qits-artifacts-service in the phase-2 renames; a marker that learned the
     * new name and dropped the old ones would report "no wrapper repository at or above …" for a
     * checkout sitting right there.
     */
    @Test
    void theRenamedStoreIsTheMarkerAndTheOldNamesStillAre() throws IOException {
        Path root = Files.createDirectories(temp.resolve("renamed"));
        Files.writeString(root.resolve(".gitmodules"), """
                [submodule "qits-artifacts-service"]
                	path = components/qits-artifacts/qits-artifacts-service
                	url = ../qits-artifacts-service.git
                """, StandardCharsets.UTF_8);

        assertThat(WrapperDir.isWrapper(root)).isTrue();
        assertThat(WrapperDir.detect(Files.createDirectories(
                root.resolve("components/qits-bootstrap/qits-bootstrap-cli")))).contains(root);

        // And the same checkout with no readable .gitmodules: the directory arm knows it too.
        Path onDisk = Files.createDirectories(temp.resolve("renamed-no-gitmodules"));
        Files.createDirectories(onDisk.resolve("components/qits-artifacts/qits-artifacts-service"));

        assertThat(WrapperDir.isWrapper(onDisk)).isTrue();
    }

    /**
     * The same wrapper with no readable {@code .gitmodules}: the directory on disk is the second
     * arm, and it has to know both layouts as well as every spelling of the store.
     */
    @Test
    void aComponentDirectoryIsMarkerEnoughOnItsOwn() throws IOException {
        Path root = Files.createDirectories(temp.resolve("no-gitmodules-components"));
        Path store = Files.createDirectories(
                root.resolve("components/qits-artifacts/qits-artifacts"));

        assertThat(WrapperDir.detect(store)).contains(root);
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

    /**
     * <b>A platform repository that embeds another one is NOT a wrapper.</b> Every SPA-hosting
     * service carries its frontend as a submodule at {@code service/src/main/webui}, and qits-ci
     * carries qits-eventstream — so "declares a qits- submodule" would make each of them answer the
     * walk before the real wrapper above it did. The store is the marker for that reason.
     */
    @Test
    void aServiceEmbeddingItsFrontendIsNotAWrapper() throws IOException {
        Path service = Files.createDirectories(temp.resolve("qits-ci"));
        Files.writeString(service.resolve(".gitmodules"), """
                [submodule "qits-spa-ci"]
                	path = service/src/main/webui
                	url = ../qits-spa-ci.git
                [submodule "qits-eventstream"]
                	path = lib/qits-eventstream
                	url = ../qits-eventstream.git
                """, StandardCharsets.UTF_8);

        assertThat(WrapperDir.isWrapper(service)).isFalse();
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
