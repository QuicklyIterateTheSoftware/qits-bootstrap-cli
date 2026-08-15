package eu.wohlben.qits.cli.bootstrap.workstation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/** The small OAuth form client shared by login and Git's credential helper. */
public final class TokenClient {
    public static final String CLIENT_ID = "qits-git-workstation";
    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient http;

    public TokenClient() {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
    }

    TokenClient(HttpClient http) {
        this.http = http;
    }

    public Token exchange(String idpUrl, String code, String redirectUri, String verifier) throws IOException, InterruptedException {
        return post(idpUrl, Map.of(
                "grant_type", "authorization_code", "client_id", CLIENT_ID, "code", code,
                "redirect_uri", redirectUri, "code_verifier", verifier));
    }

    public Token refresh(WorkstationCredential credential) throws IOException, InterruptedException {
        return post(credential.idpUrl(), Map.of(
                "grant_type", "refresh_token", "client_id", CLIENT_ID,
                "refresh_token", credential.refreshToken(), "audience", credential.audience()));
    }

    private Token post(String idpUrl, Map<String, String> values) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(trim(idpUrl) + "/token"))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form(values)))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("the IdP refused the OAuth token request (HTTP " + response.statusCode() + ")");
        }
        JsonNode body = JSON.readTree(response.body());
        String access = required(body, "access_token");
        String refresh = required(body, "refresh_token");
        return new Token(access, refresh);
    }

    private static String required(JsonNode body, String field) throws IOException {
        String value = body.path(field).asText();
        if (value.isBlank()) {
            throw new IOException("the IdP token response omitted " + field);
        }
        return value;
    }

    public static String trim(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private static String form(Map<String, String> values) {
        Map<String, String> ordered = new LinkedHashMap<>(values);
        return ordered.entrySet().stream().map(entry -> URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8)
                + "=" + URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8)).reduce((a, b) -> a + "&" + b).orElse("");
    }

    public record Token(String accessToken, String refreshToken) {
    }
}
