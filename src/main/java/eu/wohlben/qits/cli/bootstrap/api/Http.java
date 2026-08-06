package eu.wohlben.qits.cli.bootstrap.api;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

/**
 * The platform's HTTP, spoken directly with java.net.http. Everything the bootstrap calls is
 * reachable from the host: qits-artifacts on its published registry port (the registry, the git
 * host and the artifacts API), qits-ci and qits-platform-deployments through the gateway's
 * route table.
 * <p>
 * Failures are answers, not exceptions: a call made while qits-gateway or qits-artifacts is mid
 * cutover is expected, and a poll that treats it as fatal would fail a boot that is working.
 */
public class Http {

    /** A status of 0 means the call never got an answer. */
    public record Response(int status, String body) {
        public boolean ok() {
            return status >= 200 && status < 300;
        }

        public boolean reached() {
            return status > 0;
        }

        public String describe() {
            if (!reached()) {
                return "no answer (" + body + ")";
            }
            return status + (body.isBlank() ? "" : ": " + body.substring(0, Math.min(300, body.length())));
        }
    }

    private final HttpClient client;
    private final Duration timeout;

    public Http() {
        this(Duration.ofSeconds(30));
    }

    public Http(Duration timeout) {
        this.timeout = timeout;
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public Response get(String url, Map<String, String> headers) {
        return send(request(url, headers).GET().build());
    }

    public Response head(String url) {
        return send(request(url, Map.of()).method("HEAD", HttpRequest.BodyPublishers.noBody()).build());
    }

    public Response postJson(String url, String json, Map<String, String> headers) {
        return send(request(url, headers)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json)).build());
    }

    public Response patchJson(String url, String json, Map<String, String> headers) {
        return send(request(url, headers)
                .header("Content-Type", "application/json")
                .method("PATCH", HttpRequest.BodyPublishers.ofString(json)).build());
    }

    public Response putJson(String url, String json, Map<String, String> headers) {
        return send(request(url, headers)
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(json)).build());
    }

    public Response putFile(String url, Path file, Duration uploadTimeout) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(uploadTimeout)
                    .header("Content-Type", "application/octet-stream")
                    .PUT(HttpRequest.BodyPublishers.ofFile(file))
                    .build();
            return send(request);
        } catch (IOException e) {
            return new Response(0, e.toString());
        }
    }

    private HttpRequest.Builder request(String url, Map<String, String> headers) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url)).timeout(timeout);
        headers.forEach(builder::header);
        return builder;
    }

    private Response send(HttpRequest request) {
        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            return new Response(response.statusCode(), response.body() == null ? "" : response.body());
        } catch (IOException e) {
            return new Response(0, e.toString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Response(0, "interrupted");
        }
    }
}
