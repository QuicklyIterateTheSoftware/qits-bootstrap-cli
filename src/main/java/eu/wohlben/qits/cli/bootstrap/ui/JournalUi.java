package eu.wohlben.qits.cli.bootstrap.ui;

import eu.wohlben.qits.cli.bootstrap.config.BootstrapConfig;
import eu.wohlben.qits.cli.bootstrap.engine.Phase;
import eu.wohlben.qits.cli.bootstrap.engine.PhaseOutcome;
import eu.wohlben.qits.cli.bootstrap.engine.RunResult;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

/** Writes the real browser state where the durable supervisor can read it. */
public final class JournalUi implements Ui {
    private final WebUi delegate;
    private final Path path;

    public JournalUi(BootstrapConfig config, Path path) {
        this.delegate = new WebUi(config);
        this.path = path;
        save();
    }

    @Override public void started(List<Phase> phases) { delegate.started(phases); save(); }
    @Override public void phaseStarted(int index, Phase phase) { delegate.phaseStarted(index, phase); save(); }
    @Override public void output(String line) { delegate.output(line); save(); }
    @Override public void status(String status) { delegate.status(status); save(); }
    @Override public void phaseFinished(PhaseOutcome outcome) { delegate.phaseFinished(outcome); save(); }
    @Override public void finished(RunResult result) { delegate.finished(result); save(); }
    @Override public void message(String line) { delegate.message(line); save(); }
    @Override public void event(String line) { delegate.event(line); save(); }
    @Override public void close() { save(); }

    private void save() {
        Path parent = path.toAbsolutePath().getParent();
        Path temporary = path.resolveSibling(path.getFileName() + ".new");
        try {
            Files.createDirectories(parent);
            Files.writeString(temporary, delegate.state().snapshotJson(), StandardCharsets.UTF_8);
            try {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new IllegalStateException("cannot publish bootstrap progress to " + path, e);
        }
    }
}
