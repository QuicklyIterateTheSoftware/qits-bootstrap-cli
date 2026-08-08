package eu.wohlben.qits.cli.bootstrap.platform;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@code .qits-bootstrap.env}: what a previous bootstrap generated and this one must not change —
 * the ci-daemon digest and the idp's client secrets.
 * <p>
 * It is read before anything needs it, so a full rerun keeps the secrets it issued last time.
 * Regenerating them would leave every already-deployed service holding a credential the idp no
 * longer knows.
 */
public class BootstrapState {

    public static final String FILE_NAME = ".qits-bootstrap.env";

    private final Path file;
    private final Map<String, String> values = new LinkedHashMap<>();

    public BootstrapState(Path file) {
        this.file = file;
    }

    public Path file() {
        return file;
    }

    public boolean exists() {
        return Files.isRegularFile(file);
    }

    public void read() throws IOException {
        values.clear();
        if (!exists()) {
            return;
        }
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            String text = line.trim();
            if (text.isEmpty() || text.startsWith("#")) {
                continue;
            }
            int eq = text.indexOf('=');
            if (eq > 0) {
                values.put(text.substring(0, eq).trim(), unquote(text.substring(eq + 1).trim()));
            }
        }
    }

    private static String unquote(String value) {
        if (value.length() >= 2 && (value.startsWith("\"") && value.endsWith("\"")
                || value.startsWith("'") && value.endsWith("'"))) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    public Optional<String> daemonSha() {
        return Optional.ofNullable(values.get("DAEMON_SHA")).filter(s -> !s.isBlank());
    }

    /** What a previous run recorded for this client, if anything. */
    public Optional<String> secret(String clientId) {
        return Optional.ofNullable(values.get("IDP_SECRET_" + PlatformModel.clientKey(clientId)))
                .filter(s -> !s.isBlank());
    }

    /** Rewrites the file with the digest and the whole secret set. */
    public void write(String daemonSha, Map<String, String> secrets) throws IOException {
        StringBuilder text = new StringBuilder();
        text.append("# Written by qits-cli-bootstrap. Keep it: a rotated client secret locks every\n")
                .append("# already-deployed service out until it too is redeployed.\n");
        text.append("DAEMON_SHA=").append(daemonSha == null ? "" : daemonSha).append('\n');
        // Whatever the caller resolved, not a list this class holds its own copy of: a client id
        // is a wire alias now, so the set follows the environment name and only the caller knows
        // it. A key that was written by an earlier run under another environment is left in the
        // file rather than dropped — it costs a line and it is the only record of that secret.
        for (Map.Entry<String, String> secret : secrets.entrySet()) {
            text.append("IDP_SECRET_").append(PlatformModel.clientKey(secret.getKey())).append('=')
                    .append(secret.getValue()).append('\n');
        }
        try {
            Files.writeString(file, text.toString(), StandardCharsets.UTF_8);
        } catch (java.nio.file.AccessDeniedException e) {
            // A pre-CLI bootstrap wrote this file as root from inside its container. The directory
            // is the user's, so replace the file instead of failing the run on a migration relic.
            Files.deleteIfExists(file);
            Files.writeString(file, text.toString(), StandardCharsets.UTF_8);
        }
        values.put("DAEMON_SHA", daemonSha == null ? "" : daemonSha);
        secrets.forEach((client, value) ->
                values.put("IDP_SECRET_" + PlatformModel.clientKey(client), value));
    }

    public List<String> keys() {
        return List.copyOf(values.keySet());
    }
}
