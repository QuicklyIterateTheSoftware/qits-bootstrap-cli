package eu.wohlben.qits.cli.bootstrap.phases;

import eu.wohlben.qits.cli.bootstrap.api.ArtifactsApi;
import eu.wohlben.qits.cli.bootstrap.api.PdApi;
import eu.wohlben.qits.cli.bootstrap.api.CiApi;
import eu.wohlben.qits.cli.bootstrap.api.Http;
import eu.wohlben.qits.cli.bootstrap.api.IdpApi;
import eu.wohlben.qits.cli.bootstrap.config.BootstrapConfig;
import eu.wohlben.qits.cli.bootstrap.engine.PhaseContext;
import eu.wohlben.qits.cli.bootstrap.engine.Waiter;
import eu.wohlben.qits.cli.bootstrap.platform.Docker;
import eu.wohlben.qits.cli.bootstrap.platform.Git;
import eu.wohlben.qits.cli.bootstrap.platform.RunState;
import eu.wohlben.qits.cli.bootstrap.proc.ProcessResult;
import eu.wohlben.qits.cli.bootstrap.proc.ProcessRunner;
import eu.wohlben.qits.cli.bootstrap.proc.RunLog;

import java.time.Duration;
import java.util.function.Supplier;

/** Everything the phases share: the tools, the addresses and the run's own state. */
public class Boot {

    public static final String NETWORK = "qits-net";

    public final BootstrapConfig config;
    public final RunLog log;
    public final ProcessRunner runner;
    public final Docker docker;
    public final Git git;
    public final RunState state = new RunState();

    public final Http http = new Http();
    public final ArtifactsApi artifacts;
    public final CiApi ci;
    public final PdApi pd;
    public final IdpApi idp;

    public Boot(BootstrapConfig config, RunLog log) {
        this.config = config;
        this.log = log;
        this.runner = new ProcessRunner(log);
        this.docker = new Docker(runner);
        this.git = new Git(runner);
        this.artifacts = new ArtifactsApi(http, config.artifactsUrl());
        this.ci = new CiApi(http, config.ciUrl());
        this.pd = new PdApi(http, config.platformDeploymentsUrl());
        this.idp = new IdpApi(http, config.idpIssuer());
    }

    /** Fails the phase, with the command's own last words attached. */
    public static void must(ProcessResult result, String what) {
        if (!result.ok()) {
            throw new IllegalStateException(what + " (exit " + result.exitCode() + ")\n"
                    + result.tailText(20));
        }
    }

    /**
     * A machine token for this bootstrap's own calls into the platform, or null when the gate is
     * off. It borrows a platform client rather than owning one: the calls it makes stand in for
     * announcements a service never sent, and a token that says so is exactly right.
     */
    public String tokenOrNull(String clientId, String audience) {
        if (!config.machineAuth()) {
            return null;
        }
        String secret = state.secrets.get(clientId);
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("no secret for the " + clientId
                    + " client — .qits-bootstrap.env and the running idp disagree");
        }
        return idp.token(clientId, secret, audience);
    }

    /** Waits for a health endpoint, saying which one and what it last answered. */
    public void awaitHealth(PhaseContext ctx, String what, Supplier<Http.Response> probe)
            throws Exception {
        Waiter.await(ctx, what, config.healthTimeout(), Duration.ofSeconds(5), () -> {
            Http.Response response = probe.get();
            return response.ok()
                    ? Waiter.Poll.done(response, "ready")
                    : Waiter.Poll.pending(response.describe());
        });
        ctx.log("  " + what + " ready");
    }
}
