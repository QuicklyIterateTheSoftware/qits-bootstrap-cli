package eu.wohlben.qits.cli.bootstrap.platform;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A cold start cannot pull through the mirror it is starting, so a seed build gets the committed
 * Dockerfile with the mirror prefixes rewritten back to the direct upstreams.
 */
class SeedDockerfileTest {

    @Test
    void rewritesEveryMirrorPrefixBackToItsUpstream() {
        String dockerfile = """
                FROM mirror.dev.localhost:8080/quay/quarkus/ubi9-quarkus-mandrel-builder-image:jdk-25 AS build
                FROM mirror.dev.localhost:8080/redhat/ubi9/ubi-minimal:9.6
                COPY --from=mirror.dev.localhost:8080/hub/library/node:24-alpine /usr/local /usr/local
                """;

        String rewritten = SeedDockerfile.rewrite(dockerfile);

        assertThat(rewritten).contains("FROM quay.io/quarkus/ubi9-quarkus-mandrel-builder-image:jdk-25");
        assertThat(rewritten).contains("FROM registry.access.redhat.com/ubi9/ubi-minimal:9.6");
        assertThat(rewritten).contains("COPY --from=docker.io/library/node:24-alpine");
        assertThat(rewritten).doesNotContain("mirror.dev.localhost:8080");
    }

    @Test
    void rewritesEveryOccurrenceNotJustTheFirst() {
        String dockerfile = """
                FROM mirror.dev.localhost:8080/quay/one
                FROM mirror.dev.localhost:8080/quay/two
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
    void leavesHeadroomInsideTheFourGigabyteBuilder() {
        String dockerfile = "RUN ./mvnw package -Dquarkus.native.native-image-xmx=4g\n";

        assertThat(SeedDockerfile.rewrite(dockerfile))
                .contains("-Dquarkus.native.native-image-xmx=3g")
                .contains("MAVEN_OPTS=\"-Xmx384m\" ./mvnw")
                .doesNotContain("native-image-xmx=4g");
    }

    @Test
    void doesNotRewriteAnUnrelatedMirrorRef() {
        // Only the three upstream namespaces are the mirror's; any other path under the same host
        // is left alone.
        String dockerfile = "FROM mirror.dev.localhost:8080/qits/build-images/ci-base:latest\n";

        assertThat(SeedDockerfile.rewrite(dockerfile)).isEqualTo(dockerfile);
    }

    /**
     * <b>The hosted registry is not the mirror, and a rewrite that touched it would break the one
     * image reference a seed build must keep.</b> The platform's own images come from qits-artifacts
     * — at registry.&lt;env&gt;.localhost through the edge, and at localhost:8081 on the machines
     * that still spell the old host port — and no cache stands in front of them.
     */
    @Test
    void leavesTheHostedRegistryAlone() {
        assertThat(SeedDockerfile.rewrite("FROM localhost:8081/qits/build-images/ci-base:latest\n"))
                .isEqualTo("FROM localhost:8081/qits/build-images/ci-base:latest\n");
        assertThat(SeedDockerfile.rewrite(
                "FROM registry.dev.localhost:8080/qits/build-images/ci-base:latest\n"))
                .isEqualTo("FROM registry.dev.localhost:8080/qits/build-images/ci-base:latest\n");
    }

    /**
     * The old spellings are not rewritten either, and that is the cost of the constant being a
     * LITERAL: a Dockerfile still naming the mirror's retired host port is left as it is, and the
     * seed build fails on a port nothing holds rather than pulling from the wrong place. The fleet's
     * FROM lines and this constant move together.
     */
    @Test
    void leavesARetiredMirrorSpellingAlone() {
        String dockerfile = "FROM localhost:8082/quay/quarkus/ubi9-quarkus-mandrel-builder-image\n";

        assertThat(SeedDockerfile.rewrite(dockerfile)).isEqualTo(dockerfile);
    }
}
