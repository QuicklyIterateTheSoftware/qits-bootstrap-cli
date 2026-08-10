package eu.wohlben.qits.cli.bootstrap.ui;

import com.fasterxml.jackson.databind.JsonNode;
import eu.wohlben.qits.cli.bootstrap.api.EventsApi;
import eu.wohlben.qits.cli.bootstrap.api.Http;
import eu.wohlben.qits.cli.bootstrap.api.Json;
import eu.wohlben.qits.cli.bootstrap.config.BootstrapConfig;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The platform's events, followed for as long as the boot runs and fed to the displays.
 * <p>
 * The left of the screen says what this program is doing; this is what the PLATFORM says it is
 * doing, so an operator can see the cause and the effect at once: a push, then a ci run, then a
 * release, then a deployment — announced by the services themselves rather than inferred from the
 * phase that is waiting.
 * <p>
 * <b>It follows a service the boot itself deploys, and redeploys.</b> qits-events does not exist for
 * the first phases, arrives with the seed, and goes away again when the platform deploys its own
 * successor. So there is no connect step and no disconnect: every poll is a fresh request through
 * {@link Http}, which builds a fresh client and a fresh name lookup each time, and a read that does
 * not answer is simply a poll with nothing in it. Coming and going is silent in both directions —
 * an operator watching a bootstrap does not need to be told twice a run that the bus is mid-cutover.
 * <p>
 * <b>The feed starts at the HEAD.</b> First contact asks for the newest row alone and keeps it as
 * the watermark, so what shows up is what is happening now. A platform reseeded over a surviving
 * database has months of history and none of it is this run's.
 * <p>
 * <b>Never fails or slows the boot.</b> It is CiLogStream's courtesy rule with nothing left to fail:
 * it runs on its own daemon thread, every read is an answer rather than an exception, and a display
 * that throws costs one line.
 */
public final class EventFeed implements AutoCloseable {

    /** Often enough to feel live, rare enough to be free. qits-ci's own client polls on three. */
    private static final Duration INTERVAL = Duration.ofSeconds(4);

    /**
     * A read's own deadline, well under the 30s the phases allow. Nothing waits on this thread, but
     * a poll still stuck on a half-open connection is a feed that has stopped without saying so.
     */
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(10);

    /** Rows per poll. A busy release train makes tens; the whole log is a browser away. */
    private static final int PAGE = 50;

    /** Ids kept to recognise a row already shown. Two pages' worth — see {@link #poll}. */
    private static final int REMEMBERED_IDS = 128;

    /** Payload fields that say WHICH thing an event is about, most specific first. */
    private static final List<String> SUBJECTS =
            List.of("packageName", "repositoryName", "repository", "repoId", "application");

    /** Prose is bounded here rather than at the display: one event is worth one line. */
    private static final int MAX_DESCRIPTION = 100;

    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final EventsApi events;
    private final Ui ui;
    private final Duration interval;
    private final ZoneId zone;

    private final Deque<String> shown = new ArrayDeque<>();
    private final Set<String> shownIds = new HashSet<>();

    /** Where the last page stopped: {@code <occurredAt>,<id>}. Null is "from the beginning". */
    private String cursor;

    /** Whether the head has been found. Until it is, every poll is another attempt to find it. */
    private boolean atHead;

    private Thread thread;

    EventFeed(EventsApi events, Ui ui, Duration interval, ZoneId zone) {
        this.events = events;
        this.ui = ui;
        this.interval = interval;
        this.zone = zone;
    }

    /**
     * The feed for a run, already following. {@code QITS_EVENTS_FEED=0} gives one that does nothing,
     * so the caller has no branch of its own.
     */
    public static EventFeed start(BootstrapConfig config, Ui ui) {
        EventFeed feed = new EventFeed(new EventsApi(new Http(READ_TIMEOUT), config.eventsUrl()),
                ui, INTERVAL, ZoneId.systemDefault());
        if (config.eventsFeed()) {
            feed.thread = Thread.ofPlatform().name("events-feed").daemon().start(feed::loop);
        }
        return feed;
    }

    private void loop() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                Thread.sleep(interval);
            } catch (InterruptedException stopping) {
                Thread.currentThread().interrupt();
                return;
            }
            poll();
        }
    }

    /**
     * One read, and everything it can go wrong with is nothing.
     * <p>
     * <b>The watermark is the page's own last row, not {@code nextCursor}.</b> That field is null on
     * the last page, and for a feed that has caught up the last page is EVERY page — reading it as
     * "nowhere to resume" would restart the follow at the head on every poll.
     * <p>
     * The ids are remembered beside it because the watermark is the only thing that stops a row
     * arriving twice: a row this program cannot build a cursor from (no id, no time) leaves the
     * watermark where it was, and without the ids that page would scroll past again every four
     * seconds for the rest of the boot.
     */
    void poll() {
        try {
            if (!atHead) {
                findHead();
                return;
            }
            Optional<JsonNode> page = events.page(cursor, true, PAGE);
            if (page.isEmpty()) {
                return;
            }
            for (JsonNode event : page.get().path("events")) {
                String id = Json.text(event, "id");
                if (!remember(id)) {
                    continue;
                }
                show(line(event, zone));
            }
            cursor = lastCursor(page.get(), cursor);
        } catch (RuntimeException e) {
            // A courtesy that throws is still only a courtesy. The boot never learns of it.
        }
    }

    /**
     * The newest row, kept as the watermark without being shown — the feed is what is happening now.
     * <p>
     * An empty log leaves the watermark null, and that is right rather than a missing case: reading
     * ascending from the beginning of a log with nothing in it yields exactly the rows written
     * after this moment.
     */
    private void findHead() {
        Optional<JsonNode> head = events.page(null, false, 1);
        if (head.isEmpty()) {
            return;
        }
        cursor = lastCursor(head.get(), null);
        atHead = true;
    }

    /** Feeds one line, and swallows a display that breaks on it. */
    private void show(String line) {
        try {
            ui.event(line);
        } catch (RuntimeException e) {
            // No display is worth a boot, and this one is worth less than the others.
        }
    }

    /** Whether this row is new. Blank ids are always new — there is nothing to remember them by. */
    private boolean remember(String id) {
        if (id == null || id.isBlank()) {
            return true;
        }
        if (!shownIds.add(id)) {
            return false;
        }
        shown.addLast(id);
        while (shown.size() > REMEMBERED_IDS) {
            shownIds.remove(shown.removeFirst());
        }
        return true;
    }

    /**
     * Where this page stops, or {@code fallback} when it holds no row that can be resumed from.
     * Keeping the old watermark is what makes an unreadable page cost nothing instead of a replay.
     */
    static String lastCursor(JsonNode page, String fallback) {
        String cursor = fallback;
        for (JsonNode event : page.path("events")) {
            String at = Json.text(event, "occurredAt");
            String id = Json.text(event, "id");
            if (!at.isBlank() && !id.isBlank()) {
                cursor = at + "," + id;
            }
        }
        return cursor;
    }

    /**
     * One event, one line: the local clock, the event's name, and the most telling thing its payload
     * carries.
     * <p>
     * <b>The payload is a JSON string inside the envelope and is read defensively.</b> qits-events
     * stores it verbatim and parses none of it, so what arrives is whatever a publisher wrote —
     * canonical JSON today, and an unparseable string is a display question rather than an error.
     * <p>
     * {@code packageName} comes before the repository fields on purpose: a repository that publishes
     * three artifacts announces three releases, and named by their repository they would be the same
     * line three times.
     */
    static String line(JsonNode event, ZoneId zone) {
        String name = Json.text(event, "name");
        JsonNode payload = Json.parse(Json.text(event, "payload"));
        StringBuilder line = new StringBuilder(clock(Json.text(event, "occurredAt"), zone))
                .append(' ').append(name.isBlank() ? "event" : name);

        String subject = firstOf(payload, SUBJECTS);
        if (!subject.isBlank()) {
            line.append(' ').append(subject);
            String version = Json.text(payload, "version");
            if (!version.isBlank()) {
                line.append(' ').append(version);
            }
            return line.toString();
        }
        String description = Json.text(event, "description");
        if (!description.isBlank()) {
            line.append(" — ").append(description.length() <= MAX_DESCRIPTION ? description
                    : description.substring(0, MAX_DESCRIPTION - 1) + "…");
        }
        return line.toString();
    }

    private static String firstOf(JsonNode payload, List<String> fields) {
        for (String field : fields) {
            String value = Json.text(payload, field);
            if (!value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    /**
     * The event's own instant on the wall clock of whoever is watching. An event carries the time it
     * happened, and a bootstrap is read against the clock on the wall beside the screen.
     */
    private static String clock(String occurredAt, ZoneId zone) {
        try {
            return CLOCK.format(Instant.parse(occurredAt).atZone(zone));
        } catch (DateTimeParseException noTime) {
            return "--:--:--";
        }
    }

    /** Stops following. The thread is a daemon, so this is tidiness rather than a requirement. */
    @Override
    public void close() {
        if (thread != null) {
            thread.interrupt();
            thread = null;
        }
    }
}
