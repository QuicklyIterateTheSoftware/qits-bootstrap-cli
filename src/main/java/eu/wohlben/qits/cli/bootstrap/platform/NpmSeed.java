package eu.wohlben.qits.cli.bootstrap.platform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import eu.wohlben.qits.cli.bootstrap.api.Json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * <b>The seed's npm half: every {@code @qits} package a seed frontend pins, built from source into a
 * volume, and each frontend built against those tarballs</b> (qits-889).
 * <p>
 * A seed service image carries its real client bundle. The bundle is the service's gitlinked webui
 * built with {@code npm ci && npm run build} — what each service Dockerfile's header asks for — and
 * its committed lockfile names two platform hosts: {@code @qits/*} on {@code registry.qits.<domain>}
 * and everything else through the npmjs cache on {@code mirror.qits.<domain>}. Phase 1 reaches
 * neither. So:
 * <ul>
 *   <li><b>Every {@code @qits} package is produced here.</b> A jslib ({@code @qits/ui-components},
 *       {@code @qits/angular}) is built at the release whose version the lockfile resolves —
 *       {@code pnpm install --frozen-lockfile && pnpm build}, then {@code npm pack} of the one
 *       {@code dist/*} that carries a manifest, as the platform's {@code npm-library} release does.
 *       A {@code @qits/<app>-golden-masters} package is the provider's {@code golden-masters/} at
 *       its release, laid out as {@code ContractPackager.npm} lays it out. The tarballs go into the
 *       {@link #VOLUME} volume, one per {@code name@version}.
 *   <li><b>The lockfile is retargeted in a SCRATCH copy, never in a checkout.</b> Each {@code @qits}
 *       entry's {@code resolved} becomes the local tarball and its {@code integrity} that tarball's
 *       digest; each npmjs-cache {@code resolved} becomes the same path on
 *       {@link #UPSTREAM_REGISTRY}, where the integrity the lock already holds still matches. Not
 *       npm {@code overrides}: npm refuses an override of a DIRECT dependency unless package.json's
 *       own spec is changed to match (EOVERRIDE), every {@code @qits} pin here is direct, and a
 *       changed spec is a lock {@code npm ci} no longer accepts. Retargeting the lock leaves
 *       package.json alone even in the copy, so {@code npm ci} — and for a jslib
 *       {@code pnpm install --frozen-lockfile} — still holds the build to the committed versions.
 *   <li><b>The committed {@code .npmrc} keeps everything but its registries.</b> The scope is
 *       pointed at {@link #REFUSED_SCOPE_REGISTRY}, an address nothing answers at: a {@code @qits}
 *       package this boot did not build must fail the install, not resolve from npmjs, where the
 *       scope is not ours. {@link #refuseServers} is the last word on what is written.
 * </ul>
 */
public final class NpmSeed {

    /** The tarballs, one per {@code name@version}, and the built bundles under {@code bundles/}. */
    public static final String VOLUME = "qits-npm-seed";

    /** Where a container sees {@link #VOLUME}. */
    public static final String MOUNT = "/qits-npm";

    /**
     * <b>The image every npm build of the seed runs in.</b> Debian rather than alpine: every frontend
     * and jslib of the estate is built today on qits/build-images/ci-slot, which is Debian with Node
     * 24 (qits-build-images-oci {@code ci-slot/Dockerfile}), so the native prebuilds their locks
     * resolve are the glibc ones a release run proves. Upstream, like every phase 1 base.
     */
    public static final String NODE_IMAGE = "node:24-bookworm-slim";

    /** Where every unscoped package comes from in phase 1. */
    public static final String UPSTREAM_REGISTRY = "https://registry.npmjs.org/";

    /**
     * <b>What the {@code @qits} scope resolves against in a seed build: nothing.</b> The discard
     * port on the loopback refuses the connection at once, so a scoped package missing from the
     * local tarballs fails the install by name instead of being looked up on npmjs.
     */
    static final String REFUSED_SCOPE_REGISTRY = "http://127.0.0.1:9/";

    /** Inside a scratch tree, where its {@code @qits} tarballs are copied — relative in the lock. */
    static final String LOCAL_DIR = ".qits-npm";

    /** The platform hosts no phase 1 npm configuration may name. */
    private static final List<String> PLATFORM_HOSTS = List.of(".qits.");

    private static final Pattern NAME = Pattern.compile("@qits/[a-z0-9][a-z0-9._-]*");
    private static final Pattern VERSION = Pattern.compile("[0-9][0-9A-Za-z.+-]*");
    private static final Pattern GOLDEN_MASTERS = Pattern.compile("@qits/([a-z0-9][a-z0-9.-]*)-golden-masters");

    /** A {@code packages:} key of a pnpm lock that names a {@code @qits} package: name, version. */
    private static final Pattern PNPM_KEY = Pattern.compile("^ {2}'?(@qits/[^@'\\s]+)@([^'(\\s:]+)'?:\\s*$");

    private static final ObjectMapper JSON = new ObjectMapper();

    private NpmSeed() {
    }

    /** One {@code @qits} package at one version. Both halves are checked: they reach a shell. */
    public record Pkg(String name, String version) implements Comparable<Pkg> {

        public Pkg {
            if (!NAME.matcher(name).matches() || !VERSION.matcher(version).matches()) {
                throw new IllegalArgumentException("not a @qits package this boot can build: "
                        + name + "@" + version);
            }
        }

        /** {@code @qits/angular@2026.1020.213938}. */
        public String id() {
            return name + "@" + version;
        }

        /** The tarball's file name — npm pack's own: {@code qits-angular-2026.1020.213938.tgz}. */
        public String file() {
            return name.substring(1).replace('/', '-') + "-" + version + ".tgz";
        }

        @Override
        public int compareTo(Pkg other) {
            return id().compareTo(other.id());
        }
    }

    /** How a package is produced. */
    public enum Kind { JSLIB, GOLDEN_MASTERS }

    /**
     * The model name of the repository whose release a package is built from, or null for a
     * {@code @qits} name this boot does not know how to make. A jslib's is its release publisher
     * ({@link PlatformModel#releasePackages}); a golden-masters package's is the provider's, by the
     * platform's coordinate rule ({@code CiContracts.coordinate}: the application with one
     * {@code qits-} removed, then {@code -golden-masters}) — the same model name the maven coordinate
     * {@code qits-<app>-golden-masters} names.
     */
    public static String producer(String name) {
        String jslib = PinnedVersions.NPM_PRODUCERS.get(name);
        if (jslib != null) {
            return jslib;
        }
        Matcher golden = GOLDEN_MASTERS.matcher(name);
        if (golden.matches() && PlatformModel.sourceRepos().contains(golden.group(1))) {
            return golden.group(1);
        }
        return null;
    }

    /** The jslibs a release publishes, by package name, sorted. */
    public static List<String> jslibs() {
        return PinnedVersions.NPM_PRODUCERS.keySet().stream().sorted().toList();
    }

    public static Kind kind(String name) {
        return PinnedVersions.NPM_PRODUCERS.containsKey(name) ? Kind.JSLIB : Kind.GOLDEN_MASTERS;
    }

    /**
     * <b>Every {@code @qits} package a {@code package-lock.json} installs</b>, at any depth: what
     * {@code npm ci} would fetch from the scope's registry. A lockfile older than v2 has no
     * {@code packages} map and is refused — {@code npm ci} on npm 7+ reads nothing else.
     */
    public static Set<Pkg> packageLockPins(String lock, String origin) {
        JsonNode root = Json.parse(lock);
        if (root == null || !root.path("packages").isObject()) {
            throw new IllegalStateException(origin + " has no `packages` map (lockfileVersion "
                    + (root == null ? "?" : root.path("lockfileVersion").asText("?"))
                    + "), so this boot cannot read what it installs");
        }
        Set<Pkg> pins = new LinkedHashSet<>();
        root.path("packages").properties().forEach(entry -> {
            String name = installedName(entry.getKey());
            if (name.startsWith("@qits/") && !entry.getValue().path("link").asBoolean(false)) {
                pins.add(new Pkg(name, entry.getValue().path("version").asText("")));
            }
        });
        return pins;
    }

    /** {@code node_modules/a/node_modules/@qits/b} → {@code @qits/b}. */
    static String installedName(String key) {
        int at = key.lastIndexOf("node_modules/");
        return at < 0 ? key : key.substring(at + "node_modules/".length());
    }

    /** <b>Every {@code @qits} package a {@code pnpm-lock.yaml} installs</b> — its {@code packages:} keys. */
    public static Set<Pkg> pnpmLockPins(String lock) {
        Set<Pkg> pins = new LinkedHashSet<>();
        String section = "";
        for (String line : lock.split("\n")) {
            if (!line.isEmpty() && !Character.isWhitespace(line.charAt(0)) && line.contains(":")) {
                section = line.substring(0, line.indexOf(':'));
                continue;
            }
            Matcher key = PNPM_KEY.matcher(line);
            if ("packages".equals(section) && key.matches()) {
                pins.add(new Pkg(key.group(1), key.group(2)));
            }
        }
        return pins;
    }

    /**
     * <b>The order the packages are built in</b>: golden masters first — they depend on nothing —
     * then each jslib after every {@code @qits} package its own lock installs. A cycle cannot be
     * built from source at all, and says which packages it runs through.
     *
     * @param needs what each package's own lock installs; golden masters map to nothing
     */
    public static List<Pkg> buildOrder(Map<Pkg, Set<Pkg>> needs) {
        List<Pkg> order = new ArrayList<>();
        needs.keySet().stream().sorted().filter(pkg -> kind(pkg.name()) == Kind.GOLDEN_MASTERS)
                .forEach(order::add);
        Set<Pkg> visiting = new LinkedHashSet<>();
        for (Pkg pkg : needs.keySet().stream().sorted().toList()) {
            visit(pkg, needs, order, visiting);
        }
        return order;
    }

    private static void visit(Pkg pkg, Map<Pkg, Set<Pkg>> needs, List<Pkg> order,
                              Set<Pkg> visiting) {
        if (order.contains(pkg)) {
            return;
        }
        if (!visiting.add(pkg)) {
            throw new IllegalStateException("the @qits packages pin each other in a circle ("
                    + String.join(" -> ", visiting.stream().map(Pkg::id).toList()) + " -> "
                    + pkg.id() + "), so none of them can be built from source first");
        }
        for (Pkg dependency : needs.getOrDefault(pkg, Set.of()).stream().sorted().toList()) {
            visit(dependency, needs, order, visiting);
        }
        visiting.remove(pkg);
        order.add(pkg);
    }

    /**
     * <b>The {@code .npmrc} a scratch build runs with</b>: the committed one without its comments,
     * registries and credentials, then the two lines of phase 1. Whatever else a repository set
     * (a peer-deps mode, an engine check) stays, because the lock was written under it.
     */
    public static String scratchNpmrc(String committed) {
        StringBuilder out = new StringBuilder();
        if (committed != null) {
            for (String line : committed.split("\n")) {
                String entry = line.strip();
                if (entry.isEmpty() || entry.startsWith("#") || entry.startsWith(";")
                        || entry.matches("(@[^:=\\s]+:)?registry\\s*=.*")
                        || entry.startsWith("//") || entry.contains("_auth")) {
                    continue;
                }
                out.append(entry).append('\n');
            }
        }
        out.append("registry=").append(UPSTREAM_REGISTRY).append('\n')
                .append("@qits:registry=").append(REFUSED_SCOPE_REGISTRY).append('\n');
        return out.toString();
    }

    /**
     * <b>The last word on a phase 1 npm configuration</b>, {@link SeedDockerfile#refuseServers}'s
     * twin: no line of it names a platform host. A build that may not reach the platform must not
     * be handed an address of it to try.
     *
     * @return the configuration, unchanged
     */
    public static String refuseServers(String npmrc, String origin) {
        for (String line : npmrc.split("\n")) {
            for (String host : PLATFORM_HOSTS) {
                if (line.contains(host)) {
                    throw new IllegalStateException("the npm configuration for " + origin
                            + " still names a platform host (`" + line.strip() + "`). Phase 1 "
                            + "builds from the " + VOLUME + " tarballs and " + UPSTREAM_REGISTRY
                            + " only; teach NpmSeed.scratchNpmrc about this line");
                }
            }
        }
        return npmrc;
    }

    /**
     * {@code ContractPackager.npm}'s manifest: name, version, description, {@code UNLICENSED}, and
     * {@code files: ["golden-masters/"]}.
     */
    public static String goldenMastersManifest(Pkg pkg, String application) {
        ObjectNode manifest = JSON.createObjectNode();
        manifest.put("name", pkg.name());
        manifest.put("version", pkg.version());
        manifest.put("description", application + " golden masters (contracts)");
        manifest.put("license", "UNLICENSED");
        manifest.putArray("files").add("golden-masters/");
        try {
            return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(manifest);
        } catch (Exception e) {
            throw new IllegalStateException("writing package.json failed", e);
        }
    }

    /**
     * Where one package's source archive is copied into the package container — under
     * {@code /tmp}, because {@code docker cp} creates no missing parent directory.
     */
    public static String archive(Pkg pkg) {
        return "/tmp/" + pkg.file().replaceAll("\\.tgz$", ".tar");
    }

    /**
     * One package to build: what it is, the {@code @qits} packages its own lock installs (built
     * before it), its scratch {@code .npmrc}, and for golden masters the manifest.
     */
    public record Build(Pkg pkg, Set<Pkg> needs, String npmrc, String manifest) {
    }

    /** {@code {"<id>": ".qits-npm/<file>"}} — what the lock rewrites read. JSON, single-quotable. */
    static String localMap(Set<Pkg> pins) {
        Map<String, String> map = new LinkedHashMap<>();
        pins.stream().sorted().forEach(pkg -> map.put(pkg.id(), LOCAL_DIR + "/" + pkg.file()));
        try {
            return JSON.writeValueAsString(map);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** The environment every npm build here runs under: non-interactive, no telemetry, no nagging. */
    private static final String ENVIRONMENT = """
            export CI=true NG_CLI_ANALYTICS=false npm_config_update_notifier=false \\
              npm_config_fund=false npm_config_audit=false
            """;

    /** A heredoc that writes one file, with nothing expanded inside it. */
    private static String write(String path, String content) {
        if (content.contains("\nQITS_EOF\n")) {
            throw new IllegalArgumentException(path + " holds the heredoc delimiter");
        }
        return "cat > " + path + " <<'QITS_EOF'\n" + content + (content.endsWith("\n") ? "" : "\n")
                + "QITS_EOF\n";
    }

    /** The {@code @qits} tarballs a scratch tree installs, copied in beside its lock. */
    private static String copyLocal(Set<Pkg> pins) {
        StringBuilder script = new StringBuilder("mkdir -p " + LOCAL_DIR + "\n");
        for (Pkg pin : pins.stream().sorted().toList()) {
            script.append("cp ").append(MOUNT).append('/').append(pin.file()).append(' ')
                    .append(LOCAL_DIR).append("/\n");
        }
        return script.toString();
    }

    /**
     * <b>The package container's script</b>: one block per package, in {@link #buildOrder}, each
     * writing its tarball into {@link #VOLUME} under a temporary name first and then renaming it,
     * so a build that dies leaves nothing a rerun would take for finished.
     */
    public static String packagesScript(List<Build> builds) {
        StringBuilder script = new StringBuilder("set -eu\n").append(ENVIRONMENT)
                .append(write("/tmp/pnpm-lock-rewrite.mjs", PNPM_LOCK_REWRITE));
        for (Build build : builds) {
            Pkg pkg = build.pkg();
            script.append("echo '== ").append(pkg.id()).append("'\n")
                    .append("rm -rf /build /tmp/packed && mkdir -p /build /tmp/packed\n");
            if (kind(pkg.name()) == Kind.GOLDEN_MASTERS) {
                script.append("mkdir -p /build/package && tar -xf ").append(archive(pkg))
                        .append(" -C /build/package\n")
                        .append("test -d /build/package/golden-masters || { echo '").append(pkg.id())
                        .append(": the release has no golden-masters/' >&2; exit 1; }\n")
                        .append(write("/build/package/package.json", build.manifest()))
                        // ContractPackager's entries: package/package.json and the files of the
                        // tree under package/golden-masters/, no directory entries.
                        .append("(cd /build && find package -type f | LC_ALL=C sort > /tmp/files"
                                + " && tar --owner=0 --group=0 --numeric-owner -czf /tmp/packed/")
                        .append(pkg.file()).append(" -T /tmp/files)\n");
            } else {
                script.append("tar -xf ").append(archive(pkg)).append(" -C /build\n")
                        .append("cd /build\n")
                        .append(write(".npmrc", build.npmrc()))
                        .append(copyLocal(build.needs()))
                        .append("node /tmp/pnpm-lock-rewrite.mjs pnpm-lock.yaml '")
                        .append(localMap(build.needs())).append("'\n")
                        // The version the repository's packageManager field pins, from npmjs.
                        .append("pm=$(node -p \"require('./package.json').packageManager || ''\")\n")
                        .append("case \"$pm\" in pnpm@*) ;; *) echo '").append(pkg.id())
                        .append(": package.json names no pnpm in packageManager' >&2; exit 1;; esac\n")
                        .append("pm=${pm#pnpm@}; pm=${pm%%+*}\n")
                        .append("[ \"$(pnpm --version 2>/dev/null || true)\" = \"$pm\" ]"
                                + " || npm install -g \"pnpm@$pm\" --registry=")
                        .append(UPSTREAM_REGISTRY).append('\n')
                        .append("pnpm install --frozen-lockfile --store-dir /tmp/pnpm-store\n")
                        .append("pnpm build\n")
                        // The npm-library release's rule: exactly one dist/* carries a manifest,
                        // and it says the name and the version the tag is.
                        .append("manifests=$(find dist -mindepth 2 -maxdepth 2 -name package.json)\n")
                        .append("[ \"$(printf '%s\\n' \"$manifests\" | grep -c .)\" = 1 ] || { echo '")
                        .append(pkg.id()).append(": expected exactly one dist/*/package.json, found:'"
                                + " $manifests >&2; exit 1; }\n")
                        .append("dist=${manifests%/package.json}\n")
                        .append("built=$(node -p \"const m = require('./' + process.argv[1] +"
                                + " '/package.json'); m.name + '@' + m.version\" \"$dist\")\n")
                        .append("[ \"$built\" = '").append(pkg.id()).append("' ] || { echo \"")
                        .append(pkg.id()).append(": the release built $built\" >&2; exit 1; }\n")
                        .append("(cd \"$dist\" && npm pack --ignore-scripts --pack-destination"
                                + " /tmp/packed)\n")
                        .append("test -f /tmp/packed/").append(pkg.file()).append('\n')
                        .append("cd /\n");
            }
            script.append("mv /tmp/packed/").append(pkg.file()).append(' ').append(MOUNT)
                    .append('/').append(pkg.file()).append(".part && mv ").append(MOUNT).append('/')
                    .append(pkg.file()).append(".part ").append(MOUNT).append('/')
                    .append(pkg.file()).append('\n');
        }
        return script.toString();
    }

    /** Where a frontend's committed tree is copied into its build container. */
    public static final String FRONTEND_ARCHIVE = "/tmp/webui.tar";

    /** Where the built bundle waits in the container to be copied out. */
    public static final String FRONTEND_OUT = "/out";

    /** A bundle already built for one webui commit — the inputs are all pinned, so it is reused. */
    public static String bundle(String key) {
        return MOUNT + "/bundles/" + key + ".tgz";
    }

    /**
     * <b>A frontend container's script</b>: unpack the committed tree, put the phase 1
     * {@code .npmrc} and the local {@code @qits} tarballs in, retarget the lock, {@code npm ci},
     * {@code npm run build}, and leave {@code <dist>} in {@link #FRONTEND_OUT}. A bundle built for
     * the same webui commit before is unpacked instead, because every input of it is pinned: the
     * commit, the tarballs built from release tags, and the npmjs tarballs the lock's integrity
     * holds to.
     *
     * @param key  the webui commit's cache key
     * @param dist the bundle's path inside the webui, {@code dist/<project>/browser}
     * @param pins the {@code @qits} packages its lock installs
     */
    public static String frontendScript(String key, String dist, Set<Pkg> pins, String npmrc) {
        String cached = bundle(key);
        return "set -eu\n" + ENVIRONMENT
                + "mkdir -p /work " + FRONTEND_OUT + " && tar -xf " + FRONTEND_ARCHIVE
                + " -C /work && cd /work\n"
                + "if [ -f " + cached + " ]; then\n"
                + "  echo 'the bundle of " + key + " is already built'\n"
                + "  tar -xzf " + cached + " -C " + FRONTEND_OUT + "\n"
                + "  exit 0\n"
                + "fi\n"
                + "test -f package.json && test -f package-lock.json || { echo 'the webui has no"
                + " package.json and package-lock.json to build from' >&2; exit 1; }\n"
                + "node -e \"process.exit((require('./package.json').scripts || {}).build ? 0 : 1)\""
                + " || { echo 'package.json has no build script' >&2; exit 1; }\n"
                + write(".npmrc", npmrc)
                + copyLocal(pins)
                + write("/tmp/lock-rewrite.mjs", LOCK_REWRITE)
                + "node /tmp/lock-rewrite.mjs package-lock.json '" + localMap(pins) + "'\n"
                + "npm ci\n"
                + "npm run build\n"
                + "test -f " + dist + "/index.html || { echo 'the build wrote no " + dist
                + "/index.html, which is where the service Dockerfile reads the bundle' >&2;"
                + " exit 1; }\n"
                + "mkdir -p " + MOUNT + "/bundles\n"
                + "tar -czf " + cached + ".part -C " + dist + " . && mv " + cached + ".part "
                + cached + "\n"
                + "cp -a " + dist + "/. " + FRONTEND_OUT + "/\n";
    }

    /**
     * <b>{@code package-lock.json}, retargeted.</b> Every {@code @qits} entry resolves to its local
     * tarball with that tarball's digest; every npmjs-cache entry to the same path on npmjs, whose
     * bytes the lock's integrity already names. Anything else naming a platform host stops the
     * build with the entry named, and so does a {@code @qits} pin with no local tarball. v2's
     * legacy {@code dependencies} mirror is dropped: npm 7+ reads {@code packages}.
     */
    static final String LOCK_REWRITE = """
            import { readFileSync, writeFileSync } from 'node:fs';
            import { createHash } from 'node:crypto';
            const [file, mapJson] = process.argv.slice(2);
            const local = JSON.parse(mapJson);
            const fail = (message) => { console.error(file + ': ' + message); process.exit(1); };
            const digest = (path) => 'sha512-' + createHash('sha512').update(readFileSync(path)).digest('base64');
            const lock = JSON.parse(readFileSync(file, 'utf8'));
            if (!(lock.lockfileVersion >= 2) || typeof lock.packages !== 'object') {
              fail('lockfileVersion ' + lock.lockfileVersion + ' has no packages map for npm ci to read');
            }
            delete lock.dependencies;
            const upstream = /^https?:\\/\\/mirror\\.qits\\.[^/]+\\/npm\\/npmjs\\//;
            const platform = /\\.qits\\./;
            for (const [key, entry] of Object.entries(lock.packages)) {
              if (key === '' || entry.link) continue;
              const name = key.slice(key.lastIndexOf('node_modules/') + 'node_modules/'.length);
              if (name.startsWith('@qits/')) {
                const tarball = local[name + '@' + entry.version];
                if (!tarball) fail(key + ' pins ' + name + '@' + entry.version + ', which this boot did not build');
                entry.resolved = 'file:' + tarball;
                entry.integrity = digest(tarball);
                continue;
              }
              if (typeof entry.resolved === 'string' && platform.test(entry.resolved)) {
                const retargeted = entry.resolved.replace(upstream, 'https://registry.npmjs.org/');
                if (retargeted === entry.resolved) fail(key + ' resolves from ' + entry.resolved + ', which is no npmjs cache path');
                entry.resolved = retargeted;
              }
            }
            for (const [key, entry] of Object.entries(lock.packages)) {
              if (typeof entry.resolved === 'string' && platform.test(entry.resolved)) {
                fail(key + ' still resolves from ' + entry.resolved);
              }
            }
            writeFileSync(file, JSON.stringify(lock, null, 2) + '\\n');
            """;

    /**
     * <b>{@code pnpm-lock.yaml}, retargeted</b>, line by line because the container has no YAML
     * library and the lock's shape is pnpm's own: under {@code packages:}, each {@code @qits} key's
     * {@code resolution: {integrity: …}} line becomes the local tarball — pnpm fetches a
     * {@code tarball: file:} resolution from disk, relative to the lock — with its digest. The
     * importers' specifiers are untouched, so {@code --frozen-lockfile} still holds. An entry that
     * is not in that shape, a pin with no tarball, or a platform host anywhere in the result stops
     * the build.
     */
    static final String PNPM_LOCK_REWRITE = """
            import { readFileSync, writeFileSync } from 'node:fs';
            import { createHash } from 'node:crypto';
            const [file, mapJson] = process.argv.slice(2);
            const local = JSON.parse(mapJson);
            const fail = (message) => { console.error(file + ': ' + message); process.exit(1); };
            const digest = (path) => 'sha512-' + createHash('sha512').update(readFileSync(path)).digest('base64');
            const lines = readFileSync(file, 'utf8').split('\\n');
            const key = /^  '?(@qits\\/[^@'\\s]+)@([^'(\\s:]+)'?:\\s*$/;
            const resolution = /^    resolution: \\{integrity: [^,}]+\\}\\s*$/;
            let section = '';
            for (let i = 0; i < lines.length; i++) {
              const top = /^([^\\s][^:]*):/.exec(lines[i]);
              if (top) { section = top[1]; continue; }
              const match = section === 'packages' ? key.exec(lines[i]) : null;
              if (!match) continue;
              const id = match[1] + '@' + match[2];
              const tarball = local[id];
              if (!tarball) fail(id + ' is installed, and this boot did not build it');
              if (!resolution.test(lines[i + 1] || '')) fail(id + ' has no plain integrity resolution: ' + lines[i + 1]);
              lines[i + 1] = '    resolution: {integrity: ' + digest(tarball) + ', tarball: file:' + tarball + '}';
            }
            const out = lines.join('\\n');
            if (/\\.qits\\./.test(out)) fail('still names a platform host');
            writeFileSync(file, out);
            """;

    /**
     * ONE throwaway container answers which tarballs the volume holds: a {@code test -f} each, the
     * held ones echoed.
     */
    public static List<String> probeCommand(List<String> files) {
        StringBuilder script = new StringBuilder();
        for (String file : files) {
            script.append("[ -f '").append(MOUNT).append('/').append(file).append("' ] && echo '")
                    .append(file).append("'\n");
        }
        script.append("exit 0\n");
        return List.of("docker", "run", "--rm", "-v", VOLUME + ":" + MOUNT + ":ro",
                "--entrypoint", "sh", NODE_IMAGE, "-c", script.toString());
    }
}
