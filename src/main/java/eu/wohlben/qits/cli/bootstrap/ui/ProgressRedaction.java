package eu.wohlben.qits.cli.bootstrap.ui;

import java.util.regex.Pattern;

/** The externally reachable bootstrap progress view never carries credential-like values. */
final class ProgressRedaction {

    private static final Pattern NAMED = Pattern.compile(
            "(?i)\\b(token|secret|password|authorization)\\s*([=:])\\s*([^\\s,;]+)");
    private static final Pattern BEARER = Pattern.compile("(?i)\\b(bearer|basic)\\s+[A-Za-z0-9._~+/-]+=*");

    private ProgressRedaction() {
    }

    static String redact(String value) {
        if (value == null || value.isBlank()) {
            return value == null ? "" : value;
        }
        return BEARER.matcher(NAMED.matcher(value).replaceAll("$1$2***"))
                .replaceAll("$1 ***");
    }
}
