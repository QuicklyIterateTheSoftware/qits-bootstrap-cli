package eu.wohlben.qits.cli.bootstrap.phases;

import com.fasterxml.jackson.databind.JsonNode;
import eu.wohlben.qits.cli.bootstrap.api.CiApi;
import eu.wohlben.qits.cli.bootstrap.api.Json;
import eu.wohlben.qits.cli.bootstrap.engine.PhaseContext;
import eu.wohlben.qits.cli.bootstrap.proc.Ansi;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * A ci run, read out loud while a phase waits for it.
 * <p>
 * The wait for a deployment used to be silent for as long as the build took, and a cold native
 * build is fifty minutes. "No cpu utilization, I think it's stalled" is what that silence reads as,
 * and the only way to tell a stalled build from a slow one was to leave the boot and go look at
 * qits-ci.
 * <p>
 * <b>Polling, because there is nothing else.</b> qits-ci serves no SSE and no websocket for run
 * logs — {@code CiRunController.getRun}'s own javadoc says "following along is polling this
 * endpoint", and its Angular client does exactly that every three seconds. So this reads the same
 * {@code GET /ci/api/runs/{runId}} the wait is already timed by, on the wait's own interval, and
 * needs no client this program does not already have.
 * <p>
 * What that endpoint gives is a step's output <b>whole</b> on every read, and only a bounded tail
 * of it: finished steps as rows, the step in flight as the {@code live} object. So the work here is
 * subtraction — what is in this read that was not in the last one — and it is done by overlap
 * rather than by length, because the tail rolls and the same output can arrive shorter than it was.
 * <p>
 * <b>Never fails a phase.</b> The endpoint is a courtesy, not a dependency: reads that stop
 * answering turn the relay off with one line saying so, and the wait goes on exactly as it did
 * before this existed.
 */
public final class CiLogStream {

    /** Marks a line as the build's rather than the boot's. Narrow, because build lines are wide. */
    static final String PREFIX = "  ci| ";

    /** Lines shown per poll. Above this the boot's own output is drowned; the run log keeps all. */
    private static final int LINES_PER_POLL = 40;

    /** How much of the current step is remembered — only enough to recognise the overlap. */
    private static final int REMEMBERED_LINES = 200;

    /** Reads that may fail before the relay gives up. A cutover blips; a missing endpoint does not. */
    private static final int TOLERATED_FAILURES = 3;

    private final CiApi ci;
    private final PhaseContext ctx;

    private String runId;
    private int step = -1;
    private List<String> seen = List.of();
    private int failures;
    private boolean off;

    public CiLogStream(CiApi ci, PhaseContext ctx) {
        this.ci = ci;
        this.ctx = ctx;
    }

    /**
     * Reads whatever this run has printed since the last call. A null id is "no run to follow yet",
     * which is the ordinary state of the first few polls and says nothing.
     */
    public void follow(String runId) {
        if (off || runId == null || runId.isBlank()) {
            return;
        }
        if (!runId.equals(this.runId)) {
            this.runId = runId;
            step = -1;
            seen = List.of();
            ctx.log(PREFIX + "following ci run " + runId);
        }
        Optional<JsonNode> run = ci.run(runId);
        if (run.isEmpty()) {
            // The edge this read travels through is an application the boot deploys,
            // so a single miss is a cutover rather than an answer.
            if (++failures >= TOLERATED_FAILURES) {
                off = true;
                ctx.log(PREFIX + "the run is not readable — waiting without its log");
            }
            return;
        }
        failures = 0;
        relay(run.get());
    }

    /**
     * Steps first, then the one in flight. A step's row carries its whole captured output and the
     * {@code live} object carries the step that has no row yet, so the two never describe the same
     * step and reading them in this order is reading the run in order.
     */
    private void relay(JsonNode run) {
        for (JsonNode row : run.path("steps")) {
            show(row.path("stepIndex").asInt(-1), Json.text(row, "output"), true);
        }
        JsonNode live = run.path("live");
        if (live.isObject()) {
            show(live.path("stepIndex").asInt(-1), Json.text(live, "output"), false);
        }
    }

    private void show(int stepIndex, String output, boolean finished) {
        if (stepIndex < 0 || stepIndex < step) {
            return;
        }
        if (stepIndex > step) {
            step = stepIndex;
            seen = List.of();
            ctx.log(PREFIX + "-- step " + (stepIndex + 1) + " --");
        }
        List<String> lines = lines(output, finished);
        List<String> fresh = newLines(seen, lines);
        int skipped = fresh.size() - LINES_PER_POLL;
        if (skipped > 0) {
            ctx.log(PREFIX + "… " + skipped + " lines skipped, they are in the run log");
            fresh = fresh.subList(skipped, fresh.size());
        }
        fresh.forEach(line -> ctx.log(PREFIX + line));
        seen = lines.size() <= REMEMBERED_LINES ? lines
                : List.copyOf(lines.subList(lines.size() - REMEMBERED_LINES, lines.size()));
    }

    /**
     * The output as lines, with the one still being written held back.
     * <p>
     * A step's output is accumulated from chunks, so a read can land mid-line. Showing that line
     * and then showing it again finished is worse than showing it a poll later — except at the
     * step's end, where there is no later.
     */
    static List<String> lines(String output, boolean finished) {
        if (output == null || output.isEmpty()) {
            return List.of();
        }
        List<String> parts = new ArrayList<>(List.of(output.split("\n", -1)));
        // The last element is either "" (the text ended with a newline) or a line still open.
        String open = parts.removeLast();
        if (finished && !open.isEmpty()) {
            parts.add(open);
        }
        // Build logs carry ANSI, and the display's arithmetic breaks on it — the same cleaning
        // every subprocess line gets.
        return parts.stream().map(Ansi::clean).toList();
    }

    /**
     * What {@code now} holds that {@code seen} did not — matched by overlap, not by length.
     * <p>
     * The endpoint answers with a bounded tail of a step, so the text can grow at the end and be
     * cut at the front between two reads. The longest suffix of what was seen that is also a prefix
     * of what is here now is where the two join. No overlap at all means the tail rolled past
     * everything remembered, and then all of it is new.
     */
    static List<String> newLines(List<String> seen, List<String> now) {
        if (seen.isEmpty() || now.isEmpty()) {
            return now;
        }
        for (int overlap = Math.min(seen.size(), now.size()); overlap > 0; overlap--) {
            if (seen.subList(seen.size() - overlap, seen.size()).equals(now.subList(0, overlap))) {
                return now.subList(overlap, now.size());
            }
        }
        return now;
    }
}
