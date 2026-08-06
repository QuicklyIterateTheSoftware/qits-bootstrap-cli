package eu.wohlben.qits.cli.bootstrap.proc;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AnsiTest {

    private static final String ESC = String.valueOf((char) 27);

    @Test
    void stripsColours() {
        assertThat(Ansi.clean(ESC + "[1;31mFATAL" + ESC + "[0m: no daemon"))
                .isEqualTo("FATAL: no daemon");
    }

    @Test
    void keepsOnlyWhatALineWasRedrawnAs() {
        assertThat(Ansi.clean("Downloading 40%\rDownloading 100%")).isEqualTo("Downloading 100%");
    }

    @Test
    void stripsCursorMoves() {
        assertThat(Ansi.clean(ESC + "[2K" + ESC + "[1Abuilding")).isEqualTo("building");
    }

    @Test
    void leavesOrdinaryOutputAlone() {
        assertThat(Ansi.clean("[INFO] Building qits-cd 2026.802.191319"))
                .isEqualTo("[INFO] Building qits-cd 2026.802.191319");
    }
}
