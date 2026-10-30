package eu.wohlben.qits.cli.bootstrap.ingress;

import java.util.Locale;
import java.util.Set;

/**
 * The complete public policy of the temporary bootstrap ingress.
 *
 * <p>It serves the progress page and nothing else: three GET surfaces, without credentials, and
 * no catch-all route. A seed build resolves nothing through it — the platform's jars and the musl
 * toolchain come out of the {@code qits-maven-seed} volume as a named build context — and no phase
 * pushes through it. Any new bootstrap surface must be added here explicitly, with a test, instead
 * of accidentally becoming a general reverse proxy.</p>
 */
public final class BootstrapIngressPolicy {

    public enum Target { UI }

    public record Decision(int status, Target target, String path) {
        public boolean permitted() {
            return status == 0;
        }
    }

    private static final Set<String> UI_PATHS = Set.of("/", "/state.json", "/events");

    /** The public progress surface has no credential and does not use Host as a route selector. */
    public Decision authorizeProgress(String method, String path) {
        if (method == null || path == null || unsafePath(path)) {
            return new Decision(403, null, null);
        }
        if ("CONNECT".equals(method.toUpperCase(Locale.ROOT))) {
            return new Decision(405, null, null);
        }
        if (allowedRoute(method, path)) {
            return new Decision(0, Target.UI, path);
        }
        return new Decision(403, null, null);
    }

    /** Route-only check for the clear-text listener: redirect only known bootstrap surfaces. */
    public boolean allowedRoute(String method, String path) {
        if (method == null || path == null || unsafePath(path)) {
            return false;
        }
        return UI_PATHS.contains(path) && "GET".equals(method.toUpperCase(Locale.ROOT));
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
}
