package eu.wohlben.qits.cli.bootstrap.platform;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * <b>The wrapper's own {@code .gitmodules}, read: which repository sits where.</b>
 * <p>
 * This is the AUTHORITY on a repository's directory, and it replaces deriving one from the
 * repository's name. The name never said where a repository sits — it said where the archetype
 * layout USED to put it — and the component layout
 * ({@code components/<component>/<repo>}) breaks that derivation outright: a component is not
 * derivable from a name, {@code qits-spa-ci} belonging to {@code qits-ci}. The wrapper knows,
 * because the path is written down in the file the bootstrap is standing next to.
 * <p>
 * <b>An entry is looked up three ways, and each arm is a wrapper somebody really has.</b>
 * <ol>
 *   <li>By NAME. Every entry of the wrapper is meant to be named for the bare repository, which is
 *       what the platform catalog adopts by.
 *   <li>By the PATH's last segment. Modern git defaults a submodule's name to its full path, so an
 *       entry added without {@code --name} reads {@code [submodule "components/qits-ci/qits-ci"]}.
 *   <li>By the URL's last segment. A name and the repository it points at can DISAGREE — measured
 *       on the wrapper of 2026-08-30, where four entries are named for a rename that has not
 *       happened ({@code [submodule "qits-platform-events"] url = ../qits-events.git}). The url is
 *       the one thing on an entry that cannot be aspirational: it is what a clone resolves.
 * </ol>
 * <p>
 * The parse is deliberately small: git's config syntax allows more than this file uses, and what is
 * unreadable answers "nothing declared" rather than throwing — the callers all have a better
 * message for a wrapper they cannot read.
 */
public final class GitModules {

    /** The file, at the root of a wrapper checkout. */
    public static final String FILE = ".gitmodules";

    private static final Pattern SECTION = Pattern.compile("\\[submodule\\s+\"(.*)\"]");
    private static final Pattern PATH = Pattern.compile("path\\s*=\\s*(.+)");
    private static final Pattern URL = Pattern.compile("url\\s*=\\s*(.+)");

    /** One declared submodule: what it is called, where it sits, and what it points at. */
    public record Entry(String name, String path, String url) {
    }

    /** The entries, in the file's own order. */
    private final List<Entry> entries;

    private GitModules(List<Entry> entries) {
        this.entries = List.copyOf(entries);
    }

    /** Nothing declared: no file, an unreadable one, or a wrapper this machine has not cloned yet. */
    public static GitModules none() {
        return new GitModules(List.of());
    }

    /** The file at the root of this wrapper, or {@link #none()} when there is none to read. */
    public static GitModules of(Path wrapperDir) {
        if (wrapperDir == null) {
            return none();
        }
        Path file = wrapperDir.resolve(FILE);
        if (!Files.isRegularFile(file)) {
            return none();
        }
        try {
            return parse(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException unreadable) {
            return none();
        }
    }

    /** The same, from the file's text. */
    public static GitModules parse(String text) {
        Map<String, String> paths = new LinkedHashMap<>();
        Map<String, String> urls = new LinkedHashMap<>();
        String section = null;
        for (String raw : text.split("\\R")) {
            String line = raw.trim();
            if (line.startsWith("#") || line.startsWith(";")) {
                continue;
            }
            Matcher header = SECTION.matcher(line);
            if (header.matches()) {
                section = header.group(1);
                continue;
            }
            if (section == null) {
                continue;
            }
            Matcher path = PATH.matcher(line);
            if (path.matches()) {
                paths.put(section, path.group(1).trim());
                continue;
            }
            Matcher url = URL.matcher(line);
            if (url.matches()) {
                urls.put(section, url.group(1).trim());
            }
        }
        List<Entry> entries = new ArrayList<>();
        // A section with no `path` declares no submodule, whatever else it carries.
        paths.forEach((name, path) -> entries.add(new Entry(name, path, urls.get(name))));
        return new GitModules(entries);
    }

    /** True when the wrapper declared nothing, which is what an absent file answers. */
    public boolean isEmpty() {
        return entries.isEmpty();
    }

    /** Every declared path, in the file's order. */
    public List<String> declaredPaths() {
        return entries.stream().map(Entry::path).toList();
    }

    /**
     * Where the wrapper puts one repository, by its bare name ({@code qits-ci}). Empty when the
     * wrapper does not declare it — which is a real answer: not every repository this bootstrap
     * builds has to be a submodule of this wrapper.
     */
    public Optional<String> path(String repo) {
        return entry(repo).map(Entry::path);
    }

    /** The whole entry, by the three arms the class comment describes, in that order. */
    public Optional<Entry> entry(String repo) {
        return first(entry -> repo.equals(entry.name()))
                .or(() -> first(entry -> lastSegment(entry.path()).equals(repo)))
                .or(() -> first(entry -> repo.equals(repository(entry.url()))));
    }

    /** Whether the wrapper declares this repository at all. */
    public boolean declares(String repo) {
        return entry(repo).isPresent();
    }

    /** Whether the wrapper declares any of these repositories — the wrapper's own marker test. */
    public boolean declaresAny(Collection<String> repos) {
        return repos.stream().anyMatch(this::declares);
    }

    private Optional<Entry> first(Predicate<Entry> match) {
        return entries.stream().filter(match).findFirst();
    }

    /** The repository a submodule url names: its last segment, without {@code .git}. */
    private static String repository(String url) {
        if (url == null) {
            return null;
        }
        String tail = lastSegment(url.endsWith("/") ? url.substring(0, url.length() - 1) : url);
        return tail.endsWith(".git") ? tail.substring(0, tail.length() - ".git".length()) : tail;
    }

    private static String lastSegment(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
    }
}
