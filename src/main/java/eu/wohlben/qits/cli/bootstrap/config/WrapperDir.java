package eu.wohlben.qits.cli.bootstrap.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Where the wrapper repository is. This CLI lives at {@code cli/qits-cli-bootstrap} INSIDE the
 * wrapper, so in the ordinary case it can find the wrapper by looking up from wherever it was run.
 * <p>
 * The old default was {@code .}, which is right only when the working directory happens to BE the
 * wrapper root — from anywhere else it resolved to a directory with no submodules in it and the
 * preflight failed on a path nobody had chosen. An explicit {@code QITS_WRAPPER_DIR} still wins;
 * this is what happens when nobody set one.
 * <p>
 * On a machine with no checkout at all there is still an answer: {@link #resolveOrClone} names the
 * directory the wrapper will be CLONED into. That is the cold start, and it is supported rather
 * than refused.
 */
public final class WrapperDir {

    /**
     * The marker. A wrapper checkout has a {@code .gitmodules} that names the qits submodules, and
     * a worktree of it has the same file — which is why the file is the marker rather than
     * {@code .git}, a directory a worktree does not have.
     * <p>
     * The services directory is the second half of the same question, and it is what tells a
     * wrapper apart from any other repository that happens to use submodules.
     * <p>
     * The directory is named for the repository the registry and the git host live in, which is
     * qits-platform-artifacts since the 2026-08-08 rename. A wrapper checked out before it carries
     * the old {@code services/qits-artifacts} instead — and is still recognised, because the
     * {@code .gitmodules} test below only asks for {@code services/qits-} and every wrapper that
     * ever existed matches it.
     */
    private static final String MARKER_FILE = ".gitmodules";
    private static final String MARKER_DIR = "services/qits-platform-artifacts";

    /**
     * The wrapper's repository name, which is also the directory a cold start clones it into. It is
     * a constant here rather than in {@code PlatformModel} because the wrapper is not one of the
     * platform's repositories: nothing deploys it, and no phase but the cold clone names it.
     */
    public static final String REPO = "qits-qits";

    private WrapperDir() {
    }

    /** True when this directory is a wrapper checkout. */
    public static boolean isWrapper(Path candidate) {
        if (candidate == null || !Files.isDirectory(candidate)) {
            return false;
        }
        if (Files.isDirectory(candidate.resolve(MARKER_DIR))) {
            return true;
        }
        Path modules = candidate.resolve(MARKER_FILE);
        if (!Files.isRegularFile(modules)) {
            return false;
        }
        try {
            return Files.readString(modules, StandardCharsets.UTF_8).contains("services/qits-");
        } catch (IOException unreadable) {
            return false;
        }
    }

    /**
     * The first wrapper at or above this directory. First hit wins: a wrapper nested in another
     * one is still the one the caller is standing in.
     */
    public static Optional<Path> detect(Path from) {
        Path candidate = from == null ? null : from.toAbsolutePath().normalize();
        while (candidate != null) {
            if (isWrapper(candidate)) {
                return Optional.of(candidate);
            }
            candidate = candidate.getParent();
        }
        return Optional.empty();
    }

    /** Where the walk starts when nothing says otherwise. */
    public static Optional<Path> detect() {
        return detect(Path.of("").toAbsolutePath());
    }

    /** A wrapper directory and how it was arrived at, which is what preflight prints. */
    public record Resolved(Path path, String how) {
    }

    /**
     * The configured value if there is one, else the walk up from {@code from}. A configured value
     * is taken as given and not checked for the marker: someone who names a directory means it, and
     * preflight's own "no wrapper directory at …" is the better message when it is wrong.
     */
    public static Resolved resolve(Optional<String> configured, Path from) {
        Path start = from.toAbsolutePath().normalize();
        return found(configured, start)
                .orElseThrow(() -> new IllegalStateException(notFound(start)));
    }

    /**
     * The same answer, plus the one a COLD START needs: {@code <working directory>/qits-qits}, a
     * path that does not exist yet.
     * <p>
     * {@code curl … | bash} on a bare machine has no checkout to run from, and there is no platform
     * git host to clone one from either — the bootstrap is what creates that host. So "nothing
     * found" is an answer rather than a failure here: the boot's {@code wrapper} phase clones the
     * repository from the org into this path, and the operator is left holding a real checkout they
     * can rerun from. The working directory is deliberate — it is the directory the person typed
     * the command in, and the one place a container's clone reaches the host.
     * <p>
     * {@link #resolve} still refuses, and {@code unwrap} still uses it: a machine with no wrapper
     * has nothing to tear down, and nothing to clone one for.
     */
    public static Resolved resolveOrClone(Optional<String> configured, Path from) {
        Path start = from.toAbsolutePath().normalize();
        return found(configured, start)
                .orElseGet(() -> new Resolved(start.resolve(REPO), "not on this machine yet"));
    }

    /** The wrapper someone named, else the one the walk finds, else nothing. */
    private static Optional<Resolved> found(Optional<String> configured, Path start) {
        Optional<String> given = configured.map(String::trim).filter(value -> !value.isEmpty());
        if (given.isPresent()) {
            return Optional.of(
                    new Resolved(Path.of(given.get()).toAbsolutePath().normalize(), "configured"));
        }
        return detect(start).map(path -> new Resolved(path, "detected from " + start));
    }

    /** What to say when neither the configuration nor the walk found one. */
    public static String notFound(Path from) {
        return "no wrapper repository at or above " + from.toAbsolutePath().normalize()
                + " — run this from inside the qits-qits checkout, or set QITS_WRAPPER_DIR "
                + "(--wrapper-dir) to where it is";
    }
}
