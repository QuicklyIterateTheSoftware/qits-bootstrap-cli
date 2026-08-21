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
 * the ci-daemon digest, the idp's client secrets, the postgres passwords.
 * <p>
 * It is read before anything needs it, so a full rerun keeps the secrets it issued last time.
 * Regenerating them would leave every already-deployed service holding a credential the idp no
 * longer knows.
 * <p>
 * <b>Every write MERGES.</b> The file is one run's whole memory and several phases write it at
 * different points, so a write that rebuilt the file from the keys its own caller happened to hold
 * would delete the ones the other phases recorded. That is not theoretical: {@code seed-postgres}
 * records the postgres passwords minutes before {@code idp-secrets} writes the client secrets, and
 * a postgres password that is on the data volume but not in this file locks the next rerun out of
 * a database nothing can reset.
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
        values.putAll(onDisk());
    }

    /** What the file holds right now, in the order it holds it. */
    private Map<String, String> onDisk() throws IOException {
        Map<String, String> parsed = new LinkedHashMap<>();
        if (!exists()) {
            return parsed;
        }
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            String text = line.trim();
            if (text.isEmpty() || text.startsWith("#")) {
                continue;
            }
            int eq = text.indexOf('=');
            if (eq > 0) {
                parsed.put(text.substring(0, eq).trim(), unquote(text.substring(eq + 1).trim()));
            }
        }
        return parsed;
    }

    private static String unquote(String value) {
        if (value.length() >= 2 && (value.startsWith("\"") && value.endsWith("\"")
                || value.startsWith("'") && value.endsWith("'"))) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    /**
     * The one-time token the first account registers with.
     * <p>
     * <b>Its presence is what makes minting a once-per-installation act.</b> The idp mints a fresh
     * token on every call and each one creates an admin, so a boot that minted on every rerun would
     * leave a pile of live keys to this platform behind it. Recorded here instead: a rerun finds
     * one and mints nothing.
     * <p>
     * Delete the line to mint a fresh one — which is also the repair when the token was lost before
     * anyone used it.
     */
    public static final String REGISTER_TOKEN_KEY = "IDP_REGISTER_TOKEN";

    /**
     * <b>Which storage id the git host keys each platform repository by</b>, one line per
     * repository: {@code REPO_ID_QITS_CI=8b1f0f0e-9a0c-4c3a-9a5b-000000000001}.
     * <p>
     * <b>It is recorded because a rerun that minted a different one would orphan every bare this
     * platform stands on.</b> The id is what {@code PUT /git/<id>} created and what qits-projects'
     * row is keyed by; the NAME is what everything above the seam says, the seeded histories
     * included — those are pushed to {@code /git/<projectId>/<repoName>}. The two have nothing in
     * common ({@link PlatformModel#seedStorageId} mints a uuid), so this map is the run's only
     * memory of the pairing and never a derivation anyone may repeat.
     * <p>
     * It also survives a resumed run for the same reason the client secrets do: this file is the
     * one thing a boot carries across its own restarts.
     */
    public static final String REPO_ID_PREFIX = "REPO_ID_";

    /** What an earlier run seeded this repository under, if it recorded one. */
    public Optional<String> repositoryId(String repo) {
        return value(REPO_ID_PREFIX + PlatformModel.clientKey(repo));
    }

    /** Records the storage id of one repository for the next {@link #write()}. */
    public void putRepositoryId(String repo, String storageId) {
        put(REPO_ID_PREFIX + PlatformModel.clientKey(repo), storageId);
    }

    public Optional<String> daemonSha() {
        return Optional.ofNullable(values.get("DAEMON_SHA")).filter(s -> !s.isBlank());
    }

    /** What a previous run minted, if it minted one. */
    public Optional<String> registerToken() {
        return value(REGISTER_TOKEN_KEY);
    }

    /** What a previous run recorded for this client, if anything. */
    public Optional<String> secret(String clientId) {
        return Optional.ofNullable(values.get("IDP_SECRET_" + PlatformModel.clientKey(clientId)))
                .filter(s -> !s.isBlank());
    }

    /** Records one key for the next {@link #write()}. */
    public void put(String key, String value) {
        values.put(key, value == null ? "" : value);
    }

    /** What this state holds for a key, if anything. */
    public Optional<String> value(String key) {
        return Optional.ofNullable(values.get(key)).filter(s -> !s.isBlank());
    }

    /** Writes the digest and the whole secret set, keeping every other key the file holds. */
    public void write(String daemonSha, Map<String, String> secrets) throws IOException {
        values.put("DAEMON_SHA", daemonSha == null ? "" : daemonSha);
        // Whatever the caller resolved, not a list this class holds its own copy of: a client id
        // is a wire alias now, so the set follows the environment name and only the caller knows
        // it. A key that was written by an earlier run under another environment is left in the
        // file rather than dropped — it costs a line and it is the only record of that secret.
        secrets.forEach((client, value) ->
                values.put("IDP_SECRET_" + PlatformModel.clientKey(client), value));
        write();
    }

    /**
     * Writes what this state holds over what the file holds.
     * <p>
     * The file is re-read here rather than trusted from an earlier {@link #read()}: a phase asks
     * for this class when it has something to record, and the run that recorded the other keys is
     * this same run a few minutes earlier. Reading at write time is what makes a caller that never
     * read the file unable to erase it.
     */
    public void write() throws IOException {
        Map<String, String> merged = new LinkedHashMap<>(onDisk());
        merged.putAll(values);

        StringBuilder text = new StringBuilder();
        text.append("# Written by qits-cli-bootstrap. Keep it: a rotated client secret locks every\n")
                .append("# already-deployed service out until it too is redeployed, and a postgres\n")
                .append("# password that lives only on the data volume locks the next rerun out.\n");
        merged.forEach((key, value) -> text.append(key).append('=').append(value).append('\n'));
        try {
            Files.writeString(file, text.toString(), StandardCharsets.UTF_8);
        } catch (java.nio.file.AccessDeniedException e) {
            // A pre-CLI bootstrap wrote this file as root from inside its container. The directory
            // is the user's, so replace the file instead of failing the run on a migration relic.
            Files.deleteIfExists(file);
            Files.writeString(file, text.toString(), StandardCharsets.UTF_8);
        }
        values.clear();
        values.putAll(merged);
    }

    public List<String> keys() {
        return List.copyOf(values.keySet());
    }
}
