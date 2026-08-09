package eu.wohlben.qits.cli.bootstrap.phases;

import com.fasterxml.jackson.databind.JsonNode;
import eu.wohlben.qits.cli.bootstrap.api.CiApi;
import eu.wohlben.qits.cli.bootstrap.api.Http;
import eu.wohlben.qits.cli.bootstrap.api.Json;
import eu.wohlben.qits.cli.bootstrap.engine.PhaseContext;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Turning successive whole reads of a bounded, still-growing tail into the lines nobody has seen.
 * qits-ci answers with the step's output entire on every poll, so this subtraction is the whole of
 * what makes a poll look like a stream.
 */
class CiLogStreamTest {

    @Test
    void holdsBackTheLineStillBeingWritten() {
        assertThat(CiLogStream.lines("mvn clean\ndownloading qu", false))
                .containsExactly("mvn clean");
    }

    @Test
    void showsTheOpenLineOnceTheStepIsOver() {
        assertThat(CiLogStream.lines("mvn clean\nBUILD FAILURE", true))
                .containsExactly("mvn clean", "BUILD FAILURE");
    }

    @Test
    void aTrailingNewlineIsNotALine() {
        assertThat(CiLogStream.lines("one\ntwo\n", false)).containsExactly("one", "two");
    }

    @Test
    void stripsAnsiTheWayEveryOtherLineIsStripped() {
        String esc = String.valueOf((char) 27);
        assertThat(CiLogStream.lines(esc + "[1;31mFATAL" + esc + "[0m: no daemon\n", false))
                .containsExactly("FATAL: no daemon");
    }

    @Test
    void noOutputIsNoLines() {
        assertThat(CiLogStream.lines("", false)).isEmpty();
        assertThat(CiLogStream.lines(null, true)).isEmpty();
    }

    @Test
    void aGrowingTailYieldsOnlyWhatGrew() {
        assertThat(CiLogStream.newLines(List.of("a", "b"), List.of("a", "b", "c")))
                .containsExactly("c");
    }

    @Test
    void anUnchangedTailYieldsNothing() {
        assertThat(CiLogStream.newLines(List.of("a", "b"), List.of("a", "b"))).isEmpty();
    }

    /** The bound is a rolling one: the same step can arrive cut at the front and longer at the end. */
    @Test
    void aTailThatRolledIsJoinedOnItsOverlap() {
        assertThat(CiLogStream.newLines(List.of("a", "b", "c"), List.of("b", "c", "d")))
                .containsExactly("d");
    }

    @Test
    void aTailThatRolledPastEverythingRememberedIsAllNew() {
        assertThat(CiLogStream.newLines(List.of("a", "b"), List.of("x", "y")))
                .containsExactly("x", "y");
    }

    @Test
    void theFirstReadIsAllNew() {
        assertThat(CiLogStream.newLines(List.of(), List.of("a", "b"))).containsExactly("a", "b");
    }

    @Test
    void aStepThatPrintedNothingYetYieldsNothing() {
        assertThat(CiLogStream.newLines(List.of("a"), List.of())).isEmpty();
    }

    // --- following a run, poll by poll ------------------------------------------------------------

    @Test
    void relaysEachPollsNewLinesOnce() {
        FakeCi ci = new FakeCi();
        Recorder ctx = new Recorder();
        CiLogStream stream = new CiLogStream(ci, ctx);

        ci.answers.add("{\"live\":{\"stepIndex\":0,\"output\":\"cloning\\n\"}}");
        ci.answers.add("{\"live\":{\"stepIndex\":0,\"output\":\"cloning\\nbuilding\\n\"}}");
        stream.follow("run-1");
        stream.follow("run-1");

        assertThat(ctx.logs).containsExactly(
                CiLogStream.PREFIX + "following ci run run-1",
                CiLogStream.PREFIX + "-- step 1 --",
                CiLogStream.PREFIX + "cloning",
                CiLogStream.PREFIX + "building");
    }

    /** A finished step's row carries what its live object was carrying; it is not shown twice. */
    @Test
    void aStepsRowDoesNotRepeatItsLiveOutput() {
        FakeCi ci = new FakeCi();
        Recorder ctx = new Recorder();
        CiLogStream stream = new CiLogStream(ci, ctx);

        ci.answers.add("{\"live\":{\"stepIndex\":0,\"output\":\"compiling\\n\"}}");
        ci.answers.add("{\"steps\":[{\"stepIndex\":0,\"output\":\"compiling\\ndone\\n\"}],"
                + "\"live\":{\"stepIndex\":1,\"output\":\"pushing\\n\"}}");
        stream.follow("run-1");
        stream.follow("run-1");

        assertThat(ctx.logs).containsExactly(
                CiLogStream.PREFIX + "following ci run run-1",
                CiLogStream.PREFIX + "-- step 1 --",
                CiLogStream.PREFIX + "compiling",
                CiLogStream.PREFIX + "done",
                CiLogStream.PREFIX + "-- step 2 --",
                CiLogStream.PREFIX + "pushing");
    }

    /** A run this phase did not cause is dropped and the new one starts from its own first line. */
    @Test
    void anotherRunStartsOver() {
        FakeCi ci = new FakeCi();
        Recorder ctx = new Recorder();
        CiLogStream stream = new CiLogStream(ci, ctx);

        ci.answers.add("{\"live\":{\"stepIndex\":0,\"output\":\"first\\n\"}}");
        ci.answers.add("{\"live\":{\"stepIndex\":0,\"output\":\"second\\n\"}}");
        stream.follow("run-1");
        stream.follow("run-2");

        assertThat(ctx.logs).containsExactly(
                CiLogStream.PREFIX + "following ci run run-1",
                CiLogStream.PREFIX + "-- step 1 --",
                CiLogStream.PREFIX + "first",
                CiLogStream.PREFIX + "following ci run run-2",
                CiLogStream.PREFIX + "-- step 1 --",
                CiLogStream.PREFIX + "second");
    }

    @Test
    void noRunYetSaysNothing() {
        FakeCi ci = new FakeCi();
        Recorder ctx = new Recorder();
        new CiLogStream(ci, ctx).follow(null);
        assertThat(ctx.logs).isEmpty();
    }

    /** A relay that cannot read gives up with one line and never touches the wait again. */
    @Test
    void anUnreadableRunTurnsTheRelayOff() {
        FakeCi ci = new FakeCi();
        Recorder ctx = new Recorder();
        CiLogStream stream = new CiLogStream(ci, ctx);

        for (int poll = 0; poll < 6; poll++) {
            stream.follow("run-1");
        }

        assertThat(ctx.logs).containsExactly(
                CiLogStream.PREFIX + "following ci run run-1",
                CiLogStream.PREFIX + "the run is not readable — waiting without its log");
        assertThat(ci.reads).isEqualTo(3);
    }

    /** Answers a scripted run read; an empty script is a read that did not answer. */
    static class FakeCi extends CiApi {
        final Deque<String> answers = new ArrayDeque<>();
        int reads;

        FakeCi() {
            super(new Http(), "http://127.0.0.1:1/ci");
        }

        @Override
        public Optional<JsonNode> run(String runId) {
            reads++;
            String body = answers.poll();
            return body == null ? Optional.empty() : Optional.of(Json.parse(body));
        }
    }

    static class Recorder implements PhaseContext {
        final List<String> logs = new ArrayList<>();

        @Override
        public void log(String line) {
            logs.add(line);
        }

        @Override
        public void status(String status) {
        }

        @Override
        public void note(String note) {
        }

        @Override
        public void warn(String message) {
            logs.add("!! " + message);
        }
    }
}
