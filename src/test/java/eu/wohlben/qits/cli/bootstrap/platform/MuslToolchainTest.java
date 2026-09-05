package eu.wohlben.qits.cli.bootstrap.platform;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The two tarballs read out of qits-ci-daemon's builder Dockerfile. What this decides is what a
 * cold boot uploads into an empty store and what it checks the bytes against, so a parse that
 * quietly found nothing would be a boot that dies in an {@code ADD} thirty-seven phases in.
 */
class MuslToolchainTest {

    /** The shape the real file has, trimmed to the lines this reads. */
    private static final String DOCKERFILE = """
            # --- WHERE THE TWO TARBALLS COME FROM ---
            #   x86_64-linux-musl-native.tgz  89080066 B  sha256 eb1db6f0f3c2bdbdbfb993d7ef7e2eeef82ac1259f6a6e1757c33a97dbcef3ad
            #                                 from https://more.musl.cc/11.2.1/x86_64-linux-musl/
            #   zlib-1.3.1.tar.gz              1512791 B  sha256 9a93b2b7dfdac77ceba5a558a580e74667dd6fede4585b91eefb60f03b72df23
            #                                 from the GitHub release, not zlib.net
            #
            # The upstream URLs remain valid overrides:
            #
            #   docker build --build-arg MUSL_URL=https://more.musl.cc/11.2.1/x86_64-linux-musl/x86_64-linux-musl-native.tgz \\
            #                --build-arg ZLIB_URL=https://github.com/madler/zlib/releases/download/v1.3.1/zlib-1.3.1.tar.gz \\
            #                -t qits/graalvmce-musl-builder:jdk-25 -f docker/Dockerfile.musl-builder docker/
            ARG MUSL_URL=http://registry.dev.localhost:8080/artifacts/maven/maven/eu/wohlben/qits/toolchain/x86_64-linux-musl-native/11.2.1/x86_64-linux-musl-native-11.2.1.tgz
            ARG ZLIB_URL=http://registry.dev.localhost:8080/artifacts/maven/maven/eu/wohlben/qits/toolchain/zlib/1.3.1/zlib-1.3.1.tar.gz

            FROM scratch AS tarballs
            ARG MUSL_URL
            ARG ZLIB_URL
            ADD $MUSL_URL /musl.tgz
            ADD $ZLIB_URL /zlib.tar.gz
            """;

    /**
     * <b>The coordinate is the ARG default's, which is real Dockerfile syntax.</b> The store path
     * has to be the one the {@code ADD} would have fetched, character for character: this run
     * uploads to it and then hands the build the same path under an address that exists.
     */
    @Test
    void theStoreCoordinateIsTheOneTheArgDefaultNames() {
        List<MuslToolchain.Tarball> tarballs = MuslToolchain.read(DOCKERFILE);

        assertThat(tarballs).hasSize(2);
        MuslToolchain.Tarball musl = tarballs.get(0);
        assertThat(musl.arg()).isEqualTo("MUSL_URL");
        assertThat(musl.groupPath()).isEqualTo("eu/wohlben/qits/toolchain");
        assertThat(musl.groupId()).isEqualTo("eu.wohlben.qits.toolchain");
        assertThat(musl.artifactId()).isEqualTo("x86_64-linux-musl-native");
        assertThat(musl.version()).isEqualTo("11.2.1");
        assertThat(musl.extension()).isEqualTo("tgz");
        assertThat(musl.storePath()).isEqualTo("eu/wohlben/qits/toolchain/"
                + "x86_64-linux-musl-native/11.2.1/x86_64-linux-musl-native-11.2.1.tgz");
        // A two-part extension, which Maven's packaging carries as it stands.
        assertThat(tarballs.get(1).extension()).isEqualTo("tar.gz");
        assertThat(tarballs.get(1).storePath())
                .isEqualTo("eu/wohlben/qits/toolchain/zlib/1.3.1/zlib-1.3.1.tar.gz");
    }

    /**
     * <b>The pins belong to the repository that consumes the bytes</b>, so they are read rather
     * than copied into this program. Verified once against three sources on 2026-09-05: the
     * Dockerfile's own record, the live platform's store, and a fresh download from upstream — all
     * three agreed on both hashes.
     */
    @Test
    void theSizeAndHashComeFromTheDockerfilesOwnRecord() {
        List<MuslToolchain.Tarball> tarballs = MuslToolchain.read(DOCKERFILE);

        assertThat(tarballs.get(0).sha256())
                .isEqualTo("eb1db6f0f3c2bdbdbfb993d7ef7e2eeef82ac1259f6a6e1757c33a97dbcef3ad");
        assertThat(tarballs.get(0).bytes()).isEqualTo(89080066L);
        assertThat(tarballs.get(1).sha256())
                .isEqualTo("9a93b2b7dfdac77ceba5a558a580e74667dd6fede4585b91eefb60f03b72df23");
        assertThat(tarballs.get(1).bytes()).isEqualTo(1512791L);
    }

    /**
     * The comment names the UPSTREAM file and the store names the versioned one, so the pin line is
     * found by artifact id rather than by file name — the half both spellings share.
     */
    @Test
    void theUpstreamAddressIsTheOverrideExampleInTheSameFile() {
        List<MuslToolchain.Tarball> tarballs = MuslToolchain.read(DOCKERFILE);

        assertThat(tarballs.get(0).upstream()).isEqualTo(
                "https://more.musl.cc/11.2.1/x86_64-linux-musl/x86_64-linux-musl-native.tgz");
        assertThat(tarballs.get(1).upstream()).isEqualTo(
                "https://github.com/madler/zlib/releases/download/v1.3.1/zlib-1.3.1.tar.gz");
    }

    /** An ARG that points somewhere else is a dev machine's business, not the store's. */
    @Test
    void anArgThatDoesNotPointIntoTheStoreIsNotSeeded() {
        assertThatThrownBy(() -> MuslToolchain.read(
                "ARG MUSL_URL=https://more.musl.cc/11.2.1/x86_64-linux-musl-native.tgz\n"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("declares no ARG");
    }

    /**
     * <b>A reformatted comment stops the seed instead of unpinning it.</b> The alternative is
     * uploading ninety megabytes from a community host with nothing to check them against, into a
     * store every later build of this platform trusts.
     */
    @Test
    void aMissingPinIsLoudRatherThanAnUncheckedUpload() {
        String withoutPins = DOCKERFILE.replaceAll("(?m)^#   x86_64.*$", "#   the musl toolchain");

        assertThatThrownBy(() -> MuslToolchain.read(withoutPins))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("x86_64-linux-musl-native")
                .hasMessageContaining("Dockerfile.musl-builder");
    }

    @Test
    void aMissingUpstreamAddressIsLoudToo() {
        String withoutUpstream = DOCKERFILE.replaceAll("(?m)^#   docker build --build-arg MUSL_URL.*$",
                "#   docker build \\\\");

        assertThatThrownBy(() -> MuslToolchain.read(withoutUpstream))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no upstream address for MUSL_URL");
    }
}
