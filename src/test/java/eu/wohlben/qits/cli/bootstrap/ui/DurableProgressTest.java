package eu.wohlben.qits.cli.bootstrap.ui;

import eu.wohlben.qits.cli.bootstrap.config.TestConfig;
import eu.wohlben.qits.cli.bootstrap.engine.Phase;
import eu.wohlben.qits.cli.bootstrap.engine.PhaseOutcome;
import eu.wohlben.qits.cli.bootstrap.engine.PhaseState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DurableProgressTest {
    @TempDir Path directory;

    @Test
    void failedWorkerStateSurvivesAndAReplacementWorkerResumesIt() throws Exception {
        Path file = directory.resolve("progress.json");
        Phase build = new Phase("build", "build images", ctx -> { });
        JournalUi firstWorker = new JournalUi(TestConfig.from(Map.of()), file);
        firstWorker.started(List.of(build));
        firstWorker.phaseStarted(0, build);
        firstWorker.phaseFinished(new PhaseOutcome(0, build, PhaseState.FAILED,
                Duration.ofSeconds(4), "", new IllegalStateException("builder exited")));

        BootState supervisor = new BootState(200);
        supervisor.replaceSnapshot(Files.readString(file));
        assertThat(supervisor.snapshotJson()).contains("\"state\":\"FAILED\"")
                .contains("builder exited");

        JournalUi replacementWorker = new JournalUi(TestConfig.from(Map.of()), file);
        replacementWorker.started(List.of(build));
        replacementWorker.phaseStarted(0, build);
        replacementWorker.output("replacement worker is building");
        supervisor.replaceSnapshot(Files.readString(file));

        assertThat(supervisor.snapshotJson()).contains("\"state\":\"RUNNING\"")
                .contains("replacement worker is building")
                .doesNotContain("builder exited");
    }
}
