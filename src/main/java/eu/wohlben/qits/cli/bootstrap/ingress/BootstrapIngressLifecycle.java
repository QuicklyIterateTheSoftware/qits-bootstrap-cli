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
    /** The edge's certificate volume: what public mode serves, and what decides whether it can. */
    static final String CERTIFICATE_VOLUME = "qits-edge-letsencrypt";
    /** The two files the ingress is configured with. Their presence IS the mode decision. */
    static final String CERTIFICATE = "/cert/lets-encrypt.crt";
    static final String CERTIFICATE_KEY = "/cert/lets-encrypt.key";
    /** The mode as the state file spells it, so a retry serves what the first attempt started. */
    static final String MODE_KEY = "QITS_BOOTSTRAP_INGRESS_MODE";
    private final Boot boot;
    /**
     * <b>Where this run's ingress listens, decided once and read by everything that has to agree
     * with it</b>: the publish, the TLS environment, the address the operator is given and the
     * maven url every seed image build is built with. It lives here rather than in the run state
     * because this class is the only thing that decides it — and because a run with no ingress at
     * all still has to answer the question, which it does with LOOPBACK.
     */
    private BootstrapIngressMode mode;

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
        Path file = stateFile();
        if (Files.isRegularFile(file) && restore(file, out)) {
            boot.state.bootstrapIngressEnvFile = file;
            return;
        }
        // BEFORE THE URL, because the url is the mode's. Every seed image build resolves the
        // platform's jars through the address this decides, so a mode chosen after the fact would
        // be a build argument pointing at a door nothing opened.
        mode = decide(out);
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
        if (mode() == BootstrapIngressMode.PUBLIC_TLS) {
            // The only ingress mount is the retained certificate pair, read-only. This container
            // receives neither the Docker socket nor any platform/configuration volume.
            argv.addAll(List.of("-p", "80:8080", "-p", "443:8443", "-v",
                    CERTIFICATE_VOLUME + ":/cert:ro"));
        } else if (mode() == BootstrapIngressMode.PUBLIC_HTTP) {
            // The same public door with no TLS half: no 443 publish, no certificate mount, and no
            // TLS environment — the server then listens plainly on its one port and redirects
            // nothing.
            argv.addAll(List.of("-p", "80:8080"));
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
        out.accept("  bootstrap ingress: " + address() + " (" + description()
                + ", expires " + Instant.ofEpochSecond(boot.state.bootstrapIngressExpiresAt) + ")");
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
                        + PlatformModel.wireAlias("githost", env) + ":8080",
                // Recorded so a worker retry serves what the first attempt started: the retained
                // container is not restarted, but the maven url a restore hands back has to be the
                // one the seed builds were already given.
                MODE_KEY + "=" + mode());
        if (mode() == BootstrapIngressMode.PUBLIC_TLS) {
            text += "\nQITS_BOOTSTRAP_INGRESS_TLS_PORT=8443"
                    + "\nQITS_BOOTSTRAP_INGRESS_TLS_CERTIFICATE=" + CERTIFICATE
                    + "\nQITS_BOOTSTRAP_INGRESS_TLS_KEY=" + CERTIFICATE_KEY;
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
        // A state file from a run older than the three modes records none; deciding afresh is the
        // right answer there, because the certificate volume says the same thing it said then.
        mode = BootstrapIngressMode.of(values.get(MODE_KEY), null);
        if (mode == null) {
            mode = decide(out);
        }
        boot.useBootstrapMavenRepository(mavenRepositoryUrl(), password);
        out.accept("  bootstrap edge capability retained (expires " + Instant.ofEpochSecond(expiresAt) + ")");
        return true;
    }

    /**
     * <b>WHICH MODE THIS RUN IS IN, and it is decided by what is on the certificate volume.</b>
     * <p>
     * Configuration decides only whether public mode is wanted at all: it needs a domain and it is
     * on by default there. What it cannot decide is whether the machine can serve TLS, because
     * that is a fact about the volume — a re-bootstrap keeps the pair its last run wrote, a fresh
     * host has an empty volume, and the placeholder certificate is written forty phases below this
     * one. Asking the volume is the only honest question, and asking it wrongly costs the boot its
     * first seed image build.
     * <p>
     * The probe runs {@code test -f} on the two paths the ingress would be CONFIGURED with, in this
     * program's own payload image, mounting the volume read-only. Where the image cannot be
     * identified the answer is TLS, which is what this did before there were three modes — and
     * {@code start} refuses a run with no payload image two phases later anyway.
     */
    private BootstrapIngressMode decide(Consumer<String> out) {
        BootstrapIngressMode decided = decide(boot.config.bootstrapIngressPublicEffective(),
                certificatePairPresent());
        if (decided == BootstrapIngressMode.PUBLIC_HTTP) {
            out.accept("  " + CERTIFICATE_VOLUME + " holds no certificate pair yet — the bootstrap "
                    + "ingress serves " + boot.config.domain().orElseThrow() + " over plain HTTP "
                    + "on port 80 for this run");
        }
        return decided;
    }

    /** The decision itself, so the two facts it turns on can be read without a docker daemon. */
    static BootstrapIngressMode decide(boolean publicWanted, boolean certificatePresent) {
        if (!publicWanted) {
            return BootstrapIngressMode.LOOPBACK;
        }
        return certificatePresent
                ? BootstrapIngressMode.PUBLIC_TLS : BootstrapIngressMode.PUBLIC_HTTP;
    }

    private boolean certificatePairPresent() {
        String image = boot.docker.selfImage();
        if (image == null) {
            return true;
        }
        return boot.docker.run(Cmd.of(List.of("docker", "run", "--rm",
                "-v", CERTIFICATE_VOLUME + ":/cert:ro", "--entrypoint", "sh", image,
                "-c", "test -f " + CERTIFICATE + " -a -f " + CERTIFICATE_KEY)), null).ok();
    }

    /**
     * The mode this run decided. Before {@code prepare} has decided it — and for a run that never
     * prepares an ingress at all — it is what configuration alone implies, which is what every
     * consumer read before the certificate volume had a say.
     */
    public BootstrapIngressMode mode() {
        return mode != null ? mode
                : decide(boot.config.bootstrapIngress()
                        && boot.config.bootstrapIngressPublicEffective(), true);
    }

    /** The seam the address tests drive: a decided mode without a docker daemon to ask. */
    void mode(BootstrapIngressMode decided) {
        this.mode = decided;
    }

    /** Where a person reaches the live progress page while this runs. */
    String address() {
        return mode().isPublic()
                ? mode().scheme() + "://" + ingressHost()
                : "http://" + boot.config.bootstrapIngressHost() + ":"
                        + boot.config.bootstrapIngressPort();
    }

    private String description() {
        return switch (mode()) {
            case PUBLIC_TLS -> "TLS domain handoff";
            case PUBLIC_HTTP -> "plain HTTP on the domain, no certificate on the volume yet";
            case LOOPBACK -> "loopback-published";
        };
    }

    private String ingressHost() {
        return mode().isPublic()
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

    /**
     * <b>Where every seed image build resolves this platform's own jars.</b> One address per mode,
     * and it is the mode's own scheme: host-networked builds use the same door an operator does,
     * so there is no hidden 8481 publish in either public mode.
     * <p>
     * Plain HTTP on the domain resolves as well as TLS does, which is what makes PUBLIC_HTTP a
     * mode rather than a compromise: every repository's {@code .qits-maven-settings.xml} mirrors
     * the {@code qits-maven} repository id to this url by EXACT id, and an exact-id mirror wins
     * over Maven's {@code external:http:*} blocker.
     */
    String mavenRepositoryUrl() {
        return address() + "/artifacts/maven/maven";
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
