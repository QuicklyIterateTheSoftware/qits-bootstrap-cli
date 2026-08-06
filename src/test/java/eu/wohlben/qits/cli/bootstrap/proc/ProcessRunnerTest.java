package eu.wohlben.qits.cli.bootstrap.proc;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ProcessRunnerTest {

    @TempDir
    Path temp;

    private ProcessRunner runner() {
        return new ProcessRunner(new RunLog(temp.resolve("run.log")));
    }

    @Test
    void streamsEveryLineAsItArrivesAndMergesStderr() {
        List<String> seen = new ArrayList<>();
        ProcessResult result = runner().run(
                Cmd.of("sh", "-c", "echo one; echo two >&2; echo three"), seen::add);

        assertThat(result.ok()).isTrue();
        // The first line the display gets is the command itself, so the run is readable.
        assertThat(seen.getFirst()).startsWith("$ sh -c");
        assertThat(seen).contains("one", "two", "three");
        assertThat(result.captured()).containsExactly("one", "two", "three");
    }

    @Test
    void reportsTheExitCode() {
        ProcessResult result = runner().run(Cmd.of("sh", "-c", "echo nope >&2; exit 3"), null);

        assertThat(result.ok()).isFalse();
        assertThat(result.exitCode()).isEqualTo(3);
        assertThat(result.tailText(5)).contains("nope");
    }

    @Test
    void writesStdinWithoutDeadlockingOnLargeInput() {
        String dockerfile = "FROM quay.io/example\n".repeat(20_000);
        ProcessResult result = runner().run(Cmd.of("sh", "-c", "wc -l").stdin(dockerfile), null);

        assertThat(result.ok()).isTrue();
        assertThat(result.trimmed()).contains("20000");
    }

    @Test
    void boundsWhatItKeepsWhateverTheCommandPrints() {
        ProcessResult result = runner().run(
                Cmd.of("sh", "-c", "i=0; while [ $i -lt 5000 ]; do echo line$i; i=$((i+1)); done")
                        .captureLimit(10), null);

        assertThat(result.captured()).hasSize(10);
        assertThat(result.truncated()).isTrue();
        // The tail is the end of the output, which is what an error message needs.
        assertThat(result.tail().getLast()).isEqualTo("line4999");
        assertThat(result.tail().size()).isLessThanOrEqualTo(300);
    }

    @Test
    void killsACommandThatOutlivesItsTimeout() {
        ProcessResult result = runner().run(
                Cmd.of("sh", "-c", "sleep 30").timeout(Duration.ofMillis(300)), null);

        assertThat(result.timedOut()).isTrue();
        assertThat(result.ok()).isFalse();
    }

    @Test
    void answersRatherThanThrowsWhenTheCommandDoesNotExist() {
        ProcessResult result = runner().run(Cmd.of("qits-no-such-binary"), null);

        assertThat(result.exitCode()).isEqualTo(127);
        assertThat(result.out()).contains("cannot start qits-no-such-binary");
    }

    @Test
    void hidesMaskedSecretsFromTheDisplayAndTheLog() throws Exception {
        List<String> seen = new ArrayList<>();
        runner().run(Cmd.of("sh", "-c", "echo token=s3cret").mask("s3cret"), seen::add);

        assertThat(seen).contains("token=***");
        assertThat(Files.readString(temp.resolve("run.log"))).doesNotContain("s3cret");
    }
}
