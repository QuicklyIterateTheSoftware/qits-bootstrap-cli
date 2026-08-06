package eu.wohlben.qits.cli.bootstrap;

import eu.wohlben.qits.cli.bootstrap.config.BootstrapConfig;
import eu.wohlben.qits.cli.bootstrap.config.OverridableConfig;
import eu.wohlben.qits.cli.bootstrap.engine.PhaseEngine;
import eu.wohlben.qits.cli.bootstrap.engine.RunResult;
import eu.wohlben.qits.cli.bootstrap.phases.Boot;
import eu.wohlben.qits.cli.bootstrap.phases.UnwrapPhases;
import eu.wohlben.qits.cli.bootstrap.proc.RunLog;
import eu.wohlben.qits.cli.bootstrap.ui.Ui;
import eu.wohlben.qits.cli.bootstrap.ui.UiFactory;
import jakarta.inject.Inject;
import picocli.CommandLine;

import java.nio.file.Path;
import java.util.concurrent.Callable;

/**
 * The opposite of bootstrap: containers, images and networks go, and the volumes stay.
 * <p>
 * The volumes are where the platform's databases, the registry's blobs and the git host's
 * repositories live, so removing them is the one decision this command asks for out loud.
 */
@CommandLine.Command(
        name = "unwrap",
        mixinStandardHelpOptions = true,
        description = "Remove the platform's containers, images and networks. "
                + "Volumes stay unless --with-volumes.")
public class UnwrapCommand implements Callable<Integer> {

    @Inject
    BootstrapConfig config;

    @CommandLine.Option(names = "--with-volumes",
            description = "Also delete the qits-* volumes: databases, registry blobs, git host. "
                    + "The full clean slate.")
    boolean withVolumes;

    @CommandLine.Option(names = "--dry-run", description = "List what would go, remove nothing.")
    boolean dryRun;

    @CommandLine.Option(names = "--wrapper-dir",
            description = "Where the generated compose file is.")
    String wrapperDir;

    @CommandLine.Option(names = "--no-tui", description = "Plain sequential output.")
    boolean noTui;

    @Override
    public Integer call() throws Exception {
        BootstrapConfig effective = new OverridableConfig(config)
                .wrapperDir(wrapperDir)
                .tui(noTui ? Boolean.FALSE : null);

        try (RunLog log = new RunLog(Path.of(effective.logFile()))) {
            log.section("unwrap" + (withVolumes ? " --with-volumes" : "") + (dryRun ? " --dry-run" : ""));
            Boot boot = new Boot(effective, log);
            Ui ui = UiFactory.create(effective, System.out);
            RunResult result;
            try {
                result = new PhaseEngine(ui)
                        .run(new UnwrapPhases(boot, withVolumes, dryRun).build());
            } finally {
                ui.close();
            }
            if (!withVolumes) {
                System.out.println();
                System.out.println("the qits-* volumes are still there — unwrap --with-volumes "
                        + "removes them too");
            }
            return result.exitCode();
        }
    }
}
