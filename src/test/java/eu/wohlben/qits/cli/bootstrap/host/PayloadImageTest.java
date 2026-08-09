package eu.wohlben.qits.cli.bootstrap.host;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The payload image's tag is a digest of what the image build reads. Two properties matter: the
 * same checkout names the same image, so a rerun rebuilds nothing, and a changed source names a
 * different one, so a stale image cannot be run by mistake.
 */
class PayloadImageTest {

    private static Path checkout(Path root) throws IOException {
        Files.createDirectories(root.resolve("docker"));
        Files.createDirectories(root.resolve(".mvn/wrapper"));
        Files.createDirectories(root.resolve("src/main/java"));
        Files.writeString(root.resolve("docker/Dockerfile.bootstrap"), "FROM scratch\n");
        Files.writeString(root.resolve("pom.xml"), "<project/>\n");
        Files.writeString(root.resolve("mvnw"), "#!/bin/sh\n");
        Files.writeString(root.resolve(".mvn/wrapper/maven-wrapper.properties"), "wrapper=3\n");
        Files.writeString(root.resolve("src/main/java/Boot.java"), "class Boot {}\n");
        return root;
    }

    @Test
    void theSameCheckoutIsTheSameImage(@TempDir Path root) throws IOException {
        checkout(root);
        assertThat(PayloadImage.tag(root)).isEqualTo(PayloadImage.tag(root)).hasSize(12);
        assertThat(PayloadImage.reference(root)).startsWith("qits-bootstrap:");
    }

    @Test
    void thePayloadIsNotSweptByUnwrap() {
        // unwrap removes every image under qits/, and this is the image the running unwrap is
        // executing from. The name is what keeps it out of that sweep.
        assertThat(PayloadImage.REPOSITORY).doesNotStartWith("qits/");
    }

    @Test
    void aChangedSourceIsANewImage(@TempDir Path root) throws IOException {
        checkout(root);
        String before = PayloadImage.tag(root);
        Files.writeString(root.resolve("src/main/java/Boot.java"), "class Boot { int x; }\n");
        assertThat(PayloadImage.tag(root)).isNotEqualTo(before);
    }

    @Test
    void aChangedDockerfileIsANewImage(@TempDir Path root) throws IOException {
        checkout(root);
        String before = PayloadImage.tag(root);
        Files.writeString(root.resolve("docker/Dockerfile.bootstrap"), "FROM alpine\n");
        assertThat(PayloadImage.tag(root)).isNotEqualTo(before);
    }

    @Test
    void aMovedFileIsANewImage(@TempDir Path root) throws IOException {
        checkout(root);
        String before = PayloadImage.tag(root);
        Files.move(root.resolve("src/main/java/Boot.java"), root.resolve("src/main/java/Run.java"));
        assertThat(PayloadImage.tag(root)).isNotEqualTo(before);
    }

    @Test
    void whatTheImageBuildDoesNotReadDoesNotRebuildIt(@TempDir Path root) throws IOException {
        checkout(root);
        String before = PayloadImage.tag(root);
        // The tests are the host's `./mvnw clean verify` gate, and the image build skips them.
        Files.createDirectories(root.resolve("src/test/java"));
        Files.writeString(root.resolve("src/test/java/BootTest.java"), "class BootTest {}\n");
        Files.writeString(root.resolve("README.md"), "read me\n");
        Files.createDirectories(root.resolve("target"));
        Files.writeString(root.resolve("target/qits-cli-bootstrap-1-runner.jar"), "not a jar\n");
        assertThat(PayloadImage.tag(root)).isEqualTo(before);
    }

    @Test
    void everythingTheDockerfileCopiesIsHashed() throws IOException {
        // The two lists drift silently and both directions are wrong: a path copied but not hashed
        // is a source change that reuses a stale image, and a path hashed but not copied is a
        // rebuild that changes nothing. The real Dockerfile is read, so this catches the drift on
        // the commit that causes it.
        List<String> copied = Files.readAllLines(Path.of(PayloadImage.DOCKERFILE)).stream()
                .map(String::strip)
                .filter(line -> line.startsWith("COPY "))
                // COPY --from=<stage> moves the built binary between stages; its source is target/,
                // which the context does not even hold.
                .filter(line -> !line.contains("--from="))
                .map(line -> line.split("\\s+")[1])
                .map(source -> source.endsWith("/") ? source.substring(0, source.length() - 1)
                        : source)
                .toList();

        assertThat(copied).isNotEmpty()
                .allSatisfy(source -> assertThat(PayloadImage.CONTENT).contains(source));
        assertThat(PayloadImage.CONTENT).containsExactlyInAnyOrderElementsOf(
                Stream.concat(copied.stream(), Stream.of(PayloadImage.DOCKERFILE)).toList());
    }

    @Test
    void aContextThatIsNotThisCheckoutSaysSo(@TempDir Path root) {
        assertThatThrownBy(() -> PayloadImage.tag(root))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("is not a qits-cli-bootstrap checkout");
    }
}
