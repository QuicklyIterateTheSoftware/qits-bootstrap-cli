package eu.wohlben.qits.cli.bootstrap.config;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The names the edge's certificate carries beyond the wildcards it derives.
 * <p>
 * The whole reason this knob exists is that a wildcard covers ONE label: {@code *.<domain>} answers
 * for {@code editor.<domain>} and for nothing under it, so {@code editor.<project>.<domain>} — the
 * web editor's origin, one per project — is reachable by no wildcard this platform orders.
 */
class ExtraSansTest {

    private static final String DOMAIN = "qits-dev.eu";

    private static BootstrapConfig config(String value) {
        return TestConfig.from(Map.of("QITS_ACME_EXTRA_SANS", value));
    }

    /** No knob is the ordinary platform, and the derived wildcards are the whole set. */
    @Test
    void nothingConfiguredIsNoExtraNames() {
        assertThat(ExtraSans.of(TestConfig.from(Map.of()), Optional.of(DOMAIN))).isEmpty();
        assertThat(ExtraSans.of(config("   "), Optional.of(DOMAIN))).isEmpty();
    }

    /**
     * With no domain there is no certificate at all, so a value left in {@code .env} from a
     * platform that had one is not a set of names to order — it is nothing.
     */
    @Test
    void withNoDomainThereIsNothingToPutANameOn() {
        assertThat(ExtraSans.of(config("editor.acme"), Optional.empty())).isEmpty();
    }

    /**
     * <b>A name may be written relative to the domain</b>, which is what a person adding one per
     * project writes. Both spellings are the same name, so they collapse rather than ordering the
     * name twice.
     */
    @Test
    void aRelativeNameAndTheWholeOneAreTheSameName() {
        assertThat(ExtraSans.of("editor.acme", DOMAIN))
                .containsExactly("editor.acme." + DOMAIN);
        assertThat(ExtraSans.of("editor.acme." + DOMAIN, DOMAIN))
                .containsExactly("editor.acme." + DOMAIN);
        assertThat(ExtraSans.of("editor.acme, editor.acme." + DOMAIN, DOMAIN))
                .containsExactly("editor.acme." + DOMAIN);
    }

    /**
     * Commas, whitespace or both: the value is written into a {@code .env} line and onto a command
     * line, and the two habits differ. Order is kept, because the order is what a reader wrote.
     */
    @Test
    void namesAreSeparatedByCommasOrSpaceAndKeepTheirOrder() {
        assertThat(ExtraSans.of("editor.acme,editor.gizmo  editor.qits", DOMAIN))
                .containsExactly("editor.acme." + DOMAIN, "editor.gizmo." + DOMAIN,
                        "editor.qits." + DOMAIN);
    }

    /** Case and a trailing root dot are two spellings of one name, not two names. */
    @Test
    void caseAndATrailingDotAreNotPartOfTheName() {
        assertThat(ExtraSans.of(" Editor.Acme. ", DOMAIN))
                .containsExactly("editor.acme." + DOMAIN);
    }

    /**
     * <b>Every name ends up inside the domain.</b> The edge answers its challenges in this domain's
     * own zone, so a name outside it cannot be validated — and one such name fails the WHOLE order,
     * taking the names that would have worked with it.
     * <p>
     * The mistake this cannot see is stated where it is made: a name written whole for another
     * domain reads as a relative one, and the closing report is what puts the resolved name on the
     * screen.
     */
    @Test
    void everyNameEndsUpInsideTheDomain() {
        assertThat(ExtraSans.of("editor.acme.example.com", DOMAIN))
                .containsExactly("editor.acme.example.com." + DOMAIN);
    }

    /** The apex is refused: the edge already orders it, and a second copy is a name to keep in step. */
    @Test
    void theApexIsRefusedAndTheMessageNamesTheKnob() {
        assertThatThrownBy(() -> ExtraSans.of(DOMAIN, DOMAIN))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("QITS_ACME_EXTRA_SANS")
                .hasMessageContaining("--acme-extra-san")
                .hasMessageContaining("apex");
    }

    /**
     * A wildcard is refused too. The edge already orders the apex, {@code *.<domain>} and
     * {@code *.<env>.<domain>}, so one written by hand either repeats a name it has or asks for a
     * depth its Host reading does not serve.
     */
    @Test
    void aWildcardIsNotAnExtraName() {
        assertThatThrownBy(() -> ExtraSans.of("*.acme." + DOMAIN, DOMAIN))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not a DNS label");
    }

    @Test
    void aLabelThatIsNotALabelIsRefused() {
        assertThatThrownBy(() -> ExtraSans.of("editor..acme", DOMAIN))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ExtraSans.of("editor.-acme", DOMAIN))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ExtraSans.of("editor.ac_me", DOMAIN))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** The one spelling of an editor origin, so the report and the knob cannot disagree. */
    @Test
    void oneSpellingOfAnEditorOrigin() {
        assertThat(ExtraSans.editorHost("acme", DOMAIN)).isEqualTo("editor.acme." + DOMAIN);
        assertThat(ExtraSans.of("editor.acme", DOMAIN))
                .containsExactly(ExtraSans.editorHost("acme", DOMAIN));
    }

    /** Answerable for one run, and a blank answer leaves {@code .env} alone. */
    @Test
    void theCommandLineAnswersItToo() {
        BootstrapConfig base = config("editor.from-env");

        assertThat(ExtraSans.of(new OverridableConfig(base).acmeExtraSans("editor.a,editor.b"),
                Optional.of(DOMAIN)))
                .containsExactly("editor.a." + DOMAIN, "editor.b." + DOMAIN);
        assertThat(ExtraSans.of(new OverridableConfig(base).acmeExtraSans("  "),
                Optional.of(DOMAIN)))
                .containsExactly("editor.from-env." + DOMAIN);
    }
}
