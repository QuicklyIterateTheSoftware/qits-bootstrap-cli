package eu.wohlben.qits.cli.bootstrap;

import eu.wohlben.qits.cli.bootstrap.config.BootstrapConfig;
import eu.wohlben.qits.cli.bootstrap.config.DomainName;
import eu.wohlben.qits.cli.bootstrap.config.OverridableConfig;
import eu.wohlben.qits.cli.bootstrap.engine.Phase;
import eu.wohlben.qits.cli.bootstrap.engine.PhaseEngine;
import eu.wohlben.qits.cli.bootstrap.engine.RunResult;
import eu.wohlben.qits.cli.bootstrap.host.HostLauncher;
import eu.wohlben.qits.cli.bootstrap.phases.Boot;
import eu.wohlben.qits.cli.bootstrap.phases.BootstrapPlan;
import eu.wohlben.qits.cli.bootstrap.proc.RunLog;
import eu.wohlben.qits.cli.bootstrap.ui.EventFeed;
import eu.wohlben.qits.cli.bootstrap.ui.Ui;
import eu.wohlben.qits.cli.bootstrap.ui.UiFactory;
import io.quarkus.runtime.annotations.CommandLineArguments;
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

    /**
     * This run's own arguments, so the host half can relay them verbatim into the container. Taken
     * as they were typed rather than rebuilt from the options above: a flag added later is passed
     * on without anyone remembering to.
     */
    @Inject
    @CommandLineArguments
    String[] args;

    @CommandLine.Option(names = "--wrapper-dir",
            description = "The wrapper repository whose submodule checkouts are the sources.")
    String wrapperDir;

    @CommandLine.Option(names = "--skip-build",
            description = "The seed images and the daemon binary exist; skip to compose and the pushes.")
    Boolean skipBuild;

    /**
     * The dev loop's flag, and the whole of what it changes is where the deploy ref points.
     * <p>
     * A bootstrap RESTORES: every deployable comes back at the commit of its newest release tag.
     * Shipping the mains in your checkouts is the other thing a person may want — it is how this
     * program was used daily — and it now takes saying so, which is the fix for the 2026-08-08
     * accident at the root: an unreleased local main cannot deploy by not being noticed.
     */
    @CommandLine.Option(names = "--ship-mains",
            description = "Deploy the local mains instead of restoring each deployable's newest "
                    + "release tag. The dev loop (QITS_SHIP_MAINS).")
    Boolean shipMains;

    @CommandLine.Option(names = "--no-tui",
            description = "Plain sequential output, even on a terminal that could take the live display.")
    boolean noTui;

    /**
     * The standing environment's name, and with it every derived name: the deploy ref
     * {@code environment/<name>}, the wire alias {@code <name>-qits-<app>} inside every generated
     * address, the deployed container names, and the idp client ids.
     * <p>
     * It is also the PLATFORM environment — the one whose branch deploys the platform plane — which
     * is why the option says so rather than being called {@code --env-name}. Making a different
     * environment the platform one later is a PATCH on the deployer, and is not this.
     * <p>
     * This is a knob for a FIRST boot. Re-bootstrapping with a different name is refused, not
     * honoured as a rename — see the {@code environment} phase.
     */
    @CommandLine.Option(names = "--platform-env", paramLabel = "<name>",
            description = "The standing environment to build the platform in. It deploys the "
                    + "platform plane and names every wire alias. Default: prod (QITS_ENV_NAME).")
    String platformEnv;

    /**
     * The domain this platform serves. Unset — the default — is a platform with no public names:
     * qits-platform-dns runs with no zones and the edge stays on plain HTTP.
     */
    @CommandLine.Option(names = "--domain", paramLabel = "<domain>",
            description = "The domain to serve: the dns zone, its ns1/hostmaster identity and the "
                    + "name the edge's certificate is issued for. Unset = no public names "
                    + "(QITS_DOMAIN).")
    String domain;

    @Override
    public Integer call() throws Exception {
        BootstrapConfig effective = new OverridableConfig(config)
                .wrapperDir(wrapperDir)
                .skipBuild(skipBuild)
                .shipMains(shipMains)
                .platformEnv(platformEnv)
                .domain(domain)
                .tui(noTui ? Boolean.FALSE : null);

        // BEFORE either half does anything, and on the host half too: a misspelled domain becomes a
        // zone row and a certificate request for a name nobody owns, and neither is undone by
        // rerunning with the spelling fixed. The message is the whole output — no stack trace, since
        // there is no bug here to report.
        try {
            DomainName.of(effective);
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            return 2;
        }

        // The host half. Every address the phases dial is a wire alias on qits-net, so they only
        // ever run inside the payload image — this builds it and runs this same program in it, and
        // relays whatever exit code comes back.
        if (!effective.inContainer()) {
            return HostLauncher.run(effective, List.of(args), System.out);
        }

        try (RunLog log = new RunLog(Path.of(effective.logFile()))) {
            log.section("bootstrap");
            Boot boot = new Boot(effective, log);
            List<Phase> phases = BootstrapPlan.build(boot);
            Ui ui = UiFactory.create(effective, System.out);
            // Ctrl-C in the middle of a four-hour build must still hand the terminal back.
            Thread restore = new Thread(ui::close, "ui-restore");
            Runtime.getRuntime().addShutdownHook(restore);
            // Started with the boot rather than by a phase: the platform's events run across the
            // whole run, and for the first phases there is no service to read them from yet.
            EventFeed feed = EventFeed.start(effective, ui);
            RunResult result;
            try {
                result = new PhaseEngine(ui).run(phases);
            } finally {
                feed.close();
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
