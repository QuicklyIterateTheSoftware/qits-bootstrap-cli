package eu.wohlben.qits.cli.bootstrap;

import eu.wohlben.qits.cli.bootstrap.config.BootstrapConfig;
import eu.wohlben.qits.cli.bootstrap.config.OverridableConfig;
import eu.wohlben.qits.cli.bootstrap.engine.Phase;
import eu.wohlben.qits.cli.bootstrap.engine.PhaseEngine;
import eu.wohlben.qits.cli.bootstrap.engine.RunResult;
import eu.wohlben.qits.cli.bootstrap.phases.Boot;
import eu.wohlben.qits.cli.bootstrap.phases.BootstrapPlan;
import eu.wohlben.qits.cli.bootstrap.proc.RunLog;
import eu.wohlben.qits.cli.bootstrap.ui.Ui;
import eu.wohlben.qits.cli.bootstrap.ui.UiFactory;
import jakarta.inject.Inject;
import picocli.CommandLine;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * The full boot: hand-build the platform's build and deploy core once, then let the platform
 * deploy every one of its components through its own pipeline.
 * <p>
 * Cost, honestly: every seed image and every pipeline run is a cold GraalVM native build. A first
 * run is measured in hours. What this program adds over the shell script it replaces is that you
 * can see which one of them is running, and what it is doing right now.
 */
@CommandLine.Command(
        name = "bootstrap",
        mixinStandardHelpOptions = true,
        description = "Build the seed, start it, and push the platform through its own pipeline.")
public class BootstrapCommand implements Callable<Integer> {

    @Inject
    BootstrapConfig config;

    @CommandLine.Option(names = "--wrapper-dir",
            description = "The wrapper repository whose submodule checkouts are the sources.")
    String wrapperDir;

    @CommandLine.Option(names = "--skip-build",
            description = "The seed images and the daemon binary exist; skip to compose and the pushes.")
    Boolean skipBuild;

    @CommandLine.Option(names = "--no-tui",
            description = "Plain sequential output, even on a terminal that could take the live display.")
    boolean noTui;

    @Override
    public Integer call() throws Exception {
        BootstrapConfig effective = new OverridableConfig(config)
                .wrapperDir(wrapperDir)
                .skipBuild(skipBuild)
                .tui(noTui ? Boolean.FALSE : null);

        try (RunLog log = new RunLog(Path.of(effective.logFile()))) {
            log.section("bootstrap");
            Boot boot = new Boot(effective, log);
            List<Phase> phases = BootstrapPlan.build(boot);
            Ui ui = UiFactory.create(effective, System.out);
            // Ctrl-C in the middle of a four-hour build must still hand the terminal back.
            Thread restore = new Thread(ui::close, "ui-restore");
            Runtime.getRuntime().addShutdownHook(restore);
            RunResult result;
            try {
                result = new PhaseEngine(ui).run(phases);
            } finally {
                try {
                    Runtime.getRuntime().removeShutdownHook(restore);
                } catch (IllegalStateException alreadyShuttingDown) {
                    // Ctrl-C got here first; the hook is doing exactly what it is for.
                }
                ui.close();
            }
            if (ui.live()) {
                boot.state.summary.forEach(System.out::println);
            }
            System.out.println();
            System.out.println("full log: " + log.path().toAbsolutePath());
            return result.exitCode();
        }
    }
}
