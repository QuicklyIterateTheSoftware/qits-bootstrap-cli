package eu.wohlben.qits.cli.bootstrap.workstation;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class LoopbackCallbackTest {
    @Test
    void receivesOnlyThroughEphemeralLoopbackCallback() throws Exception {
        try (LoopbackCallback callback = LoopbackCallback.open()) {
            HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(callback.redirectUri()
                            + "?code=one%202&state=state"))
                    .GET().build(), HttpResponse.BodyHandlers.discarding());

            LoopbackCallback.Callback result = callback.await(Duration.ofSeconds(2));
            assertThat(result.code()).isEqualTo("one 2");
            assertThat(result.state()).isEqualTo("state");
        }
    }
}
