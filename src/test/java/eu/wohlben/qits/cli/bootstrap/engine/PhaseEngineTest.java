package eu.wohlben.qits.cli.bootstrap.engine;

import eu.wohlben.qits.cli.bootstrap.ui.Ui;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/** The engine is the part of the boot that can be proven without a docker daemon. */
class PhaseEngineTest {

    /** Records what the display was told, in order. */
    static class RecordingUi implements Ui {
        final List<String> events = new ArrayList<>();
        final List<PhaseOutcome> outcomes = new ArrayList<>();
        RunResult result;

        @Override
        public void started(List<Phase> phases) {
            events.add("started:" + phases.size());
        }

        @Override
        public void phaseStarted(int index, Phase phase) {
            events.add("start:" + phase.id());
        }

        @Override
        public void output(String line) {
            events.add("out:" + line);
        }

        @Override
        public void status(String status) {
            events.add("status:" + status);
        }

        @Override
        public void phaseFinished(PhaseOutcome outcome) {
            outcomes.add(outcome);
            events.add("end:" + outcome.phase().id() + ":" + outcome.state());
        }

        @Override
        public void finished(RunResult result) {
            this.result = result;
        }

        @Override
        public void message(String line) {
            events.add("message:" + line);
        }

        @Override
        public void event(String line) {
            events.add("ev:" + line);
        }

        @Override
        public void close() {
        }
    }

    private static Phase phase(String id, PhaseAction action) {
        return new Phase(id, "title of " + id, action);
    }

    @Test
    void runsEveryPhaseInOrder() {
        RecordingUi ui = new RecordingUi();
        RunResult result = new PhaseEngine(ui).run(List.of(
                phase("one", ctx -> ctx.log("hello")),
                phase("two", ctx -> {
                }),
                phase("three", ctx -> {
                })));

        assertThat(result.exitCode()).isZero();
        assertThat(ui.events).containsSubsequence("started:3", "start:one", "out:hello",
                "end:one:DONE", "start:two", "end:two:DONE", "start:three", "end:three:DONE");
        assertThat(result.count(PhaseState.DONE)).isEqualTo(3);
    }

    @Test
    void aFailingPhaseStopsTheRun() {
        RecordingUi ui = new RecordingUi();
        List<String> ran = new ArrayList<>();
        RunResult result = new PhaseEngine(ui).run(List.of(
                phase("first", ctx -> ran.add("first")),
                phase("boom", ctx -> {
                    throw new IllegalStateException("the daemon said no");
                }),
                phase("never", ctx -> ran.add("never"))));

        assertThat(ran).containsExactly("first");
        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.failure()).isNotNull();
        assertThat(result.failure().phase().id()).isEqualTo("boom");
        assertThat(result.failure().note()).isEqualTo("the daemon said no");
        assertThat(ui.result).isSameAs(result);
    }

    @Test
    void aWarningKeepsGoingAndMakesTheExitCodeNonzero() {
        RecordingUi ui = new RecordingUi();
        RunResult result = new PhaseEngine(ui).run(List.of(
                phase("deploy", ctx -> ctx.warn("no deployment row after an hour")),
                phase("after", ctx -> {
                })));

        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.count(PhaseState.WARNED)).isEqualTo(1);
        assertThat(result.count(PhaseState.DONE)).isEqualTo(1);
        assertThat(ui.outcomes.getFirst().note()).isEqualTo("no deployment row after an hour");
    }

    @Test
    void aSkippedPhaseSaysWhy() {
        RecordingUi ui = new RecordingUi();
        RunResult result = new PhaseEngine(ui).run(List.of(
                phase("publish", ctx -> ctx.skip("already published")),
                phase("after", ctx -> {
                })));

        assertThat(result.exitCode()).isZero();
        assertThat(ui.outcomes.getFirst().state()).isEqualTo(PhaseState.SKIPPED);
        assertThat(ui.outcomes.getFirst().note()).isEqualTo("already published");
    }

    @Test
    void everyPhaseIsTimed() {
        AtomicLong nanos = new AtomicLong();
        RecordingUi ui = new RecordingUi();
        RunResult result = new PhaseEngine(ui, nanos::get).run(List.of(
                phase("slow", ctx -> nanos.addAndGet(Duration.ofMinutes(2).toNanos())),
                phase("quick", ctx -> nanos.addAndGet(Duration.ofSeconds(3).toNanos()))));

        assertThat(ui.outcomes.get(0).took()).isEqualTo(Duration.ofMinutes(2));
        assertThat(ui.outcomes.get(1).took()).isEqualTo(Duration.ofSeconds(3));
        assertThat(result.took()).isEqualTo(Duration.ofSeconds(123));
    }
}
