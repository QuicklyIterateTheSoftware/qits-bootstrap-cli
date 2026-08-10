package eu.wohlben.qits.cli.bootstrap.platform;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A cold start cannot pull through the registry it is starting, so a seed build gets the committed
 * Dockerfile with the mirror prefixes rewritten back to the direct upstreams.
 */
class SeedDockerfileTest {

    @Test
    void rewritesEveryMirrorPrefixBackToItsUpstream() {
        String dockerfile = """
                FROM localhost:8082/quay/quarkus/ubi9-quarkus-mandrel-builder-image:jdk-25 AS build
                FROM localhost:8082/redhat/ubi9/ubi-minimal:9.6
                COPY --from=localhost:8082/hub/library/node:24-alpine /usr/local /usr/local
                """;

        String rewritten = SeedDockerfile.rewrite(dockerfile);

        assertThat(rewritten).contains("FROM quay.io/quarkus/ubi9-quarkus-mandrel-builder-image:jdk-25");
        assertThat(rewritten).contains("FROM registry.access.redhat.com/ubi9/ubi-minimal:9.6");
        assertThat(rewritten).contains("COPY --from=docker.io/library/node:24-alpine");
        assertThat(rewritten).doesNotContain("localhost:8082");
    }

    @Test
    void rewritesEveryOccurrenceNotJustTheFirst() {
        String dockerfile = """
                FROM localhost:8082/quay/one
                FROM localhost:8082/quay/two
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
    void doesNotRewriteAnUnrelatedMirrorRef() {
        // Only the three upstream namespaces are the mirror's; any other path under the same host
        // is left alone.
        String dockerfile = "FROM localhost:8082/qits/build-images/ci-base:latest\n";

        assertThat(SeedDockerfile.rewrite(dockerfile)).isEqualTo(dockerfile);
    }

    /**
     * <b>The hosted registry's port is not the mirror's, and a rewrite that touched it would break
     * the one image reference a seed build must keep.</b> The platform's own images are pulled from
     * localhost:8081 — qits-artifacts — and no cache stands in front of them.
     */
    @Test
    void leavesTheHostedRegistryAlone() {
        String dockerfile = "FROM localhost:8081/qits/build-images/ci-base:latest\n";

        assertThat(SeedDockerfile.rewrite(dockerfile)).isEqualTo(dockerfile);
    }
}
