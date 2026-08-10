package eu.wohlben.qits.cli.bootstrap.phases;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Turning timestamped {@code docker logs} reads into the deployer's fresh lines about one
 * repository. The since-boundary is inclusive on docker's side, so the stamp comparison here is
 * what keeps a line from being shown twice.
 */
class DeployLogStreamTest {

    private static final Instant SINCE = Instant.parse("2026-08-10T05:34:00Z");

    @Test
    void relaysOnlyLinesAboutTheRepositoryStampedAfterSince() {
        List<String> read = List.of(
                "2026-08-10T05:33:59.000000000Z old: Deployed qits-observability early",
                "2026-08-10T05:34:02.058000000Z Registered qits-observability in environment prod",
                "2026-08-10T05:34:08.263000000Z Deployed qits-observability@dfdbfb4 into prod",
                "2026-08-10T05:34:09.000000000Z Failed to export TraceRequestMarshaler");
        assertThat(DeployLogStream.fresh(read, SINCE, "qits-observability")).containsExactly(
                "Registered qits-observability in environment prod",
                "Deployed qits-observability@dfdbfb4 into prod");
    }

    @Test
    void aLineAtTheBoundaryIsNotNew() {
        List<String> read = List.of("2026-08-10T05:34:00.000000000Z Registered qits-ci again");
        assertThat(DeployLogStream.fresh(read, SINCE, "qits-ci")).isEmpty();
    }

    @Test
    void aLineWithoutAStampIsLeftAlone() {
        List<String> read = List.of("no stamp here at all", "short");
        assertThat(DeployLogStream.fresh(read, SINCE, "qits-ci")).isEmpty();
        assertThat(DeployLogStream.lastStamp(read, SINCE)).isEqualTo(SINCE);
    }

    @Test
    void theBoundaryAdvancesPastNoiseSoItIsNeverReadTwice() {
        List<String> read = List.of(
                "2026-08-10T05:34:05.000000000Z Failed to export TraceRequestMarshaler",
                "2026-08-10T05:34:07.000000000Z Registered qits-ci in environment prod");
        assertThat(DeployLogStream.lastStamp(read, SINCE))
                .isEqualTo(Instant.parse("2026-08-10T05:34:07Z"));
    }

    @Test
    void stripsAnsiTheWayEveryOtherLineIsStripped() {
        String esc = String.valueOf((char) 27);
        List<String> read = List.of(
                "2026-08-10T05:34:05.000000000Z " + esc + "[31mFAILED" + esc + "[0m qits-ci deploy");
        assertThat(DeployLogStream.fresh(read, SINCE, "qits-ci"))
                .containsExactly("FAILED qits-ci deploy");
    }
}
