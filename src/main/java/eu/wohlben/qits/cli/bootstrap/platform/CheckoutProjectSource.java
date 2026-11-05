package eu.wohlben.qits.cli.bootstrap.platform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.bootstrap.graph.Coordinate;
import eu.wohlben.qits.bootstrap.graph.Ecosystem;
import eu.wohlben.qits.bootstrap.graph.Pin;
import eu.wohlben.qits.bootstrap.graph.ProjectSource;
import eu.wohlben.qits.bootstrap.graph.manifest.DeclaredArtifact;
import eu.wohlben.qits.bootstrap.graph.manifest.Deployment;
import eu.wohlben.qits.bootstrap.graph.manifest.DeploymentDeclaration;
import eu.wohlben.qits.bootstrap.graph.manifest.GitmodulesParser;
import eu.wohlben.qits.bootstrap.graph.manifest.ManifestReader;
import eu.wohlben.qits.bootstrap.graph.manifest.ParsedPin;
import eu.wohlben.qits.bootstrap.graph.manifest.ReleaseDeclaration;
import eu.wohlben.qits.bootstrap.graph.manifest.RepositoryFiles;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;

/**
 * <b>The platform as the wrapper's checkouts declare it</b> — the {@link ProjectSource} a
 * {@code ProjectGraph} of this estate is built from (qits-3).
 * <p>
 * <b>The nodes are the wrapper's submodules</b>, keyed by repository name (the url's last segment,
 * {@link GitmodulesParser}) and sorted by it, less {@link PlatformModel#READ_ONLY_REPOS}: those are
 * read by the boot and never hosted, deployed or replayed, so they are no part of the order.
 * <p>
 * <b>Every fact is read once, when the source is made.</b> A declaration that will not parse is the
 * repository's to fix and fails here, naming it — never a node with fewer pins than it holds.
 * <ul>
 *   <li><b>consumes</b>: {@link ManifestReader}'s pins, kept only when they are INTERNAL — see
 *       {@link #internal} — plus the names of a lockless package.json ({@link #lockless}).
 *   <li><b>produces</b>: {@code artifacts:} of {@code .config/qits/release.yml}, its maven, npm and
 *       docker entries. {@code daemon} and {@code docs} are no ecosystem a manifest can pin.
 *   <li><b>deployment</b>: {@code .config/qits/deployments.yml}, when the repository carries one.
 * </ul>
 */
public final class CheckoutProjectSource implements ProjectSource {

    /**
     * Resource kind to the application that provides it — what turns a {@code resources:} entry
     * into a RUNTIME edge. The database is the seed's and never re-deployed (see
     * {@link PlatformModel#DEPLOYABLES}); the edge to it still orders every consumer after it.
     */
    public static final Map<String, String> RESOURCE_PROVIDERS =
            Map.of("postgresql", "qits-oci-postgresql", "idp", "qits-idp");

    // INTERNAL VS EXTERNAL IS A NAME RULE. These three restate qits-maintenance-service's
    // maintenance/src/main/resources/META-INF/microprofile-config.properties:145-147
    // (qits.maintenance.internal.maven-groups / npm-scopes / image-prefixes); change them together.

    /** A maven group claims itself and everything under it, never a longer sibling. */
    static final String INTERNAL_MAVEN_GROUP = "eu.wohlben.qits";

    /** The npm scope this platform publishes under. */
    static final String INTERNAL_NPM_SCOPE = "@qits/";

    /** The image namespace this platform publishes under, registry host already stripped. */
    static final String INTERNAL_IMAGE_PREFIX = "qits/";

    /**
     * <b>Where each repository's files come from</b> — the one seam between this source and a
     * disk. Three forms: the wrapper's working trees ({@code order --wrapper}), the test fixture,
     * and each checkout at a ref through {@link PinnedVersions.Sources}, which is the form
     * {@code Boot} would use.
     */
    public interface Checkouts {

        /** The wrapper's own {@code .gitmodules}. */
        String gitmodules();

        /** One submodule's files. */
        RepositoryFiles files(GitmodulesParser.Submodule submodule);

        /**
         * The wrapper checkout on disk: each submodule's working tree at its {@code .gitmodules}
         * path, its own gitlinks from {@code git ls-tree HEAD}.
         */
        static Checkouts workingTree(Path wrapper) {
            return new Checkouts() {
                @Override
                public String gitmodules() {
                    return readFile(wrapper.resolve(GitmodulesParser.PATH));
                }

                @Override
                public RepositoryFiles files(GitmodulesParser.Submodule submodule) {
                    Path dir = wrapper.resolve(submodule.path());
                    return new DirectoryRepositoryFiles(dir, () -> headGitlinks(dir));
                }
            };
        }

        /**
         * The {@code src/test/resources/estate/} layout {@code refresh.sh} writes: the wrapper's
         * {@code .gitmodules} at the top, and per repository {@code <repo>/files/} plus a
         * {@code <repo>/gitlinks} of {@code <path> <sha>} lines.
         */
        static Checkouts fixture(Path estate) {
            return new Checkouts() {
                @Override
                public String gitmodules() {
                    return readFile(estate.resolve(GitmodulesParser.PATH));
                }

                @Override
                public RepositoryFiles files(GitmodulesParser.Submodule submodule) {
                    Path repo = estate.resolve(submodule.name());
                    return new DirectoryRepositoryFiles(repo.resolve("files"),
                            () -> gitlinksFile(repo.resolve("gitlinks")));
                }
            };
        }

        /**
         * Each checkout at {@code ref}, read the way the pin closure reads it. A submodule the
         * model has no name for cannot be addressed through {@code Sources} and stops here.
         */
        static Checkouts atRef(String wrapperGitmodules, PinnedVersions.Sources sources, String ref) {
            return new Checkouts() {
                @Override
                public String gitmodules() {
                    return wrapperGitmodules;
                }

                @Override
                public RepositoryFiles files(GitmodulesParser.Submodule submodule) {
                    String name = PlatformModel.sourceNameOf(submodule.name());
                    if (name == null) {
                        throw new IllegalStateException(submodule.name()
                                + " is a submodule of the wrapper and no repository of the model");
                    }
                    return new RefRepositoryFiles(sources, name, ref);
                }
            };
        }
    }

    private final List<String> repositories;
    private final Map<String, List<Pin>> consumes = new LinkedHashMap<>();
    private final Map<String, Set<Coordinate>> produces = new LinkedHashMap<>();
    private final Map<String, Deployment> deployments = new LinkedHashMap<>();

    private CheckoutProjectSource(Map<String, RepositoryFiles> files) {
        this.repositories = List.copyOf(files.keySet());
        for (Map.Entry<String, RepositoryFiles> entry : files.entrySet()) {
            String repository = entry.getKey();
            RepositoryFiles repo = entry.getValue();
            try {
                consumes.put(repository, pins(repo));
                produces.put(repository, coordinates(repo));
                repo.read(DeploymentDeclaration.PATH)
                        .map(DeploymentDeclaration::parse)
                        .ifPresent(deployment -> deployments.put(repository, deployment));
            } catch (RuntimeException e) {
                throw new IllegalStateException(repository + ": " + e.getMessage(), e);
            }
        }
    }

    /** The wrapper's submodules, less the read-only ones, read through {@code checkouts}. */
    public static CheckoutProjectSource read(Checkouts checkouts) {
        Set<String> excluded = new LinkedHashSet<>();
        for (String name : PlatformModel.READ_ONLY_REPOS) {
            excluded.add(PlatformModel.repo(name));
        }
        Map<String, RepositoryFiles> files = new TreeMap<>(Comparator.naturalOrder());
        for (GitmodulesParser.Submodule submodule : GitmodulesParser.parse(checkouts.gitmodules())) {
            if (!excluded.contains(submodule.name()) && !files.containsKey(submodule.name())) {
                files.put(submodule.name(), checkouts.files(submodule));
            }
        }
        return new CheckoutProjectSource(files);
    }

    @Override
    public List<String> repositories() {
        return repositories;
    }

    @Override
    public List<Pin> consumes(String repository) {
        return consumes.getOrDefault(repository, List.of());
    }

    @Override
    public Set<Coordinate> produces(String repository) {
        return produces.getOrDefault(repository, Set.of());
    }

    @Override
    public Optional<Deployment> deployment(String repository) {
        return Optional.ofNullable(deployments.get(repository));
    }

    /**
     * Whether a pin names something this platform publishes: GITLINK always (a submodule is one of
     * the estate's own repositories or nobody's), and the other three by qits-maintenance's name
     * rule.
     */
    static boolean internal(Ecosystem ecosystem, String name) {
        if (name == null) {
            return false;
        }
        return switch (ecosystem) {
            case GITLINK -> true;
            case MAVEN -> {
                int colon = name.indexOf(':');
                String group = colon < 0 ? name : name.substring(0, colon);
                yield group.equals(INTERNAL_MAVEN_GROUP)
                        || group.startsWith(INTERNAL_MAVEN_GROUP + ".");
            }
            case NPM -> name.startsWith(INTERNAL_NPM_SCOPE);
            case DOCKER -> name.startsWith(INTERNAL_IMAGE_PREFIX);
        };
    }

    private static List<Pin> pins(RepositoryFiles files) {
        List<Pin> pins = new ArrayList<>();
        for (ParsedPin parsed : ManifestReader.read(files)) {
            if (internal(parsed.ecosystem(), parsed.name())) {
                pins.add(new Pin(new Coordinate(parsed.ecosystem(), parsed.name()),
                        parsed.version(), parsed.manifestPath(), parsed.location()));
            }
        }
        pins.addAll(lockless(files));
        return List.copyOf(pins);
    }

    /** The two blocks of a root package.json a build installs. */
    private static final List<String> NPM_BLOCKS = List.of("dependencies", "devDependencies");

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * <b>The npm pins of a root {@code package.json} with no {@code package-lock.json} beside it</b>
     * — the two jslibs, which lock with pnpm. {@code ManifestReader} takes an npm VERSION from the
     * lock alone, so it pins nothing for them, and qits-ui-components-jslib's devDependency on
     * {@code @qits/angular} was no edge at all.
     * <p>
     * Ordering needs the edge, not the version; qits-maintenance does not read pnpm locks either,
     * so this stays the boot's. The version is the declared range verbatim — for these two an
     * exact calver — and the location is the block, as {@code NpmParser} writes it.
     */
    static List<Pin> lockless(RepositoryFiles files) {
        List<String> paths = files.paths();
        if (!paths.contains("package.json") || paths.contains(ManifestReader.PACKAGE_LOCK)) {
            return List.of();
        }
        JsonNode manifest;
        try {
            manifest = JSON.readTree(files.read("package.json").orElseThrow(() ->
                    new IllegalStateException("package.json is listed and could not be read")));
        } catch (IOException e) {
            throw new IllegalStateException("package.json is not JSON: " + e.getMessage(), e);
        }
        List<Pin> pins = new ArrayList<>();
        for (String block : NPM_BLOCKS) {
            JsonNode dependencies = manifest == null ? null : manifest.get(block);
            if (dependencies == null || !dependencies.isObject()) {
                continue;
            }
            for (Map.Entry<String, JsonNode> entry : dependencies.properties()) {
                if (entry.getValue().isTextual() && internal(Ecosystem.NPM, entry.getKey())) {
                    pins.add(new Pin(new Coordinate(Ecosystem.NPM, entry.getKey()),
                            entry.getValue().asText(), "package.json", block));
                }
            }
        }
        return pins;
    }

    private static Set<Coordinate> coordinates(RepositoryFiles files) {
        Optional<String> release = files.read(ReleaseDeclaration.PATH);
        if (release.isEmpty()) {
            return Set.of();
        }
        Set<Coordinate> coordinates = new LinkedHashSet<>();
        for (DeclaredArtifact artifact : ReleaseDeclaration.artifacts(release.get())) {
            Ecosystem ecosystem = switch (artifact.type()) {
                case "maven" -> Ecosystem.MAVEN;
                case "npm" -> Ecosystem.NPM;
                case "docker" -> Ecosystem.DOCKER;
                // daemon, docs: released, and pinned by no manifest.
                default -> null;
            };
            if (ecosystem != null) {
                coordinates.add(new Coordinate(ecosystem, artifact.name()));
            }
        }
        return Set.copyOf(coordinates);
    }

    private static String readFile(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + file, e);
        }
    }

    /** {@code <path> <sha>} lines; a missing file is a repository with no gitlinks. */
    static Map<String, String> gitlinksFile(Path file) {
        Map<String, String> gitlinks = new LinkedHashMap<>();
        if (!Files.isRegularFile(file)) {
            return gitlinks;
        }
        for (String line : readFile(file).split("\n")) {
            String[] parts = line.strip().split("\\s+");
            if (parts.length == 2) {
                gitlinks.put(parts[0], parts[1]);
            }
        }
        return gitlinks;
    }

    /**
     * Every gitlink a working tree's HEAD records, for the paths its {@code .gitmodules} declares:
     * {@code git ls-tree HEAD -- <paths>} prints {@code 160000 commit <sha>\t<path>} for each.
     */
    static Map<String, String> headGitlinks(Path repo) {
        Map<String, String> gitlinks = new LinkedHashMap<>();
        Path gitmodules = repo.resolve(GitmodulesParser.PATH);
        if (!Files.isRegularFile(gitmodules)) {
            return gitlinks;
        }
        List<String> command = new ArrayList<>(List.of("git", "-C", repo.toString(), "ls-tree",
                "HEAD", "--"));
        for (GitmodulesParser.Submodule module : GitmodulesParser.parse(readFile(gitmodules))) {
            command.add(module.path());
        }
        if (command.size() == 6) {
            return gitlinks;
        }
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(false).start();
            process.getOutputStream().close();
            String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!process.waitFor(30, TimeUnit.SECONDS) || process.exitValue() != 0) {
                process.destroyForcibly();
                throw new IllegalStateException("git ls-tree HEAD failed in " + repo);
            }
            for (String line : out.split("\n")) {
                int tab = line.indexOf('\t');
                String[] parts = (tab < 0 ? line : line.substring(0, tab)).strip().split("\\s+");
                if (tab > 0 && parts.length == 3 && "commit".equals(parts[1])) {
                    gitlinks.put(line.substring(tab + 1).strip(), parts[2]);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot run git in " + repo, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted reading gitlinks of " + repo, e);
        }
        return gitlinks;
    }
}
