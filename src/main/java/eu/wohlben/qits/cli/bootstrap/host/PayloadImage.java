package eu.wohlben.qits.cli.bootstrap.host;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;

/**
 * The image the phases run in, addressed BY ITS CONTENT.
 * <p>
 * The tag is a digest of everything {@code docker/Dockerfile.bootstrap} reads, so the same
 * checkout always names the same image and a rerun finds it already built. The recovery payload is
 * a JVM image so it leaves the host budget to the seed images it orchestrates. Change a source
 * file and the tag changes with it, so a stale image cannot be run by mistake either.
 * <p>
 * <b>The repository is {@code qits-bootstrap}, deliberately not {@code qits/bootstrap}.</b>
 * {@code unwrap}'s image sweep removes everything under {@code qits/}, and this is the image the
 * running unwrap is executing from — docker refuses to remove it, the sweep reports a failure, and
 * a clean unwrap ends with a warning and exit 1. Keeping the payload out of that pattern is also
 * what makes the unwrap-then-bootstrap cycle fast: the image the next bootstrap needs is still
 * there.
 */
public final class PayloadImage {

    /** Not {@code qits/…}: see the class comment. */
    public static final String REPOSITORY = "qits-bootstrap";

    /** Relative to the build context, which is this CLI's own checkout. */
    public static final String DOCKERFILE = "docker/Dockerfile.bootstrap";

    /**
     * What the image is made of, and therefore what the tag is a digest of. It is the Dockerfile's
     * {@code COPY} list plus the Dockerfile itself, and the two have to stay in step: a path copied
     * but not hashed is a change that does not rebuild, and a path hashed but not copied is a
     * rebuild that changes nothing.
     * <p>
     * The builder is a pinned Maven/Temurin image, so neither the host Maven installation nor a
     * wrapper distribution cache is part of this recovery path.
     * <p>
     * {@code src/test} is in neither. The tests are the {@code ./mvnw clean verify} gate on the
     * host; the image build skips them, so a test-only edit is not a new image.
     */
    static final List<String> CONTENT = List.of(DOCKERFILE, "pom.xml", "src/main");

    private PayloadImage() {
    }

    /** The image reference to build and to run: {@code qits-bootstrap:<content digest>}. */
    public static String reference(Path context) throws IOException {
        return REPOSITORY + ":" + tag(context);
    }

    /**
     * A digest of the build's inputs, short enough to read in a {@code docker images} listing.
     * <p>
     * The path is hashed beside the bytes, so moving a file changes the tag; the file list is
     * sorted, because a directory walk's order is the filesystem's and not a fact about the
     * content.
     */
    public static String tag(Path context) throws IOException {
        List<Path> files = new ArrayList<>();
        for (String entry : CONTENT) {
            Path path = context.resolve(entry);
            if (Files.isDirectory(path)) {
                try (Stream<Path> walk = Files.walk(path)) {
                    walk.filter(Files::isRegularFile).forEach(files::add);
                }
            } else if (Files.isRegularFile(path)) {
                files.add(path);
            } else {
                // The one honest failure here: the context is not this CLI's checkout.
                throw new IOException(context + " is not a qits-bootstrap-cli checkout — it has "
                        + "no " + entry + ", so the payload image cannot be built from it");
            }
        }
        files.sort(Comparator.comparing(file -> context.relativize(file).toString()));

        MessageDigest sha = sha256();
        for (Path file : files) {
            sha.update(context.relativize(file).toString().getBytes(StandardCharsets.UTF_8));
            sha.update((byte) 0);
            sha.update(Files.readAllBytes(file));
            sha.update((byte) 0);
        }
        return HexFormat.of().formatHex(sha.digest()).substring(0, 12);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("no SHA-256 on this JVM", impossible);
        }
    }
}
