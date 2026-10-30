package eu.wohlben.qits.cli.bootstrap.ingress;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.net.PemKeyCertOptions;

import java.net.URI;
import java.util.Locale;
import java.util.Set;

/**
 * A deliberately tiny streaming reverse proxy for the bootstrap window only: the progress page,
 * on the public door, until the edge's seed deploy takes that door over.
 */
public final class BootstrapIngressServer implements AutoCloseable {

    private static final Set<String> HOP_BY_HOP = Set.of("authorization", "host", "connection",
            "keep-alive", "proxy-authenticate", "proxy-authorization", "te", "trailer",
            "transfer-encoding", "upgrade", "forwarded", "x-forwarded-for", "x-forwarded-host",
            "x-forwarded-proto");

    /**
     * @param host the name the TLS redirect sends a browser to
     * @param uiUpstream the progress page this ingress fronts
     * @param tls the certificate pair, or null for plain HTTP on {@code port}
     */
    public record Settings(int port, String host, URI uiUpstream, Tls tls) {
        public Settings(int port, String host, URI uiUpstream) {
            this(port, host, uiUpstream, null);
        }
        public Settings {
            if (port < 1 || port > 65535) {
                throw new IllegalArgumentException("invalid bootstrap ingress port " + port);
            }
            if (host == null || host.isBlank()) {
                throw new IllegalArgumentException("a bootstrap ingress host is required");
            }
            if (uiUpstream == null) {
                throw new IllegalArgumentException("a bootstrap ingress UI upstream is required");
            }
        }
    }

    /** Fixed certificate paths supplied by the retained edge certificate volume. */
    public record Tls(int port, String certificate, String key) {
        public Tls {
            if (port < 1 || port > 65535 || certificate == null || certificate.isBlank()
                    || key == null || key.isBlank()) {
                throw new IllegalArgumentException("a TLS bootstrap ingress needs port, certificate and key");
            }
        }
    }

    private final Vertx vertx;
    private final HttpClient client;
    private final BootstrapIngressPolicy policy;
    private final Settings settings;
    private HttpServer server;
    private HttpServer redirect;

    public BootstrapIngressServer(Vertx vertx, Settings settings) {
        this.vertx = vertx;
        this.client = vertx.createHttpClient();
        this.policy = new BootstrapIngressPolicy();
        this.settings = settings;
    }

    public Future<Void> start() {
        if (settings.tls() == null) {
            server = vertx.createHttpServer();
            return server.requestHandler(this::handle).listen(settings.port()).mapEmpty();
        }
        redirect = vertx.createHttpServer();
        server = vertx.createHttpServer(new HttpServerOptions().setSsl(true)
                .setPemKeyCertOptions(new PemKeyCertOptions()
                        .setCertPath(settings.tls().certificate()).setKeyPath(settings.tls().key())));
        return Future.all(
                redirect.requestHandler(this::redirectToTls).listen(settings.port()),
                server.requestHandler(this::handle).listen(settings.tls().port())).mapEmpty();
    }

    private void redirectToTls(HttpServerRequest incoming) {
        if (!policy.allowedRoute(incoming.method().name(), incoming.path())) {
            incoming.response().setStatusCode(403).end();
            return;
        }
        incoming.response().setStatusCode(308)
                .putHeader("Location", "https://" + settings.host() + incoming.uri()).end();
    }

    private void handle(HttpServerRequest incoming) {
        BootstrapIngressPolicy.Decision decision = policy.authorizeProgress(incoming.method().name(),
                incoming.path());
        if (!decision.permitted()) {
            incoming.response().setStatusCode(decision.status()).end();
            incoming.resume();
            return;
        }
        URI upstream = settings.uiUpstream();
        String uri = decision.path() + (incoming.query() == null ? "" : "?" + incoming.query());
        // Do not let Vert.x consume a zero-byte GET before the upstream connection exists.
        incoming.pause();
        client.request(incoming.method(), upstream.getPort(), upstream.getHost(), uri)
                .onSuccess(outgoing -> stream(incoming, outgoing))
                .onFailure(error -> {
                    incoming.resume();
                    unavailable(incoming.response());
                });
    }

    private void stream(HttpServerRequest incoming, HttpClientRequest outgoing) {
        incoming.headers().forEach(header -> {
            String name = header.getKey();
            if (!HOP_BY_HOP.contains(name.toLowerCase(Locale.ROOT))) {
                outgoing.putHeader(name, header.getValue());
            }
        });
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

    private static void unavailable(HttpServerResponse response) {
        if (!response.ended()) {
            response.setStatusCode(503).end();
        }
    }

    @Override
    public void close() {
        if (server != null) {
            server.close();
        }
        if (redirect != null) {
            redirect.close();
        }
        client.close();
    }
}
