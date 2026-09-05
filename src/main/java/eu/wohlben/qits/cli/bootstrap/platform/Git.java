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

    /**
     * Is this checkout standing on no branch? A restoring boot leaves every source tree detached at
     * its release tag, and {@code git pull} on a detached HEAD fails — so the refresh asks this
     * before it pulls, rather than reading the failure as an untrustworthy source and stopping the
     * boot.
     */
    public boolean isDetached(Path repo) {
        return !in(repo, null, "symbolic-ref", "-q", "HEAD").ok();
    }

    /**
     * Put the checkout back on a branch. {@code -f} because this tree is the bootstrap's own — seed
     * builds write a placeholder client into it and the pipeline overlay commits to it — so a
     * modified file here is this program's leftover and never a person's work.
     */
    public ProcessResult checkoutBranch(Path repo, String branch, Consumer<String> out) {
        return in(repo, out, "checkout", "-f", branch);
    }

    /**
     * Stand the checkout at one commit, on no branch. THE BOOT'S IDENTITY is applied with this: the
     * seed images, the seed publishes and the deployed successors are all built from the tree it
     * leaves, so a repository's release tag becomes the one version this boot means by it.
     */
    public ProcessResult checkoutDetached(Path repo, String ref, Consumer<String> out) {
        return in(repo, out, "checkout", "-f", "--detach", ref);
    }

    /**
     * <b>Stand every nested checkout exactly at its gitlink, whatever {@code .gitmodules} asks for
     * and whatever was there before.</b> Every service repository declares {@code update = merge}
     * for its {@code service/src/main/webui} frontend, which is right for a person following a
     * branch and wrong for a seed build: on a rerun whose checkout moved to another release tag,
     * git tried to MERGE the new gitlink into the previous shallow clone and died on
     * {@code refusing to merge unrelated histories} — two shallow trees have none in common —
     * taking {@code seed-image-githost} with it at exit 128, measured on 2026-09-05.
     * <p>
     * So {@code --checkout} overrides the declared strategy and {@code --force} discards whatever
     * the nested tree holds. Nothing of this run's is lost by either: the seed writes its SPA
     * placeholder into {@code dist/} AFTER this, and that path is untracked, so a forced checkout
     * leaves it alone — measured too. {@code --depth 1} still reaches a gitlink that is no longer a
     * branch tip: git asks the remote for the sha itself, which GitHub answers for any reachable
     * commit.
     */
    public ProcessResult submodulesShallow(Path repo, Consumer<String> out) {
        return runner.run(Cmd.of(submodulesShallowCommand(repo))
                .timeout(Duration.ofMinutes(30)), out);
    }

    /** The command, in one place, so the flags that make a rerun survivable are provable. */
    static List<String> submodulesShallowCommand(Path repo) {
        return List.of("git", "-C", repo.toString(), "submodule", "update",
                "--init", "--checkout", "--force", "--depth", "1");
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
     * The tags reachable from a ref, NEWEST VERSION FIRST — git's own version sort, which is the
     * order qits-ci collapses a multi-tag push by, so both sides of the platform mean the same
     * thing by "the newest tag".
     * <p>
     * Not {@link #describeTag}, which answers the nearest tag in HISTORY: a release cut on an older
     * commit and tagged later is nearer while being older, and the deploy ref must follow the
     * newest RELEASE rather than the newest ancestor. {@code --merged} keeps it to this history —
     * a tag on a side branch is not a release of main.
     * <p>
     * The list is bounded by the capture limit, which costs nothing: the caller wants the first
     * entry and the sort has already put it there.
     */
    public List<String> tagsNewestFirst(Path repo, String ref) {
        ProcessResult result = in(repo, null, "tag", "--list", "--sort=-v:refname", "--merged", ref);
        return result.ok()
                ? result.captured().stream().map(String::strip).filter(line -> !line.isBlank())
                        .toList()
                : List.of();
    }

    /**
     * <b>One file out of one ref, without touching the working tree</b> — null when the ref or the
     * file is not there, which is the honest answer for a tag the clone never fetched and for a
     * repository that carries no pom at all.
     * <p>
     * This is how the pin closure is read ({@link PinnedVersions}): a restoring boot stands every
     * checkout at its release tag, so {@code HEAD:pom.xml} is that tag's pom, and each pinned
     * version is read at its own tag beside it. The capture limit is raised because a root pom is
     * hundreds of lines and a truncated one would parse into a WRONG answer rather than a failure.
     */
    public String fileAt(Path repo, String ref, String path) {
        ProcessResult result = runner.run(Cmd.of(List.of("git", "-C", repo.toString(), "show",
                ref + ":" + path)).captureLimit(20_000), null);
        return result.ok() && !result.truncated() ? result.out() : null;
    }

    /**
     * <b>The names directly inside one directory of a ref</b>, empty when the directory is not
     * there. Two small listings beat one recursive one: a repository's whole tree overruns the
     * capture limit, and what the pin closure wants is the Dockerfiles at the root and under
     * {@code docker/} and nothing else.
     *
     * @param directory the directory inside the ref, or empty for the root
     */
    public List<String> namesAt(Path repo, String ref, String directory) {
        String target = directory == null || directory.isBlank() ? ref : ref + ":" + directory;
        ProcessResult result = in(repo, null, "ls-tree", "--name-only", target);
        return result.ok()
                ? result.captured().stream().map(String::strip).filter(line -> !line.isBlank())
                        .toList()
                : List.of();
    }

    /**
     * A second working tree of one ref, for copying somewhere the checkout itself cannot go. The
     * seed publishes several versions of a library per boot and each needs its own tree in the
     * maven container; the checkout holds one.
     * <p>
     * A worktree rather than {@code git archive} because it needs git alone — no tar on the host,
     * no binary stream through a process pipe. The {@code .git} it leaves is a text file pointing
     * at a path the container does not have, and nothing these builds run reads it: none of the
     * seed libraries carries a git-commit-id or buildnumber plugin.
     */
    public ProcessResult worktreeAdd(Path repo, String ref, Path into, Consumer<String> out) {
        return in(repo, out, "worktree", "add", "--detach", "--force", into.toString(), ref);
    }

    /** And its removal. Best effort: a leftover tree costs disk, not correctness. */
    public ProcessResult worktreeRemove(Path repo, Path tree, Consumer<String> out) {
        return in(repo, out, "worktree", "remove", "--force", tree.toString());
    }

    /**
     * Forget worktrees whose directory is gone. A run killed between the export and the copy leaves
     * git holding a registration for a path nothing occupies, and {@code worktree add} then refuses
     * the same path on the next run.
     */
    public ProcessResult worktreePrune(Path repo, Consumer<String> out) {
        return in(repo, out, "worktree", "prune");
    }

    /**
     * The COMMIT a ref names, peeled. An annotated tag's ref names a tag object, and a branch has
     * to point at a commit — {@code rev-list -n 1} answers the commit for either shape.
     */
    public String commitOf(Path repo, String ref) {
        ProcessResult result = in(repo, null, "rev-list", "-n", "1", ref);
        return result.ok() ? result.trimmed() : "";
    }

    /**
     * A push to the platform git host.
     *
     * @param options push options; {@code qits.no-ci} keeps a push quiet, {@code qits.token} is
     *                the bootstrap's standing exception to "release is the only door into main"
     */
    public ProcessResult push(Path repo, String url, List<String> options, String refspec,
                              String pushToken, String bearer, Consumer<String> out) {
        if (bearer == null || bearer.isBlank() || bearer.indexOf('\r') >= 0 || bearer.indexOf('\n') >= 0) {
            throw new IllegalArgumentException("A qits-githost bearer is required");
        }
        List<String> command = new ArrayList<>(List.of("git", "-C", repo.toString(), "-c",
                "http.extraHeader=Authorization: Bearer " + bearer, "push"));
        for (String option : options) {
            command.add("-o");
            command.add(option);
        }
        command.add(url);
        command.add(refspec);
        return runner.run(Cmd.of(command).timeout(Duration.ofMinutes(60)).mask(pushToken).mask(bearer), out);
    }

    // THIS PROGRAM WRITES NO COMMITS ANY MORE, and `add` plus `commitAsBootstrap` went with the one
    // that did. The deploy phase used to overlay a ci-post-receive.yml into a repository that
    // carried no pipeline config and commit it, so that the ref it was about to push would build
    // something. Nothing is deployed by a push now — a release is, and its recipe is the
    // repository's own — so an overlaid pipeline would build a commit nobody deploys, in a commit
    // this program invented on somebody's checkout. A deployable with no release recipe is a fact
    // the deploy phase reports; it is not one to paper over from here.

    public boolean available() {
        return runner.run(Cmd.of("git", "--version"), null).ok();
    }

    private ProcessResult in(Path repo, Consumer<String> out, String... args) {
        List<String> command = new ArrayList<>(List.of("git", "-C", repo.toString()));
        command.addAll(List.of(args));
        return runner.run(Cmd.of(command), out);
    }
}
