package eu.wohlben.qits.cli.bootstrap.api;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The platform's HTTP, spoken directly with java.net.http. <b>Everything the bootstrap calls is a
 * wire alias on qits-net</b>, which the run joins before it dials anything: qits-platform-artifacts
 * (the registry, the git host and the artifacts API), qits-ci and qits-deployments through
 * qits-platform-edge and the gateway behind it, and qits-platform-idp, which publishes no host port
 * at all.
 * <p>
 * That last one is why this class is now the only HTTP there is. The idp used to be reached by
 * running a throwaway curl container on qits-net — a network position borrowed per call, because
 * the CLI was on the host. The CLI holds that position itself now, so the borrowing is gone and
 * with it {@code InNetworkHttp}.
 * <p>
 * Failures are answers, not exceptions: a call made while the edge, the gateway or the artifacts
 * service is mid cutover is expected, and a poll that treats it as fatal would fail a boot that is
 * working.
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

    private final Duration timeout;

    public Http() {
        this(Duration.ofSeconds(30));
    }

    public Http(Duration timeout) {
        this.timeout = timeout;
    }

    public Response get(String url, Map<String, String> headers) {
        return send(request(url, headers).GET().build());
    }

    public Response head(String url) {
        return send(request(url, Map.of()).method("HEAD", HttpRequest.BodyPublishers.noBody()).build());
    }

    /**
     * A form POST with basic auth — the shape of an OAuth2 client-credentials token request, and
     * the one call that carries a secret.
     * <p>
     * <b>The secret needs no {@code Cmd.mask} here, and that is the difference from the curl
     * container this replaced.</b> A shelled curl put the client secret on a command line that the
     * run log and the screen both show, so it had to be masked out of them. This request is made
     * in-process: neither the header nor the body is ever printed.
     */
    public Response postForm(String url, String user, String password, Map<String, String> form) {
        StringBuilder body = new StringBuilder();
        form.forEach((key, value) -> {
            if (!body.isEmpty()) {
                body.append('&');
            }
            body.append(encode(key)).append('=').append(encode(value));
        });
        return send(request(url, Map.of("Authorization", basic(user, password)))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build());
    }

    /**
     * The {@code Authorization} value for a Basic pair, for the calls that carry one as a header
     * rather than as a form post's credentials — the idp's own APIs are guarded that way.
     */
    public static String basic(String user, String password) {
        return "Basic " + Base64.getEncoder().encodeToString(
                (user + ":" + password).getBytes(StandardCharsets.UTF_8));
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
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

    /**
     * A fresh client — a fresh connection and a fresh name lookup — per request, deliberately.
     * <p>
     * A pooled connection is a cached answer to "who is this name", and on qits-net that answer
     * changes mid-run: every peer this program dials is a container it restarts or replaces. The
     * third proving run measured what the cache costs. qits-platform-idp crashed its first boot
     * (fresh postgres still in initdb — the compose-resurrection design, restart-until-ready) and
     * came back healthy at 16s; one poll connected during the restart's DNS flux, landed on a
     * WRONG peer, and the shared client then reused that connection for every later poll — ninety
     * seconds of steady 404 from a service that was up the whole time, and a failed boot. Polls
     * are seconds apart; the pool saves nothing this program can feel and caches the one thing it
     * must never cache.
     */
    private Response send(HttpRequest request) {
        try (HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build()) {
            // HttpRequest.timeout is a transport timeout, not a reliable wall-clock bound: a
            // connection that disappears while HttpClient's synchronous send is settling can
            // leave its CompletableFuture parked indefinitely. The bootstrap's one phase thread
            // then freezes forever although the polled CI run and deployment both completed.
            // Bound the future from outside as well and cancel it so closing this one-use client
            // has no outstanding exchange to await.
            var pending = client.sendAsync(request, HttpResponse.BodyHandlers.ofString());
            Duration deadline = request.timeout().orElse(timeout).plusSeconds(2);
            HttpResponse<String> response;
            try {
                response = pending.get(deadline.toMillis(), TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                pending.cancel(true);
                return new Response(0, "timed out after " + deadline);
            }
            return new Response(response.statusCode(), response.body() == null ? "" : response.body());
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            return new Response(0, cause == null ? e.toString() : cause.toString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Response(0, "interrupted");
        }
    }
}
