package eu.wohlben.qits.cli.bootstrap.ingress;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Locale;
import java.util.Set;

/**
 * The complete public policy of the temporary bootstrap ingress.
 *
 * <p>It intentionally has no catch-all route.  The bootstrap UI is three GET surfaces and Git
 * smart HTTP is four request shapes; any new bootstrap surface must be added here explicitly,
 * with a test, instead of accidentally becoming a general reverse proxy.</p>
 */
public final class BootstrapIngressPolicy {

    public enum Target { UI, MAVEN, GIT }

    public record Decision(int status, Target target, String path) {
        public boolean permitted() {
            return status == 0;
        }
    }

    private static final Set<String> UI_PATHS = Set.of("/", "/state.json", "/events");
    private static final Set<String> GIT_SERVICE_SUFFIXES = Set.of(
            "/info/refs", "/git-upload-pack", "/git-receive-pack");

    private final String host;
    private final byte[] expectedBasic;
    private volatile boolean active = true;

    public BootstrapIngressPolicy(String expectedHost, String password) {
        if (expectedHost == null || expectedHost.isBlank()) {
            throw new IllegalArgumentException("a bootstrap ingress host is required");
        }
        if (password == null || password.length() < 32) {
            throw new IllegalArgumentException("the bootstrap ingress capability is too short");
        }
        this.host = expectedHost.strip().toLowerCase(Locale.ROOT);
        this.expectedBasic = basic("bootstrap", password).getBytes(StandardCharsets.US_ASCII);
    }

    /** Makes the run-scoped capability unusable before the container disappears. */
    public void close() {
        active = false;
    }

    public Decision authorize(String method, String requestHost, String path, String authorization) {
        if (!active || !basicMatches(authorization)) {
            return new Decision(401, null, null);
        }
        if (!hostMatches(requestHost)) {
            return new Decision(421, null, null);
        }
        if (method == null || path == null || unsafePath(path)) {
            return new Decision(403, null, null);
        }
        String verb = method.toUpperCase(Locale.ROOT);
        if ("CONNECT".equals(verb)) {
            return new Decision(405, null, null);
        }
        if (!allowedRoute(method, path)) {
            return new Decision(403, null, null);
        }
        if (UI_PATHS.contains(path) && ("GET".equals(verb) || "HEAD".equals(verb))) {
            return new Decision(0, Target.UI, path);
        }
        // The Maven seed is the only pre-IdP API exposed to a host build. It still needs the
        // run-scoped Basic capability; the platform registry itself remains off the host.
        if (mavenPath(path) && ("GET".equals(verb) || "HEAD".equals(verb))) {
            return new Decision(0, Target.MAVEN, path);
        }
        if (gitPath(path) && gitMethod(verb, path)) {
            return new Decision(0, Target.GIT, path);
        }
        return new Decision(403, null, null);
    }

    /** The public progress surface has no credential and does not use Host as a route selector. */
    public Decision authorizeProgress(String method, String path) {
        if (method == null || path == null || unsafePath(path)) {
            return new Decision(403, null, null);
        }
        if (UI_PATHS.contains(path) && "GET".equals(method.toUpperCase(Locale.ROOT))) {
            return new Decision(0, Target.UI, path);
        }
        return new Decision(403, null, null);
    }

    /** Route-only check for the clear-text listener: redirect only known bootstrap surfaces. */
    public boolean allowedRoute(String method, String path) {
        if (method == null || path == null || unsafePath(path)) {
            return false;
        }
        String verb = method.toUpperCase(Locale.ROOT);
        return (UI_PATHS.contains(path) && "GET".equals(verb))
                || (mavenPath(path) && ("GET".equals(verb) || "HEAD".equals(verb)))
                || (gitPath(path) && gitMethod(verb, path));
    }

    private boolean hostMatches(String requestHost) {
        if (requestHost == null || requestHost.isBlank()) {
            return false;
        }
        String value = requestHost.strip().toLowerCase(Locale.ROOT);
        // The configured host is a host name, not a route selector. A port only says which local
        // listener received it, so it is intentionally ignored after the exact host comparison.
        int colon = value.lastIndexOf(':');
        if (colon > 0 && value.indexOf(':') == colon) {
            value = value.substring(0, colon);
        }
        return MessageDigest.isEqual(host.getBytes(StandardCharsets.US_ASCII),
                value.getBytes(StandardCharsets.US_ASCII));
    }

    private boolean basicMatches(String authorization) {
        if (authorization == null || !authorization.startsWith("Basic ")) {
            return false;
        }
        try {
            byte[] received = authorization.getBytes(StandardCharsets.US_ASCII);
            return MessageDigest.isEqual(expectedBasic, received);
        } catch (IllegalArgumentException malformed) {
            return false;
        }
    }

    private static boolean gitPath(String path) {
        if (!path.startsWith("/git/") || path.length() <= "/git/".length()) {
            return false;
        }
        return GIT_SERVICE_SUFFIXES.stream().anyMatch(path::endsWith);
    }

    private static boolean mavenPath(String path) {
        return path.startsWith("/artifacts/maven/maven/")
                || "/artifacts/maven/maven".equals(path);
    }

    private static boolean gitMethod(String method, String path) {
        if (path.endsWith("/info/refs")) {
            return "GET".equals(method) || "HEAD".equals(method);
        }
        return "POST".equals(method);
    }

    private static boolean unsafePath(String path) {
        if (!path.startsWith("/") || path.contains("//") || path.indexOf('\\') >= 0) {
            return true;
        }
        String lowered = path.toLowerCase(Locale.ROOT);
        if (lowered.contains("%2f") || lowered.contains("%5c") || lowered.contains("%2e")) {
            return true;
        }
        for (String part : path.split("/")) {
            if (".".equals(part) || "..".equals(part)) {
                return true;
            }
        }
        return false;
    }

    private static String basic(String user, String password) {
        return "Basic " + Base64.getEncoder().encodeToString(
                (user + ":" + password).getBytes(StandardCharsets.UTF_8));
    }
}
