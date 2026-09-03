package eu.wohlben.qits.cli.bootstrap.phases;

import eu.wohlben.qits.cli.bootstrap.engine.PhaseContext;
import eu.wohlben.qits.cli.bootstrap.proc.ProcessResult;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A push that meets the platform's own postgres mid-cutover fails for seconds and then works. The
 * window is bounded, every attempt is on the screen, and what it says when it finally gives up is
 * the point: how many attempts, over how long, in git's own words.
 */
class BootPushTest {

    static class Recorder implements PhaseContext {
        final List<String> statuses = new ArrayList<>();
        final List<String> logs = new ArrayList<>();

        @Override
        public void log(String line) {
            logs.add(line);
        }

        @Override
        public void status(String status) {
            statuses.add(status);
        }

        @Override
        public void note(String note) {
        }

        @Override
        public void warn(String message) {
        }
    }

    private static ProcessResult failed(String... output) {
        return new ProcessResult(128, List.of(output), List.of(output), false, false);
    }

    private static ProcessResult pushed() {
        return new ProcessResult(0, List.of("To http://dev-qits-githost:8080/git/qits-platform-idp"),
                List.of("To http://dev-qits-githost:8080/git/qits-platform-idp"), false, false);
    }

    @Test
    void aPushThatWorksIsPushedOnce() throws Exception {
        Recorder ctx = new Recorder();
        AtomicLong clock = new AtomicLong();
        AtomicInteger tries = new AtomicInteger();

        ProcessResult result = Boot.pushRetrying(ctx, "qits-platform-idp to main",
                Duration.ofSeconds(90), Duration.ofSeconds(5),
                () -> {
                    tries.incrementAndGet();
                    return pushed();
                },
                clock::get, clock::addAndGet);

        assertThat(result.ok()).isTrue();
        assertThat(tries.get()).isEqualTo(1);
        assertThat(clock.get()).isZero();
        assertThat(ctx.logs).isEmpty();
    }

    /** The measured incident: the git host misanswers while postgres cuts over, then takes it. */
    @Test
    void aPushThatFailsInTheFluxIsRetriedUntilItLands() throws Exception {
        Recorder ctx = new Recorder();
        AtomicLong clock = new AtomicLong();
        AtomicInteger tries = new AtomicInteger();

        ProcessResult result = Boot.pushRetrying(ctx, "qits-platform-idp to main",
                Duration.ofSeconds(90), Duration.ofSeconds(5),
                () -> tries.incrementAndGet() < 4
                        ? failed("remote: 500 Internal Server Error", "fatal: unable to access")
                        : pushed(),
                clock::get, clock::addAndGet);

        assertThat(result.ok()).isTrue();
        assertThat(tries.get()).isEqualTo(4);
        assertThat(clock.get()).isEqualTo(15_000);
        // Every failure is a line of its own, so a stalled push does not look like a slow one.
        assertThat(ctx.logs).hasSize(3);
        assertThat(ctx.logs.getFirst())
                .contains("push of qits-platform-idp to main failed (exit 128)")
                .contains("fatal: unable to access");
        assertThat(ctx.statuses.getFirst())
                .contains("waiting for the push of qits-platform-idp to main")
                .contains("attempt 1 failed")
                .contains("gives up after 1m30s");
    }

    @Test
    void givingUpSaysHowManyAttemptsOverHowLongAndWhatGitSaid() {
        Recorder ctx = new Recorder();
        AtomicLong clock = new AtomicLong();
        AtomicInteger tries = new AtomicInteger();

        assertThatThrownBy(() -> Boot.pushRetrying(ctx, "qits-platform-idp to main",
                Duration.ofSeconds(90), Duration.ofSeconds(5),
                () -> {
                    tries.incrementAndGet();
                    return failed("remote: repository not found", "error: failed to push some refs");
                },
                clock::get, clock::addAndGet))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("push of qits-platform-idp to main failed 19 times")
                .hasMessageContaining("over 1m30s")
                .hasMessageContaining("exit 128")
                .hasMessageContaining("error: failed to push some refs");

        // Bounded: the window is spent, not the boot.
        assertThat(tries.get()).isEqualTo(19);
        assertThat(clock.get()).isEqualTo(90_000);
    }

    @Test
    void aCommandThatSaidNothingStillHasAnAttemptLine() {
        assertThat(Boot.lastWords(new ProcessResult(1, List.of(), List.of(), false, false)))
                .isEqualTo("no output");
        assertThat(Boot.lastWords(failed("fatal: the remote hung up", "")))
                .isEqualTo("fatal: the remote hung up");
    }
}
