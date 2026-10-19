package eu.wohlben.qits.cli.bootstrap.platform;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

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
}
