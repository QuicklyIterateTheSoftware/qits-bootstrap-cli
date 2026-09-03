package eu.wohlben.qits.cli.bootstrap.phases;

import eu.wohlben.qits.cli.bootstrap.engine.PhaseContext;
import eu.wohlben.qits.cli.bootstrap.platform.Docker;
import eu.wohlben.qits.cli.bootstrap.proc.Ansi;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The deployer's own account of a deployment, read out loud while a phase waits for it.
 * <p>
 * The build half of a wait already talks ({@code ci|}); the deploy half was silent between "run
 * green" and "row ACTIVE", which on a slow image pull or a health gate is minutes of nothing. The
 * deployer says what it is doing in its log — {@code Registered qits-observability in environment
 * prod}, {@code Deployed qits-observability@<sha> into prod (<container>)} — so this relays those
 * lines, the same way the ci relay reads the run.
 * <p>
 * <b>The source is {@code docker logs}, not an event feed.</b> The bus is in the seed since
 * 2026-08-10, so it does answer by the time the deploy waits run — but what this relay wants is the
 * deployer's own account of one application, which is a log line and not an event, and the docker
 * socket is the one thing the whole program cannot run without. Timestamps come from docker
 * ({@code --timestamps}), so "what is new since the last poll" is a since-filter rather than a
 * diff, and the deployer is found fresh on every read because a self-update hand-off renames its
 * container mid-boot.
 * <p>
 * <b>Never fails a phase.</b> A deployer that is mid-cutover, a log read that errors, a stamp that
 * does not parse — all mean this poll has nothing to relay, and the wait goes on exactly as it did
 * before this existed.
 */
final class DeployLogStream {

    /** Marks a line as the deployer's rather than the boot's. */
    static final String PREFIX = "  pd| ";

    private final Docker docker;
    private final PhaseContext ctx;
    private final String application;
    private final String deployerAlias;
    private final String deployerPdPrefix;

    /** Only lines stamped after this are new. Starts at the wait, so an old boot's lines stay out. */
    private Instant since;

    DeployLogStream(Docker docker, PhaseContext ctx, String application, String deployerAlias,
            String deployerPdPrefix) {
        this.docker = docker;
        this.ctx = ctx;
        this.application = application;
        this.deployerAlias = deployerAlias;
        this.deployerPdPrefix = deployerPdPrefix;
        this.since = Instant.now();
    }

    /** Relays what the deployer has said about this application since the last call. */
    void follow() {
        Optional<String> deployer = deployerContainer();
        if (deployer.isEmpty()) {
            return;
        }
        List<String> raw;
        try {
            raw = docker.logsSince(deployer.get(), since.toString());
        } catch (RuntimeException e) {
            return;
        }
        for (String line : fresh(raw, since, application)) {
            ctx.log(PREFIX + line);
        }
        since = lastStamp(raw, since);
    }

    /**
     * The deployer's container, found on every poll: the seed compose service carries the wire
     * alias, and the self-update hand-off replaces it with a {@code qits-pd-…} one of its own.
     */
    private Optional<String> deployerContainer() {
        return docker.runningNames().stream()
                .filter(name -> name.equals(deployerAlias) || name.startsWith(deployerPdPrefix))
                .findFirst();
    }

    /**
     * The lines about this application that are stamped after {@code since}, without their stamps.
     * <p>
     * {@code docker logs --since} includes lines AT the given moment, so the boundary is compared
     * again here, on parsed stamps. The application filter is what keeps another service's telemetry
     * noise out of a wait that is only about this one; whatever the deployer says about the
     * deployment names the application, and lines it cannot recognise stay in the deployer's own
     * log.
     */
    static List<String> fresh(List<String> stamped, Instant since, String application) {
        List<String> out = new ArrayList<>();
        for (String line : stamped) {
            Instant stamp = stampOf(line);
            if (stamp == null || !stamp.isAfter(since)) {
                continue;
            }
            String text = line.substring(line.indexOf(' ') + 1);
            if (text.contains(application)) {
                out.add(Ansi.clean(text));
            }
        }
        return out;
    }

    /** The newest stamp in the read, filtered or not, so noise is never read twice. */
    static Instant lastStamp(List<String> stamped, Instant since) {
        Instant last = since;
        for (String line : stamped) {
            Instant stamp = stampOf(line);
            if (stamp != null && stamp.isAfter(last)) {
                last = stamp;
            }
        }
        return last;
    }

    /** The docker timestamp leading a log line, or null when the line carries none it can read. */
    private static Instant stampOf(String line) {
        int space = line.indexOf(' ');
        if (space <= 0) {
            return null;
        }
        try {
            return Instant.parse(line.substring(0, space));
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
