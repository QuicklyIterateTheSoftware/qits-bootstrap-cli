package eu.wohlben.qits.cli.bootstrap.proc;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * A runner that answers from a script and keeps every argv it was given.
 * <p>
 * <b>It is not a fake daemon, and nothing here asks it to behave like one.</b> What it tests is the
 * same thing the launcher's {@code docker run} argv is tested for: the exact command line, and the
 * decision taken on the answer. A flag dropped in either place is a bootstrap that gets further
 * than it should before it breaks.
 */
public class ScriptedRunner extends ProcessRunner {

    /** Every command, in order. */
    public final List<List<String>> argv = new ArrayList<>();

    /** The same commands whole, so a test can ask what would have been MASKED before it printed. */
    public final List<Cmd> cmds = new ArrayList<>();

    private final Function<List<String>, ProcessResult> answers;

    public ScriptedRunner(Function<List<String>, ProcessResult> answers) {
        super(null);
        this.answers = answers;
    }

    @Override
    public ProcessResult run(Cmd cmd, Consumer<String> sink) {
        argv.add(cmd.command());
        cmds.add(cmd);
        return answers.apply(cmd.command());
    }

    public static ProcessResult ok(String... output) {
        return new ProcessResult(0, List.of(output), List.of(output), false, false);
    }

    public static ProcessResult failed(String... output) {
        return new ProcessResult(1, List.of(output), List.of(output), false, false);
    }

    /** The commands, one string each, for readable assertions. */
    public List<String> lines() {
        return argv.stream().map(command -> String.join(" ", command)).toList();
    }
}
