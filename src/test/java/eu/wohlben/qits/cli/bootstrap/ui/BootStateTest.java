package eu.wohlben.qits.cli.bootstrap.ui;

import eu.wohlben.qits.cli.bootstrap.config.TestConfig;
import eu.wohlben.qits.cli.bootstrap.engine.Phase;
import eu.wohlben.qits.cli.bootstrap.engine.PhaseEngine;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** What the browser is served: the state of the run, and what changed since it last looked. */
class BootStateTest {

    private WebUi webUi() {
        return new WebUi(TestConfig.from(Map.of("QITS_TAIL_LINES", "200")));
    }

    private static List<String> types(List<BootState.Event> events) {
        return events.stream().map(BootState.Event::type).toList();
    }

    @Test
    void theSnapshotCarriesEveryPhaseWithItsOutcome() {
        WebUi ui = webUi();

        new PhaseEngine(ui).run(List.of(
                new Phase("a", "build qits-artifacts", ctx -> ctx.log("[INFO] building")),
                new Phase("b", "publish qits-eventstream", ctx -> ctx.skip("already published"))));

        String json = ui.state().snapshotJson();
        assertThat(json).contains("\"total\":2");
        assertThat(json).contains("\"id\":\"a\",\"title\":\"build qits-artifacts\",\"state\":\"DONE\"");
        assertThat(json).contains("\"state\":\"SKIPPED\",\"tookMs\":0,\"note\":\"already published\"");
        // Nothing is running any more, and the verdict is on the page.
        assertThat(json).contains("\"currentIndex\":-1");
        assertThat(json).contains("\"exitCode\":0");
        assertThat(json).contains("done: 1 phases, 1 skipped");
    }

    @Test
    void aFailureIsNamedInTheStateTheBrowserReads() {
        WebUi ui = webUi();

        new PhaseEngine(ui).run(List.of(new Phase("boom", "build qits-ci", ctx -> {
            throw new IllegalStateException("build of qits/ci failed");
        })));

        String json = ui.state().snapshotJson();
        assertThat(json).contains("\"state\":\"FAILED\"");
        assertThat(json).contains("build of qits/ci failed");
        assertThat(json).contains("\"exitCode\":2");
    }

    @Test
    void theTailIsTheRunningStepsOutputAndItIsBounded() {
        BootState state = new BootState(50);
        Phase phase = new Phase("a", "build", ctx -> {
        });
        state.started(List.of(phase));
        state.phaseStarted(0, phase);
        for (int i = 0; i < 200; i++) {
            state.output("line " + i);
        }

        String json = state.snapshotJson();
        assertThat(json).contains("\"line 199\"");
        assertThat(json).doesNotContain("\"line 149\"");
        assertThat(json).contains("\"line 150\"");
    }

    @Test
    void theTailStartsAgainWithEachPhase() {
        BootState state = new BootState(200);
        Phase one = new Phase("a", "first", ctx -> {
        });
        Phase two = new Phase("b", "second", ctx -> {
        });
        state.started(List.of(one, two));
        state.phaseStarted(0, one);
        state.output("output of the first phase");
        state.phaseStarted(1, two);

        assertThat(state.snapshotJson()).doesNotContain("output of the first phase");
    }

    @Test
    void aReaderIsToldWhatChangedSinceItLastLooked() {
        BootState state = new BootState(200);
        Phase phase = new Phase("a", "build", ctx -> {
        });
        state.started(List.of(phase));
        long cursor = state.seq();

        state.phaseStarted(0, phase);
        state.output("[INFO] building");
        state.status("waiting for qits-ci — no run yet · 10s elapsed");

        List<BootState.Event> events = state.since(cursor);
        assertThat(types(events)).containsExactly("snapshot", "line", "status");
        assertThat(events.getLast().json()).contains("waiting for qits-ci");
        // Each event is one SSE frame, so no value in it may carry a raw newline.
        assertThat(events).allSatisfy(e -> assertThat(e.json()).doesNotContain("\n"));
        assertThat(state.since(state.seq())).isEmpty();
    }

    @Test
    void aReaderThatFellTooFarBehindIsToldToTakeAFreshSnapshot() {
        BootState state = new BootState(200);
        Phase phase = new Phase("a", "build", ctx -> {
        });
        state.started(List.of(phase));
        state.phaseStarted(0, phase);
        long cursor = state.seq();
        for (int i = 0; i < 1000; i++) {
            state.output("line " + i);
        }

        assertThat(state.hasEverythingAfter(cursor)).isFalse();
        assertThat(state.hasEverythingAfter(state.seq())).isTrue();
    }

    @Test
    void quotesAndNewlinesInOutputSurviveTheJson() {
        BootState state = new BootState(200);
        Phase phase = new Phase("a", "build", ctx -> {
        });
        state.started(List.of(phase));
        state.phaseStarted(0, phase);
        state.output("said \"done\"\tand left");

        assertThat(state.snapshotJson()).contains("said \\\"done\\\"\\tand left");
    }

    @Test
    void theRunOfThisProcessIsWhatTheHttpLayerFinds() {
        WebUi ui = webUi();
        assertThat(BootState.published()).isSameAs(ui.state());
    }
}
