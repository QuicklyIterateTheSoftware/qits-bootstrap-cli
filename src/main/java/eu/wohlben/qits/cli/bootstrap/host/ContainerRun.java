package eu.wohlben.qits.cli.bootstrap.host;

import eu.wohlben.qits.cli.bootstrap.config.BootstrapConfig;

import java.nio.file.Path;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The {@code docker run} that starts the payload — as a value, so what the launcher is about to do
 * can be read, logged and tested without a daemon.
 * <p>
 * Every flag on it is load-bearing; the method below says which and why. Two rules shape the whole
 * thing:
 * <ul>
 *   <li><b>Host paths are mounted at THEIR OWN PATH.</b> A run's state — the wrapper's checkouts,
 *       {@code .qits-bootstrap.env}, the clones, the generated compose file, the log — has to
 *       survive the container, and every one of those places is named by a {@code QITS_*} value or
 *       derived from one. Mounting each at the same absolute path inside means those values need
 *       no translation: the host half passes them through and they mean the same file on both
 *       sides. There is one configuration contract, and this is what keeps it one.
 *   <li><b>No secret is ever a command-line argument.</b> Environment variables are passed by NAME
 *       ({@code -e QITS_PG_SUPERUSER_PASSWORD}), which tells docker to copy the value from the
 *       launcher's own environment. The generated postgres passwords and client secrets therefore
 *       never reach this argv, the screen or the log.
 * </ul>
 */
public final class ContainerRun {

    /**
     * The docker socket, mounted so the payload drives the HOST's daemon. Everything the bootstrap
     * does is a docker command; without it the container is an empty JVM.
     */
    static final String SOCKET = "/var/run/docker.sock";

    /**
     * One bootstrap at a time, and it is nameable: {@code docker logs qits-bootstrap-cli} works
     * while it runs. A second run is already refused by the browser view's port, so a fixed name
     * costs nothing and {@code --rm} takes it away again.
     * <p>
     * The name is inside {@code unwrap}'s own {@code qits-*} sweep on purpose — that phase excludes
     * the container it is running in rather than relying on a name outside the pattern, so a person
     * who starts this image by hand under any name is protected too.
     */
    public static final String CONTAINER_NAME = "qits-bootstrap-cli";

    private ContainerRun() {
    }

    /**
     * What the launcher runs.
     *
     * @param image         the content-tagged payload image
     * @param wrapper       the wrapper repository, resolved on the host — a path that may not exist
     *                      yet
     * @param wrapperOnHost whether that path is already there. A cold start's wrapper is cloned by
     *                      the run itself, inside the container, and an absent path is NOT mounted:
     *                      see {@link #mounts}
     * @param gitDirs       the real git directories a wrapper checked out as a linked worktree
     *                      points into — places OUTSIDE the wrapper that every git command in the
     *                      container reads through — and empty for an ordinary checkout, whose
     *                      {@code .git} the wrapper mount already covers
     * @param workDir       the directory the launcher was started in, which is where {@code .env}
     *                      is and what every relative {@code QITS_*} path resolves against
     * @param sources       {@code QITS_SRC}, resolved against {@code workDir} the way the payload
     *                      will resolve it
     * @param logFile       {@code QITS_LOG_FILE}, resolved the same way
     * @param user          {@code <uid>:<gid>} of whoever started the launcher
     * @param dockerGid     the docker socket's group
     * @param tty           whether the launcher has a terminal to hand on
     * @param args          this program's own arguments, relayed verbatim
     */
    public record Plan(String image, Path wrapper, boolean wrapperOnHost, List<Path> gitDirs,
                       Path workDir, Path sources, Path logFile, String user, String dockerGid,
                       boolean tty, BootstrapConfig config, Map<String, String> environment,
                       List<String> args) {
    }

    public static List<String> command(Plan plan) {
        List<String> argv = new ArrayList<>();
        argv.add("docker");
        argv.add("run");
        // The container is the run. Nothing is left behind to inspect afterwards that the log and
        // the browser view do not already hold.
        argv.add("--rm");
        argv.add("--name");
        argv.add(CONTAINER_NAME);

        // A terminal, only when there is one to hand on. `-it` against a pipe is docker's "the
        // input device is not a TTY" and a stopped run, and without it UiFactory finds no console
        // and draws plain lines — which is what a CI job wanted anyway. Detected, never assumed.
        //
        // TERM is left to docker, which sets `xterm`. JLine carries its own capability database,
        // so it needs no terminfo in the image; forwarding an exotic host TERM would only add a
        // way for the display to come out wrong.
        if (plan.tty()) {
            argv.add("-it");
        }

        argv.add("-v");
        argv.add(SOCKET + ":" + SOCKET);

        // AS THE PERSON WHO STARTED IT, not as root. Two things follow from it, and both are the
        // reason: the clones and the generated files under the wrapper stay the user's own rather
        // than arriving root-owned in their checkout, and git reads those checkouts without
        // "detected dubious ownership", which is what it says when the repository belongs to
        // somebody else. The socket's group is then not optional — a plain uid is in no groups
        // inside the container, and the socket is srw-rw---- root:docker.
        argv.add("--user");
        argv.add(plan.user());
        argv.add("--group-add");
        argv.add(plan.dockerGid());

        for (Path mount : mounts(plan)) {
            argv.add("-v");
            argv.add(mount + ":" + mount);
        }

        // The directory the launcher stood in, so `.env` is the same file, and so a relative
        // QITS_SRC or QITS_LOG_FILE resolves to the same place it would have on the host.
        argv.add("-w");
        argv.add(plan.workDir().toString());

        environment(plan).forEach((name, value) -> {
            argv.add("-e");
            argv.add(name + "=" + value);
        });
        for (String name : passThrough(plan)) {
            // By NAME: docker copies the value across, so a secret in the environment stays out of
            // this command line.
            argv.add("-e");
            argv.add(name);
        }

        publish(plan).ifPresent(publish -> {
            argv.add("-p");
            argv.add(publish);
        });

        // NOT --network qits-net. The network may not exist yet on a cold machine, and the run's
        // second phase both creates it and attaches this container to it. Naming it here would
        // fail the run before that phase could.
        argv.add(plan.image());
        argv.addAll(plan.args());
        return List.copyOf(argv);
    }

    /**
     * The host paths that must mean the same thing inside, smallest set that covers them all.
     * <p>
     * Bind-mounting host paths is right here and nowhere else in this program: the launcher runs on
     * the host, so its paths are the daemon's. The payload's own {@code docker build} contexts and
     * {@code docker cp} sources are read by the CLIENT and therefore resolve INSIDE the container —
     * which is exactly why the ones below have to be mounted rather than assumed.
     * <p>
     * <b>Every path here exists before the run starts, and that is a rule rather than a
     * coincidence.</b> Docker creates a missing bind source itself, as a ROOT-owned directory — and
     * this run is {@code --user <uid>:<gid>}, so what it would get is a mount it cannot write.
     */
    static List<Path> mounts(Plan plan) {
        List<Path> mounts = new ArrayList<>();
        // The checkouts the platform is built from, .qits-bootstrap.env — the generated secrets and
        // the postgres passwords, without which a re-bootstrap is locked out of its own platform —
        // and docker-compose.qits.yml, which the run WRITES here and unwrap reads back.
        //
        // ONLY WHEN IT IS ALREADY THERE. A cold start has no wrapper: the run clones it, in the
        // container, into the working directory — which is mounted just below, so the checkout
        // lands on the host and belongs to the user the run is. Mounting the absent path instead
        // would have docker create it as root, and the clone would fail on its own mount point.
        if (plan.wrapperOnHost()) {
            add(mounts, plan.wrapper());
        }
        // A linked worktree's real git directories. The wrapper's .git is then a pointer FILE, as
        // is every submodule's, and phase 4's clones read the sources through them — outside these
        // mounts they die with "not a git repository". Usually one directory after the dedup
        // below; a submodule with an embedded .git adds its own.
        for (Path gitDir : plan.gitDirs()) {
            add(mounts, gitDir);
        }
        // .env, and whatever a relative QITS_* path hangs off.
        add(mounts, plan.workDir());
        // The clones. A platform's worth of source: re-cloning it every run is not an option.
        add(mounts, plan.sources());
        // The run log, by its directory — the file itself may not exist yet.
        add(mounts, plan.logFile().getParent());
        mounts.sort(Comparator.comparing(Path::toString));
        return List.copyOf(mounts);
    }

    /** Keeps the set minimal: a path already inside another mount is not a second mount. */
    private static void add(List<Path> mounts, Path candidate) {
        if (candidate == null) {
            return;
        }
        Path path = candidate.toAbsolutePath().normalize();
        if (mounts.stream().anyMatch(path::startsWith)) {
            return;
        }
        mounts.removeIf(existing -> existing.startsWith(path));
        mounts.add(path);
    }

    /**
     * The values the launcher decides, as opposed to the ones it hands through. There are four, and
     * each is a boundary that moved when the phases moved into a container.
     */
    static Map<String, String> environment(Plan plan) {
        Map<String, String> explicit = new LinkedHashMap<>();
        // A writable home. The docker CLI and buildx both keep state under it, and a uid with no
        // /etc/passwd entry has none.
        explicit.put("HOME", "/tmp");
        // The host's clock, told to a container that has no /etc/localtime. Without it the log's
        // timestamps jump between the launcher's local time and the payload's UTC, and a wait line
        // says it gives up at a time that is not on the reader's clock. The JVM carries its own
        // zone database, so the name is enough.
        explicit.put("TZ", ZoneId.systemDefault().getId());
        // "You are the payload": run the phases instead of launching a container to run them in.
        explicit.put("QITS_IN_CONTAINER", "1");
        // WrapperDir walks up from the working directory when nothing says otherwise, and that walk
        // must not be what decides this inside a container. The launcher already resolved it on the
        // host; this is that answer, told rather than re-derived. On a cold start it is where the
        // wrapper WILL be — the run clones it there, and both halves have to mean the same path.
        explicit.put("QITS_WRAPPER_DIR", plan.wrapper().toString());
        // Only the payload binds the browser view. The launcher is the same binary and would
        // otherwise hold the port the publish below needs, so the server defaults to not binding
        // and this is QITS_WEB's answer, told to the half that may act on it.
        explicit.put("QITS_WEB_BIND", String.valueOf(plan.config().web()));
        if (plan.config().web()) {
            // QITS_WEB_HOST answers "who can reach the browser view", and inside a container that
            // boundary is the PUBLISH, not the bind — a view bound to the container's loopback is
            // reachable by nobody at all. So the knob's answer moves to the -p below and the server
            // binds every interface of a container that has one address.
            explicit.put("QITS_WEB_HOST", "0.0.0.0");
        }
        return explicit;
    }

    /**
     * Every {@code QITS_*} the launcher was given, by name. The host half reads no {@code QITS_*}
     * meaning of its own and re-interprets none of them: whatever configures a run on the host
     * configures the same run in the container, and the values that live in {@code .env} rather
     * than in the environment arrive by the working directory being the same directory.
     */
    static List<String> passThrough(Plan plan) {
        return plan.environment().keySet().stream()
                .filter(name -> name.startsWith("QITS_"))
                .filter(name -> !environment(plan).containsKey(name))
                .sorted()
                .toList();
    }

    /**
     * The browser view's port, published so it stays reachable from the machine the person is on.
     * {@code QITS_WEB=0} means "do not bind", and then there is nothing to publish either.
     */
    static Optional<String> publish(Plan plan) {
        BootstrapConfig config = plan.config();
        if (!config.web()) {
            return Optional.empty();
        }
        String host = config.webHost();
        // The host-side bind is where "keep it off the LAN" now applies. 0.0.0.0 is not an address
        // to bind the publish to, it is the absence of one.
        String bind = host == null || host.isBlank() || "0.0.0.0".equals(host) ? "" : host + ":";
        return Optional.of(bind + config.webPort() + ":" + config.webPort());
    }
}
