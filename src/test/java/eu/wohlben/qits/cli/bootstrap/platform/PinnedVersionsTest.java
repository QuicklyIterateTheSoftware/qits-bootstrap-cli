package eu.wohlben.qits.cli.bootstrap.platform;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The pin closure, over poms written here rather than over a platform. What it decides is how many
 * versions of each library the seed publishes, and getting it wrong is a boot that dies minutes
 * into the maven phase naming a version nothing ever published — which is what happened on
 * 2026-09-05.
 */
class PinnedVersionsTest {

    /** A checkout, as (name, ref, path) to pom text. */
    private final Map<String, String> poms = new LinkedHashMap<>();

    private final PinnedVersions.Poms source = (name, ref, path) ->
            poms.get(name + "@" + ref + ":" + path);

    private void pom(String name, String ref, String text) {
        poms.put(name + "@" + ref + ":pom.xml", text);
    }

    private void module(String name, String ref, String module, String text) {
        poms.put(name + "@" + ref + ":" + module + "/pom.xml", text);
    }

    private static String library(String version, String... pins) {
        StringBuilder pom = new StringBuilder("<project><artifactId>lib</artifactId><version>")
                .append(version).append("</version><properties>");
        for (int i = 0; i < pins.length; i += 2) {
            pom.append("<qits.").append(pins[i]).append(".version>").append(pins[i + 1])
                    .append("</qits.").append(pins[i]).append(".version>");
        }
        return pom.append("</properties></project>").toString();
    }

    /**
     * <b>The defect, in four lines.</b> A consumer pins the eventstream version it was released
     * against, the eventstream checkout has moved on, and the store on a fresh platform holds only
     * what this boot publishes. The pinned version has to be published too.
     */
    @Test
    void aLaggingPinIsAVersionTheSeedHasToPublishAsWell() {
        pom("eventstream", PinnedVersions.HEAD, library("2026.905.25646"));
        pom("eventstream", "2026.904.82646", library("2026.904.82646"));
        pom("ci", PinnedVersions.HEAD, library("1.0.0", "eventstream", "2026.904.82646"));

        PinnedVersions pins = PinnedVersions.read(List.of("ci"), source);

        assertThat(pins.extraVersions("eventstream")).containsExactly("2026.904.82646");
        assertThat(pins.warnings()).isEmpty();
    }

    /** A pin that names what the checkout already is asks for nothing extra. */
    @Test
    void aPinThatIsUpToDateAddsNothing() {
        pom("eventstream", PinnedVersions.HEAD, library("2026.905.25646"));
        pom("ci", PinnedVersions.HEAD, library("1.0.0", "eventstream", "2026.905.25646"));

        assertThat(PinnedVersions.read(List.of("ci"), source).extraVersions("eventstream"))
                .isEmpty();
    }

    /**
     * <b>A pinned pom is a consumer too.</b> The eventstream release a consumer still names was
     * itself built against an older integrations release, so publishing it needs that one first.
     * The closure is what stops the seed one hop short.
     */
    @Test
    void thePinsOfAPinnedVersionAreFollowedThrough() {
        pom("eventstream", PinnedVersions.HEAD, library("2026.905.25646"));
        pom("eventstream", "2026.904.82646",
                library("2026.904.82646", "db-core", "2026.902.53026"));
        pom("integrations-quarkus", PinnedVersions.HEAD, library("2026.904.82648"));
        pom("integrations-quarkus", "2026.902.53026", library("2026.902.53026"));
        pom("ci", PinnedVersions.HEAD, library("1.0.0", "eventstream", "2026.904.82646"));

        PinnedVersions pins = PinnedVersions.read(List.of("ci"), source);

        assertThat(pins.extraVersions("eventstream")).containsExactly("2026.904.82646");
        // db-core is one of six jars this one repository publishes, so the pin resolves to it.
        assertThat(pins.extraVersions("integrations-quarkus")).containsExactly("2026.902.53026");
    }

    /** Oldest first, which is the order they are built in — and CalVer's last field is a time. */
    @Test
    void versionsComeBackOldestFirstAndAreComparedAsNumbers() {
        pom("eventstream", PinnedVersions.HEAD, library("2026.905.25646"));
        for (String version : List.of("2026.904.210416", "2026.904.82646", "2026.825.74539")) {
            pom("eventstream", version, library(version));
        }
        pom("a", PinnedVersions.HEAD, library("1.0.0", "eventstream", "2026.904.210416"));
        pom("b", PinnedVersions.HEAD, library("1.0.0", "eventstream", "2026.904.82646"));
        pom("c", PinnedVersions.HEAD, library("1.0.0", "eventstream", "2026.825.74539"));

        assertThat(PinnedVersions.read(List.of("a", "b", "c"), source).extraVersions("eventstream"))
                .containsExactly("2026.825.74539", "2026.904.82646", "2026.904.210416");
    }

    /**
     * <b>A tag no checkout has is warned about and dropped.</b> It is a coordinate nothing on this
     * machine can build, so stopping the boot over it would buy the platform nothing — and the
     * warning is what says the pin is still out there.
     */
    @Test
    void aPinWhoseTagIsNotInTheCheckoutWarnsInsteadOfStoppingTheBoot() {
        pom("eventstream", PinnedVersions.HEAD, library("2026.905.25646"));
        pom("ci", PinnedVersions.HEAD, library("1.0.0", "eventstream", "2026.101.1"));

        PinnedVersions pins = PinnedVersions.read(List.of("ci"), source);

        assertThat(pins.extraVersions("eventstream")).isEmpty();
        assertThat(pins.warnings()).singleElement().asString()
                .contains("qits-eventstream-javalib").contains("2026.101.1");
    }

    /**
     * <b>The over-approximation the module filter removes.</b> qits-githost's root pom pinned a
     * qits-blobstore version whose tag lives in a repository that has been retired, and the seed
     * builds one module of that tag — {@code githost-events}, which depends on qits-eventstream
     * and nothing else. Reading the root pins alone would chase a version no checkout has and warn
     * about it on every boot.
     */
    @Test
    void onlyThePinsTheBuiltModulesUseCountForARepositoryPublishedByModule() {
        pom("eventstream", PinnedVersions.HEAD, library("2026.905.25646"));
        pom("eventstream", "2026.817.202549", library("2026.817.202549"));
        pom("registries", PinnedVersions.HEAD, library("2026.905.90028"));
        pom("githost", PinnedVersions.HEAD, library("2026.905.1"));
        pom("githost", "2026.820.65553", """
                <project><artifactId>qits-githost</artifactId><version>2026.820.65553</version>
                <properties>
                  <qits.blobstore.version>2026.814.71936</qits.blobstore.version>
                  <qits.eventstream.version>2026.817.202549</qits.eventstream.version>
                </properties>
                <dependencyManagement><dependencies>
                  <dependency><groupId>${project.groupId}</groupId>
                    <artifactId>qits-blobstore</artifactId>
                    <version>${qits.blobstore.version}</version></dependency>
                  <dependency><groupId>${project.groupId}</groupId>
                    <artifactId>qits-eventstream</artifactId>
                    <version>${qits.eventstream.version}</version></dependency>
                </dependencies></dependencyManagement></project>
                """);
        module("githost", "2026.820.65553", "githost-events", """
                <project><artifactId>qits-githost-events</artifactId><dependencies>
                  <dependency><groupId>${project.groupId}</groupId>
                    <artifactId>qits-eventstream</artifactId></dependency>
                </dependencies></project>
                """);
        pom("ci", PinnedVersions.HEAD, library("1.0.0", "githost-events", "2026.820.65553"));

        PinnedVersions pins = PinnedVersions.read(List.of("ci"), source);

        assertThat(pins.extraVersions("githost")).containsExactly("2026.820.65553");
        assertThat(pins.extraVersions("eventstream")).containsExactly("2026.817.202549");
        // The blob store version that tag names is in no repository any more, and the module the
        // seed builds does not depend on it. Nothing chases it and nothing warns.
        assertThat(pins.extraVersions("registries")).isEmpty();
        assertThat(pins.warnings()).isEmpty();
    }

    /** Every property some pom in the estate spells resolves to a repository this boot clones. */
    @Test
    void everyPinPropertyNamesARepositoryTheBootHas() {
        assertThat(PinnedVersions.PRODUCERS.values())
                .allSatisfy(producer -> assertThat(PlatformModel.platformRepos())
                        .contains(producer));
        // The six jars of one reactor, and the two service repositories that publish a library
        // module each.
        assertThat(PinnedVersions.PRODUCERS).containsEntry("db-core", "integrations-quarkus")
                .containsEntry("auth-core", "integrations-quarkus")
                .containsEntry("blobstore", "registries")
                .containsEntry("githost-events", "githost")
                .containsEntry("containers-client", "containers");
    }

    /** A property nothing publishes is said once rather than chased. */
    @Test
    void aPropertyNoRepositoryPublishesIsWarnedAboutOnce() {
        pom("ci", PinnedVersions.HEAD, library("1.0.0", "not-a-library", "1.2.3"));
        pom("projects", PinnedVersions.HEAD, library("1.0.0", "not-a-library", "1.2.3"));

        assertThat(PinnedVersions.read(List.of("ci", "projects"), source).warnings())
                .singleElement().asString().contains("qits-not-a-library");
    }

    @Test
    void aPropertyWhoseValueIsItselfAPlaceholderIsNotAPin() {
        assertThat(PinnedVersions.pinsIn(
                "<qits.eventstream.version>${eventstream.version}</qits.eventstream.version>"))
                .isEmpty();
    }

    @Test
    void theRootVersionIsReadPastTheParentBlock() {
        assertThat(PinnedVersions.versionIn(
                "<project><parent><version>3.2.1</version></parent>"
                        + "<version>2026.905.1</version></project>"))
                .isEqualTo("2026.905.1");
    }
}
