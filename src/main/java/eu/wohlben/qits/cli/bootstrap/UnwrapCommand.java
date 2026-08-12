package eu.wohlben.qits.cli.bootstrap;

import eu.wohlben.qits.cli.bootstrap.config.BootstrapConfig;
import eu.wohlben.qits.cli.bootstrap.config.OverridableConfig;
import eu.wohlben.qits.cli.bootstrap.engine.PhaseEngine;
import eu.wohlben.qits.cli.bootstrap.engine.RunResult;
import eu.wohlben.qits.cli.bootstrap.host.HostLauncher;
import eu.wohlben.qits.cli.bootstrap.phases.Boot;
import eu.wohlben.qits.cli.bootstrap.phases.UnwrapPhases;
import eu.wohlben.qits.cli.bootstrap.proc.RunLog;
import eu.wohlben.qits.cli.bootstrap.ui.Ui;
import eu.wohlben.qits.cli.bootstrap.ui.UiFactory;
import io.quarkus.runtime.annotations.CommandLineArguments;
import jakarta.inject.Inject;
import picocli.CommandLine;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * The opposite of bootstrap: containers, images and networks go, and the volumes stay.
 * <p>
 * The volumes are where the platform's databases, the registry's blobs and the git host's
 * repositories live, so removing them is the one decision this command asks for out loud. There
 * are three answers, and the middle one is the re-bootstrap's: {@code --with-data-volumes} resets
 * every database and keeps the config volumes, so the push token, the client secrets and the
 * deployer's extras survive a migration that only needs the data gone.
 */
@CommandLine.Command(
        name = "unwrap",
        mixinStandardHelpOptions = true,
        description = "Remove the platform's containers, images and networks. "
                + "Volumes stay unless --with-volumes or --with-data-volumes.")
public class UnwrapCommand implements Callable<Integer> {

    @Inject
    BootstrapConfig config;

    /** This run's arguments, relayed verbatim into the container by the host half. */
    @Inject
    @CommandLineArguments
    String[] args;

    @CommandLine.Option(names = "--with-volumes",
            description = "Also delete the qits-* volumes: databases, registry blobs, git host. "
                    + "The full clean slate.")
    boolean withVolumes;

    @CommandLine.Option(names = "--with-data-volumes",
            description = "Also delete the qits-*-data volumes (and qits-maven-seed), keeping "
                    + "qits-*-config and the qits-maven-cache download cache. The re-bootstrap's "
                    + "answer. Not with --with-volumes.")
    boolean withDataVolumes;

    @CommandLine.Option(names = "--dry-run", description = "List what would go, remove nothing.")
    boolean dryRun;

    @CommandLine.Option(names = "--wrapper-dir",
            description = "Where the generated compose file is.")
    String wrapperDir;

    @CommandLine.Option(names = "--no-tui", description = "Plain sequential output.")
    boolean noTui;

    @Override
    public Integer call() throws Exception {
        // Refused rather than ranked. The two flags mean different sweeps, and a run that silently
        // picked one would delete either more or less than the person typing both expected.
        if (withVolumes && withDataVolumes) {
            System.err.println("--with-volumes and --with-data-volumes are two different sweeps: "
                    + "the first removes every qits-* volume, the second removes the data ones and "
                    + "keeps qits-*-config. Name one.");
            return 2;
        }
        BootstrapConfig effective = new OverridableConfig(config)
                .wrapperDir(wrapperDir)
                .tui(noTui ? Boolean.FALSE : null);

        // The host half, after the refusal above so a mistyped sweep is answered without building
        // anything. unwrap runs in the container for the same reason bootstrap does: it disconnects
        // itself from the platform's networks before it removes them, which only a member can do.
        if (!effective.inContainer()) {
            return HostLauncher.run(effective, List.of(args), System.out);
        }

        try (RunLog log = new RunLog(Path.of(effective.logFile()))) {
            log.section("unwrap" + (withVolumes ? " --with-volumes" : "")
                    + (withDataVolumes ? " --with-data-volumes" : "")
                    + (dryRun ? " --dry-run" : ""));
            Boot boot = new Boot(effective, log);
            Ui ui = UiFactory.create(effective, System.out);
            RunResult result;
            try {
                result = new PhaseEngine(ui)
                        .run(new UnwrapPhases(boot, withVolumes, withDataVolumes, dryRun).build());
            } finally {
                ui.close();
            }
            if (withDataVolumes) {
                System.out.println();
                System.out.println("the qits-*-config volumes are still there — they hold the push "
                        + "token, the client secrets and the deployer's extras");
            } else if (!withVolumes) {
                System.out.println();
                System.out.println("the qits-* volumes are still there — unwrap "
                        + "--with-data-volumes resets the databases, --with-volumes removes "
                        + "every one of them");
            }
            return result.exitCode();
        }
    }
}
