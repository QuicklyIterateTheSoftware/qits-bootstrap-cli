package eu.wohlben.qits.cli.bootstrap.ui;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The lower region's split. JLine's frame arithmetic breaks on a line that wraps, so the one thing
 * every case here asserts is that a row is exactly as wide as the terminal, whatever is in it.
 */
class TuiColumnsTest {

    @Test
    void aWideTerminalGivesTheEventsAThirdOfIt() {
        assertThat(TuiUi.eventColumn(120)).isEqualTo(40);
    }

    @Test
    void theEventColumnIsKeptBetweenItsBounds() {
        // A third of 100 is 33, which shows a clock and half a name.
        assertThat(TuiUi.eventColumn(100)).isEqualTo(TuiUi.MIN_EVENT_COLUMN);
        // A third of 300 would take a hundred columns from the build output for nothing.
        assertThat(TuiUi.eventColumn(300)).isEqualTo(TuiUi.MAX_EVENT_COLUMN);
    }

    /** Below the threshold there is no second column at all — half of eighty reads as neither. */
    @Test
    void aNarrowTerminalKeepsOneColumn() {
        assertThat(TuiUi.eventColumn(TuiUi.MIN_SPLIT_WIDTH - 1)).isZero();
        assertThat(TuiUi.eventColumn(80)).isZero();
        assertThat(TuiUi.row("[INFO] building", "14:22:07 SCMRelease qits-stt", 80, 0))
                .isEqualTo("[INFO] building");
    }

    @Test
    void theLeftIsPaddedToTheSplitSoTheDividerDoesNotMove() {
        String shortLine = TuiUi.row("a", "14:22:07 One", 120, 40);
        String longer = TuiUi.row("a much longer line of build output", "14:22:09 Two", 120, 40);

        assertThat(shortLine.indexOf('│')).isEqualTo(longer.indexOf('│')).isEqualTo(78);
        assertThat(shortLine).startsWith("a ").endsWith(TuiUi.GUTTER + "14:22:07 One");
    }

    /** Each side is cut to its own width: an overlong build line must not eat the event column. */
    @Test
    void eachColumnIsCutToItsOwnWidth() {
        String row = TuiUi.row("x".repeat(400), "y".repeat(400), 120, 40);

        assertThat(row).hasSize(120);
        assertThat(row.substring(0, 77)).isEqualTo("x".repeat(76) + "…");
        assertThat(row.substring(80)).isEqualTo("y".repeat(39) + "…");
    }

    @Test
    void aRowWithNothingOnTheRightIsStillTheLeftColumnsWidth() {
        String row = TuiUi.row("[INFO] building", "", 120, 40);

        assertThat(row).isEqualTo("[INFO] building" + " ".repeat(77 - 15) + TuiUi.GUTTER);
    }

    @Test
    void theDividerCarriesTheColumnsOnlyLabel() {
        String divider = TuiUi.divider(120, 40);

        assertThat(divider).hasSize(120);
        assertThat(divider).contains("┬─ platform events ");
        assertThat(divider.indexOf('┬')).isEqualTo(TuiUi.row("", "", 120, 40).indexOf('│'));
    }

    @Test
    void aNarrowTerminalsDividerIsAPlainRule() {
        assertThat(TuiUi.divider(80, 0)).isEqualTo("─".repeat(80));
    }
}
