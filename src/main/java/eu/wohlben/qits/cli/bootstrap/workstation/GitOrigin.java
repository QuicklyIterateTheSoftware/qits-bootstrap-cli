package eu.wohlben.qits.cli.bootstrap.workstation;

import java.net.URI;

/** A credential belongs to one Git HTTP origin, never to every host a person happens to use. */
public final class GitOrigin {
    private GitOrigin() {
    }

    public static String normalize(String raw) {
        URI uri = URI.create(raw);
        if (uri.getScheme() == null || uri.getHost() == null || !(uri.getScheme().equalsIgnoreCase("http")
                || uri.getScheme().equalsIgnoreCase("https"))) {
            throw new IllegalArgumentException("Git host must be an http:// or https:// URL");
        }
        String scheme = uri.getScheme().toLowerCase();
        String host = uri.getHost().toLowerCase();
        int port = uri.getPort();
        boolean defaultPort = port == -1 || (scheme.equals("http") && port == 80) || (scheme.equals("https") && port == 443);
        return scheme + "://" + (host.contains(":") ? "[" + host + "]" : host) + (defaultPort ? "" : ":" + port);
    }

    public static String fromGitRequest(String protocol, String host) {
        if (protocol == null || host == null || protocol.isBlank() || host.isBlank()) {
            throw new IllegalArgumentException("Git did not provide an HTTP host");
        }
        return normalize(protocol + "://" + host);
    }
}
