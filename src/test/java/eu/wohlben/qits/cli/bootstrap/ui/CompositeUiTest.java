package eu.wohlben.qits.cli.bootstrap.ui;

import eu.wohlben.qits.cli.bootstrap.engine.Phase;
import eu.wohlben.qits.cli.bootstrap.engine.PhaseEngine;
import eu.wohlben.qits.cli.bootstrap.engine.PhaseOutcome;
import eu.wohlben.qits.cli.bootstrap.engine.RunResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** One run, two displays: every event has to reach both of them. */
class CompositeUiTest {

    /** A display that only remembers what it was told. */
    private static class Recorder implements Ui {
        final List<String> seen = new ArrayList<>();
        final boolean live;
        boolean closed;

        Recorder(boolean live) {
            this.live = live;
        }

        @Override
        public void started(List<Phase> phases) {
            seen.add("started " + phases.size());
        }

        @Override
        public void phaseStarted(int index, Phase phase) {
            seen.add("start " + index + " " + phase.id());
        }

        @Override
        public void output(String line) {
            seen.add("out " + line);
        }

        @Override
        public void status(String status) {
            seen.add("status " + status);
        }

        @Override
        public void phaseFinished(PhaseOutcome outcome) {
            seen.add("end " + outcome.index() + " " + outcome.state());
        }

        @Override
        public void message(String line) {
            seen.add("msg " + line);
        }

        @Override
        public void finished(RunResult result) {
            seen.add("finished " + result.exitCode());
        }

        @Override
        public boolean live() {
            return live;
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    @Test
    void everyEventReachesEveryDisplay() {
        Recorder terminal = new Recorder(true);
        Recorder web = new Recorder(false);
        CompositeUi ui = new CompositeUi(terminal, web);

        new PhaseEngine(ui).run(List.of(new Phase("a", "build", ctx -> {
            ctx.log("[INFO] building");
            ctx.status("waiting");
        })));
        ui.message("the platform is up");
        ui.close();

        assertThat(terminal.seen).containsExactly("started 1", "start 0 a", "out [INFO] building",
                "status waiting", "end 0 DONE", "finished 0", "msg the platform is up");
        assertThat(web.seen).isEqualTo(terminal.seen);
        assertThat(terminal.closed).isTrue();
        assertThat(web.closed).isTrue();
    }

    @Test
    void theTerminalDecidesWhetherTheDisplayIsLive() {
        assertThat(new CompositeUi(new Recorder(true), new Recorder(false)).live()).isTrue();
        assertThat(new CompositeUi(new Recorder(false), new Recorder(false)).live()).isFalse();
    }

    @Test
    void aWatcherThatThrowsDoesNotStopTheBoot() {
        Recorder terminal = new Recorder(true);
        Ui broken = new Recorder(false) {
            @Override
            public void output(String line) {
                throw new IllegalStateException("the browser view broke");
            }
        };

        CompositeUi ui = new CompositeUi(terminal, broken);
        ui.output("[INFO] building");
        ui.close();

        assertThat(terminal.seen).containsExactly("out [INFO] building");
    }
}
