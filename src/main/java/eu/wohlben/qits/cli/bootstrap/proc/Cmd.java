package eu.wohlben.qits.cli.bootstrap.proc;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** One command to shell out to. */
public final class Cmd {

    private final List<String> command;
    private Path workDir;
    private final Map<String, String> env = new LinkedHashMap<>();
    private String stdin;
    private Duration timeout = Duration.ofHours(6);
    private int captureLimit = 2000;
    /** Arguments not to print — a secret on a command line is still a secret in the log. */
    private final List<String> masked = new ArrayList<>();

    private Cmd(List<String> command) {
        this.command = List.copyOf(command);
    }

    public static Cmd of(String... command) {
        return new Cmd(List.of(command));
    }

    public static Cmd of(List<String> command) {
        return new Cmd(command);
    }

    public Cmd in(Path workDir) {
        this.workDir = workDir;
        return this;
    }

    public Cmd env(String key, String value) {
        env.put(key, value);
        return this;
    }

    public Cmd stdin(String stdin) {
        this.stdin = stdin;
        return this;
    }

    public Cmd timeout(Duration timeout) {
        this.timeout = timeout;
        return this;
    }

    public Cmd captureLimit(int captureLimit) {
        this.captureLimit = captureLimit;
        return this;
    }

    public Cmd mask(String secret) {
        if (secret != null && !secret.isBlank()) {
            masked.add(secret);
        }
        return this;
    }

    public List<String> command() {
        return command;
    }

    public Path workDir() {
        return workDir;
    }

    public Map<String, String> environment() {
        return env;
    }

    public String stdinText() {
        return stdin;
    }

    public Duration timeout() {
        return timeout;
    }

    public int captureLimit() {
        return captureLimit;
    }

    /** The command as it may be shown: every masked secret replaced. */
    public String display() {
        String text = String.join(" ", command);
        for (String secret : masked) {
            text = text.replace(secret, "***");
        }
        return text;
    }

    /** Hides the secrets of this command in any text. */
    public String maskText(String text) {
        String out = text;
        for (String secret : masked) {
            out = out.replace(secret, "***");
        }
        return out;
    }
}
