package eu.wohlben.qits.cli.bootstrap.platform;

import java.util.Map;

/**
 * Fills {@code ${NAME}} placeholders in the generated compose file and run-args, and only those:
 * anything the templates spell that is not a known key stays as it is — which is what keeps the
 * comments about {@code ${user.home}} readable in the file they warn about.
 */
public final class Tokens {

    private Tokens() {
    }

    public static String apply(String template, Map<String, String> values) {
        String text = template;
        for (Map.Entry<String, String> entry : values.entrySet()) {
            text = text.replace("${" + entry.getKey() + "}", entry.getValue());
        }
        return text;
    }
}
