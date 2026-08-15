package eu.wohlben.qits.cli.bootstrap.workstation;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** One-use loopback callback. OAuth codes never pass through a file or a command line. */
public final class LoopbackCallback implements AutoCloseable {
    private final HttpServer server;
    private final CompletableFuture<Callback> callback = new CompletableFuture<>();

    private LoopbackCallback(HttpServer server) {
        this.server = server;
    }

    public static LoopbackCallback open() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        LoopbackCallback result = new LoopbackCallback(server);
        server.createContext("/callback", result::handle);
        server.start();
        return result;
    }

    public String redirectUri() {
        String host = server.getAddress().getAddress().getHostAddress();
        String bracketed = host.contains(":") ? "[" + host + "]" : host;
        return "http://" + bracketed + ":" + server.getAddress().getPort() + "/callback";
    }

    public Callback await(Duration timeout) throws Exception {
        return callback.get(timeout.toSeconds(), TimeUnit.SECONDS);
    }

    private void handle(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            return;
        }
        Map<String, String> query = query(exchange.getRequestURI());
        callback.complete(new Callback(query.get("code"), query.get("state"), query.get("error")));
        byte[] response = "qits login is complete. You can close this page.".getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        exchange.sendResponseHeaders(200, response.length);
        exchange.getResponseBody().write(response);
        exchange.close();
    }

    private static Map<String, String> query(URI uri) {
        Map<String, String> result = new LinkedHashMap<>();
        String raw = uri.getRawQuery();
        if (raw == null || raw.isBlank()) {
            return result;
        }
        for (String part : raw.split("&")) {
            String[] pair = part.split("=", 2);
            result.put(URLDecoder.decode(pair[0], StandardCharsets.UTF_8),
                    pair.length == 2 ? URLDecoder.decode(pair[1], StandardCharsets.UTF_8) : "");
        }
        return result;
    }

    @Override
    public void close() {
        server.stop(0);
    }

    public record Callback(String code, String state, String error) {
    }
}
