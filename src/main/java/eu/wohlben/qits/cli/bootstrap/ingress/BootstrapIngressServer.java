package eu.wohlben.qits.cli.bootstrap.ingress;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;

import java.net.URI;
import java.util.Locale;
import java.util.Set;

/** A deliberately tiny streaming reverse proxy for the bootstrap window only. */
public final class BootstrapIngressServer implements AutoCloseable {

    private static final Set<String> HOP_BY_HOP = Set.of("authorization", "host", "connection",
            "keep-alive", "proxy-authenticate", "proxy-authorization", "te", "trailer",
            "transfer-encoding", "upgrade", "forwarded", "x-forwarded-for", "x-forwarded-host",
            "x-forwarded-proto");

    public record Settings(int port, String host, String password, String githostCapability,
                           URI uiUpstream, URI gitUpstream) {
        public Settings {
            if (port < 1 || port > 65535) {
                throw new IllegalArgumentException("invalid bootstrap ingress port " + port);
            }
            if (githostCapability == null || githostCapability.isBlank()) {
                throw new IllegalArgumentException("a bootstrap-only githost capability is required");
            }
        }
    }

    private final Vertx vertx;
    private final HttpClient client;
    private final BootstrapIngressPolicy policy;
    private final Settings settings;
    private HttpServer server;

    public BootstrapIngressServer(Vertx vertx, Settings settings) {
        this.vertx = vertx;
        this.client = vertx.createHttpClient();
        this.policy = new BootstrapIngressPolicy(settings.host(), settings.password());
        this.settings = settings;
    }

    public Future<Void> start() {
        server = vertx.createHttpServer();
        return server.requestHandler(this::handle).listen(settings.port()).mapEmpty();
    }

    private void handle(HttpServerRequest incoming) {
        BootstrapIngressPolicy.Decision decision = policy.authorize(incoming.method().name(),
                incoming.host(), incoming.path(),
                incoming.getHeader(HttpHeaders.AUTHORIZATION));
        if (!decision.permitted()) {
            rejected(incoming.response(), decision.status());
            incoming.resume();
            return;
        }
        URI upstream = decision.target() == BootstrapIngressPolicy.Target.UI
                ? settings.uiUpstream() : settings.gitUpstream();
        String path = decision.target() == BootstrapIngressPolicy.Target.GIT
                ? "/bootstrap-git" + decision.path().substring("/git".length())
                : decision.path();
        String uri = path + (incoming.query() == null ? "" : "?" + incoming.query());
        // Do not let Vert.x consume a zero-byte GET before the upstream connection exists. The
        // same pause is what keeps a POST packfile streaming rather than accumulating here.
        incoming.pause();
        client.request(incoming.method(), upstream.getPort(), upstream.getHost(), uri)
                .onSuccess(outgoing -> stream(incoming, outgoing, decision.target()))
                .onFailure(error -> {
                    incoming.resume();
                    unavailable(incoming.response());
                });
    }

    private void stream(HttpServerRequest incoming, HttpClientRequest outgoing,
                        BootstrapIngressPolicy.Target target) {
        incoming.headers().forEach(header -> {
            String name = header.getKey();
            if (!HOP_BY_HOP.contains(name.toLowerCase(Locale.ROOT))) {
                outgoing.putHeader(name, header.getValue());
            }
        });
        // The incoming Basic capability never leaves this container. Git receives a distinct,
        // per-run opaque capability accepted only on githost's disabled-by-default seed route.
        if (target == BootstrapIngressPolicy.Target.GIT) {
            outgoing.putHeader("X-Qits-Bootstrap-Git-Capability", settings.githostCapability());
        }
        outgoing.setChunked(true);
        incoming.handler(outgoing::write);
        incoming.exceptionHandler(error -> outgoing.reset());
        incoming.endHandler(ignored -> outgoing.end());
        incoming.resume();
        outgoing.response().onSuccess(response -> relay(response, incoming.response()))
                .onFailure(error -> unavailable(incoming.response()));
    }

    private void relay(HttpClientResponse upstream, HttpServerResponse downstream) {
        downstream.setStatusCode(upstream.statusCode());
        upstream.headers().forEach(header -> {
            String name = header.getKey();
            if (!HOP_BY_HOP.contains(name.toLowerCase(Locale.ROOT))) {
                downstream.putHeader(name, header.getValue());
            }
        });
        downstream.setChunked(true);
        upstream.handler(downstream::write);
        upstream.exceptionHandler(error -> downstream.close());
        upstream.endHandler(ignored -> downstream.end());
    }

    private static void rejected(HttpServerResponse response, int status) {
        response.setStatusCode(status);
        if (status == 401) {
            response.putHeader("WWW-Authenticate", "Basic realm=\"qits-bootstrap\"");
        }
        response.end();
    }

    private static void unavailable(HttpServerResponse response) {
        if (!response.ended()) {
            response.setStatusCode(503).end();
        }
    }

    @Override
    public void close() {
        policy.close();
        if (server != null) {
            server.close();
        }
        client.close();
    }
}
