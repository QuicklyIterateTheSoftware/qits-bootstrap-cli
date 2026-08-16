package eu.wohlben.qits.cli.bootstrap.ingress;

import eu.wohlben.qits.cli.bootstrap.config.BootstrapConfig;
import eu.wohlben.qits.cli.bootstrap.phases.Boot;
import eu.wohlben.qits.cli.bootstrap.platform.PlatformModel;
import eu.wohlben.qits.cli.bootstrap.proc.Cmd;
import eu.wohlben.qits.cli.bootstrap.proc.ProcessResult;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/** Owns the disposable ingress and the two capabilities it needs for exactly one bootstrap run. */
public final class BootstrapIngressLifecycle {

    public static final String CONTAINER = "qits-bootstrap-edge";
    private static final String LABEL = "qits.bootstrap.ingress";
    private static final String STATE_FILE = ".qits-bootstrap-edge.env";
    private final Boot boot;

    public BootstrapIngressLifecycle(Boot boot) {
        this.boot = boot;
    }

    /** Before compose: githost receives only the hash, never the opaque upstream capability. */
    public void prepare(Consumer<String> out) throws IOException {
        BootstrapConfig config = boot.config;
        if (!config.bootstrapIngress()) {
            return;
        }
        if (!config.web()) {
            throw new IllegalStateException("QITS_BOOTSTRAP_INGRESS requires QITS_WEB=1: its fixed UI "
                    + "upstream is qits-bootstrap-cli:" + config.webPort());
        }
        if (config.bootstrapIngressPublic() && config.domain().isEmpty()) {
            throw new IllegalStateException("QITS_BOOTSTRAP_INGRESS_PUBLIC requires QITS_DOMAIN: "
                    + "the public ingress accepts exactly that Host header");
        }
        Path file = stateFile();
        if (Files.isRegularFile(file) && restore(file, out)) {
            boot.state.bootstrapIngressEnvFile = file;
            return;
        }
        String password = random();
        String capability = random();
        long expiresAt = Instant.now().plus(config.bootstrapIngressTtl()).getEpochSecond();
        boot.state.bootstrapIngressPassword = password;
        boot.state.bootstrapIngressGitCapability = capability;
        boot.state.bootstrapIngressGitCapabilityHash = sha256(capability);
        // This is intentionally one id-addressed seed repository. It makes a Git capability a
        // capability for one repository, rather than a hidden substitute for a system token.
        boot.state.bootstrapIngressRepository = "qits-bootstrap";
        boot.state.bootstrapIngressRefPattern = "refs/heads/bootstrap/*";
        boot.state.bootstrapIngressExpiresAt = expiresAt;
        Files.writeString(file, "", StandardCharsets.UTF_8);
        secure(file);
        boot.state.bootstrapIngressEnvFile = file;
        boot.useBootstrapMavenRepository(mavenRepositoryUrl(), password);
        out.accept("  bootstrap edge capability prepared (expires " + Instant.ofEpochSecond(expiresAt)
                + ")");
    }

    /** Before the seed stack: start independently of the normal edge and its deployment configuration. */
    public void start(Consumer<String> out) throws IOException {
        if (!boot.config.bootstrapIngress()) {
            return;
        }
        reconcile(out);
        if (boot.docker.allNames().contains(CONTAINER)) {
            out.accept("  bootstrap edge retained across worker retry");
            return;
        }
        String image = boot.docker.selfImage();
        if (image == null) {
            throw new IllegalStateException("cannot identify this bootstrap payload image for bootstrap ingress");
        }
        writeEnvironment();
        // The supervisor starts before qits-net exists, so the edge attaches it after the worker's
        // network phase. Its browser port is never published independently.
        if (boot.docker.allNames().contains("qits-bootstrap-progress")
                && !boot.docker.networksOf("qits-bootstrap-progress").contains(Boot.NETWORK)) {
            boot.docker.exec(out, "network", "connect", Boot.NETWORK, "qits-bootstrap-progress");
        }
        List<String> argv = new ArrayList<>(List.of("docker", "run", "-d", "--name", CONTAINER,
                "--label", LABEL + "=true", "--label", LABEL + ".expires-at="
                        + boot.state.bootstrapIngressExpiresAt, "--restart", "unless-stopped",
                "--network", Boot.NETWORK,
                "--cap-drop", "ALL", "--security-opt", "no-new-privileges"));
        if (boot.config.bootstrapIngressPublic()) {
            // The only ingress mount is the retained certificate pair, read-only. This container
            // receives neither the Docker socket nor any platform/configuration volume.
            argv.addAll(List.of("-p", "80:8080", "-p", "443:8443", "-v",
                    "qits-edge-letsencrypt:/cert:ro"));
        } else {
            argv.addAll(List.of("-p", boot.config.bootstrapIngressBind() + ":"
                    + boot.config.bootstrapIngressPort() + ":8080"));
        }
        argv.addAll(List.of("--env-file", boot.state.bootstrapIngressEnvFile.toString(), image,
                "bootstrap-edge"));
        Cmd command = Cmd.of(argv)
                .mask(boot.state.bootstrapIngressPassword)
                .mask(boot.state.bootstrapIngressGitCapability);
        Boot.must(boot.docker.run(command, out), "starting the bootstrap ingress failed");
        String address = boot.config.bootstrapIngressPublic()
                ? "https://" + ingressHost() : "http://" + boot.config.bootstrapIngressHost() + ":"
                        + boot.config.bootstrapIngressPort();
        out.accept("  bootstrap ingress: " + address + " ("
                + (boot.config.bootstrapIngressPublic() ? "TLS domain handoff" : "loopback-published")
                + ", expires "
                + Instant.ofEpochSecond(boot.state.bootstrapIngressExpiresAt) + ")");
    }

    /** Safe to call for every outcome, including Ctrl-C and an earlier failed phase. */
    public void stop(Consumer<String> out) {
        try {
            if (boot.docker.allNames().contains(CONTAINER)) {
                boot.docker.removeContainer(CONTAINER, out);
            }
        } finally {
            if (boot.state.bootstrapIngressEnvFile != null) {
                try {
                    Files.deleteIfExists(boot.state.bootstrapIngressEnvFile);
                } catch (IOException ignored) {
                    // The container is already gone; an owner-readable file is safer than a broad delete.
                }
            }
        }
    }

    /** Takes only expired containers carrying our exact label; an active bootstrap is untouched. */
    private void reconcile(Consumer<String> out) {
        ProcessResult result = boot.docker.exec(null, "ps", "-a", "--filter", "label=" + LABEL
                + "=true", "--format", "{{.Names}}|{{.Label \"" + LABEL + ".expires-at\"}}");
        if (!result.ok()) {
            return;
        }
        long now = Instant.now().getEpochSecond();
        for (String line : result.captured()) {
            String[] parts = line.trim().split("\\|", 2);
            if (parts.length == 2 && expired(parts[1], now)) {
                out.accept("  removing expired bootstrap ingress " + parts[0]);
                boot.docker.removeContainer(parts[0], out);
            }
        }
        // Do not take an unlabelled same-name container. It is not evidence it belongs to this
        // bootstrap, and Docker's name collision is a safer, actionable failure than deleting it.
    }

    private void writeEnvironment() throws IOException {
        String env = boot.config.envName();
        String text = String.join("\n",
                "QITS_WEB_BIND=false",
                "QITS_BOOTSTRAP_INGRESS_INTERNAL_PORT=8080",
                "QITS_BOOTSTRAP_INGRESS_HOST=" + ingressHost(),
                "QITS_BOOTSTRAP_INGRESS_PASSWORD=" + boot.state.bootstrapIngressPassword,
                "QITS_BOOTSTRAP_INGRESS_GITHOST_CAPABILITY=" + boot.state.bootstrapIngressGitCapability,
                "QITS_BOOTSTRAP_INGRESS_EXPIRES_AT=" + boot.state.bootstrapIngressExpiresAt,
                "QITS_BOOTSTRAP_INGRESS_UI_UPSTREAM=http://qits-bootstrap-progress:" + boot.config.webPort(),
                "QITS_BOOTSTRAP_INGRESS_MAVEN_UPSTREAM=http://qits-maven-seed-http:80",
                "QITS_BOOTSTRAP_INGRESS_GIT_UPSTREAM=http://"
                        + PlatformModel.wireAlias("githost", env) + ":8080");
        if (boot.config.bootstrapIngressPublic()) {
            text += "\nQITS_BOOTSTRAP_INGRESS_TLS_PORT=8443"
                    + "\nQITS_BOOTSTRAP_INGRESS_TLS_CERTIFICATE=/cert/lets-encrypt.crt"
                    + "\nQITS_BOOTSTRAP_INGRESS_TLS_KEY=/cert/lets-encrypt.key";
        }
        text += "\n";
        Files.writeString(boot.state.bootstrapIngressEnvFile, text, StandardCharsets.UTF_8);
        secure(boot.state.bootstrapIngressEnvFile);
    }

    /** Reuses the only credential the retained edge can still present on a retry. */
    private boolean restore(Path file, Consumer<String> out) throws IOException {
        java.util.Map<String, String> values = new java.util.HashMap<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            int equals = line.indexOf('=');
            if (equals > 0) values.put(line.substring(0, equals), line.substring(equals + 1));
        }
        String password = values.get("QITS_BOOTSTRAP_INGRESS_PASSWORD");
        String capability = values.get("QITS_BOOTSTRAP_INGRESS_GITHOST_CAPABILITY");
        String expiry = values.get("QITS_BOOTSTRAP_INGRESS_EXPIRES_AT");
        if (password == null || capability == null || expiry == null) return false;
        long expiresAt;
        try { expiresAt = Long.parseLong(expiry); } catch (NumberFormatException bad) { return false; }
        if (expiresAt <= Instant.now().getEpochSecond()) return false;
        boot.state.bootstrapIngressPassword = password;
        boot.state.bootstrapIngressGitCapability = capability;
        boot.state.bootstrapIngressGitCapabilityHash = sha256(capability);
        boot.state.bootstrapIngressRepository = "qits-bootstrap";
        boot.state.bootstrapIngressRefPattern = "refs/heads/bootstrap/*";
        boot.state.bootstrapIngressExpiresAt = expiresAt;
        boot.useBootstrapMavenRepository(mavenRepositoryUrl(), password);
        out.accept("  bootstrap edge capability retained (expires " + Instant.ofEpochSecond(expiresAt) + ")");
        return true;
    }

    private String ingressHost() {
        return boot.config.bootstrapIngressPublic()
                ? boot.config.domain().orElseThrow() : boot.config.bootstrapIngressHost();
    }

    /** Keep the capability on the same durable host mount as the supervisor journal. */
    private static Path stateFile() {
        return stateFile(System.getenv("QITS_PROGRESS_FILE"));
    }

    static Path stateFile(String progress) {
        if (progress != null && !progress.isBlank()) {
            Path journal = Path.of(progress).toAbsolutePath().normalize();
            Path parent = journal.getParent();
            if (parent != null) {
                return parent.resolve(STATE_FILE);
            }
        }
        return Path.of(STATE_FILE).toAbsolutePath().normalize();
    }

    String mavenRepositoryUrl() {
        if (boot.config.bootstrapIngressPublic()) {
            // Host-networked seed builds use the same normal TLS door as an operator. There is no
            // hidden 8481 publish in public mode.
            return "https://" + ingressHost() + "/artifacts/maven/maven";
        }
        return "http://" + boot.config.bootstrapIngressHost() + ":"
                + boot.config.bootstrapIngressPort() + "/artifacts/maven/maven";
    }

    private static boolean expired(String value, long now) {
        try {
            return Long.parseLong(value) <= now;
        } catch (NumberFormatException absentOrBad) {
            return true;
        }
    }

    private static String random() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String sha256(String value) {
        return BootstrapIngressHash.sha256(value);
    }

    private static void secure(Path file) throws IOException {
        try {
            Files.setPosixFilePermissions(file, Set.of(PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException ignored) {
            // The payload's Linux filesystem supports POSIX permissions; this keeps tests portable.
        }
    }
}
