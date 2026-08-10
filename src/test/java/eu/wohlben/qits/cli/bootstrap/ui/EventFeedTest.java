package eu.wohlben.qits.cli.bootstrap.ui;

import com.fasterxml.jackson.databind.JsonNode;
import eu.wohlben.qits.cli.bootstrap.api.EventsApi;
import eu.wohlben.qits.cli.bootstrap.api.Http;
import eu.wohlben.qits.cli.bootstrap.api.Json;
import eu.wohlben.qits.cli.bootstrap.engine.Phase;
import eu.wohlben.qits.cli.bootstrap.engine.PhaseOutcome;
import eu.wohlben.qits.cli.bootstrap.engine.RunResult;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Turning the platform's event log into one line each, and following it without reading a row
 * twice. The thread and the HTTP are not here — a real bootstrap is what proves those.
 */
class EventFeedTest {

    private static final ZoneId UTC = ZoneOffset.UTC;

    private static JsonNode event(String json) {
        return Json.parse(json);
    }

    // --- one event, one line ----------------------------------------------------------------------

    /** An envelope as the API answers it: the payload is canonical JSON inside a string. */
    private static String envelope(String head, String payload) {
        return "{" + head + ",\"payload\":" + Json.quote(payload) + "}";
    }

    @Test
    void aReleaseIsItsPackageAndItsVersion() {
        assertThat(EventFeed.line(event(envelope(
                "\"id\":\"e1\",\"name\":\"SoftwareRelease\",\"occurredAt\":\"2026-08-10T14:22:07Z\"",
                "{\"packageName\":\"@qits/ui-components\",\"repository\":\"qits-ui\","
                        + "\"version\":\"1.4.2\"}")), UTC))
                .isEqualTo("14:22:07 SoftwareRelease @qits/ui-components 1.4.2");
    }

    /** A repository that publishes three artifacts must not print the same line three times. */
    @Test
    void thePackageNameWinsOverTheRepository() {
        assertThat(EventFeed.line(event(envelope(
                "\"name\":\"SoftwareRelease\",\"occurredAt\":\"2026-08-10T14:22:07Z\"",
                "{\"packageName\":\"qits/qits-stt\",\"repository\":\"qits-stt\"}")), UTC))
                .isEqualTo("14:22:07 SoftwareRelease qits/qits-stt");
    }

    /** The row id can be a UUID minted per platform; the name is what a person recognises. */
    @Test
    void theRepositoryNameWinsOverTheRepositoryId() {
        assertThat(EventFeed.line(event(envelope(
                "\"name\":\"SCMRelease\",\"occurredAt\":\"2026-08-10T09:05:00Z\"",
                "{\"repository\":\"3f2b-uuid\",\"repositoryName\":\"qits-projects\","
                        + "\"version\":\"2026.810.090500\"}")), UTC))
                .isEqualTo("09:05:00 SCMRelease qits-projects 2026.810.090500");
    }

    @Test
    void aBuildIsItsRepositoryWithNoVersionToShow() {
        assertThat(EventFeed.line(event(envelope(
                "\"name\":\"BuildSuccessful\",\"occurredAt\":\"2026-08-10T14:20:00Z\"",
                "{\"repoId\":\"qits-stt\",\"branch\":\"environment/prod\"}")), UTC))
                .isEqualTo("14:20:00 BuildSuccessful qits-stt");
    }

    @Test
    void anEventWithNothingToNameFallsBackToItsDescription() {
        assertThat(EventFeed.line(event("{\"name\":\"Reconciled\","
                + "\"occurredAt\":\"2026-08-10T14:20:00Z\",\"payload\":\"{}\","
                + "\"description\":\"the deployer adopted three containers\"}"), UTC))
                .isEqualTo("14:20:00 Reconciled — the deployer adopted three containers");
    }

    /** The payload is opaque to the service that stores it, so anything at all can arrive in it. */
    @Test
    void aPayloadThatIsNotJsonCostsTheLineNothingButItsSubject() {
        assertThat(EventFeed.line(event(envelope(
                "\"name\":\"Odd\",\"occurredAt\":\"2026-08-10T14:20:00Z\"", "not json at all")), UTC))
                .isEqualTo("14:20:00 Odd");
    }

    @Test
    void anEnvelopeWithNoNameAndNoTimeStillMakesALine() {
        assertThat(EventFeed.line(event("{}"), UTC)).isEqualTo("--:--:-- event");
    }

    @Test
    void aLongDescriptionIsCutToOneLine() {
        String line = EventFeed.line(event("{\"name\":\"N\",\"occurredAt\":\"2026-08-10T00:00:00Z\","
                + "\"description\":\"" + "x".repeat(300) + "\"}"), UTC);
        assertThat(line).hasSize("00:00:00 N — ".length() + 100).endsWith("…");
    }

    @Test
    void theClockIsTheWatchersOwn() {
        assertThat(EventFeed.line(
                event("{\"name\":\"N\",\"occurredAt\":\"2026-08-10T14:22:07Z\"}"),
                ZoneId.of("Europe/Berlin")))
                .isEqualTo("16:22:07 N");
    }

    // --- following the log, poll by poll ----------------------------------------------------------

    @Test
    void theFirstContactTakesTheHeadAndShowsNoHistory() {
        FakeEvents api = new FakeEvents();
        Recorder ui = new Recorder();
        EventFeed feed = feed(api, ui);

        api.answers.add(page("{\"id\":\"old\",\"name\":\"Ancient\","
                + "\"occurredAt\":\"2026-01-01T00:00:00Z\"}"));
        feed.poll();

        assertThat(ui.lines).isEmpty();
        assertThat(api.asked).containsExactly("desc/null");
    }

    @Test
    void whatArrivesAfterTheHeadIsShownOnce() {
        FakeEvents api = new FakeEvents();
        Recorder ui = new Recorder();
        EventFeed feed = feed(api, ui);

        api.answers.add(page("{\"id\":\"head\",\"occurredAt\":\"2026-08-10T10:00:00Z\"}"));
        api.answers.add(page("{\"id\":\"a\",\"name\":\"One\",\"occurredAt\":\"2026-08-10T10:00:05Z\"}"));
        api.answers.add(page("{\"id\":\"b\",\"name\":\"Two\",\"occurredAt\":\"2026-08-10T10:00:09Z\"}"));
        feed.poll();
        feed.poll();
        feed.poll();

        assertThat(ui.lines).containsExactly("10:00:05 One", "10:00:09 Two");
        // Each ascending read resumes after the last row of the one before it.
        assertThat(api.asked).containsExactly(
                "desc/null",
                "asc/2026-08-10T10:00:00Z,head",
                "asc/2026-08-10T10:00:05Z,a");
    }

    /**
     * {@code nextCursor} is null on the last page, and a caught-up feed reads only last pages. The
     * watermark has to come from the page's own rows or the follow restarts on every poll.
     */
    @Test
    void aPageThatSaysItIsTheLastStillAdvancesTheWatermark() {
        FakeEvents api = new FakeEvents();
        Recorder ui = new Recorder();
        EventFeed feed = feed(api, ui);

        api.answers.add(page("{\"id\":\"head\",\"occurredAt\":\"2026-08-10T10:00:00Z\"}"));
        api.answers.add("{\"events\":[{\"id\":\"a\",\"name\":\"One\","
                + "\"occurredAt\":\"2026-08-10T10:00:05Z\"}],\"nextCursor\":null}");
        api.answers.add(page());
        feed.poll();
        feed.poll();
        feed.poll();

        assertThat(api.asked).containsExactly("desc/null", "asc/2026-08-10T10:00:00Z,head",
                "asc/2026-08-10T10:00:05Z,a");
    }

    /** A row with nothing to resume from leaves the watermark — and must not be shown again. */
    @Test
    void aRowThatCannotAdvanceTheWatermarkIsNotShownTwice() {
        FakeEvents api = new FakeEvents();
        Recorder ui = new Recorder();
        EventFeed feed = feed(api, ui);

        String stuck = page("{\"id\":\"a\",\"name\":\"One\"}");
        api.answers.add(page("{\"id\":\"head\",\"occurredAt\":\"2026-08-10T10:00:00Z\"}"));
        api.answers.add(stuck);
        api.answers.add(stuck);
        feed.poll();
        feed.poll();
        feed.poll();

        assertThat(ui.lines).containsExactly("--:--:-- One");
    }

    /** Most of a bootstrap has no bus to read. The feed keeps asking and says nothing. */
    @Test
    void aServiceThatDoesNotAnswerIsSilentAndKeepsTrying() {
        FakeEvents api = new FakeEvents();
        Recorder ui = new Recorder();
        EventFeed feed = feed(api, ui);

        feed.poll();
        feed.poll();
        api.answers.add(page("{\"id\":\"head\",\"occurredAt\":\"2026-08-10T10:00:00Z\"}"));
        feed.poll();
        api.answers.add(page("{\"id\":\"a\",\"name\":\"Up\",\"occurredAt\":\"2026-08-10T10:00:01Z\"}"));
        feed.poll();

        assertThat(ui.lines).containsExactly("10:00:01 Up");
        assertThat(api.asked).containsExactly("desc/null", "desc/null", "desc/null",
                "asc/2026-08-10T10:00:00Z,head");
    }

    /** An empty log is not a missing head: reading forward from nothing is the same feed. */
    @Test
    void anEmptyLogIsFollowedFromItsBeginning() {
        FakeEvents api = new FakeEvents();
        Recorder ui = new Recorder();
        EventFeed feed = feed(api, ui);

        api.answers.add(page());
        api.answers.add(page("{\"id\":\"a\",\"name\":\"First\",\"occurredAt\":\"2026-08-10T10:00:01Z\"}"));
        feed.poll();
        feed.poll();

        assertThat(ui.lines).containsExactly("10:00:01 First");
        assertThat(api.asked).containsExactly("desc/null", "asc/null");
    }

    /** The courtesy rule: a display that breaks costs its line and nothing else. */
    @Test
    void aDisplayThatThrowsDoesNotStopTheFeed() {
        FakeEvents api = new FakeEvents();
        Recorder ui = new Recorder() {
            @Override
            public void event(String line) {
                super.event(line);
                throw new IllegalStateException("the browser view broke");
            }
        };
        EventFeed feed = feed(api, ui);

        api.answers.add(page("{\"id\":\"head\",\"occurredAt\":\"2026-08-10T10:00:00Z\"}"));
        api.answers.add(page("{\"id\":\"a\",\"name\":\"One\",\"occurredAt\":\"2026-08-10T10:00:01Z\"}",
                "{\"id\":\"b\",\"name\":\"Two\",\"occurredAt\":\"2026-08-10T10:00:02Z\"}"));
        feed.poll();
        feed.poll();

        assertThat(ui.lines).containsExactly("10:00:01 One", "10:00:02 Two");
    }

    private static EventFeed feed(FakeEvents api, Ui ui) {
        return new EventFeed(api, ui, Duration.ofSeconds(1), UTC);
    }

    private static String page(String... events) {
        return "{\"events\":[" + String.join(",", events) + "]}";
    }

    /** Answers a scripted page; an empty script is a read that did not answer. */
    static class FakeEvents extends EventsApi {
        final Deque<String> answers = new ArrayDeque<>();
        final List<String> asked = new ArrayList<>();

        FakeEvents() {
            super(new Http(), "http://127.0.0.1:1/events");
        }

        @Override
        public Optional<JsonNode> page(String cursor, boolean ascending, int limit) {
            asked.add((ascending ? "asc/" : "desc/") + cursor);
            String body = answers.poll();
            return body == null ? Optional.empty() : Optional.of(Json.parse(body));
        }
    }

    /** A display that remembers only the event channel. */
    static class Recorder implements Ui {
        final List<String> lines = new ArrayList<>();

        @Override
        public void started(List<Phase> phases) {
        }

        @Override
        public void phaseStarted(int index, Phase phase) {
        }

        @Override
        public void output(String line) {
        }

        @Override
        public void status(String status) {
        }

        @Override
        public void phaseFinished(PhaseOutcome outcome) {
        }

        @Override
        public void message(String line) {
        }

        @Override
        public void event(String line) {
            lines.add(line);
        }

        @Override
        public void finished(RunResult result) {
        }

        @Override
        public void close() {
        }
    }
}
