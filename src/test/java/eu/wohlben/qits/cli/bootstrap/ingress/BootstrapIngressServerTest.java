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
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Real HTTP proxy checks: the bootstrap view streams and Git is rewritten to the seed-only route. */
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
    void streamsTheUiAndTranslatesCloneAndPushWithoutForwardingBasic() throws Exception {
        int uiPort = port();
        int gitPort = port();
        int edgePort = port();
        HttpServer ui = vertx.createHttpServer().requestHandler(request -> {
            request.response().setChunked(true).putHeader("Content-Type", "text/event-stream");
            request.response().write("event: live\ndata: one\n\n");
            request.response().end();
        });
        listen(ui, uiPort);
        HttpServer git = vertx.createHttpServer().requestHandler(request -> {
            assertThat(request.path()).isEqualTo("/bootstrap-git/qits-bootstrap/" + suffix(request.path()));
            assertThat(request.getHeader("X-Qits-Bootstrap-Git-Capability")).isEqualTo("internal-capability");
            assertThat(request.getHeader("Authorization")).isNull();
            request.bodyHandler(body -> request.response().end(request.method().name() + ":" + body));
        });
        listen(git, gitPort);
        ingress = new BootstrapIngressServer(vertx, new BootstrapIngressServer.Settings(edgePort,
                "127.0.0.1", password(), "internal-capability", URI.create("http://127.0.0.1:" + uiPort),
                URI.create("http://127.0.0.1:" + gitPort)));
        ingress.start().toCompletionStage().toCompletableFuture().join();

        HttpResponse<String> sse = send(edgePort, "GET", "/events", "");
        assertThat(sse.statusCode()).isEqualTo(200);
        assertThat(sse.headers().firstValue("Content-Type")).contains("text/event-stream");
        assertThat(sse.body()).contains("event: live");

        HttpResponse<String> publicState = send(edgePort, "GET", "/state.json", "", null);
        assertThat(publicState.statusCode()).isEqualTo(200);

        HttpResponse<String> clone = send(edgePort, "GET", "/git/qits-bootstrap/info/refs?service=git-upload-pack", "");
        assertThat(clone.statusCode()).isEqualTo(200);
        assertThat(clone.body()).isEqualTo("GET:");

        HttpResponse<String> push = send(edgePort, "POST", "/git/qits-bootstrap/git-receive-pack", "pack");
        assertThat(push.statusCode()).isEqualTo(200);
        assertThat(push.body()).isEqualTo("POST:pack");
    }

    @Test
    void rejectsWrongHostCredentialsAndUnsafeRoutesBeforeAnyUpstreamIsContacted() throws Exception {
        int edgePort = port();
        ingress = new BootstrapIngressServer(vertx, new BootstrapIngressServer.Settings(edgePort,
                "127.0.0.1", password(), "internal-capability", URI.create("http://127.0.0.1:9"),
                URI.create("http://127.0.0.1:9")));
        ingress.start().toCompletionStage().toCompletableFuture().join();
        assertThat(send(edgePort, "GET", "/git/qits-bootstrap/info/refs", "", "Basic bad").statusCode())
                .isEqualTo(401);
        assertThat(send(edgePort, "GET", "/", "", null).statusCode()).isEqualTo(503); // public, bad fixed upstream
        assertThat(send(edgePort, "GET", "/idp/token", "", basic()).statusCode()).isEqualTo(403);
    }

    private HttpResponse<String> send(int port, String method, String path, String body) throws Exception {
        return send(port, method, path, body, basic());
    }

    private HttpResponse<String> send(int port, String method, String path, String body, String authorization)
            throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(5));
        if (authorization != null) request.header("Authorization", authorization);
        HttpRequest built = request.method(method, HttpRequest.BodyPublishers.ofString(body)).build();
        return HttpClient.newHttpClient().send(built, HttpResponse.BodyHandlers.ofString());
    }

    private void listen(HttpServer server, int port) {
        upstreams.add(server);
        server.listen(port).toCompletionStage().toCompletableFuture().join();
    }

    private static int port() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) { return socket.getLocalPort(); }
    }

    private static String password() { return "abcdefghijklmnopqrstuvwxyz0123456789ABCDEFG"; }

    private static String basic() {
        return "Basic " + Base64.getEncoder().encodeToString(
                ("bootstrap:" + password()).getBytes(StandardCharsets.UTF_8));
    }

    private static String suffix(String path) {
        return path.substring("/bootstrap-git/qits-bootstrap/".length());
    }
}
