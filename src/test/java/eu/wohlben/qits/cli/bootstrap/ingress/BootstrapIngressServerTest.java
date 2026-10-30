package eu.wohlben.qits.cli.bootstrap.ingress;

import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Real HTTP proxy checks: the bootstrap view streams, and nothing else is proxied. */
class BootstrapIngressServerTest {

    private final Vertx vertx = Vertx.vertx();
    private final List<HttpServer> upstreams = new ArrayList<>();
    private BootstrapIngressServer ingress;

    @AfterEach
    void close() {
        if (ingress != null) ingress.close();
        upstreams.forEach(server -> server.close().toCompletionStage().toCompletableFuture().join());
        vertx.close().toCompletionStage().toCompletableFuture().join();
    }

    @Test
    void streamsTheUiWithoutForwardingAnAuthorizationHeader() throws Exception {
        int uiPort = port();
        int edgePort = port();
        List<String> seen = new ArrayList<>();
        HttpServer ui = vertx.createHttpServer().requestHandler(request -> {
            seen.add(request.path() + " " + request.getHeader("Authorization"));
            request.response().setChunked(true).putHeader("Content-Type", "text/event-stream");
            request.response().write("event: live\ndata: one\n\n");
            request.response().end();
        });
        listen(ui, uiPort);
        ingress = new BootstrapIngressServer(vertx, new BootstrapIngressServer.Settings(edgePort,
                "127.0.0.1", URI.create("http://127.0.0.1:" + uiPort)));
        ingress.start().toCompletionStage().toCompletableFuture().join();

        HttpResponse<String> sse = send(edgePort, "GET", "/events", "Basic anything");
        assertThat(sse.statusCode()).isEqualTo(200);
        assertThat(sse.headers().firstValue("Content-Type")).contains("text/event-stream");
        assertThat(sse.body()).contains("event: live");

        assertThat(send(edgePort, "GET", "/state.json", null).statusCode()).isEqualTo(200);
        assertThat(seen).containsExactly("/events null", "/state.json null");
    }

    /** The retired maven and git routes answer 403 and reach no upstream at all. */
    @Test
    void rejectsEveryOtherRouteBeforeAnyUpstreamIsContacted() throws Exception {
        int edgePort = port();
        ingress = new BootstrapIngressServer(vertx, new BootstrapIngressServer.Settings(edgePort,
                "127.0.0.1", URI.create("http://127.0.0.1:9")));
        ingress.start().toCompletionStage().toCompletableFuture().join();
        assertThat(send(edgePort, "GET", "/", null).statusCode()).isEqualTo(503); // bad fixed upstream
        assertThat(send(edgePort, "GET", "/idp/token", null).statusCode()).isEqualTo(403);
        assertThat(send(edgePort, "GET",
                "/artifacts/maven/maven/eu/wohlben/qits/example/1/example-1.pom", null)
                .statusCode()).isEqualTo(403);
        assertThat(send(edgePort, "GET", "/git/qits-bootstrap/info/refs", null).statusCode())
                .isEqualTo(403);
    }

    private HttpResponse<String> send(int port, String method, String path, String authorization)
            throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(5));
        if (authorization != null) request.header("Authorization", authorization);
        HttpRequest built = request.method(method, HttpRequest.BodyPublishers.noBody()).build();
        return HttpClient.newHttpClient().send(built, HttpResponse.BodyHandlers.ofString());
    }

    private void listen(HttpServer server, int port) {
        upstreams.add(server);
        server.listen(port).toCompletionStage().toCompletableFuture().join();
    }

    private static int port() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) { return socket.getLocalPort(); }
    }
}
