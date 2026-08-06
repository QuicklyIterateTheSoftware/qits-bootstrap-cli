package eu.wohlben.qits.cli.bootstrap.engine;

import eu.wohlben.qits.cli.bootstrap.ui.Format;

import java.time.Duration;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.TimeoutException;

/**
 * Every wait on something remote goes through here, and every wait therefore says the same four
 * things: what is being polled, what the last poll saw, how long this has been going on, and when
 * it gives up. Visible waiting is the whole point of this program.
 */
public final class Waiter {

    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss");

    /** The answer of one poll: a value once there is one, and always a word about what was seen. */
    public record Poll<T>(T value, String observed) {
        public static <T> Poll<T> done(T value, String observed) {
            return new Poll<>(value, observed);
        }

        public static <T> Poll<T> pending(String observed) {
            return new Poll<>(null, observed);
        }
    }

    @FunctionalInterface
    public interface Probe<T> {
        Poll<T> poll() throws Exception;
    }

    private Waiter() {
    }

    public static <T> T await(PhaseContext ctx, String what, Duration timeout, Duration interval,
                              Probe<T> probe) throws Exception {
        return await(ctx, what, timeout, interval, probe, System::currentTimeMillis, Thread::sleep);
    }

    /** The clock and the sleep are arguments so a test can run a wait in no time at all. */
    public static <T> T await(PhaseContext ctx, String what, Duration timeout, Duration interval,
                              Probe<T> probe, Clock clock, Sleeper sleeper) throws Exception {
        long start = clock.millis();
        long deadline = start + timeout.toMillis();
        String lastObserved = "";
        while (true) {
            Poll<T> poll = probe.poll();
            if (poll.value() != null) {
                ctx.status("");
                return poll.value();
            }
            lastObserved = poll.observed();
            long now = clock.millis();
            Duration elapsed = Duration.ofMillis(now - start);
            if (now >= deadline) {
                throw new TimeoutException("gave up waiting for " + what + " after "
                        + Format.duration(timeout) + " — last seen: " + lastObserved);
            }
            ctx.status(status(what, lastObserved, elapsed, timeout, deadline));
            sleeper.sleep(Math.min(interval.toMillis(), Math.max(1, deadline - now)));
        }
    }

    static String status(String what, String observed, Duration elapsed, Duration timeout,
                         long deadlineMillis) {
        String until = LocalTime.now().plusNanos((deadlineMillis - System.currentTimeMillis())
                * 1_000_000L).format(CLOCK);
        return "waiting for " + what + " — " + observed
                + " · " + Format.duration(elapsed) + " elapsed, gives up after "
                + Format.duration(timeout) + " (at " + until + ")";
    }

    @FunctionalInterface
    public interface Clock {
        long millis();
    }

    @FunctionalInterface
    public interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }
}
