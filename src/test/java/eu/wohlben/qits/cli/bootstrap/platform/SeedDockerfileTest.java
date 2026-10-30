package eu.wohlben.qits.cli.bootstrap.platform;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A cold start cannot pull through the mirror it is starting, so a seed build gets the committed
 * Dockerfile with the mirror prefixes rewritten back to the direct upstreams.
 */
class SeedDockerfileTest {

    /**
     * <b>The spelling since qits-1402: the mirror's public edge name, with the build-arg left
     * unexpanded.</b> The rewrite matches the TEXT of the committed file, so it is
     * {@code ${QITS_DOMAIN}} it has to find, not whatever domain this run serves.
     */
    @Test
    void rewritesEveryMirrorPrefixBackToItsUpstream() {
        String dockerfile = """
                ARG QITS_DOMAIN=wohlben.eu
                FROM mirror.qits.${QITS_DOMAIN}/quay/quarkus/ubi9-quarkus-mandrel-builder-image:jdk-25 AS build
                FROM mirror.qits.${QITS_DOMAIN}/redhat/ubi9/ubi-minimal:9.6
                COPY --from=mirror.qits.${QITS_DOMAIN}/hub/library/node:24-alpine /usr/local /usr/local
                """;

        String rewritten = SeedDockerfile.rewrite(dockerfile);

        assertThat(rewritten).contains("FROM quay.io/quarkus/ubi9-quarkus-mandrel-builder-image:jdk-25");
        assertThat(rewritten).contains("FROM registry.access.redhat.com/ubi9/ubi-minimal:9.6");
        assertThat(rewritten).contains("COPY --from=docker.io/library/node:24-alpine");
        assertThat(rewritten).doesNotContain("mirror.qits.");
    }

    /**
     * <b>The spelling before qits-1402 is still rewritten</b>, because the fleet releases one
     * repository at a time and a cold start meets both — possibly in one file, as an ARG default
     * and a FROM line that were edited apart.
     */
    @Test
    void rewritesTheLegacySpellingToo() {
        String dockerfile = """
                ARG RUNTIME_BASE=mirror.dev.localhost:8080/redhat/ubi9/ubi-minimal:9.6
                FROM mirror.dev.localhost:8080/quay/quarkus/ubi9-quarkus-mandrel-builder-image:jdk-25 AS build
                FROM mirror.qits.${QITS_DOMAIN}/hub/library/node:24-alpine AS ui
                COPY --from=mirror.dev.localhost:8080/hub/library/node:24-alpine /usr/local /usr/local
                FROM ${RUNTIME_BASE}
                """;

        String rewritten = SeedDockerfile.rewrite(dockerfile);

        assertThat(rewritten).contains("ARG RUNTIME_BASE=registry.access.redhat.com/ubi9/ubi-minimal:9.6");
        assertThat(rewritten).contains("FROM quay.io/quarkus/ubi9-quarkus-mandrel-builder-image:jdk-25");
        assertThat(rewritten).contains("FROM docker.io/library/node:24-alpine AS ui");
        assertThat(rewritten).contains("COPY --from=docker.io/library/node:24-alpine");
        assertThat(rewritten).doesNotContain("mirror.dev.localhost").doesNotContain("mirror.qits.");
    }

    /** The two-argument form rewrites exactly the host it is given, and only that. */
    @Test
    void theExplicitHostFormRewritesOnlyThatHost() {
        String dockerfile = """
                FROM mirror.example:9000/quay/one
                FROM mirror.dev.localhost:8080/quay/two
                """;

        assertThat(SeedDockerfile.rewrite(dockerfile, "mirror.example:9000"))
                .contains("FROM quay.io/one")
                .contains("FROM mirror.dev.localhost:8080/quay/two");
    }

    /** The seed-only edits are made once even though two hosts are rewritten. */
    @Test
    void boundsMavenOnceWhateverTheSpelling() {
        String dockerfile = "FROM mirror.qits.${QITS_DOMAIN}/quay/x\nRUN ./mvnw package\n";

        assertThat(SeedDockerfile.rewrite(dockerfile))
                .containsOnlyOnce("MAVEN_OPTS=");
    }

    @Test
    void rewritesEveryOccurrenceNotJustTheFirst() {
        String dockerfile = """
                FROM mirror.qits.${QITS_DOMAIN}/quay/one
                FROM mirror.qits.${QITS_DOMAIN}/quay/two
                """;

        assertThat(SeedDockerfile.rewrite(dockerfile).lines().filter(l -> l.startsWith("FROM quay.io/"))
                .count()).isEqualTo(2);
    }

    @Test
    void leavesADockerfileWithNoMirrorRefsUntouched() {
        String dockerfile = "FROM docker.io/library/nginx:alpine\nEXPOSE 80\n";

        assertThat(SeedDockerfile.rewrite(dockerfile)).isEqualTo(dockerfile);
    }

    @Test
    void boundsMavenAndRaisesTheNativeHeap() {
        String dockerfile = "RUN ./mvnw package -Dquarkus.native.native-image-xmx=4g\n";

        assertThat(SeedDockerfile.rewrite(dockerfile))
                .contains("-Dquarkus.native.native-image-xmx=6g")
                .doesNotContain("native-image-xmx=4g")
                .contains("MAVEN_OPTS=\"-Xmx384m\" ./mvnw");
    }

    @Test
    void doesNotRewriteAnUnrelatedMirrorRef() {
        // Only the three upstream namespaces are the mirror's; any other path under the same host
        // is left alone.
        String dockerfile = "FROM mirror.qits.${QITS_DOMAIN}/qits/build-images/ci-base:latest\n"
                + "FROM mirror.dev.localhost:8080/qits/build-images/ci-base:latest\n";

        assertThat(SeedDockerfile.rewrite(dockerfile)).isEqualTo(dockerfile);
    }

    /**
     * <b>The hosted registry is not the mirror, and a rewrite that touched it would break the one
     * image reference a seed build must keep.</b> The platform's own images come from qits-artifacts
     * — at registry.qits.$QITS_DOMAIN since qits-1402, at registry.&lt;env&gt;.localhost through
     * the edge before it, and at localhost:8081 on the machines that still spell the old host port
     * — and no cache stands in front of them.
     */
    @Test
    void leavesTheHostedRegistryAlone() {
        assertThat(SeedDockerfile.rewrite("FROM localhost:8081/qits/build-images/ci-base:latest\n"))
                .isEqualTo("FROM localhost:8081/qits/build-images/ci-base:latest\n");
        assertThat(SeedDockerfile.rewrite(
                "FROM registry.dev.localhost:8080/qits/build-images/ci-base:latest\n"))
                .isEqualTo("FROM registry.dev.localhost:8080/qits/build-images/ci-base:latest\n");
        assertThat(SeedDockerfile.rewrite(
                "FROM registry.qits.${QITS_DOMAIN}/qits/build-images/ci-base:latest\n"))
                .isEqualTo("FROM registry.qits.${QITS_DOMAIN}/qits/build-images/ci-base:latest\n");
    }

    /**
     * The spellings older than the legacy one are not rewritten, and that is the cost of the
     * constants being LITERALS: a Dockerfile still naming the mirror's retired host port is left
     * as it is, and the seed build fails on a port nothing holds rather than pulling from the wrong
     * place. The fleet's FROM lines and these constants move together.
     */
    @Test
    void leavesARetiredMirrorSpellingAlone() {
        String dockerfile = "FROM localhost:8082/quay/quarkus/ubi9-quarkus-mandrel-builder-image\n";

        assertThat(SeedDockerfile.rewrite(dockerfile)).isEqualTo(dockerfile);
    }

    // --- the local stores: jars and toolchain out of the qits-maven-seed volume ---------------

    /**
     * The real Dockerfiles, copied from their repositories at the time of writing. A repository
     * that changes the shape of its build RUN makes {@link SeedDockerfile#localMaven} refuse at
     * boot; refresh the copy here together with the rewrite.
     */
    private static String fixture(String name) {
        try (InputStream in = SeedDockerfileTest.class.getResourceAsStream(
                "/seed-dockerfiles/" + name)) {
            assertThat(in).as(name).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Every line a build would execute or fetch from, comments left out. */
    private static List<String> buildLines(String dockerfile) {
        return dockerfile.lines().filter(line -> !line.strip().startsWith("#")).toList();
    }

    private static long count(String text, String needle) {
        return text.lines().filter(line -> line.contains(needle)).count();
    }

    /**
     * <b>qits-ci-service: the {@code export} form.</b> Both derived urls are replaced where they
     * stand, the build RUN gets the bind mount as its first flag, and nothing a build executes
     * names a platform host afterwards — the comments still do, and nothing reads them.
     */
    @Test
    void theCiServiceBuildResolvesFromTheLocalRepositoryAndCentral() {
        String rewritten = SeedDockerfile.localMaven(
                SeedDockerfile.rewrite(fixture("qits-ci-service.Dockerfile")),
                "qits-ci-service/docker/Dockerfile");

        assertThat(rewritten)
                .contains("RUN --mount=type=bind,from=qits-m2,target=/qits-m2 \\\n"
                        + "    --mount=type=secret,id=qits-client-id \\\n")
                .contains("export QITS_MAVEN_REPOSITORY_URL=\"file:///qits-m2\"; \\")
                .contains("export QITS_MAVEN_CENTRAL_URL=\"https://repo.maven.apache.org/maven2\"; \\")
                .contains("-Dqits.maven.repository.url=\"$QITS_MAVEN_REPOSITORY_URL\"")
                // The wrapper's own RUN resolves nothing of the platform and is left alone.
                .contains("RUN MAVEN_OPTS=\"-Xmx384m\" ./mvnw --version\n");
        assertThat(count(rewritten, SeedDockerfile.SEED_M2_MOUNT)).isEqualTo(1);
        assertThat(buildLines(rewritten))
                .noneMatch(line -> line.contains("registry.qits.") || line.contains("mirror.qits."));
        // The commentary is the repository's and is left as it is.
        assertThat(rewritten).contains("#   https://registry.qits.$QITS_DOMAIN/artifacts/maven/maven");
    }

    /**
     * <b>qits-containers-service: the inline {@code VAR=value ./mvnw} form</b>, with the registry
     * also spelled out literally on {@code -Dqits.maven.repository.url} — the shape qits-edge has
     * too. Both spellings come back local.
     */
    @Test
    void theContainersServiceInlineFormIsRewrittenToo() {
        String rewritten = SeedDockerfile.localMaven(
                SeedDockerfile.rewrite(fixture("qits-containers-service.Dockerfile")),
                "qits-containers-service/docker/Dockerfile");

        assertThat(rewritten)
                .contains("RUN --mount=type=bind,from=qits-m2,target=/qits-m2 \\\n"
                        + "    --mount=type=secret,id=qits-client-id \\\n")
                .contains("    QITS_MAVEN_REPOSITORY_URL=\"file:///qits-m2\" \\\n")
                .contains("    QITS_MAVEN_CENTRAL_URL=\"https://repo.maven.apache.org/maven2\" \\\n")
                .contains("-Dqits.maven.repository.url=\"file:///qits-m2\" \\\n");
        assertThat(count(rewritten, SeedDockerfile.SEED_M2_MOUNT)).isEqualTo(1);
        assertThat(buildLines(rewritten))
                .noneMatch(line -> line.contains("registry.qits.") || line.contains("mirror.qits."));
    }

    /**
     * <b>qits-mirror-service derives no Central url</b> — it IS the Central cache and resolves
     * Central directly through an empty ARG — so only the hosted Maven is rewritten, and that is
     * enough.
     */
    @Test
    void theMirrorHasNoCentralCacheToReplace() {
        String rewritten = SeedDockerfile.localMaven(
                SeedDockerfile.rewrite(fixture("qits-mirror-service.Dockerfile")),
                "qits-mirror-service/docker/Dockerfile");

        assertThat(rewritten)
                .contains("export QITS_MAVEN_REPOSITORY_URL=\"file:///qits-m2\"; \\")
                .contains("ARG QITS_MAVEN_CENTRAL_URL=\n")
                .doesNotContain("repo.maven.apache.org/maven2\"");
        assertThat(count(rewritten, SeedDockerfile.SEED_M2_MOUNT)).isEqualTo(1);
    }

    /** Cutting a file into instructions loses nothing: the pieces are the file again. */
    @Test
    void theInstructionsAreTheWholeFile() {
        for (String name : List.of("qits-ci-service.Dockerfile",
                "qits-containers-service.Dockerfile", "qits-mirror-service.Dockerfile",
                "qits-ci-daemon.Dockerfile.musl-builder")) {
            String dockerfile = fixture(name);
            assertThat(String.join("", SeedDockerfile.instructions(dockerfile)))
                    .as(name).isEqualTo(dockerfile);
        }
    }

    /**
     * <b>A Dockerfile that stopped deriving the registry url is a REFUSAL, not a quiet build.</b>
     * Whatever it does instead, the rewrite cannot know it resolves from the volume, and a build
     * that ran anyway could be resolving from the live platform.
     */
    @Test
    void aServiceDockerfileWithNoRegistryUrlStopsTheBuild() {
        String dockerfile = """
                FROM quay.io/quarkus/ubi9-quarkus-mandrel-builder-image:jdk-25 AS build
                RUN ./mvnw -B -ntp -s .qits-maven-settings.xml package -Dnative \\
                      -Dqits.maven.repository.url="$SOMEWHERE_ELSE"
                """;

        assertThatThrownBy(() -> SeedDockerfile.localMaven(dockerfile, "qits-x/docker/Dockerfile"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("qits-x/docker/Dockerfile")
                .hasMessageContaining("https://registry.qits.$QITS_DOMAIN/artifacts/maven/maven");
    }

    /**
     * <b>And so is a platform host the rewrite did not account for</b>, in any build step — here
     * a second RUN that fetches from the npm registry, and an ADD from the mirror.
     */
    @Test
    void aPlatformHostLeftInABuildStepStopsTheBuild() {
        String withNpm = """
                RUN export QITS_MAVEN_REPOSITORY_URL="https://registry.qits.$QITS_DOMAIN/artifacts/maven/maven"; \\
                    ./mvnw package
                RUN curl -fsS https://registry.qits.$QITS_DOMAIN/artifacts/npm/npm/x.tgz
                """;
        assertThatThrownBy(() -> SeedDockerfile.localMaven(withNpm, "qits-x/docker/Dockerfile"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("qits-x/docker/Dockerfile")
                .hasMessageContaining("registry.qits.")
                .hasMessageContaining("artifacts/npm/npm");

        String withAdd = """
                RUN QITS_MAVEN_REPOSITORY_URL="https://registry.qits.${QITS_DOMAIN}/artifacts/maven/maven" ./mvnw package
                ADD https://mirror.qits.${QITS_DOMAIN}/mirror/x.tgz /x.tgz
                """;
        assertThatThrownBy(() -> SeedDockerfile.localMaven(withAdd, "qits-y/docker/Dockerfile"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("mirror.qits.");
    }

    /**
     * Only what a build executes or fetches is asked about: a comment, an ARG default and a FROM
     * line (the mirror rewrite's business) may name the hosts.
     */
    @Test
    void commentsArgsAndFromLinesAreNotBuildSteps() {
        String dockerfile = """
                # resolves https://registry.qits.$QITS_DOMAIN/artifacts/maven/maven
                ARG MUSL_URL=https://registry.qits.${QITS_DOMAIN}/artifacts/maven/maven/x.tgz
                FROM mirror.qits.${QITS_DOMAIN}/hub/library/postgres:18.4
                RUN echo ok \\
                    # a comment inside a RUN about mirror.qits.$QITS_DOMAIN
                    && true
                """;

        assertThat(SeedDockerfile.refuseServers(dockerfile, "qits-database-oci/Dockerfile"))
                .isEqualTo(dockerfile);
    }

    /**
     * <b>The musl builder: each {@code ADD $<NAME>_URL} becomes a copy out of the volume</b>, at
     * the Maven path {@code seed-toolchain} deployed the tarball under. The ARG defaults stay and
     * go unread.
     */
    @Test
    void theMuslBuilderCopiesItsTarballsOutOfTheLocalRepository() {
        String committed = fixture("qits-ci-daemon.Dockerfile.musl-builder");
        List<MuslToolchain.Tarball> tarballs = MuslToolchain.read(committed);

        String rewritten = SeedDockerfile.localToolchain(SeedDockerfile.rewrite(committed),
                "qits-ci-daemon/docker/Dockerfile.musl-builder", tarballs);

        assertThat(rewritten)
                .contains("\nCOPY --from=qits-m2 /eu/wohlben/qits/toolchain/x86_64-linux-musl-native/"
                        + "11.2.1/x86_64-linux-musl-native-11.2.1.tgz /musl.tgz\n")
                .contains("\nCOPY --from=qits-m2 /eu/wohlben/qits/toolchain/zlib/1.3.1/"
                        + "zlib-1.3.1.tar.gz /zlib.tar.gz\n");
        assertThat(buildLines(rewritten)).noneMatch(line -> line.startsWith("ADD "));
        assertThat(buildLines(rewritten)).noneMatch(line -> line.startsWith("FROM mirror.qits."));
    }

    /** A tarball whose ADD is gone, or an ADD of a URL the rewrite does not know, is a refusal. */
    @Test
    void aMuslBuilderTheRewriteCannotMatchStopsTheBuild() {
        String committed = fixture("qits-ci-daemon.Dockerfile.musl-builder");
        List<MuslToolchain.Tarball> tarballs = MuslToolchain.read(committed);

        String noZlibAdd = committed.replace("ADD $ZLIB_URL /zlib.tar.gz\n", "");
        assertThatThrownBy(() -> SeedDockerfile.localToolchain(noZlibAdd,
                "qits-ci-daemon/docker/Dockerfile.musl-builder", tarballs))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("qits-ci-daemon/docker/Dockerfile.musl-builder")
                .hasMessageContaining("ADD $ZLIB_URL");

        String extraAdd = committed.replace("ADD $ZLIB_URL /zlib.tar.gz\n",
                "ADD $ZLIB_URL /zlib.tar.gz\nADD ${LIBFFI_URL} /libffi.tar.gz\n");
        assertThatThrownBy(() -> SeedDockerfile.localToolchain(extraAdd,
                "qits-ci-daemon/docker/Dockerfile.musl-builder", tarballs))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("LIBFFI_URL");
    }
}
