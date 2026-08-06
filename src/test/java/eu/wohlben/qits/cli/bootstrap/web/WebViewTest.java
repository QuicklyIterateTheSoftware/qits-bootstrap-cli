package eu.wohlben.qits.cli.bootstrap.web;

import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.URL;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The browser view over real HTTP: the page and the snapshot answer, on the server the CLI starts
 * while it boots.
 */
@QuarkusTest
class WebViewTest {

    @TestHTTPResource("/")
    URL root;

    private String get(String path) throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(root.toString() + path))
                        .timeout(Duration.ofSeconds(10)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        return response.body();
    }

    @Test
    void thePageIsOneSelfContainedFile() throws Exception {
        String html = get("");

        assertThat(html).contains("<title>qits bootstrap</title>");
        assertThat(html).contains("id=\"phases\"");
        assertThat(html).contains("id=\"tail\"");
        assertThat(html).contains("new EventSource('events')");
        // Nothing to fetch: no page of a bootstrap may depend on a network it is bootstrapping.
        assertThat(html).doesNotContain("//cdn");
        assertThat(html).doesNotContain("src=\"http");
        assertThat(html).doesNotContain("href=\"http");
    }

    @Test
    void theSnapshotIsServedForCurl() throws Exception {
        String json = get("state.json");

        assertThat(json).contains("\"phases\":[");
        assertThat(json).contains("\"tail\":[");
        assertThat(json).contains("\"currentIndex\":");
        assertThat(json).contains("\"seq\":");
    }
}
