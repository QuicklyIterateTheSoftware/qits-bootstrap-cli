package eu.wohlben.qits.cli.bootstrap.platform;

import eu.wohlben.qits.cli.bootstrap.proc.Cmd;
import eu.wohlben.qits.cli.bootstrap.proc.ProcessResult;
import eu.wohlben.qits.cli.bootstrap.proc.ProcessRunner;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The docker CLI, driving the HOST's daemon through the socket mounted into this container.
 * <p>
 * Nearly every path this class puts on a command line is read by the CLIENT — {@code docker cp}
 * reads the source here, and {@code docker compose -f} parses the file here. So the run's own
 * working directory is the only place those paths have to be right, and the script's {@code /out}
 * mount and gitdir contortions stay retired even though the socket came back.
 * <p>
 * <b>The image builds are the exception, and {@link #buildScratch} is where that is paid for.</b>
 * They no longer go to the daemon at all: they go to a buildkitd container through a buildctl
 * container, and the {@code -v} paths of that second container are resolved by the DAEMON. Those
 * are the paths that have to mean the same thing on both sides. The others the daemon resolves are
 * the ones this program hands it deliberately: named volumes, and {@code /var/run/docker.sock} for
 * the services that need it.
 */
public class Docker {
    /**
     * <b>The platform's BuildKit daemon, created here and then owned by qits-containers.</b> The
     * bootstrap is only the first thing that needs it: it has to build the seed images before any
     * platform exists, so it makes the container itself, at the same name, image and state volume
     * qits-containers ensures from its first deployment on. That is why the run's last phase leaves
     * it standing — the warm cache in {@link #BUILDKIT_STATE_VOLUME} is the platform's from then on,
     * and a teardown here would throw away a cache the next CI build wants.
     */
    public static final String BUILDKITD = "qits-buildkitd";

    /**
     * The pin, in the one place this program reads it. During a bootstrap the host daemon resolves
     * it through its own {@code registry-mirrors}, which is what makes an unqualified Hub name work
     * before the platform's mirror exists.
     */
    public static final String BUILDKIT_IMAGE = "moby/buildkit:v0.33.0";

    /** Where the build cache lives, so it survives the container and the run. */
    public static final String BUILDKIT_STATE_VOLUME = "qits-buildkitd-state";

    /**
     * What the client dials. The daemon runs on the HOST's network namespace, so the loopback the
     * buildctl container sees under {@code --network host} is the same loopback buildkitd listens
     * on.
     */
    public static final String BUILDKIT_ADDR = "tcp://127.0.0.1:1234";

    /**
     * What buildkitd binds. Bind-all rather than loopback: the client reaches it on 127.0.0.1
     * either way, and 0.0.0.0 keeps the SAME container usable once qits-containers re-ensures it
     * onto qits-net, where every consumer dials the {@code qits-buildkitd} alias instead.
     */
    public static final String BUILDKIT_LISTEN = "tcp://0.0.0.0:1234";

    /**
     * What every buildx builder this program EVER created is named after, and nothing creates one
     * any more. It is kept as the whole test for "ours" in the legacy sweep: a builder outside this
     * prefix belongs to somebody else on a shared host and is never touched.
     */
    public static final String BUILDER_PREFIX = "qits-bootstrap-builder";

    /** Long enough for a cold GraalVM native build, which is what most of these are. */
    public static final Duration BUILD_TIMEOUT = Duration.ofHours(4);

    /** The name the rewritten Dockerfile is written under, and what {@code --opt filename} names. */
    private static final String DOCKERFILE = "Dockerfile";

    /** Where buildctl writes the image, inside its own container. */
    private static final String IMAGE_TAR = "image.tar";

    /** The only driver a network of this platform has: see {@link #ensureNetwork}. */
    public static final String OVERLAY = "overlay";

    /**
     * Where a container learns its own id. Docker sets the hostname to the container's short id
     * unless it is given another one, and both are names the daemon resolves.
     */
    private static final Path SELF = Path.of("/etc/hostname");

    private final ProcessRunner runner;
    private boolean buildkitdReady;

    /**
     * <b>What every image build this program runs carries, whatever the image is.</b> A build
     * argument an image has no {@code ARG} for is a warning and nothing else, so the honest place
     * for one that MOST builds need is all of them — see {@link #withBuildArgs}.
     * <p>
     * Each entry is a bare {@code NAME=value}: buildctl spells a build argument
     * {@code --opt build-arg:NAME=value}, so the flag is added where the command line is composed
     * rather than carried in this list.
     */
    private List<String> buildArgs = List.of();
    private List<String> buildArgMasks = List.of();

    /**
     * BuildKit secrets by id, as VALUES rather than as environment variable names. buildctl runs in
     * a container of its own here, so it can read no variable of this process — each value is
     * written to a file the run mounts read-only instead. See {@link #buildkitBuild}.
     */
    private Map<String, String> buildSecrets = Map.of();

    /**
     * <b>Where the scratch directories of a build are made, and it cannot be this container's
     * {@code /tmp}.</b>
     * <p>
     * The buildctl client runs as a container, so the paths it is given with {@code -v} are
     * resolved by the HOST's daemon and not by this process — the one place in this facade where
     * that is true. A {@code Files.createTempDirectory()} under the payload container's own
     * {@code /tmp} names a directory the host has never heard of, and docker would silently create
     * an empty root-owned one in its place. So the scratch root is a path the launcher already
     * bind-mounts at its own name, which makes it the same directory on both sides;
     * {@code Boot} sets it to the run's sources directory. The default below is for the HOST half,
     * which builds nothing through buildkitd.
     */
    private Path buildScratch = Path.of(System.getProperty("java.io.tmpdir", "/tmp"));

    public Docker(ProcessRunner runner) {
        this.runner = runner;
    }

    /**
     * <b>The arguments both build methods below carry, set once by the run that owns this facade.</b>
     * <p>
     * It is at this seam rather than at each call site because the requirement is per-DAEMON and not
     * per-image: a build this bootstrap runs resolves the platform's own maven artifacts from the
     * seed registry on the host's loopback, and there is no second answer any build of this run could
     * want. Spelled per call site, a build added later inherits nothing and fails minutes in with a
     * connection refused.
     * <p>
     * The HOST HALF's own Docker is a second instance and deliberately gets none: the payload image
     * is this CLI and resolves nothing of the platform.
     */
    public Docker withBuildArgs(List<String> args) {
        this.buildArgs = List.copyOf(args);
        this.buildArgMasks = List.of();
        this.buildSecrets = Map.of();
        return this;
    }

    /** A bootstrap capability can be in a repository URL without becoming build output. */
    public Docker withBuildArgs(List<String> args, List<String> masks) {
        this.buildArgs = List.copyOf(args);
        this.buildArgMasks = List.copyOf(masks);
        this.buildSecrets = Map.of();
        return this;
    }

    /**
     * Supplies the capability through the secret ids already mounted by every Maven Dockerfile.
     * <p>
     * The values are carried here rather than put in this process's environment because buildctl
     * runs in a container: it inherits nothing of ours, and the only way in is a file it can read.
     * They stay off the screen and out of the log through {@link Cmd#mask}.
     */
    public Docker withBootstrapMavenRepository(String url, String username, String password) {
        this.buildArgs = List.of("QITS_MAVEN_REPOSITORY_URL=" + url);
        this.buildArgMasks = List.of(password);
        // Ordered, because the argv it becomes is asserted whole.
        Map<String, String> secrets = new LinkedHashMap<>();
        secrets.put("qits-client-id", username);
        secrets.put("qits-client-secret", password);
        this.buildSecrets = Collections.unmodifiableMap(secrets);
        return this;
    }

    /** Where a build's scratch directories go: a path the host daemon knows by the same name. */
    public Docker withBuildScratch(Path directory) {
        this.buildScratch = directory;
        return this;
    }

    public ProcessResult run(Cmd cmd, Consumer<String> out) {
        return runner.run(cmd, out);
    }

    public boolean daemonReachable() {
        return runner.run(Cmd.of("docker", "version"), null).ok();
    }

    public boolean composePluginPresent() {
        return runner.run(Cmd.of("docker", "compose", "version"), null).ok();
    }

    /**
     * The daemon's swarm state, and whether this node is a MANAGER. Both halves decide something:
     * {@code active} alone is a node that may be a worker, and a worker creates no overlay network
     * and runs no service — every command this program issues needs the control plane.
     *
     * @param state {@code active}, {@code inactive}, {@code pending}, {@code locked}, {@code error}
     *              or {@code unknown} when the daemon did not answer
     */
    public record Swarm(String state, boolean manager) {

        public boolean inactive() {
            return "inactive".equals(state);
        }

        /** Active AND a manager: the only state this program can work in. */
        public boolean ready() {
            return "active".equals(state) && manager;
        }
    }

    /** One {@code docker info} for both halves of the answer. */
    public Swarm swarm() {
        ProcessResult info = runner.run(Cmd.of("docker", "info", "--format",
                "{{.Swarm.LocalNodeState}} {{.Swarm.ControlAvailable}}"), null);
        String[] answer = info.ok() ? info.trimmed().split("\\s+") : new String[0];
        String state = answer.length > 0 && !answer[0].isBlank() ? answer[0] : "unknown";
        return new Swarm(state, answer.length > 1 && "true".equals(answer[1]));
    }

    /** The state alone, for the host half's one report line. */
    public String swarmState() {
        return swarm().state();
    }

    /**
     * <b>The registry names this daemon will speak plain HTTP to.</b> Asked of the DAEMON rather
     * than read out of {@code /etc/docker/daemon.json}: the file is on the host and this program is
     * a container, the path is not the only place the setting can come from, and a daemon that has
     * not been restarted since the file changed would answer differently from it — which is exactly
     * the state worth catching.
     * <p>
     * The platform's registry and mirror are reached at {@code <app>.<env>.localhost} over HTTP
     * now, and a name is not a loopback ADDRESS: docker's built-in exemption covers 127.0.0.0/8 and
     * ::1 only, so without an entry here every push and pull fails with "http: server gave HTTP
     * response to HTTPS client".
     */
    public List<String> insecureRegistries() {
        ProcessResult info = runner.run(Cmd.of("docker", "info", "--format",
                "{{range $name, $index := .RegistryConfig.IndexConfigs}}"
                        + "{{if not $index.Secure}}{{$name}} {{end}}{{end}}"), null);
        if (!info.ok()) {
            return List.of();
        }
        return Arrays.stream(info.trimmed().split("\\s+"))
                .map(String::trim)
                .filter(name -> !name.isBlank())
                .toList();
    }

    public boolean swarmActive() {
        return swarm().ready();
    }

    /**
     * Puts this single-node machine in a swarm of its own. Called only when the daemon says
     * {@code inactive} — every other state is somebody else's swarm, and initialising over it is a
     * migration rather than a preflight repair.
     * <p>
     * No {@code --advertise-addr}: on a host with one route the daemon picks the address itself, and
     * on a host with several this program cannot pick it — see {@link
     * eu.wohlben.qits.cli.bootstrap.phases.SeedPhases#ensureSwarm}.
     */
    public ProcessResult initSwarm(Consumer<String> out) {
        return runner.run(Cmd.of("docker", "swarm", "init"), out);
    }

    /** Whether an image reference is already on this daemon. */
    public boolean imageExists(String reference) {
        return runner.run(Cmd.of("docker", "image", "inspect", "-f", "{{.Id}}", reference),
                null).ok();
    }

    /**
     * The docker socket's group. The deployer and the orchestrator join it rather than running as
     * root, and they are the only two services that hold the socket at all.
     */
    public String socketGroupId() {
        Path socket = Path.of("/var/run/docker.sock");
        try {
            Object gid = Files.getAttribute(socket, "unix:gid");
            if (gid != null) {
                return gid.toString();
            }
        } catch (IOException | UnsupportedOperationException e) {
            // Fall through to stat, then to 0 — a group that does not exist is better than a crash.
        }
        ProcessResult stat = runner.run(Cmd.of("stat", "-c", "%g", socket.toString()), null);
        return stat.ok() && !stat.trimmed().isEmpty() ? stat.trimmed() : "0";
    }

    /**
     * The name this process's own container answers to, or null when the file cannot be read. It
     * is not proof of a container — a host's {@code /etc/hostname} is a hostname too — so the
     * caller asks the daemon whether it knows the name before trusting it.
     */
    public String selfName() {
        try {
            String name = Files.readString(SELF).trim();
            return name.isBlank() ? null : name;
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * The NAME the daemon knows this process's own container by, or null when this is not one.
     * <p>
     * {@link #selfName()} answers with the id, and every sweep in this program lists names — so a
     * phase that must not touch the container it is running in has to compare like with like. That
     * is {@code unwrap}'s {@code containers} phase, which would otherwise {@code docker rm -f}
     * itself.
     */
    public String selfContainerName() {
        String self = selfName();
        if (self == null) {
            return null;
        }
        ProcessResult name = runner.run(Cmd.of("docker", "inspect", "--type", "container",
                "-f", "{{.Name}}", self), null);
        if (!name.ok()) {
            return null;
        }
        // docker reports it with a leading slash; `docker ps --format {{.Names}}` does not.
        String text = name.trimmed();
        return text.startsWith("/") ? text.substring(1) : text.isEmpty() ? null : text;
    }

    /** The exact payload image this bootstrap is running from, for a sibling with identical code. */
    public String selfImage() {
        String self = selfName();
        if (self == null) {
            return null;
        }
        ProcessResult image = runner.run(Cmd.of("docker", "inspect", "--type", "container", "-f",
                "{{.Config.Image}}", self), null);
        return image.ok() && !image.trimmed().isBlank() ? image.trimmed() : null;
    }

    public boolean containerExists(String nameOrId) {
        return runner.run(Cmd.of("docker", "inspect", "--type", "container", "-f", "{{.Id}}",
                nameOrId), null).ok();
    }

    /** The networks a container is an endpoint on. */
    public List<String> networksOf(String nameOrId) {
        ProcessResult result = runner.run(Cmd.of("docker", "inspect", "-f",
                "{{range $net, $_ := .NetworkSettings.Networks}}{{$net}} {{end}}", nameOrId), null);
        List<String> networks = new ArrayList<>();
        for (String line : lines(result)) {
            for (String name : line.split("\\s+")) {
                if (!name.isBlank()) {
                    networks.add(name);
                }
            }
        }
        return networks;
    }

    /** The driver of a network, or an empty string when there is no network by that name. */
    public String networkDriver(String name) {
        ProcessResult driver = runner.run(Cmd.of(
                "docker", "network", "inspect", "-f", "{{.Driver}}", name), null);
        return driver.ok() ? driver.trimmed() : "";
    }

    /**
     * Creates the network unless it is there, as an <b>attachable overlay</b>. The deployer and
     * compose both adopt an existing one.
     * <p>
     * Overlay because a swarm service cannot attach a local bridge — measured: {@code service create
     * --network qits-net} answers "network qits-net not found". Attachable because plain
     * {@code docker run} containers live on the same network and must keep doing so: the ci steps,
     * the workspace and agent containers, and this run itself. Both directions of DNS were measured
     * to work there, which is what makes one network enough.
     * <p>
     * <b>A bridge of the same name is refused rather than adopted.</b> It cannot be converted in
     * place, and the platform is re-bootstrapped rather than migrated — so the honest answer is to
     * name the network and the way out, not to carry on and fail later at the first service.
     */
    public void ensureNetwork(String name, Consumer<String> out) {
        String driver = networkDriver(name);
        if (OVERLAY.equals(driver)) {
            return;
        }
        if (!driver.isEmpty()) {
            throw new IllegalStateException(name + " already exists as a " + driver + " network, and "
                    + "a swarm service cannot attach one. It cannot be converted either: remove it "
                    + "and let this run make it again — `unwrap` does that, or stop everything on it "
                    + "and run `docker network rm " + name + "`");
        }
        ProcessResult created = runner.run(Cmd.of("docker", "network", "create",
                "-d", OVERLAY, "--attachable", name), out);
        // Two creators between the inspect and the create is the ordinary race here — ci and the
        // deployer ensure this network too — and whoever won made the same one.
        if (!created.ok() && !created.out().contains("already exists")) {
            throw new IllegalStateException("the overlay network " + name + " could not be created: "
                    + created.tailText(3));
        }
    }

    public void ensureVolume(String name, Consumer<String> out) {
        runner.run(Cmd.of("docker", "volume", "create", name), out);
    }

    // --- the seed stack -----------------------------------------------------------------------

    /** The seed's stack name, and the compose project name before it. */
    public static final String STACK = "qits";

    /**
     * Deploys the seed stack.
     * <p>
     * <b>{@code --resolve-image never} is not optional here.</b> Every image in the file is a local
     * tag this bootstrap built — {@code qits/ci:latest} and its neighbours — and the default
     * ({@code always}) asks a registry to resolve each one to a digest, which no registry can do.
     * The flag is spelled {@code --resolve-image never} on {@code stack deploy}; the same idea is
     * {@code --no-resolve-image} on {@code service create}, and neither spelling works for the
     * other command.
     * <p>
     * No {@code --prune}: a service the deployer has taken over is left out of the FILE, and prune
     * would then remove it from the stack — which is a removal this program has no business making
     * on a running platform. What the file names is updated; everything else is left alone.
     */
    public ProcessResult stackDeploy(Path file, String stack, Duration timeout,
                                     Consumer<String> out) {
        return runner.run(Cmd.of(List.of("docker", "stack", "deploy", "--resolve-image", "never",
                "-c", file.toString(), stack)).timeout(timeout), out);
    }

    public ProcessResult stackRm(String stack, Consumer<String> out) {
        return runner.run(Cmd.of("docker", "stack", "rm", stack), out);
    }

    /**
     * The name of every swarm service on this node's swarm.
     * <p>
     * <b>This is what {@code docker ps} used to answer.</b> A stack ignores {@code container_name}
     * and names the container {@code <stack>_<service>.<slot>.<taskid>}, so every check that asked
     * for a container by the name a service answers to now asks here instead.
     */
    public List<String> serviceNames() {
        return lines(runner.run(Cmd.of("docker", "service", "ls", "--format", "{{.Name}}"), null));
    }

    /** Service names carrying a label, which is how the deployer's own services are found. */
    public List<String> serviceNames(String filter) {
        return lines(runner.run(Cmd.of("docker", "service", "ls", "--filter", filter,
                "--format", "{{.Name}}"), null));
    }

    /** Removes services, in one call: {@code service rm} takes as many names as it is given. */
    public ProcessResult serviceRm(List<String> names, Consumer<String> out) {
        List<String> command = new ArrayList<>(List.of("docker", "service", "rm"));
        command.addAll(names);
        return runner.run(Cmd.of(command), out);
    }

    /**
     * The two names a stack service answers to, and both are addresses on the network: the
     * qualified {@code <stack>_<service>} and the bare short name — measured, on an external
     * network too. So a check for "is this alias already a service" has to ask for both.
     */
    public static String stackService(String stack, String service) {
        return stack + "_" + service;
    }

    /** Names of the running containers. */
    public List<String> runningNames() {
        return lines(runner.run(Cmd.of("docker", "ps", "--format", "{{.Names}}"), null));
    }

    /** Names of every container, running or not. */
    public List<String> allNames() {
        return lines(runner.run(Cmd.of("docker", "ps", "-a", "--format", "{{.Names}}"), null));
    }

    /** "name image" per running container — how a platform service's live sha is read. */
    public List<String> runningNamesAndImages() {
        return lines(runner.run(Cmd.of("docker", "ps", "--format", "{{.Names}} {{.Image}}"), null));
    }

    public List<String> ps(String format) {
        return lines(runner.run(Cmd.of("docker", "ps", "--format", format), null));
    }

    public void removeContainer(String name, Consumer<String> out) {
        runner.run(Cmd.of("docker", "rm", "-f", name), out);
    }

    /**
     * A container's log since an RFC3339 moment, each line led by docker's own timestamp — which is
     * what lets the caller ask only for what is new. stderr rides along: the runner merges the
     * streams, and a container's log arrives on both.
     */
    public List<String> logsSince(String container, String since) {
        return lines(runner.run(
                Cmd.of("docker", "logs", "--timestamps", "--since", since, container), null));
    }

    /**
     * Builds an image from a Dockerfile this program holds in memory, which is how the seed builds
     * get the mirror-free FROM lines without touching the checkout.
     * <p>
     * <b>It is written to a directory of its own rather than fed on stdin.</b> buildctl has no
     * {@code -f -}: the dockerfile frontend reads a named file out of a local mount, so the text has
     * to be a file somewhere. A scratch directory beside the context keeps the checkout untouched,
     * which was the whole point of the stdin form.
     */
    public ProcessResult buildFromStdin(String tag, String dockerfile, Path context,
                                        List<String> extraBuildArgs, Consumer<String> out) {
        Path dockerfileDir = null;
        try {
            dockerfileDir = Files.createTempDirectory(scratchRoot(), "dockerfile-");
            Files.writeString(dockerfileDir.resolve(DOCKERFILE), dockerfile, StandardCharsets.UTF_8);
            return buildkitBuild(tag, dockerfileDir, DOCKERFILE, context, extraBuildArgs, out);
        } catch (IOException e) {
            return failed("the Dockerfile for " + tag + " could not be written: " + e.getMessage());
        } finally {
            deleteTree(dockerfileDir);
        }
    }

    /**
     * Builds an image from a Dockerfile that IS in the checkout — the step images, whose context is
     * the repository and whose Dockerfile is a file inside it.
     * <p>
     * The two paths are named rather than handed over as a free argv: buildctl takes the directory
     * and the file name apart ({@code --local dockerfile=<dir>} plus {@code --opt filename=}), and
     * a caller that assembled {@code -f <path>} would have to be parsed back.
     */
    public ProcessResult buildWithFile(String tag, Path dockerfile, Path context,
                                       List<String> extraBuildArgs, Consumer<String> out) {
        return buildkitBuild(tag, dockerfile.getParent(), dockerfile.getFileName().toString(),
                context, extraBuildArgs, out);
    }

    /**
     * <b>One build, through the platform's buildkitd, landing in the host daemon's image store.</b>
     * <p>
     * The host has no {@code buildctl} binary and this program will not install one, so the client
     * is the same pinned image the daemon runs, with its entrypoint replaced. {@code --network host}
     * on the client is how it reaches the daemon's loopback address; the daemon is host-network too,
     * which is why a Dockerfile's {@code ADD http://localhost:8081/…} and the seed maven repository
     * still resolve during a bootstrap.
     * <p>
     * <b>{@code --output type=docker} plus {@code docker load} is what replaces buildx's
     * {@code --load}.</b> The image lands in the host daemon's store under the same tag it always
     * did, so the {@code docker tag}, {@code docker create} and
     * {@code stack deploy --resolve-image never} that follow a build need to know nothing about any
     * of this.
     */
    private ProcessResult buildkitBuild(String tag, Path dockerfileDir, String dockerfileName,
                                        Path context, List<String> extraBuildArgs,
                                        Consumer<String> out) {
        ProcessResult ready = ensureBuildkitd(out);
        if (!ready.ok()) {
            return ready;
        }
        Path outDir = null;
        Path secretsDir = null;
        try {
            outDir = Files.createTempDirectory(scratchRoot(), "image-");
            secretsDir = writeSecrets();
            List<String> command = new ArrayList<>(List.of(
                    "docker", "run", "--rm", "--network", "host",
                    "-v", context + ":/ctx:ro",
                    "-v", dockerfileDir + ":/dfdir:ro",
                    "-v", outDir + ":/out",
                    "-v", secretsDir + ":/secrets:ro",
                    "--entrypoint", "buildctl", BUILDKIT_IMAGE,
                    "--addr", BUILDKIT_ADDR,
                    "build", "--frontend", "dockerfile.v0",
                    "--local", "context=/ctx",
                    "--local", "dockerfile=/dfdir",
                    "--opt", "filename=" + dockerfileName));
            for (String arg : buildArgs) {
                command.add("--opt");
                command.add("build-arg:" + arg);
            }
            for (String arg : extraBuildArgs) {
                command.add("--opt");
                command.add("build-arg:" + arg);
            }
            for (String id : buildSecrets.keySet()) {
                command.add("--secret");
                command.add("id=" + id + ",src=/secrets/" + id);
            }
            command.add("--output");
            command.add("type=docker,name=" + tag + ",dest=/out/" + IMAGE_TAR);
            Cmd cmd = Cmd.of(command).timeout(BUILD_TIMEOUT);
            buildArgMasks.forEach(cmd::mask);
            buildSecrets.values().forEach(cmd::mask);
            ProcessResult built = runner.run(cmd, out);
            if (!built.ok()) {
                return built;
            }
            // The tar is read by the CLIENT, so this path is this container's own — the one place
            // the mounts above and this line mean the same directory for two different reasons.
            return runner.run(Cmd.of(List.of("docker", "load", "-i",
                    outDir.resolve(IMAGE_TAR).toString())).timeout(BUILD_TIMEOUT), out);
        } catch (IOException e) {
            return failed("the build of " + tag + " could not be set up: " + e.getMessage());
        } finally {
            deleteTree(outDir);
            deleteTree(secretsDir);
        }
    }

    /**
     * Builds an image on the host daemon's own default builder, and it is the ONE build that does.
     * <p>
     * This is the launcher's payload image — this CLI itself — built on the HOST half, before any
     * phase has run and therefore before any buildkitd exists to build it. It resolves nothing of
     * the platform and needs no buildx either: plain {@code docker build} with whatever builder the
     * daemon has is exactly right, and routing it through buildkitd would be a chicken-and-egg.
     */
    public ProcessResult buildOnHostDaemon(String tag, Path dockerfile, Path context,
                                           Consumer<String> out) {
        return runner.run(Cmd.of(List.of("docker", "build",
                "-f", dockerfile.toString(), "-t", tag, context.toString()))
                .timeout(BUILD_TIMEOUT), out);
    }

    /**
     * <b>The buildkitd container, made on the first build of a run and left standing at the end of
     * it.</b>
     * <p>
     * It is host-network for two reasons at once: a build's {@code ADD} and the seed maven
     * repository are on the host's loopback while the platform is being made, and the buildctl
     * client above reaches the daemon there too. {@code --privileged} is what BuildKit's own
     * container needs to run builds; the memory and cpu bounds are the ones the buildx driver-opts
     * carried, spelled as docker run flags now that there is no driver to hold them.
     * <p>
     * <b>Rerun-safe like every other phase.</b> A container of this name that is already running is
     * adopted — it may be qits-containers' own, re-ensured onto qits-net after a first bootstrap,
     * and it answers the same address — and a stopped one is started rather than replaced, because
     * its state volume is the cache.
     */
    private synchronized ProcessResult ensureBuildkitd(Consumer<String> out) {
        if (buildkitdReady) {
            return new ProcessResult(0, List.of(), List.of(), false, false);
        }
        // EVERY BUILDX BUILDER THIS PROGRAM EVER MADE, on the first build of the run. The builder
        // name was bumped whenever a driver-opt changed — buildx keeps the options a builder was
        // created with and reuses an existing one as it stands — and each bump left its
        // predecessor's container and its multi-gigabyte state volume behind, referenced by nothing
        // and swept by nobody. Nothing creates one any more, so the sweep now takes them all.
        for (String stale : staleBuilders(builderRows(), "")) {
            log(out, "  removing the legacy buildx builder " + stale
                    + " — the bootstrap builds through " + BUILDKITD + " now");
            removeBuilder(stale, out);
        }
        ProcessResult ready;
        if (containerExists(BUILDKITD)) {
            if (runningNames().contains(BUILDKITD)) {
                log(out, "  " + BUILDKITD + " is already running — adopted");
                ready = new ProcessResult(0, List.of(), List.of(), false, false);
            } else {
                log(out, "  starting the existing " + BUILDKITD + " and its warm cache");
                ready = runner.run(Cmd.of("docker", "start", BUILDKITD), out);
            }
        } else {
            ensureVolume(BUILDKIT_STATE_VOLUME, out);
            ready = runner.run(Cmd.of(List.of(
                    "docker", "run", "-d", "--name", BUILDKITD,
                    "--privileged", "--network", "host",
                    "--restart", "unless-stopped",
                    // So the kernel takes buildkitd before it takes a platform service.
                    "--oom-score-adj", "500",
                    "--memory", "9g", "--cpu-quota", "400000", "--cpuset-cpus", "0-3",
                    "-v", BUILDKIT_STATE_VOLUME + ":/var/lib/buildkit",
                    BUILDKIT_IMAGE,
                    // The image entrypoints to buildkitd, so this rides as its arguments.
                    "--addr", BUILDKIT_LISTEN)), out);
        }
        buildkitdReady = ready.ok();
        return ready;
    }

    /**
     * Each secret as a file only its owner can read, in a directory of its own. buildctl's
     * {@code env=} form cannot be used here: the client is a container and inherits none of this
     * process's environment.
     */
    private Path writeSecrets() throws IOException {
        Path dir = Files.createTempDirectory(scratchRoot(), "secrets-");
        for (Map.Entry<String, String> secret : buildSecrets.entrySet()) {
            Path file = dir.resolve(secret.getKey());
            Files.writeString(file, secret.getValue(), StandardCharsets.UTF_8);
            try {
                Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
            } catch (IOException | UnsupportedOperationException e) {
                // A filesystem with no posix bits is not a reason to fail a build; the directory is
                // this run's own and goes away with it either way.
            }
        }
        return dir;
    }

    private Path scratchRoot() throws IOException {
        return Files.createDirectories(buildScratch);
    }

    /** Best effort: a scratch directory left behind is untidy, never a failed build. */
    private static void deleteTree(Path dir) {
        if (dir == null) {
            return;
        }
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    // Left behind, and said nothing: see above.
                }
            });
        } catch (IOException e) {
            // Same.
        }
    }

    /** What a failure this program decided itself looks like, in the shape a command's would. */
    private static ProcessResult failed(String message) {
        return new ProcessResult(1, List.of(message), List.of(message), false, false);
    }

    private static void log(Consumer<String> out, String line) {
        if (out != null) {
            out.accept(line);
        }
    }

    /**
     * {@code docker buildx ls}, RAW: builder rows and the node rows under them, with the leading
     * whitespace kept. It deliberately does not go through {@link #lines}, which trims — and the
     * indent is half of what tells a node from a builder. See {@link #staleBuilders}.
     */
    public List<String> builderRows() {
        ProcessResult result = runner.run(Cmd.of("docker", "buildx", "ls"), null);
        if (!result.ok()) {
            return List.of();
        }
        List<String> rows = new ArrayList<>();
        for (String line : result.captured()) {
            if (!line.isBlank() && !line.startsWith("$ ")) {
                rows.add(line);
            }
        }
        return rows;
    }

    /**
     * <b>The builders this program left behind, out of {@code docker buildx ls}' own table.</b>
     * <p>
     * That table interleaves two kinds of row: a BUILDER at column zero, then its nodes indented
     * under {@code \_}. A docker-container builder names its node after itself with an index
     * appended — {@code qits-bootstrap-builder-v5} has the node {@code qits-bootstrap-builder-v50}
     * — so a sweep that read every row would ask buildx to remove a node as if it were a builder.
     * A node row is marked twice over, and BOTH are checked because either one alone has been
     * wrong here: it is indented, and its first token is {@code \_}. The indent is the natural
     * test and it does not survive a caller that trimmed — which is exactly what this class's own
     * {@code lines} helper does to every other command's output.
     * <p>
     * Only names under {@link #BUILDER_PREFIX} are ours. {@code current} is what to leave alone,
     * and it is EMPTY at every caller now: nothing creates a buildx builder any more, so every one
     * of ours that is still on a host is a leftover of the buildx era.
     */
    public static List<String> staleBuilders(List<String> rows, String current) {
        List<String> stale = new ArrayList<>();
        for (String row : rows) {
            if (row.isBlank() || Character.isWhitespace(row.charAt(0))
                    || row.trim().startsWith("\\_")) {
                continue;
            }
            String name = row.trim().split("\\s+")[0];
            if (name.endsWith("*")) {
                name = name.substring(0, name.length() - 1);
            }
            if (name.startsWith(BUILDER_PREFIX) && !name.equals(current) && !stale.contains(name)) {
                stale.add(name);
            }
        }
        return List.copyOf(stale);
    }

    /**
     * Removes a builder, ITS CONTAINER AND ITS STATE VOLUME — which is the whole point: the volume
     * is where the multi-gigabyte cache lives, and {@code buildx rm} is the only command that knows
     * the name it was given ({@code buildx_buildkit_<builder>0_state}).
     */
    public ProcessResult removeBuilder(String name, Consumer<String> out) {
        return runner.run(Cmd.of("docker", "buildx", "rm", name), out);
    }

    /**
     * <b>A removal that found nothing is a removal that succeeded.</b> A run that never built, or a
     * second run of this phase, has no builder to remove, and buildx says so on the error stream
     * rather than with a status of its own.
     */
    public static boolean alreadyGone(ProcessResult result) {
        String said = result.tailText(5).toLowerCase();
        return said.contains("no builder") || said.contains("not found")
                || said.contains("no such");
    }

    /** The volumes no container holds — the only ones this program will consider removing. */
    public List<String> danglingVolumes() {
        return lines(runner.run(
                Cmd.of("docker", "volume", "ls", "-q", "-f", "dangling=true"), null));
    }

    public ProcessResult removeVolume(String name, Consumer<String> out) {
        return runner.run(Cmd.of("docker", "volume", "rm", name), out);
    }

    public ProcessResult exec(Consumer<String> out, String... args) {
        return runner.run(Cmd.of(prepend("docker", args)), out);
    }

    public ProcessResult exec(Duration timeout, Consumer<String> out, String... args) {
        return runner.run(Cmd.of(prepend("docker", args)).timeout(timeout), out);
    }

    private static List<String> prepend(String first, String[] rest) {
        List<String> all = new ArrayList<>(rest.length + 1);
        all.add(first);
        all.addAll(Arrays.asList(rest));
        return all;
    }

    private static List<String> lines(ProcessResult result) {
        // A COMMAND THAT FAILED LISTED NOTHING. Docker writes its refusal on the same stream the
        // runner captures, so without this a `service ls` on a daemon in no swarm answers one
        // "name" reading "This node is not a swarm manager" — and a sweep would try to remove it.
        if (!result.ok()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String line : result.captured()) {
            if (!line.isBlank() && !line.startsWith("$ ")) {
                out.add(line.trim());
            }
        }
        return out;
    }
}
