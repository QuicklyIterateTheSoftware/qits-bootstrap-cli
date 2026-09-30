package eu.wohlben.qits.cli.bootstrap.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.ServerSocket;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

class HttpTest {

    @Test
    void aPeerThatAcceptsAndNeverAnswersCannotFreezeTheBootstrap() throws Exception {
        try (ServerSocket server = new ServerSocket(0);
             var acceptor = Executors.newSingleThreadExecutor()) {
            acceptor.submit(() -> {
                try (var ignored = server.accept()) {
                    Thread.sleep(5_000);
                }
                return null;
            });

            long started = System.nanoTime();
            Http.Response response = new Http(Duration.ofMillis(100))
                    .get("http://127.0.0.1:" + server.getLocalPort() + "/run", Map.of());

            assertThat(response.status()).isZero();
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(3));
        }
    }

    /**
     * <b>{@code getAs} sends the Host it is given to the address it is given</b> — the one thing
     * {@code java.net.http} will not do, and the whole reason the method exists: a name-routed door
     * asked about a name the asking network does not resolve.
     */
    @Test
    void getAsSendsTheNamedHostToTheGivenAddressAndReadsTheStatus() throws Exception {
        try (ServerSocket server = new ServerSocket(0);
             var acceptor = Executors.newSingleThreadExecutor()) {
            var request = acceptor.submit(() -> {
                try (var socket = server.accept()) {
                    var in = new java.io.BufferedReader(new java.io.InputStreamReader(
                            socket.getInputStream(), java.nio.charset.StandardCharsets.US_ASCII));
                    StringBuilder head = new StringBuilder();
                    for (String line = in.readLine(); line != null && !line.isEmpty();
                         line = in.readLine()) {
                        head.append(line).append('\n');
                    }
                    socket.getOutputStream().write(("HTTP/1.1 401 Unauthorized\r\n"
                            + "WWW-Authenticate: Bearer realm=\"x\"\r\n\r\n")
                            .getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                    return head.toString();
                }
            });

            Http.Response response = new Http(Duration.ofSeconds(2)).getAs(
                    "http://127.0.0.1:" + server.getLocalPort() + "/v2/",
                    "registry.dev.localhost:8080");

            assertThat(response.status()).isEqualTo(401);
            assertThat(request.get()).startsWith("GET /v2/ HTTP/1.1\n")
                    .contains("Host: registry.dev.localhost:8080\n");
        }
    }

    /** Nothing listening is no answer, said the way every other call of this class says it. */
    @Test
    void getAsWithNothingListeningIsNoAnswer() throws Exception {
        int port;
        try (ServerSocket closed = new ServerSocket(0)) {
            port = closed.getLocalPort();
        }

        Http.Response response = new Http(Duration.ofMillis(500))
                .getAs("http://127.0.0.1:" + port + "/v2/", "registry.dev.localhost:8080");

        assertThat(response.reached()).isFalse();
        assertThat(response.describe()).startsWith("no answer (");
    }

    @Test
    void aStatusLineIsReadForItsCodeAndNothingElseIsOne() {
        assertThat(Http.statusOf("HTTP/1.1 401 Unauthorized")).isEqualTo(401);
        assertThat(Http.statusOf("HTTP/1.0 200")).isEqualTo(200);
        assertThat(Http.statusOf("SSH-2.0-OpenSSH")).isZero();
        assertThat(Http.statusOf("HTTP/1.1 abc")).isZero();
        assertThat(Http.statusOf(null)).isZero();
    }
}
