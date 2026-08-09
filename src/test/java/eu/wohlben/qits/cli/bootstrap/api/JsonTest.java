package eu.wohlben.qits.cli.bootstrap.api;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The small bodies this program sends. Worth a test for one reason: the environment create carries
 * a JSON <b>boolean</b>, and everything else this builder writes is a quoted string.
 */
class JsonTest {

    @Test
    void everyOrdinaryValueIsQuoted() {
        assertThat(Json.object("name", "prod", "branch", "environment/prod"))
                .isEqualTo("{\"name\":\"prod\",\"branch\":\"environment/prod\"}");
    }

    /**
     * {@code platform} must reach the deployer as {@code true}, not {@code "true"}. Whether Jackson
     * would coerce the string is not the point — the payload is a contract with another repository,
     * and one that only works because the far side is lenient breaks on the day it stops being.
     */
    @Test
    void aVerbatimValueIsWrittenAsItself() {
        assertThat(Json.object("name", "prod", "platform", Json.verbatim("true")))
                .isEqualTo("{\"name\":\"prod\",\"platform\":true}");
    }

    /** A value that merely looks like a boolean is still a string — the reason for the marker. */
    @Test
    void aStringThatLooksLikeABooleanStaysAString() {
        assertThat(Json.object("name", "true")).isEqualTo("{\"name\":\"true\"}");
    }

    @Test
    void quotingEscapesWhatWouldBreakTheDocument() {
        assertThat(Json.object("detail", "a \"quoted\" line\nand a \\ backslash"))
                .isEqualTo("{\"detail\":\"a \\\"quoted\\\" line\\nand a \\\\ backslash\"}");
    }
}
