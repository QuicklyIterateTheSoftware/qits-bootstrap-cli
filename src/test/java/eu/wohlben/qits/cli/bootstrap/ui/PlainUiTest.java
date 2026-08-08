package eu.wohlben.qits.cli.bootstrap.ui;

import eu.wohlben.qits.cli.bootstrap.engine.Phase;
import eu.wohlben.qits.cli.bootstrap.engine.PhaseEngine;
import eu.wohlben.qits.cli.bootstrap.engine.RunResult;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/** The fallback display: what a CI job or a piped run gets. */
class PlainUiTest {

    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    private final PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8);

    private String printed() {
        out.flush();
        return bytes.toString(StandardCharsets.UTF_8);
    }

    @Test
    void marksEveryPhaseWithItsPositionAndOutcome() {
        AtomicLong nanos = new AtomicLong();
        PhaseEngine engine = new PhaseEngine(new PlainUi(out), nanos::get);

        RunResult result = engine.run(List.of(
                new Phase("a", "build qits-platform-artifacts", ctx -> {
                    ctx.log("[INFO] building");
                    nanos.addAndGet(130_000_000_000L);
                }),
                new Phase("b", "publish qits-eventstream", ctx -> ctx.skip("already published"))));

        String text = printed();
        assertThat(text).contains("qits bootstrap: 2 phases");
        assertThat(text).contains("==> 1/2 build qits-platform-artifacts");
        assertThat(text).contains("    [INFO] building");
        assertThat(text).contains("  ok 1/2 build qits-platform-artifacts (2m10s)");
        assertThat(text).contains("skip 2/2 publish qits-eventstream (0s) — already published");
        assertThat(text).contains("done: 1 phases, 1 skipped");
        assertThat(result.exitCode()).isZero();
    }

    @Test
    void printsWhatAWaitIsWaitingOnWithoutRepeatingItself() {
        AtomicLong millis = new AtomicLong();
        PlainUi ui = new PlainUi(out, 15_000, millis::get);
        ui.started(List.of(new Phase("w", "wait", ctx -> {
        })));
        ui.phaseStarted(0, new Phase("w", "wait", ctx -> {
        }));

        ui.status("waiting for qits-ci — no run yet · 10s elapsed");
        ui.status("waiting for qits-ci — no run yet · 20s elapsed");
        millis.addAndGet(20_000);
        ui.status("waiting for qits-ci — no run yet · 30s elapsed");
        ui.status("waiting for qits-ci — run RUNNING · 40s elapsed");

        String text = printed();
        assertThat(text).contains("  · waiting for qits-ci — no run yet · 10s elapsed");
        // The same wait, one poll later, is not news.
        assertThat(text).doesNotContain("20s elapsed");
        // The repeat interval passed, so the clock is shown again.
        assertThat(text).contains("30s elapsed");
        // A change in what the poll sees is news whenever it happens.
        assertThat(text).contains("run RUNNING · 40s elapsed");
    }

    @Test
    void aFailureIsSaidTwice_onItsLineAndAtTheEnd() {
        PhaseEngine engine = new PhaseEngine(new PlainUi(out));

        RunResult result = engine.run(List.of(new Phase("boom", "build qits-ci", ctx -> {
            throw new IllegalStateException("build of qits/ci failed");
        })));

        String text = printed();
        assertThat(text).contains("FAIL 1/1 build qits-ci");
        assertThat(text).contains("FAILED: build qits-ci");
        assertThat(text).contains("build of qits/ci failed");
        assertThat(text).contains("stopped after");
        assertThat(result.exitCode()).isEqualTo(2);
    }

    @Test
    void aWarningIsVisibleInTheSummary() {
        PhaseEngine engine = new PhaseEngine(new PlainUi(out));

        engine.run(List.of(new Phase("d", "qits-stt: push -> ci build -> cd deploy",
                ctx -> ctx.warn("no terminal deployment after 3600s"))));

        assertThat(printed()).contains("warn 1/1 qits-stt")
                .contains("!! no terminal deployment after 3600s")
                .contains("finished with warnings");
    }

    @Test
    void isNotALiveDisplaySoTheCallerDoesNotReprintTheReport() {
        assertThat(new PlainUi(out).live()).isFalse();
    }
}
