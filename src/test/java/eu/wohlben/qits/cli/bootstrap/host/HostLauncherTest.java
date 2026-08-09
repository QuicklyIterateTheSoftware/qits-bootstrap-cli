package eu.wohlben.qits.cli.bootstrap.host;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Where the payload image is built from. The rest of the host half needs a daemon; this one
 * question does not, and it is the one a COLD machine answers differently: there is no wrapper
 * until the run clones one, so the only checkout on the box is this CLI's own.
 */
class HostLauncherTest {

    @TempDir
    Path temp;

    /** A checkout of this CLI, as the launcher recognises one: it has the Dockerfile. */
    private Path cliCheckout(String at) throws IOException {
        Path root = Files.createDirectories(temp.resolve(at));
        Path dockerfile = root.resolve(PayloadImage.DOCKERFILE);
        Files.createDirectories(dockerfile.getParent());
        Files.writeString(dockerfile, "FROM scratch\n", StandardCharsets.UTF_8);
        return root;
    }

    @Test
    void theWrappersOwnSubmoduleIsTheOrdinaryAnswer() throws IOException {
        Path wrapper = Files.createDirectories(temp.resolve("qits-qits"));
        Path cli = cliCheckout("qits-qits/" + HostLauncher.CLI_PATH);

        assertThat(HostLauncher.imageContext(wrapper, temp)).contains(cli);
    }

    @Test
    void aCloneOfThisCliAloneAnswersItOnAColdMachine() throws IOException {
        // curl … | bash: this repository is cloned, built and run, and the wrapper does not exist
        // yet. The wrapper path handed in is where the run WILL clone it.
        Path cli = cliCheckout("qits-cli-bootstrap");

        assertThat(HostLauncher.imageContext(temp.resolve("qits-qits"), cli)).contains(cli);
        // From a directory inside it, too: the walk goes up.
        Path deeper = Files.createDirectories(cli.resolve("target"));
        assertThat(HostLauncher.imageContext(temp.resolve("qits-qits"), deeper)).contains(cli);
    }

    @Test
    void aCloneBesideTheWorkingDirectoryAnswersItToo() throws IOException {
        // The same cold clone, from a script that stepped back out of it so the wrapper is cloned
        // beside this CLI rather than inside it.
        Path cli = cliCheckout("work/qits-cli-bootstrap");
        Path workDir = temp.resolve("work");

        assertThat(HostLauncher.imageContext(workDir.resolve("qits-qits"), workDir)).contains(cli);
    }

    @Test
    void aWrapperWithUninitialisedSubmodulesIsNotOneAndSaysSoByFindingNothing()
            throws IOException {
        // The gitlink is there, the checkout is not, so there is no Dockerfile to build from. The
        // launcher's message then names every place it looked.
        Path wrapper = Files.createDirectories(temp.resolve("qits-qits/" + HostLauncher.CLI_PATH));

        assertThat(HostLauncher.imageContext(wrapper.getParent().getParent(), temp)).isEmpty();
    }
}
