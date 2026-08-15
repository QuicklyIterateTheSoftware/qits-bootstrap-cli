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
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/** Owns the disposable ingress and the two capabilities it needs for exactly one bootstrap run. */
public final class BootstrapIngressLifecycle {

    public static final String CONTAINER = "qits-bootstrap-edge";
    private static final String LABEL = "qits.bootstrap.ingress";
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
        Path file = Files.createTempFile(boot.state.wrapperDir, ".qits-bootstrap-ingress-", ".env");
        secure(file);
        boot.state.bootstrapIngressEnvFile = file;
        out.accept("  bootstrap ingress capability prepared (expires " + Instant.ofEpochSecond(expiresAt)
                + ", credential file " + file + ")");
    }

    /** Before the seed stack: start independently of the normal edge and its deployment configuration. */
    public void start(Consumer<String> out) throws IOException {
        if (!boot.config.bootstrapIngress()) {
            return;
        }
        reconcile(out);
        String image = boot.docker.selfImage();
        if (image == null) {
            throw new IllegalStateException("cannot identify this bootstrap payload image for bootstrap ingress");
        }
        writeEnvironment();
        Cmd command = Cmd.of(List.of("docker", "run", "-d", "--rm", "--name", CONTAINER,
                        "--label", LABEL + "=true", "--label",
                        LABEL + ".expires-at=" + boot.state.bootstrapIngressExpiresAt,
                        "--network", Boot.NETWORK, "--cap-drop", "ALL", "--security-opt",
                        "no-new-privileges", "-p", boot.config.bootstrapIngressBind() + ":"
                                + boot.config.bootstrapIngressPort() + ":8080", "--env-file",
                        boot.state.bootstrapIngressEnvFile.toString(), image, "bootstrap-edge"))
                .mask(boot.state.bootstrapIngressPassword)
                .mask(boot.state.bootstrapIngressGitCapability);
        Boot.must(boot.docker.run(command, out), "starting the bootstrap ingress failed");
        out.accept("  bootstrap ingress: http://" + boot.config.bootstrapIngressHost() + ":"
                + boot.config.bootstrapIngressPort() + " (loopback-published, expires "
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
                "QITS_BOOTSTRAP_INGRESS_HOST=" + boot.config.bootstrapIngressHost(),
                "QITS_BOOTSTRAP_INGRESS_PASSWORD=" + boot.state.bootstrapIngressPassword,
                "QITS_BOOTSTRAP_INGRESS_GITHOST_CAPABILITY=" + boot.state.bootstrapIngressGitCapability,
                "QITS_BOOTSTRAP_INGRESS_UI_UPSTREAM=http://qits-bootstrap-cli:" + boot.config.webPort(),
                "QITS_BOOTSTRAP_INGRESS_GIT_UPSTREAM=http://"
                        + PlatformModel.wireAlias("githost", env) + ":8080", "");
        Files.writeString(boot.state.bootstrapIngressEnvFile, text, StandardCharsets.UTF_8);
        secure(boot.state.bootstrapIngressEnvFile);
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
