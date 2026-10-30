package eu.wohlben.qits.cli.bootstrap.platform;

import eu.wohlben.qits.cli.bootstrap.api.Json;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The seed's npm half (qits-889): which {@code @qits} packages a seed client pins, where each is
 * built from, in which order, and the scripts that build them and the clients — with nothing of
 * them reaching a platform host.
 */
class NpmSeedTest {

    private static final NpmSeed.Pkg ANGULAR = new NpmSeed.Pkg("@qits/angular", "2026.1020.200308");
    private static final NpmSeed.Pkg UI = new NpmSeed.Pkg("@qits/ui-components", "2026.1021.185351");
    private static final NpmSeed.Pkg CI_GM =
            new NpmSeed.Pkg("@qits/ci-golden-masters", "2026.1026.163551");
    private static final NpmSeed.Pkg EDGE_GM =
            new NpmSeed.Pkg("@qits/edge-golden-masters", "2026.1010.182320");

    /** A frontend lock's shape, trimmed: the platform's two hosts in every {@code resolved}. */
    private static final String PACKAGE_LOCK = """
            {
              "name": "qits-spa-ci",
              "lockfileVersion": 3,
              "packages": {
                "": {"name": "qits-spa-ci", "dependencies": {"@qits/ui-components": "^2026.1021.185351"}},
                "node_modules/@qits/ui-components": {
                  "version": "2026.1021.185351",
                  "resolved": "https://registry.qits.wohlben.eu/artifacts/npm/npm/@qits/ui-components/-/ui-components-2026.1021.185351.tgz",
                  "integrity": "sha512-svx6"
                },
                "node_modules/@qits/ci-golden-masters": {
                  "version": "2026.1026.163551",
                  "resolved": "https://registry.qits.wohlben.eu/artifacts/npm/npm/@qits/ci-golden-masters/-/ci-golden-masters-2026.1026.163551.tgz"
                },
                "node_modules/vitest/node_modules/@qits/edge-golden-masters": {
                  "version": "2026.1010.182320"
                },
                "node_modules/@qits/linked": {"version": "0.0.0", "link": true},
                "node_modules/tslib": {
                  "version": "2.8.1",
                  "resolved": "https://mirror.qits.wohlben.eu/npm/npmjs/tslib/-/tslib-2.8.1.tgz"
                }
              }
            }
            """;

    /** A jslib lock's shape: importers, packages and snapshots all name the package. */
    private static final String PNPM_LOCK = """
            lockfileVersion: '9.0'

            importers:

              .:
                devDependencies:
                  '@qits/angular':
                    specifier: 2026.1020.200308
                    version: 2026.1020.200308(@angular/common@21.2.19)
                  '@qits/ci-golden-masters':
                    specifier: 2026.1018.180637
                    version: 2026.1018.180637

            packages:

              '@angular/core@21.2.19':
                resolution: {integrity: sha512-aaa}

              '@qits/angular@2026.1020.200308':
                resolution: {integrity: sha512-ja1h}
                hasBin: true

              '@qits/ci-golden-masters@2026.1018.180637':
                resolution: {integrity: sha512-8MT+}

            snapshots:

              '@qits/angular@2026.1020.200308(@angular/common@21.2.19)':
                dependencies:
                  tslib: 2.8.1

              '@qits/projects-golden-masters@2026.1019.190811': {}
            """;

    @Test
    void aPackageIsNamedAndFiledAsNpmPackItOnlyInTheQitsScope() {
        assertThat(UI.id()).isEqualTo("@qits/ui-components@2026.1021.185351");
        assertThat(UI.file()).isEqualTo("qits-ui-components-2026.1021.185351.tgz");
        assertThat(NpmSeed.archive(UI)).isEqualTo("/tmp/qits-ui-components-2026.1021.185351.tar");
        // Both halves reach a shell, so a name or version of any other shape is refused outright.
        assertThatThrownBy(() -> new NpmSeed.Pkg("left-pad", "1.0.0"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new NpmSeed.Pkg("@qits/x", "1.0.0'; rm -rf /"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * <b>Each package is built from the repository whose release publishes it</b>: a jslib from its
     * release publisher, a golden-masters package from the provider the coordinate rule names —
     * the same model name the maven {@code qits-<app>-golden-masters} names.
     */
    @Test
    void eachPackageIsBuiltFromTheRepositoryThatPublishesIt() {
        assertThat(NpmSeed.producer("@qits/ui-components")).isEqualTo("spa-ui-components");
        assertThat(NpmSeed.producer("@qits/angular")).isEqualTo("integrations-angular");
        assertThat(NpmSeed.producer("@qits/ci-golden-masters")).isEqualTo("ci");
        assertThat(NpmSeed.producer("@qits/edge-golden-masters")).isEqualTo("edge");
        assertThat(NpmSeed.producer("@qits/system-golden-masters")).isEqualTo("system");
        assertThat(NpmSeed.kind("@qits/angular")).isEqualTo(NpmSeed.Kind.JSLIB);
        assertThat(NpmSeed.kind("@qits/ci-golden-masters")).isEqualTo(NpmSeed.Kind.GOLDEN_MASTERS);
        // Nothing this boot clones publishes these.
        assertThat(NpmSeed.producer("@qits/nowhere-golden-masters")).isNull();
        assertThat(NpmSeed.producer("@qits/something")).isNull();
        assertThat(NpmSeed.jslibs()).containsExactly("@qits/angular", "@qits/ui-components");
    }

    /** Every {@code @qits} entry {@code npm ci} installs, at any depth; links install nothing. */
    @Test
    void aPackageLockPinsEveryScopedEntryAtAnyDepth() {
        assertThat(NpmSeed.packageLockPins(PACKAGE_LOCK, "qits-ci-service/service/src/main/webui"))
                .containsExactlyInAnyOrder(UI, CI_GM, EDGE_GM);
        // npm ci on npm 7+ reads the packages map and nothing else.
        assertThatThrownBy(() -> NpmSeed.packageLockPins(
                "{\"lockfileVersion\": 1, \"dependencies\": {}}", "old/package-lock.json"))
                .hasMessageContaining("old/package-lock.json")
                .hasMessageContaining("lockfileVersion 1");
    }

    /** The {@code packages:} keys are what a pnpm lock installs; snapshots repeat them with peers. */
    @Test
    void aPnpmLockPinsItsPackagesKeys() {
        assertThat(NpmSeed.pnpmLockPins(PNPM_LOCK)).containsExactlyInAnyOrder(ANGULAR,
                new NpmSeed.Pkg("@qits/ci-golden-masters", "2026.1018.180637"));
    }

    /**
     * <b>Golden masters first, then each jslib after what its own lock installs</b>:
     * ui-components builds against @qits/angular, which therefore has to be in the volume first.
     */
    @Test
    void theBuildOrderPutsWhatAPackageInstallsBeforeIt() {
        Map<NpmSeed.Pkg, Set<NpmSeed.Pkg>> needs = new LinkedHashMap<>();
        needs.put(UI, Set.of(ANGULAR, CI_GM));
        needs.put(ANGULAR, Set.of());
        needs.put(EDGE_GM, Set.of());
        needs.put(CI_GM, Set.of());

        assertThat(NpmSeed.buildOrder(needs)).containsExactly(CI_GM, EDGE_GM, ANGULAR, UI);
    }

    @Test
    void packagesThatPinEachOtherCannotBeBuiltAndSaySo() {
        Map<NpmSeed.Pkg, Set<NpmSeed.Pkg>> needs = new LinkedHashMap<>();
        needs.put(UI, Set.of(ANGULAR));
        needs.put(ANGULAR, Set.of(UI));

        assertThatThrownBy(() -> NpmSeed.buildOrder(needs))
                .hasMessageContaining("circle")
                .hasMessageContaining("@qits/angular@2026.1020.200308")
                .hasMessageContaining("@qits/ui-components@2026.1021.185351");
    }

    /**
     * <b>The committed {@code .npmrc} keeps everything but its registries</b>: the scope is refused
     * rather than left to resolve on npmjs, everything else comes from npmjs, and what is left
     * passes the refusal. The input is a frontend's committed file, comments and all.
     */
    @Test
    void theScratchNpmrcPointsAtNpmjsAndRefusesTheScope() {
        String committed = """
                # Both hosts answer 401 without a credential.
                #   //mirror.qits.wohlben.eu/npm/npmjs/:_authToken=...
                registry=https://mirror.qits.wohlben.eu/npm/npmjs/
                @qits:registry=https://registry.qits.wohlben.eu/artifacts/npm/npm/
                //registry.qits.wohlben.eu/artifacts/npm/npm/:_authToken=secret
                legacy-peer-deps=true
                """;

        String npmrc = NpmSeed.scratchNpmrc(committed);

        assertThat(npmrc).isEqualTo("legacy-peer-deps=true\n"
                + "registry=https://registry.npmjs.org/\n"
                + "@qits:registry=http://127.0.0.1:9/\n");
        assertThat(NpmSeed.refuseServers(npmrc, "qits-ci-service/service/src/main/webui"))
                .isSameAs(npmrc);
        // A repository with no .npmrc of its own gets the two lines alone.
        assertThat(NpmSeed.scratchNpmrc(null)).isEqualTo("registry=https://registry.npmjs.org/\n"
                + "@qits:registry=http://127.0.0.1:9/\n");
    }

    /** The refusal names the file and the line, so a new spelling cannot slip through silently. */
    @Test
    void anNpmConfigurationNamingAPlatformHostIsRefused() {
        assertThatThrownBy(() -> NpmSeed.refuseServers(
                "registry=https://registry.npmjs.org/\ncafile-url=https://mirror.qits.example/x\n",
                "qits-ci-service/service/src/main/webui"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("qits-ci-service/service/src/main/webui")
                .hasMessageContaining("cafile-url=https://mirror.qits.example/x")
                .hasMessageContaining("NpmSeed.scratchNpmrc");
    }

    /** {@code ContractPackager.npm}'s manifest, field for field. */
    @Test
    void aGoldenMastersManifestIsThePlatformPackagers() {
        var manifest = Json.parse(NpmSeed.goldenMastersManifest(CI_GM, "qits-ci"));

        assertThat(manifest.path("name").asText()).isEqualTo("@qits/ci-golden-masters");
        assertThat(manifest.path("version").asText()).isEqualTo("2026.1026.163551");
        assertThat(manifest.path("description").asText()).isEqualTo("qits-ci golden masters (contracts)");
        assertThat(manifest.path("license").asText()).isEqualTo("UNLICENSED");
        assertThat(manifest.path("files").toString()).isEqualTo("[\"golden-masters/\"]");
    }

    /**
     * <b>A golden-masters package is packed, not built</b>: the provider's {@code golden-masters/}
     * under {@code package/} beside the manifest, files only, into the volume under a temporary
     * name first.
     */
    @Test
    void aGoldenMastersPackageIsPackedFromItsArchiveIntoTheVolume() {
        String script = NpmSeed.packagesScript(List.of(new NpmSeed.Build(CI_GM, Set.of(), null,
                NpmSeed.goldenMastersManifest(CI_GM, "qits-ci"))));

        assertThat(script).startsWith("set -eu\n")
                .contains("mkdir -p /build/package && tar -xf "
                        + "/tmp/qits-ci-golden-masters-2026.1026.163551.tar -C /build/package\n")
                .contains("test -d /build/package/golden-masters || { echo "
                        + "'@qits/ci-golden-masters@2026.1026.163551: the release has no "
                        + "golden-masters/' >&2; exit 1; }")
                .contains("cat > /build/package/package.json <<'QITS_EOF'\n{\n")
                .contains("\"description\" : \"qits-ci golden masters (contracts)\"")
                .contains("(cd /build && find package -type f | LC_ALL=C sort > /tmp/files && tar"
                        + " --owner=0 --group=0 --numeric-owner -czf"
                        + " /tmp/packed/qits-ci-golden-masters-2026.1026.163551.tgz -T /tmp/files)")
                .contains("mv /tmp/packed/qits-ci-golden-masters-2026.1026.163551.tgz"
                        + " /qits-npm/qits-ci-golden-masters-2026.1026.163551.tgz.part && mv"
                        + " /qits-npm/qits-ci-golden-masters-2026.1026.163551.tgz.part"
                        + " /qits-npm/qits-ci-golden-masters-2026.1026.163551.tgz\n");
        assertThat(script).doesNotContain("pnpm install").doesNotContain(".qits.");
    }

    /**
     * <b>A jslib is built the way its release builds it</b> — {@code pnpm install
     * --frozen-lockfile && pnpm build}, the pnpm its packageManager pins, from npmjs — with its own
     * {@code @qits} pins retargeted at the tarballs built before it, and the manifest its one
     * {@code dist/*} carries checked against the version the tag names.
     */
    @Test
    void aJslibIsBuiltFromItsReleaseAgainstTheTarballsBuiltBeforeIt() {
        String npmrc = NpmSeed.scratchNpmrc(null);
        String script = NpmSeed.packagesScript(List.of(
                new NpmSeed.Build(UI, Set.of(ANGULAR, CI_GM), npmrc, null)));

        assertThat(script)
                .contains("cat > /tmp/pnpm-lock-rewrite.mjs <<'QITS_EOF'\n")
                .contains("tar -xf /tmp/qits-ui-components-2026.1021.185351.tar -C /build\ncd /build\n")
                .contains("cat > .npmrc <<'QITS_EOF'\nregistry=https://registry.npmjs.org/\n"
                        + "@qits:registry=http://127.0.0.1:9/\nQITS_EOF\n")
                .contains("mkdir -p .qits-npm\n"
                        + "cp /qits-npm/qits-angular-2026.1020.200308.tgz .qits-npm/\n"
                        + "cp /qits-npm/qits-ci-golden-masters-2026.1026.163551.tgz .qits-npm/\n")
                .contains("node /tmp/pnpm-lock-rewrite.mjs pnpm-lock.yaml '{"
                        + "\"@qits/angular@2026.1020.200308\":\".qits-npm/qits-angular-2026.1020.200308.tgz\","
                        + "\"@qits/ci-golden-masters@2026.1026.163551\":"
                        + "\".qits-npm/qits-ci-golden-masters-2026.1026.163551.tgz\"}'\n")
                .contains("|| npm install -g \"pnpm@$pm\" --registry=https://registry.npmjs.org/\n")
                .contains("pnpm install --frozen-lockfile --store-dir /tmp/pnpm-store\npnpm build\n")
                .contains("[ \"$built\" = '@qits/ui-components@2026.1021.185351' ]")
                .contains("(cd \"$dist\" && npm pack --ignore-scripts --pack-destination /tmp/packed)\n")
                .contains("mv /qits-npm/qits-ui-components-2026.1021.185351.tgz.part"
                        + " /qits-npm/qits-ui-components-2026.1021.185351.tgz\n");
        // The pnpm lock is retargeted, never package.json: --frozen-lockfile still holds.
        assertThat(script).doesNotContain("overrides").doesNotContain(".qits.")
                .doesNotContain("--no-frozen-lockfile");
    }

    /**
     * <b>A seed client is {@code npm ci && npm run build} of its committed tree</b>, against the
     * local tarballs and npmjs, its bundle checked where the Dockerfile reads it and kept for the
     * next run of the same commit.
     */
    @Test
    void aClientIsBuiltFromItsCommittedTreeAgainstTheLocalTarballs() {
        String script = NpmSeed.frontendScript("qits-ci-service@0123abcd", "dist/qits-spa-ci/browser",
                Set.of(UI, CI_GM), NpmSeed.scratchNpmrc(null));

        assertThat(script).startsWith("set -eu\n")
                .contains("mkdir -p /work /out && tar -xf /tmp/webui.tar -C /work && cd /work\n")
                .contains("if [ -f /qits-npm/bundles/qits-ci-service@0123abcd.tgz ]; then\n"
                        + "  echo 'the bundle of qits-ci-service@0123abcd is already built'\n"
                        + "  tar -xzf /qits-npm/bundles/qits-ci-service@0123abcd.tgz -C /out\n"
                        + "  exit 0\nfi\n")
                .contains("cat > .npmrc <<'QITS_EOF'\nregistry=https://registry.npmjs.org/\n")
                .contains("cp /qits-npm/qits-ui-components-2026.1021.185351.tgz .qits-npm/\n")
                .contains("cat > /tmp/lock-rewrite.mjs <<'QITS_EOF'\n")
                .contains("node /tmp/lock-rewrite.mjs package-lock.json '{"
                        + "\"@qits/ci-golden-masters@2026.1026.163551\":"
                        + "\".qits-npm/qits-ci-golden-masters-2026.1026.163551.tgz\","
                        + "\"@qits/ui-components@2026.1021.185351\":"
                        + "\".qits-npm/qits-ui-components-2026.1021.185351.tgz\"}'\n"
                        + "npm ci\nnpm run build\n")
                .contains("test -f dist/qits-spa-ci/browser/index.html || {")
                .contains("tar -czf /qits-npm/bundles/qits-ci-service@0123abcd.tgz.part"
                        + " -C dist/qits-spa-ci/browser . && mv")
                .contains("cp -a dist/qits-spa-ci/browser/. /out/\n");
        assertThat(script).doesNotContain(".qits.").doesNotContain("npm install");
    }

    /**
     * <b>The lock rewrite keeps the build honest</b>: {@code @qits} to the local tarball with its
     * own digest, the npmjs cache to npmjs, and anything else naming a platform host stops it.
     */
    @Test
    void theLockRewritesRetargetOnlyWhatTheyKnowAndRefuseTheRest() {
        assertThat(NpmSeed.LOCK_REWRITE)
                .contains("entry.resolved = 'file:' + tarball;")
                .contains("entry.integrity = digest(tarball);")
                .contains("const upstream = /^https?:\\/\\/mirror\\.qits\\.[^/]+\\/npm\\/npmjs\\//;")
                .contains("entry.resolved.replace(upstream, 'https://registry.npmjs.org/')")
                .contains("which is no npmjs cache path")
                .contains("which this boot did not build")
                .contains("const platform = /\\.qits\\./;")
                .contains("still resolves from");
        assertThat(NpmSeed.PNPM_LOCK_REWRITE)
                .contains("', tarball: file:' + tarball + '}'")
                .contains("is installed, and this boot did not build it")
                .contains("if (/\\.qits\\./.test(out)) fail('still names a platform host');");
    }

    /** ONE container answers for every tarball, read-only, on the image the builds use anyway. */
    @Test
    void theVolumeIsAskedForEveryTarballInOneContainer() {
        assertThat(NpmSeed.probeCommand(List.of(UI.file(), CI_GM.file()))).containsExactly(
                "docker", "run", "--rm", "-v", "qits-npm-seed:/qits-npm:ro",
                "--entrypoint", "sh", "node:24-bookworm-slim", "-c",
                "[ -f '/qits-npm/qits-ui-components-2026.1021.185351.tgz' ] && echo"
                        + " 'qits-ui-components-2026.1021.185351.tgz'\n"
                        + "[ -f '/qits-npm/qits-ci-golden-masters-2026.1026.163551.tgz' ] && echo"
                        + " 'qits-ci-golden-masters-2026.1026.163551.tgz'\n"
                        + "exit 0\n");
    }
}
