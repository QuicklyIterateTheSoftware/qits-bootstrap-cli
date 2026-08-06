package eu.wohlben.qits.cli.bootstrap.engine;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Every remote wait says what it is polling, what it saw, how long, and when it gives up. */
class WaiterTest {

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
            logs.add("!! " + message);
        }
    }

    @Test
    void returnsAsSoonAsThePollHasAnAnswer() throws Exception {
        Recorder ctx = new Recorder();
        AtomicLong clock = new AtomicLong();
        AtomicInteger polls = new AtomicInteger();

        String value = Waiter.await(ctx, "a deployment row", Duration.ofMinutes(10),
                Duration.ofSeconds(10),
                () -> polls.incrementAndGet() < 3
                        ? Waiter.Poll.pending("ci run RUNNING, deployment no row yet")
                        : Waiter.Poll.done("ACTIVE", "ACTIVE"),
                clock::get, clock::addAndGet);

        assertThat(value).isEqualTo("ACTIVE");
        assertThat(polls.get()).isEqualTo(3);
        assertThat(ctx.statuses).hasSize(3);
        assertThat(ctx.statuses.getFirst())
                .contains("waiting for a deployment row")
                .contains("ci run RUNNING, deployment no row yet")
                .contains("gives up after 10m00s");
        // The last thing a finished wait does is take its status off the screen.
        assertThat(ctx.statuses.getLast()).isEmpty();
    }

    @Test
    void theStatusCarriesTheElapsedTime() throws Exception {
        Recorder ctx = new Recorder();
        AtomicLong clock = new AtomicLong();
        AtomicInteger polls = new AtomicInteger();

        Waiter.await(ctx, "qits-ci", Duration.ofMinutes(10), Duration.ofSeconds(30),
                () -> polls.incrementAndGet() < 4
                        ? Waiter.Poll.pending("no answer")
                        : Waiter.Poll.done("ready", "ready"),
                clock::get, clock::addAndGet);

        assertThat(ctx.statuses.get(0)).contains("0s elapsed");
        assertThat(ctx.statuses.get(1)).contains("30s elapsed");
        assertThat(ctx.statuses.get(2)).contains("1m00s elapsed");
    }

    @Test
    void givingUpSaysWhatItLastSaw() {
        Recorder ctx = new Recorder();
        AtomicLong clock = new AtomicLong();

        assertThatThrownBy(() -> Waiter.await(ctx, "a deployment row for qits-stt",
                Duration.ofMinutes(1), Duration.ofSeconds(10),
                () -> Waiter.Poll.pending("ci run RUNNING, deployment no row yet"),
                clock::get, clock::addAndGet))
                .isInstanceOf(TimeoutException.class)
                .hasMessageContaining("gave up waiting for a deployment row for qits-stt")
                .hasMessageContaining("after 1m00s")
                .hasMessageContaining("last seen: ci run RUNNING, deployment no row yet");
    }

    @Test
    void neverSleepsPastItsDeadline() throws Exception {
        Recorder ctx = new Recorder();
        AtomicLong clock = new AtomicLong();
        AtomicInteger polls = new AtomicInteger();

        try {
            Waiter.await(ctx, "something", Duration.ofSeconds(25), Duration.ofSeconds(10),
                    () -> {
                        polls.incrementAndGet();
                        return Waiter.Poll.pending("nothing");
                    },
                    clock::get, clock::addAndGet);
        } catch (TimeoutException expected) {
            assertThat(clock.get()).isEqualTo(25_000);
            assertThat(polls.get()).isEqualTo(4);
            return;
        }
        throw new AssertionError("the wait should have timed out");
    }
}
