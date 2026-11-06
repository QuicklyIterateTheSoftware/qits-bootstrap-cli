package eu.wohlben.qits.cli.bootstrap.platform;

import eu.wohlben.qits.bootstrap.graph.manifest.GitmodulesParser;
import eu.wohlben.qits.bootstrap.graph.manifest.ManifestReader;
import eu.wohlben.qits.bootstrap.graph.manifest.RepositoryFiles;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * <b>One repository's files at ONE ref of its checkout, read through {@link PinnedVersions.Sources}</b>
 * — the same {@code git show <ref>:<path>} the pin closure reads, so a derivation over this form
 * reads the revision the boot builds. It is the form {@code Boot} would hand
 * {@link CheckoutProjectSource} ({@code Boot.checkouts()} at {@link PinnedVersions#HEAD}); nothing
 * calls it yet.
 * <p>
 * <b>The listing is the part {@code Sources} has no question for</b>, and it is not needed whole:
 * {@code ManifestReader} takes only the ROOT's files and {@code docker/}'s Dockerfiles from
 * {@link #paths()}, and reads a reactor module's pom by path. So the listing is the root files that
 * reader looks for — each one included only when it reads — plus {@code Sources.dockerfiles}.
 * <p>
 * <b>Two limits are inherited, and they are stated rather than hidden.</b> {@code Sources.at}
 * answers null for a file past its capture limit (twenty thousand lines), so a lock longer than
 * that is absent here and its repository's npm pins carry no version — they drop out rather than
 * guess (the root package.json is then read lockless, by name, see
 * {@link CheckoutProjectSource#lockless}). And {@code Boot}'s {@code Sources.dockerfiles} lists only
 * names starting {@code Dockerfile}, so a {@code node.Dockerfile} is not listed; widening that
 * listing would change what the pin closure reads, which is a behaviour change of its own.
 */
public final class RefRepositoryFiles implements RepositoryFiles {

    /**
     * The root files {@code ManifestReader} looks for by name. {@code "pnpm-lock.yaml"} is a
     * literal because the library pin this repository builds against has no PNPM_LOCK constant
     * yet; with that library {@code ManifestReader} never asks for it, so listing it here changes
     * nothing today — it only lets the at-ref path see the file once a bump makes the library
     * read it.
     */
    private static final List<String> ROOT_FILES = List.of(
            "pom.xml", "package.json", ManifestReader.PACKAGE_LOCK, "pnpm-lock.yaml",
            GitmodulesParser.PATH);

    private final PinnedVersions.Sources sources;
    private final String name;
    private final String ref;
    private final Map<String, Optional<String>> read = new LinkedHashMap<>();

    /**
     * @param sources the checkouts
     * @param name    the model name {@code Sources} addresses the checkout by
     * @param ref     the revision every answer is read at
     */
    public RefRepositoryFiles(PinnedVersions.Sources sources, String name, String ref) {
        this.sources = sources;
        this.name = name;
        this.ref = ref;
    }

    @Override
    public Optional<String> read(String path) {
        return read.computeIfAbsent(path, p -> Optional.ofNullable(sources.at(name, ref, p)));
    }

    @Override
    public List<String> paths() {
        List<String> paths = new ArrayList<>();
        for (String file : ROOT_FILES) {
            if (read(file).isPresent()) {
                paths.add(file);
            }
        }
        paths.addAll(sources.dockerfiles(name, ref));
        return paths;
    }

    @Override
    public Map<String, String> gitlinks() {
        Map<String, String> gitlinks = new LinkedHashMap<>();
        for (GitmodulesParser.Submodule module
                : GitmodulesParser.parse(read(GitmodulesParser.PATH).orElse(null))) {
            String sha = sources.gitlink(name, ref, module.path());
            if (sha != null) {
                gitlinks.put(module.path(), sha);
            }
        }
        return gitlinks;
    }
}
