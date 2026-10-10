package eu.wohlben.qits.cli.bootstrap.ui;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.ZoneOffset;

/**
 * The event feed's own readers of a qits-events page, opened to the consumer pacts in
 * {@code eu.wohlben.qits.cli.bootstrap.contracts}. Test code only.
 */
public final class EventFeedReads {

    private EventFeedReads() {
    }

    /** The cursor the feed keeps after a page: the last row's {@code occurredAt,id}. */
    public static String lastCursor(JsonNode page) {
        return EventFeed.lastCursor(page, null);
    }

    /** The line the feed shows for one event, in UTC. */
    public static String line(JsonNode event) {
        return EventFeed.line(event, ZoneOffset.UTC);
    }
}
