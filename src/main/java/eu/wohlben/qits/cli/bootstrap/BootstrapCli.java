package eu.wohlben.qits.cli.bootstrap;

import io.quarkus.picocli.runtime.annotations.TopCommand;
import jakarta.inject.Inject;
import picocli.CommandLine;

import java.util.concurrent.Callable;

/**
 * Two modes, and they are opposites: {@code bootstrap} brings the platform up on this machine's
 * docker daemon through the platform's own pipeline, {@code unwrap} takes it off again.
 * <p>
 * Called with no mode, it bootstraps — that is the thing people came here to do.
 */
@TopCommand
@CommandLine.Command(
        name = "qits",
        mixinStandardHelpOptions = true,
        subcommands = {BootstrapCommand.class, UnwrapCommand.class, LoginCommand.class, GitCredentialCommand.class},
        description = "Bring the qits platform up on this workstation, and take it down again.")
public class BootstrapCli implements Callable<Integer> {

    @Inject
    BootstrapCommand bootstrap;

    @Override
    public Integer call() throws Exception {
        return bootstrap.call();
    }
}
