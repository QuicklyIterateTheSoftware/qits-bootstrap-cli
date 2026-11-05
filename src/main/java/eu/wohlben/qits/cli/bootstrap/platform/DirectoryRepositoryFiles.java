package eu.wohlben.qits.cli.bootstrap.platform;

import eu.wohlben.qits.bootstrap.graph.manifest.RepositoryFiles;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * <b>One repository's files as a directory on disk</b> — a working tree, or one repository of the
 * {@code src/test/resources/estate/} fixture — for {@link CheckoutProjectSource}.
 * <p>
 * <b>The working tree, not a commit.</b> {@code order --wrapper <dir>} asks what the checkout in
 * front of it declares, edits included, and the fixture is a copy of exactly that. The boot itself
 * would read the revision it builds ({@link RefRepositoryFiles}); this form is for reading a tree a
 * person points at.
 * <p>
 * {@link #paths()} walks the tree and leaves out {@code .git}, {@code node_modules}, {@code target}
 * and every directory that is a nested checkout (it holds a {@code .git} of its own): a submodule's
 * files are not its parent's, and {@code ManifestReader} reads a gitlink only as a gitlink. The
 * gitlinks come from the caller, because a directory cannot say which commit its parent records.
 */
public final class DirectoryRepositoryFiles implements RepositoryFiles {

    /** Directories that are never a repository's own declarations. */
    private static final Set<String> SKIPPED = Set.of(".git", "node_modules", "target");

    private final Path root;
    private final Supplier<Map<String, String>> gitlinks;
    private List<String> paths;

    /**
     * @param root     the repository's top directory
     * @param gitlinks submodule path to recorded sha, asked at most once
     */
    public DirectoryRepositoryFiles(Path root, Supplier<Map<String, String>> gitlinks) {
        this.root = root;
        this.gitlinks = gitlinks;
    }

    @Override
    public Optional<String> read(String path) {
        Path file = root.resolve(path).normalize();
        if (!file.startsWith(root) || !Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            return Optional.of(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + file, e);
        }
    }

    @Override
    public List<String> paths() {
        if (paths == null) {
            paths = walk();
        }
        return paths;
    }

    @Override
    public Map<String, String> gitlinks() {
        return gitlinks.get();
    }

    private List<String> walk() {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        List<String> found = new ArrayList<>();
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (dir.equals(root)) {
                        return FileVisitResult.CONTINUE;
                    }
                    String name = dir.getFileName().toString();
                    if (SKIPPED.contains(name) || Files.exists(dir.resolve(".git"))) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (attrs.isRegularFile()) {
                        found.add(root.relativize(file).toString().replace('\\', '/'));
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException("cannot list " + root, e);
        }
        found.removeIf(path -> path.equals(".git"));
        found.sort(String::compareTo);
        return List.copyOf(found);
    }
}
