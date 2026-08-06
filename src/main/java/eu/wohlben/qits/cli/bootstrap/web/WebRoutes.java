package eu.wohlben.qits.cli.bootstrap.web;

import eu.wohlben.qits.cli.bootstrap.ui.BootState;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

import java.util.List;

/**
 * The three doors of the browser view:
 *
 * <pre>
 *   GET /            the page, one self-contained file
 *   GET /events      the run as it happens, server-sent events
 *   GET /state.json  the same state in one answer, for curl
 * </pre>
 *
 * A connection is served by a timer on the event loop rather than by the boot thread writing into
 * it: the engine only ever appends to {@link BootState}, and each connection drains what is new
 * every quarter second. Nothing the browser does can slow the boot down, and no response is
 * touched from a thread that does not own it.
 */
@ApplicationScoped
public class WebRoutes {

    /** How often a connection looks for new events. Below what an eye can see. */
    private static final long POLL_MILLIS = 250;

    /** A comment on a quiet connection, so a proxy in the middle does not decide it is dead. */
    private static final long HEARTBEAT_MILLIS = 15_000;

    void routes(@Observes Router router) {
        router.get("/").handler(this::page);
        router.get("/state.json").handler(this::state);
        router.get("/events").handler(this::events);
    }

    private void page(RoutingContext ctx) {
        ctx.response()
                .putHeader("Content-Type", "text/html;charset=UTF-8")
                .putHeader("Cache-Control", "no-store")
                .end(WebPage.HTML);
    }

    private void state(RoutingContext ctx) {
        ctx.response()
                .putHeader("Content-Type", "application/json;charset=UTF-8")
                .putHeader("Cache-Control", "no-store")
                .end(BootState.published().snapshotJson());
    }

    private void events(RoutingContext ctx) {
        HttpServerResponse response = ctx.response();
        response.setChunked(true);
        response.putHeader("Content-Type", "text/event-stream;charset=UTF-8");
        response.putHeader("Cache-Control", "no-cache, no-transform");
        response.putHeader("Connection", "keep-alive");
        // A proxy that buffers turns a live view into a page that arrives when the run is over.
        response.putHeader("X-Accel-Buffering", "no");

        BootState.readerJoined();
        BootState state = BootState.published();
        long[] cursor = {state.seq()};
        BootState[] seen = {state};
        long[] lastWrite = {System.currentTimeMillis()};
        response.write(frame("snapshot", state.snapshotJson()));

        Vertx vertx = ctx.vertx();
        long[] timer = {-1};
        boolean[] gone = {false};
        // Every handler below runs on this connection's event loop, so the flag needs no lock.
        Runnable leave = () -> {
            if (!gone[0]) {
                gone[0] = true;
                BootState.readerLeft();
                if (timer[0] >= 0) {
                    vertx.cancelTimer(timer[0]);
                }
            }
        };
        timer[0] = vertx.setPeriodic(POLL_MILLIS, id -> {
            if (response.closed() || response.ended()) {
                leave.run();
                return;
            }
            // A reader the network cannot keep up with waits; the state it missed is still in the
            // ring, or it gets a fresh snapshot.
            if (response.writeQueueFull()) {
                return;
            }
            BootState current = BootState.published();
            StringBuilder out = new StringBuilder();
            if (current != seen[0] || !current.hasEverythingAfter(cursor[0])) {
                seen[0] = current;
                cursor[0] = current.seq();
                out.append(frame("snapshot", current.snapshotJson()));
            } else {
                List<BootState.Event> events = current.since(cursor[0]);
                for (BootState.Event event : events) {
                    out.append(frame(event.type(), event.json()));
                    cursor[0] = event.seq();
                }
            }
            if (out.isEmpty() && System.currentTimeMillis() - lastWrite[0] >= HEARTBEAT_MILLIS) {
                out.append(": still here\n\n");
            }
            if (!out.isEmpty()) {
                response.write(out.toString());
                lastWrite[0] = System.currentTimeMillis();
            }
        });
        response.closeHandler(v -> leave.run());
        ctx.request().connection().closeHandler(v -> leave.run());
    }

    private static String frame(String type, String json) {
        return "event: " + type + "\ndata: " + json + "\n\n";
    }
}
