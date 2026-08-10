package eu.wohlben.qits.cli.bootstrap.ui;

import eu.wohlben.qits.cli.bootstrap.api.Json;
import eu.wohlben.qits.cli.bootstrap.engine.Phase;
import eu.wohlben.qits.cli.bootstrap.engine.PhaseOutcome;
import eu.wohlben.qits.cli.bootstrap.engine.PhaseState;
import eu.wohlben.qits.cli.bootstrap.engine.RunResult;
import eu.wohlben.qits.cli.bootstrap.proc.TailBuffer;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * What the run looks like right now, in a form a browser can read: the phase list, the current
 * wait, and the running step's tail.
 * <p>
 * The engine writes to it from its own thread and the HTTP server reads from the event loop, so
 * every method is synchronized and everything handed out is a copy.
 * <p>
 * Beside the state it keeps a short ring of what just changed. A browser that connects gets one
 * snapshot and then only the changes, and a browser that falls behind the ring gets a new snapshot
 * rather than a hole.
 */
public final class BootState {

    /** How many changes are kept for a connection that is between polls. */
    private static final int EVENT_RING = 512;

    /** The state of the run in this process, for the HTTP layer to find. */
    private static final AtomicReference<BootState> PUBLISHED = new AtomicReference<>();

    /** How many browsers are reading the stream right now. */
    private static final AtomicInteger READERS = new AtomicInteger();

    /** One change, ready to send. */
    public record Event(long seq, String type, String json) {
    }

    /** The right column's lines. Its own ring, because it is not cleared with the step's tail. */
    private static final int EVENT_LINES = 500;

    private final TailBuffer tail;
    private final TailBuffer eventTail = new TailBuffer(EVENT_LINES);
    private final Deque<Event> events = new ArrayDeque<>();
    private final List<Phase> phases = new ArrayList<>();
    private final List<PhaseState> states = new ArrayList<>();
    private final List<Long> tookMillis = new ArrayList<>();
    private final List<String> notes = new ArrayList<>();

    private long seq;
    private long startedAt = System.currentTimeMillis();
    private long currentStartedAt;
    private int currentIndex = -1;
    private String status = "";
    private String summary = "";
    private Integer exitCode;
    private String logPath = "";

    public BootState(int tailLines) {
        this.tail = new TailBuffer(Math.max(50, tailLines));
    }

    /**
     * The state the HTTP layer serves. There is one run per process, so this is the run — and
     * before it starts, an empty one, so a browser opened early sees a page rather than an error.
     */
    public static BootState published() {
        return PUBLISHED.updateAndGet(state -> state != null ? state : new BootState(2000));
    }

    public static void publish(BootState state) {
        PUBLISHED.set(state);
    }

    public static void readerJoined() {
        READERS.incrementAndGet();
    }

    public static void readerLeft() {
        READERS.decrementAndGet();
    }

    /** Whether anyone is watching — the one thing the closing display asks. */
    public static boolean watched() {
        return READERS.get() > 0;
    }

    public synchronized void logPath(String path) {
        this.logPath = path == null ? "" : path;
    }

    public synchronized void started(List<Phase> plan) {
        phases.clear();
        states.clear();
        tookMillis.clear();
        notes.clear();
        for (Phase phase : plan) {
            phases.add(phase);
            states.add(PhaseState.PENDING);
            tookMillis.add(0L);
            notes.add("");
        }
        startedAt = System.currentTimeMillis();
        currentIndex = -1;
        status = "";
        summary = "";
        exitCode = null;
        // The whole plan changed, so the cheapest correct change is the whole state.
        emit("snapshot", snapshotJson());
    }

    public synchronized void phaseStarted(int index, Phase phase) {
        grow(index, phase);
        states.set(index, PhaseState.RUNNING);
        currentIndex = index;
        currentStartedAt = System.currentTimeMillis();
        status = "";
        // The tail belongs to the step that is running, the way the terminal display's does.
        tail.clear();
        emit("snapshot", snapshotJson());
    }

    public synchronized void output(String line) {
        tail.add(line);
        emit("line", Json.object("text", line));
    }

    /**
     * One line of what the platform announced. The frame is named {@code ev} rather than
     * {@code event} because in the browser every frame is an event, and a stream whose event type
     * is "event" reads as a mistake.
     */
    public synchronized void event(String line) {
        eventTail.add(line);
        emit("ev", Json.object("text", line));
    }

    public synchronized void status(String status) {
        if (status == null) {
            return;
        }
        this.status = status;
        emit("status", Json.object("text", status));
    }

    public synchronized void phaseFinished(PhaseOutcome outcome) {
        int index = outcome.index();
        grow(index, outcome.phase());
        states.set(index, outcome.state());
        tookMillis.set(index, outcome.took() == null ? 0L : outcome.took().toMillis());
        notes.set(index, note(outcome));
        if (currentIndex == index) {
            currentIndex = -1;
            status = "";
        }
        emit("phase", phaseJson(index));
    }

    public synchronized void finished(RunResult result) {
        summary = PlainUi.summary(result);
        exitCode = result.exitCode();
        currentIndex = -1;
        emit("done", "{\"summary\":" + Json.quote(summary) + ",\"exitCode\":" + exitCode + "}");
    }

    /** The whole state, which is what a new connection and {@code /state.json} both want. */
    public synchronized String snapshotJson() {
        StringBuilder json = new StringBuilder("{");
        json.append("\"seq\":").append(seq);
        json.append(",\"now\":").append(System.currentTimeMillis());
        json.append(",\"total\":").append(phases.size());
        json.append(",\"currentIndex\":").append(currentIndex);
        // The run's identity, for the page: a tab left open from an EARLIER run keeps that run's
        // HTML and quietly reattaches its stream to the next one — measured as "the new layout was
        // never added". A snapshot naming a different boot makes the page reload itself.
        json.append(",\"bootId\":").append(startedAt);
        json.append(",\"runElapsedMs\":").append(System.currentTimeMillis() - startedAt);
        json.append(",\"currentElapsedMs\":")
                .append(currentIndex < 0 ? 0 : System.currentTimeMillis() - currentStartedAt);
        json.append(",\"status\":").append(Json.quote(status));
        json.append(",\"summary\":").append(Json.quote(summary));
        json.append(",\"exitCode\":").append(exitCode == null ? "null" : exitCode);
        json.append(",\"log\":").append(Json.quote(logPath));
        json.append(",\"phases\":[");
        for (int i = 0; i < phases.size(); i++) {
            if (i > 0) {
                json.append(',');
            }
            json.append(phaseJson(i));
        }
        json.append("],\"tail\":[");
        quoted(json, tail.all());
        json.append("],\"events\":[");
        quoted(json, eventTail.all());
        return json.append("]}").toString();
    }

    private static void quoted(StringBuilder json, List<String> lines) {
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) {
                json.append(',');
            }
            json.append(Json.quote(lines.get(i)));
        }
    }

    /** The changes after {@code cursor}, oldest first. */
    public synchronized List<Event> since(long cursor) {
        List<Event> out = new ArrayList<>();
        for (Event event : events) {
            if (event.seq() > cursor) {
                out.add(event);
            }
        }
        return out;
    }

    /**
     * Whether everything after {@code cursor} is still in the ring. A false answer means the
     * reader was away too long and needs a snapshot.
     */
    public synchronized boolean hasEverythingAfter(long cursor) {
        Event oldest = events.peekFirst();
        return oldest == null || oldest.seq() <= cursor + 1;
    }

    public synchronized long seq() {
        return seq;
    }

    private void emit(String type, String json) {
        events.addLast(new Event(++seq, type, json));
        while (events.size() > EVENT_RING) {
            events.removeFirst();
        }
    }

    private String phaseJson(int index) {
        Phase phase = phases.get(index);
        long took = currentIndex == index
                ? System.currentTimeMillis() - currentStartedAt
                : tookMillis.get(index);
        return "{\"index\":" + index
                + ",\"id\":" + Json.quote(phase.id())
                + ",\"title\":" + Json.quote(phase.title())
                + ",\"state\":" + Json.quote(states.get(index).name())
                + ",\"tookMs\":" + took
                + ",\"note\":" + Json.quote(notes.get(index))
                + "}";
    }

    /**
     * A phase may be reported without the plan ever being announced — a test, or a mode that runs
     * one step. Growing the lists keeps that from being an index error.
     */
    private void grow(int index, Phase phase) {
        while (phases.size() <= index) {
            phases.add(phase);
            states.add(PhaseState.PENDING);
            tookMillis.add(0L);
            notes.add("");
        }
        phases.set(index, phase);
    }

    private static String note(PhaseOutcome outcome) {
        if (outcome.state() == PhaseState.FAILED && outcome.error() != null) {
            return outcome.error().getMessage() == null
                    ? outcome.error().toString() : outcome.error().getMessage();
        }
        return outcome.note() == null ? "" : outcome.note();
    }
}
