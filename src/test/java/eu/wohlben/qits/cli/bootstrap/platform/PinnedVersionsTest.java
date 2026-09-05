package eu.wohlben.qits.cli.bootstrap.platform;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

/**
 * The pin closure, over poms written here rather than over a platform. What it decides is how many
 * versions of each library the seed publishes, and getting it wrong is a boot that dies minutes
 * into the maven phase naming a version nothing ever published — which is what happened on
 * 2026-09-05.
 */
class PinnedVersionsTest {

    /** A checkout, as (name, ref, path) to file text. */
    private final Map<String, String> files = new LinkedHashMap<>();

    /** The newest release tag of an image or npm producer, which is also its published version. */
    private final Map<String, String> releases = new LinkedHashMap<>();

    /** The commit a submodule path is recorded at, by (name, ref, path). */
    private final Map<String, String> gitlinks = new LinkedHashMap<>();

    private final PinnedVersions.Sources source = new PinnedVersions.Sources() {

        @Override
        public String at(String name, String ref, String path) {
            return files.get(name + "@" + ref + ":" + path);
        }

        @Override
        public List<String> dockerfiles(String name, String ref) {
            String prefix = name + "@" + ref + ":";
            return files.keySet().stream()
                    .filter(key -> key.startsWith(prefix))
                    .map(key -> key.substring(prefix.length()))
                    .filter(path -> path.endsWith("Dockerfile")
                            || path.contains("Dockerfile"))
                    .toList();
        }

        @Override
        public String gitlink(String name, String ref, String path) {
            return gitlinks.get(name + "@" + ref + ":" + path);
        }

        @Override
        public String releaseVersion(String name) {
            return releases.get(name);
        }
    };

    private void pom(String name, String ref, String text) {
        files.put(name + "@" + ref + ":pom.xml", text);
    }

    private void module(String name, String ref, String module, String text) {
        files.put(name + "@" + ref + ":" + module + "/pom.xml", text);
    }

    private void dockerfile(String name, String ref, String path, String text) {
        files.put(name + "@" + ref + ":" + path, text);
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

    // --- the images a Dockerfile pins -------------------------------------------------------------

    /**
     * <b>The three shapes this estate actually writes</b>, taken from the mains of 2026-09-05: an
     * ARG default with the registry host in front of it, the same without, and a {@code FROM} that
     * names an ARG above it. All three are the coordinate a build pulls, and a boot that publishes
     * only the newest tag leaves every one of them unresolvable.
     */
    @Test
    void anImagePinIsReadFromAnArgDefaultOrAFromLine() {
        assertThat(PinnedVersions.imagePinsIn("""
                ARG WORKSPACE_BASE=registry.dev.localhost:8080/qits/workspace-base:2026.904.223651
                FROM ${WORKSPACE_BASE}
                """))
                .containsExactly(entry("qits/workspace-base", "2026.904.223651"));
        // The same image without a registry host, which is how qits-projects-daemon spells it.
        assertThat(PinnedVersions.imagePinsIn("ARG BASE=qits/workspace-base:2026.904.223651\n"))
                .containsExactly(entry("qits/workspace-base", "2026.904.223651"));
        // Only the FROM names it, and the ARG above resolves it.
        assertThat(PinnedVersions.imagePinsIn("""
                ARG WORKSPACE_IMAGE=registry.dev.localhost:8080/qits/workspace:2026.904.223250
                FROM ${WORKSPACE_IMAGE}
                """))
                .containsExactly(entry("qits/workspace", "2026.904.223250"));
    }

    /**
     * <b>An ARG that resolves to something this platform never minted is not a pin.</b>
     * {@code qits/projects-daemon:${DAEMON_VERSION}} with {@code DAEMON_VERSION=latest} is a build
     * that follows the newest tag by design, and a replay of "latest" is not a thing.
     */
    @Test
    void onlyAVersionThisPlatformMintsCounts() {
        assertThat(PinnedVersions.imagePinsIn("""
                ARG DAEMON_VERSION=latest
                ARG DAEMON_IMAGE=qits/projects-daemon:${DAEMON_VERSION}
                FROM ${DAEMON_IMAGE} AS daemon
                FROM qits/workspace:latest
                FROM qits/workspace:native
                FROM qits/workspace-editor:local
                FROM qits/projects-daemon:<version>
                FROM ${NEVER_DECLARED}
                FROM mirror.dev.localhost:8080/quay/quarkus/ubi9-quarkus-mandrel-builder-image:jdk-25
                """))
                .isEmpty();
    }

    /** The image half of the closure follows the same recursion the jars do. */
    @Test
    void anImagePinnedByAnotherImagesTagIsFollowedThrough() {
        releases.put("oci-workspace", "2026.905.92439");
        releases.put("workspace-daemon", "2026.905.92112");
        // The daemon's main pins a base four releases back...
        dockerfile("workspace-daemon", PinnedVersions.HEAD, "docker/Dockerfile",
                "ARG WORKSPACE_BASE=registry.dev.localhost:8080/qits/workspace-base:2026.904.223651\n"
                        + "FROM ${WORKSPACE_BASE}\n");
        // ...and the daemon release something else pins names an older base still.
        dockerfile("workspace-daemon", "2026.904.223250", "docker/Dockerfile",
                "ARG WORKSPACE_BASE=qits/workspace-base:2026.902.143920\nFROM ${WORKSPACE_BASE}\n");
        dockerfile("oci-workspace", "2026.904.223651", "Dockerfile", "FROM debian:13\n");
        dockerfile("oci-workspace", "2026.902.143920", "Dockerfile", "FROM debian:13\n");
        // A repository that pins the older daemon — this is the hop that reaches the older base.
        dockerfile("editor", PinnedVersions.HEAD, "Dockerfile",
                "ARG WORKSPACE_IMAGE=registry.dev.localhost:8080/qits/workspace:2026.904.223250\n"
                        + "FROM ${WORKSPACE_IMAGE}\n");

        PinnedVersions pins = PinnedVersions.read(
                List.of("editor", "workspace-daemon", "oci-workspace"), source);

        assertThat(pins.extraVersions("workspace-daemon")).containsExactly("2026.904.223250");
        // Oldest first, which is the order they are replayed in.
        assertThat(pins.extraVersions("oci-workspace"))
                .containsExactly("2026.902.143920", "2026.904.223651");
        assertThat(pins.warnings()).isEmpty();
    }

    /** A pin naming the producer's own newest release adds nothing: the replay publishes it anyway. */
    @Test
    void anImagePinOnTheNewestReleaseIsNotAnExtra() {
        releases.put("oci-workspace", "2026.905.92439");
        dockerfile("workspace-daemon", PinnedVersions.HEAD, "docker/Dockerfile",
                "FROM qits/workspace-base:2026.905.92439\n");

        assertThat(PinnedVersions.read(List.of("workspace-daemon"), source)
                .extraVersions("oci-workspace")).isEmpty();
    }

    /** Every qits image the estate pins resolves to the publisher whose run pushes it. */
    @Test
    void everyQitsImageNamesItsPublisher() {
        assertThat(PinnedVersions.IMAGE_PRODUCERS)
                .containsEntry("qits/workspace-base", "oci-workspace")
                .containsEntry("qits/workspace", "workspace-daemon")
                .containsEntry("qits/projects-daemon", "projects-daemon")
                .containsEntry("qits/project-agent", "projects-daemon");
        // Derived from releasePackages, so a publisher that grows an image says so in one place.
        assertThat(PinnedVersions.IMAGE_PRODUCERS.values())
                .allSatisfy(producer -> assertThat(PlatformModel.RELEASE_PUBLISHERS)
                        .contains(producer));
    }

    /** An image nothing in the plan publishes is said once rather than chased. */
    @Test
    void anImageNoPublisherPushesIsWarnedAbout() {
        dockerfile("ci", PinnedVersions.HEAD, "docker/Dockerfile",
                "FROM qits/nothing-publishes-this:2026.905.1\n");

        assertThat(PinnedVersions.read(List.of("ci"), source).warnings())
                .singleElement().asString().contains("qits/nothing-publishes-this");
    }

    /** A pinned image tag no checkout has warns and is dropped, exactly as a jar's would be. */
    @Test
    void anImageTagThatIsNotInTheCheckoutWarnsInsteadOfStoppingTheBoot() {
        releases.put("oci-workspace", "2026.905.92439");
        dockerfile("workspace-daemon", PinnedVersions.HEAD, "docker/Dockerfile",
                "FROM qits/workspace-base:2026.101.1\n");

        PinnedVersions pins = PinnedVersions.read(List.of("workspace-daemon"), source);

        assertThat(pins.extraVersions("oci-workspace")).isEmpty();
        assertThat(pins.warnings()).singleElement().asString()
                .contains("qits-workspace-oci").contains("2026.101.1");
    }

    // --- the npm versions a frontend's lockfile pins ----------------------------------------------

    private static String lock(String version) {
        return """
                {"lockfileVersion":3,"packages":{
                  "":{"name":"qits-githost-frontend"},
                  "node_modules/@qits/ui-components":{"version":"%s",
                    "resolved":"http://localhost:8081/artifacts/npm/npm/@qits/ui-components/-/x.tgz"}
                }}""".formatted(version);
    }

    private void frontendPinning(String version) {
        releases.put("spa-ui-components", "2026.905.91746");
        files.put("githost@" + PinnedVersions.HEAD + ":.gitmodules", """
                [submodule "qits-githost-frontend"]
                	path = service/src/main/webui
                	url = ../qits-githost-frontend.git
                """);
        gitlinks.put("githost@" + PinnedVersions.HEAD + ":service/src/main/webui", "052f94e3");
        files.put("spa-githost@052f94e3:package-lock.json", lock(version));
        files.put("spa-ui-components@" + version + ":package.json", "{}");
    }

    /**
     * <b>The pin is in the FRONTEND's lock at the GITLINK, not in the deployable.</b> A restore
     * stands a deployable at its release tag; that tag records a frontend commit; that commit's
     * lock pins {@code @qits/ui-components} exactly, and {@code npm ci} obeys the lock rather than
     * the caret in package.json. So the version its release build installs is weeks behind the
     * publisher's newest tag — measured on 2026-09-05, where nine deployables wanted
     * 2026.902.204627 and the registry held only 2026.905.91746.
     */
    @Test
    void anNpmPinIsReadFromTheFrontendLockAtTheGitlink() {
        frontendPinning("2026.902.204627");

        PinnedVersions pins = PinnedVersions.read(List.of("githost"), source);

        assertThat(pins.extraVersions("spa-ui-components")).containsExactly("2026.902.204627");
        assertThat(pins.warnings()).isEmpty();
    }

    /** A lock pinning what the publisher's newest tag already is adds nothing to replay. */
    @Test
    void anNpmPinOnTheNewestReleaseIsNotAnExtra() {
        frontendPinning("2026.905.91746");

        assertThat(PinnedVersions.read(List.of("githost"), source)
                .extraVersions("spa-ui-components")).isEmpty();
    }

    /**
     * <b>A prerelease pin is warned about and skipped.</b> The registry holds
     * {@code <calver>-main.g<sha>} builds, and no release tag names one — so there is nothing for a
     * replay to push, and pretending otherwise would stop the boot on a tag that does not exist.
     */
    @Test
    void aPrereleasePinIsSkippedWithAWarning() {
        frontendPinning("2026.902.204627-main.geec9f2c");

        PinnedVersions pins = PinnedVersions.read(List.of("githost"), source);

        assertThat(pins.extraVersions("spa-ui-components")).isEmpty();
        assertThat(pins.warnings()).singleElement().asString()
                .contains("@qits/ui-components").contains("2026.902.204627-main.geec9f2c");
    }

    /**
     * A gitlink the frontend's own clone does not hold is one WARN and no closure entry — the
     * frontend follows main, and an old release tag can name a commit that is no longer on it.
     */
    @Test
    void aGitlinkTheFrontendCloneLacksWarnsRatherThanStoppingTheBoot() {
        frontendPinning("2026.902.204627");
        files.remove("spa-githost@052f94e3:package-lock.json");

        PinnedVersions pins = PinnedVersions.read(List.of("githost"), source);

        assertThat(pins.extraVersions("spa-ui-components")).isEmpty();
        assertThat(pins.warnings()).singleElement().asString()
                .contains("qits-githost-frontend").contains("052f94e3");
    }

    /** Both lockfile shapes, and only the @qits packages out of them. */
    @Test
    void onlyTheQitsPackagesOfALockAreRead() {
        List<String> warnings = new java.util.ArrayList<>();

        assertThat(PinnedVersions.npmPinsIn("""
                {"lockfileVersion":3,"packages":{
                  "node_modules/@qits/ui-components":{"version":"2026.902.204627"},
                  "node_modules/@angular/core":{"version":"21.0.1"},
                  "node_modules/rxjs":{"version":"7.8.1"}}}""", warnings))
                .containsExactly(entry("@qits/ui-components", "2026.902.204627"));
        assertThat(warnings).isEmpty();
        // The v1 shape, in case a frontend still carries one.
        assertThat(PinnedVersions.npmPinsIn(
                "{\"dependencies\":{\"@qits/angular\":{\"version\":\"2026.904.202810\"}}}",
                warnings))
                .containsExactly(entry("@qits/angular", "2026.904.202810"));
    }

    /** Every @qits package a lock could pin resolves to the publisher whose run publishes it. */
    @Test
    void everyQitsPackageNamesItsPublisher() {
        assertThat(PinnedVersions.NPM_PRODUCERS)
                .containsEntry("@qits/ui-components", "spa-ui-components")
                .containsEntry("@qits/angular", "integrations-angular");
        assertThat(PinnedVersions.NPM_PRODUCERS.values())
                .allSatisfy(producer -> assertThat(PlatformModel.RELEASE_PUBLISHERS)
                        .contains(producer));
    }
}
