package eu.wohlben.qits.cli.bootstrap.platform;

import eu.wohlben.qits.cli.bootstrap.proc.Cmd;
import eu.wohlben.qits.cli.bootstrap.proc.ProcessResult;
import eu.wohlben.qits.cli.bootstrap.proc.ProcessRunner;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** The host's git. Same binary the user runs, same checkouts, same credentials. */
public class Git {

    private final ProcessRunner runner;

    public Git(ProcessRunner runner) {
        this.runner = runner;
    }

    public boolean isCheckout(Path dir) {
        return Files.exists(dir.resolve(".git"));
    }

    public ProcessResult clone(String from, Path to, Consumer<String> out) {
        return runner.run(Cmd.of("git", "clone", "--branch", "main", "--single-branch", from,
                to.toString()).timeout(Duration.ofMinutes(30)), out);
    }

    /**
     * {@code --tags} is load-bearing, not a nicety. The release replay reads the version to replay
     * off the newest tag reachable from main, and a plain pull moves the BRANCH without the tags —
     * measured: a refresh that carried the release commit but not its tag made the replay
     * faithfully re-release the previous version, republishing an old image and driving a
     * consumer's pin backwards through the follow-bumps.
     */
    public ProcessResult pullFastForward(Path repo, Consumer<String> out) {
        return in(repo, out, "pull", "--ff-only", "--tags");
    }

    public ProcessResult submodulesShallow(Path repo, Consumer<String> out) {
        return runner.run(Cmd.of(List.of("git", "-C", repo.toString(), "submodule", "update",
                "--init", "--depth", "1")).timeout(Duration.ofMinutes(30)), out);
    }

    public String head(Path repo) {
        return in(repo, null, "rev-parse", "HEAD").trimmed();
    }

    public String shortHead(Path repo) {
        return in(repo, null, "rev-parse", "--short", "HEAD").trimmed();
    }

    public String shortRef(Path repo, String ref) {
        return in(repo, null, "rev-parse", "--short", ref).trimmed();
    }


    /** The newest release tag reachable from a ref, or empty when the repo has never released. */
    public String describeTag(Path repo, String ref) {
        ProcessResult result = in(repo, null, "describe", "--tags", "--abbrev=0", ref);
        return result.ok() ? result.trimmed() : "";
    }

    /**
     * A push to the platform git host.
     *
     * @param options push options; {@code qits.no-ci} keeps a push quiet, {@code qits.token} is
     *                the bootstrap's standing exception to "release is the only door into main"
     */
    public ProcessResult push(Path repo, String url, List<String> options, String refspec,
                              String maskedToken, Consumer<String> out) {
        List<String> command = new ArrayList<>(List.of("git", "-C", repo.toString(), "push"));
        for (String option : options) {
            command.add("-o");
            command.add(option);
        }
        command.add(url);
        command.add(refspec);
        return runner.run(Cmd.of(command).timeout(Duration.ofMinutes(60)).mask(maskedToken), out);
    }

    public ProcessResult add(Path repo, String path, Consumer<String> out) {
        return in(repo, out, "add", path);
    }

    /** A commit made by the bootstrap itself, identified as such. */
    public ProcessResult commitAsBootstrap(Path repo, String message, Consumer<String> out) {
        return runner.run(Cmd.of(List.of("git", "-C", repo.toString(),
                "-c", "user.name=qits-bootstrap", "-c", "user.email=bootstrap@qits.invalid",
                "commit", "-q", "-m", message)), out);
    }

    public boolean available() {
        return runner.run(Cmd.of("git", "--version"), null).ok();
    }

    private ProcessResult in(Path repo, Consumer<String> out, String... args) {
        List<String> command = new ArrayList<>(List.of("git", "-C", repo.toString()));
        command.addAll(List.of(args));
        return runner.run(Cmd.of(command), out);
    }
}
