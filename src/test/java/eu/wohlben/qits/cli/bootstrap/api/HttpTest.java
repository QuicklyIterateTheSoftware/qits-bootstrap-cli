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
}
