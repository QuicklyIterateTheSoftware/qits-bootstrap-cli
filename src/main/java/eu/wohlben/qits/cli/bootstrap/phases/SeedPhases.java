package eu.wohlben.qits.cli.bootstrap.phases;

import eu.wohlben.qits.cli.bootstrap.api.Http;
import eu.wohlben.qits.cli.bootstrap.config.WrapperDir;
import eu.wohlben.qits.cli.bootstrap.engine.Phase;
import eu.wohlben.qits.cli.bootstrap.engine.PhaseContext;
import eu.wohlben.qits.cli.bootstrap.platform.BootstrapState;
import eu.wohlben.qits.cli.bootstrap.platform.ComposeTemplate;
import eu.wohlben.qits.cli.bootstrap.platform.Docker;
import eu.wohlben.qits.cli.bootstrap.platform.PlatformModel;
import eu.wohlben.qits.cli.bootstrap.platform.SeedDockerfile;
import eu.wohlben.qits.cli.bootstrap.proc.Cmd;
import eu.wohlben.qits.cli.bootstrap.proc.ProcessResult;
import org.eclipse.microprofile.config.ConfigProvider;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The hand-built part: everything the pipeline cannot make for itself on the first boot, plus the
 * files the seed stack is started from.
 */
public class SeedPhases {

    /** The temporary maven registry's container name — known here, not only in the run state. */
    static final String AUTH_SEED_HTTP = "qits-maven-seed-http";

    private final Boot boot;

    public SeedPhases(Boot boot) {
        this.boot = boot;
    }

    // --- preflight and sources -------------------------------------------------------------------

    public Phase preflight() {
        return new Phase("preflight", "preflight: docker, git and the wrapper checkouts", ctx -> {
            if (!boot.docker.daemonReachable()) {
                throw new IllegalStateException("cannot reach a docker daemon — is it running?");
            }
            ctx.log("  docker daemon: reachable");
            if (!boot.docker.composePluginPresent()) {
                throw new IllegalStateException("docker compose plugin missing");
            }
            ctx.log("  docker compose: present");
            if (!boot.git.available()) {
                throw new IllegalStateException("no git on PATH");
            }
            boot.state.dockerGid = boot.docker.socketGroupId();
            ctx.log("  docker socket group: " + boot.state.dockerGid);

            // The CLI lives at cli/qits-cli-bootstrap inside the wrapper, so an unset
            // QITS_WRAPPER_DIR is answered by walking up from here rather than by assuming the
            // working directory IS the wrapper. Which of the two happened is printed: a run
            // against the wrong checkout is otherwise indistinguishable from a run against the
            // right one until the sources phase clones something surprising.
            WrapperDir.Resolved resolved =
                    WrapperDir.resolve(boot.config.wrapperDir(), Path.of("").toAbsolutePath());
            Path wrapper = resolved.path();
            if (!Files.isDirectory(wrapper)) {
                throw new IllegalStateException("no wrapper directory at " + wrapper);
            }
            boot.state.wrapperDir = wrapper;
            boot.state.srcDir = Path.of(boot.config.src()).toAbsolutePath().normalize();
            boot.state.composeFile = wrapper.resolve("docker-compose.qits.yml");
            Files.createDirectories(boot.state.srcDir);
            ctx.log("  wrapper: " + wrapper + "  (" + resolved.how() + ")");
            ctx.log("  sources: " + boot.state.srcDir);

            long local = PlatformModel.platformRepos().stream()
                    .filter(name -> boot.git.isCheckout(boot.state.wrapperCheckout(name)))
                    .count();
            int total = PlatformModel.platformRepos().size();
            ctx.log("  " + local + " of " + total + " repositories have a local checkout; the rest "
                    + "come from " + boot.config.orgUrl());
            ctx.note(local + "/" + total + " local checkouts");
        });
    }

    /**
     * <b>Both silences here are gone.</b> This phase decides which sha the whole platform is built
     * from, and both of its old fallbacks answered a broken input with a working-looking run:
     * <ul>
     *   <li>A wrapper path that was not a checkout fell through to GitHub. A rename that outran
     *       {@link PlatformModel#repoPath} then deployed the org's last push instead of the work in
     *       the checkout — and said so in one line among thousands. Now: an ABSENT directory is
     *       still answered by the org URL (not every model repository has to be a submodule of this
     *       wrapper), but a directory that exists and is not a checkout stops the boot.
     *   <li>A refresh that failed logged "using what is checked out" and built the stale copy. A
     *       non-fast-forward is the ordinary cause and the ordinary cause is a rebase, so the stale
     *       copy is a commit that no longer exists anywhere.
     * </ul>
     */
    public Phase sources() {
        return new Phase("sources", "clone or refresh the platform's sources", ctx -> {
            for (String name : PlatformModel.platformRepos()) {
                String repo = PlatformModel.repo(name);
                Path localSrc = boot.state.wrapperCheckout(name);
                if (Files.exists(localSrc) && !boot.git.isCheckout(localSrc)) {
                    throw new IllegalStateException(localSrc + " is not a git checkout, so "
                            + repo + " has no source. Either the submodule is not initialised "
                            + "(git submodule update --init) or PlatformModel.repoPath names the "
                            + "wrong directory for '" + name + "'.");
                }
                String from = boot.git.isCheckout(localSrc)
                        ? localSrc.toString()
                        : boot.config.orgUrl() + "/" + repo + ".git";
                Path target = boot.state.repoDir(name);
                if (boot.git.isCheckout(target)) {
                    ctx.status("refreshing " + repo);
                    Boot.must(boot.git.pullFastForward(target, ctx::log),
                            "refresh of " + repo + " failed — " + target + " cannot fast-forward "
                                    + "to " + from + ", so this run would build a commit that is "
                                    + "no longer anywhere. Delete that directory and rerun");
                } else {
                    ctx.status("cloning " + repo + " from " + from);
                    Boot.must(boot.git.clone(from, target, ctx::log), "clone of " + repo + " failed");
                }
                ctx.log(String.format("  %-26s %s  (%s)", repo, boot.git.shortHead(target), from));
            }
            ctx.note(PlatformModel.platformRepos().size() + " repositories");
        });
    }

    /**
     * What a previous bootstrap generated and this one must not change. Read before anything needs
     * it, so a full rerun keeps the secrets it issued last time.
     */
    public Phase recordedState() {
        return new Phase("recorded-state", "read the recorded run state", ctx -> {
            BootstrapState state = new BootstrapState(
                    boot.state.wrapperDir.resolve(BootstrapState.FILE_NAME));
            state.read();
            boot.state.daemonSha = state.daemonSha().orElse(null);
            for (String client : PlatformModel.idpClients(boot.config.envName())) {
                state.secret(client).ifPresent(secret -> boot.state.secrets.put(client, secret));
            }
            if (!state.exists()) {
                ctx.log("  no " + BootstrapState.FILE_NAME + " — this is a first boot");
                ctx.note("first boot");
                return;
            }
            ctx.log("  " + state.file());
            ctx.log("  recorded daemon digest: "
                    + (boot.state.daemonSha == null ? "none" : shortSha(boot.state.daemonSha)));
            ctx.log("  recorded client secrets: " + boot.state.secrets.size() + " of "
                    + PlatformModel.idpClients(boot.config.envName()).size());
            ctx.note("kept " + boot.state.secrets.size() + " secrets");
        });
    }

    /** The skip-build path's one duty: the digest is a run-pinned value the compose file needs. */
    public Phase skipBuildGate() {
        return new Phase("seed-skipped", "seed builds skipped (QITS_SKIP_BUILD)", ctx -> {
            if (boot.state.daemonSha == null || boot.state.daemonSha.isBlank()) {
                throw new IllegalStateException("QITS_SKIP_BUILD is set but no DAEMON_SHA was "
                        + "recorded in " + BootstrapState.FILE_NAME + " — rerun without it");
            }
            ctx.log("  reusing the recorded ci-daemon digest " + shortSha(boot.state.daemonSha));
            ctx.note("digest " + shortSha(boot.state.daemonSha));
        });
    }

    // --- the first-boot dependency cycle ----------------------------------------------------------

    /**
     * qits-platform-artifacts consumes qits-auth-core while also being the Maven registry that owns
     * it in steady state. The cycle is broken with a temporary, bootstrap-owned file repository,
     * served over HTTP on the registry port and removed before the real artifacts container claims
     * it.
     */
    public Phase authCoreSeed() {
        return new Phase("auth-core-seed", "seed qits-auth-core 1.0.0 for the first artifacts build",
                ctx -> {
                    // Version-agnostic on purpose: the checkouts publish their real calver, so a
                    // pinned-version probe never matches and a rerun collides with whoever holds
                    // the registry port. Metadata present = auth-core is served, whatever version.
                    String metadata = boot.config.artifactsUrl()
                            + "/maven/maven/eu/wohlben/qits/qits-auth-core/maven-metadata.xml";
                    if (boot.http.get(metadata, Map.of()).ok()) {
                        ctx.skip("already served on port " + boot.config.registryPort());
                    }
                    // The platform's own store may already hold the registry port, and then the
                    // temporary one cannot have it — the bind fails with "port is already
                    // allocated" and the boot stops. It does not need it either: the store IS the
                    // Maven registry the seed builds resolve against, and qits-auth-core is
                    // published into it by every bootstrap that gets past phase 10. If a store this
                    // far along somehow has not got it, the seed build below fails by name rather
                    // than resolving nothing quietly.
                    storeAlreadyServing().ifPresent(who ->
                            ctx.skip(who + " serves port " + boot.config.registryPort()
                                    + " — it is the registry"));
                    boot.docker.ensureVolume("qits-maven-seed", ctx::log);
                    String cid = create(ctx, List.of(
                            "docker", "create", "--user", "root", "--entrypoint", "sh",
                            "-v", "qits-maven-seed:/repo", "maven:3.9-eclipse-temurin-25",
                            "-c", "cd /src && mvn -B -ntp deploy -DskipTests "
                                    + "-DaltDeploymentRepository=seed::default::file:///repo"));
                    copyIn(ctx, boot.state.repoDir("integrations-quarkus"), cid);
                    startAndReap(ctx, cid, "qits-auth-core seed failed");

                    String container = AUTH_SEED_HTTP;
                    boot.docker.removeContainer(container, null);
                    Boot.must(boot.docker.exec(ctx::log, "run", "-d", "--name", container,
                                    "-p", "127.0.0.1:" + boot.config.registryPort() + ":80",
                                    "-v", "qits-maven-seed:/usr/share/nginx/html/artifacts/maven/maven:ro",
                                    "nginx:alpine"),
                            "the temporary maven registry did not start");
                    boot.state.authSeedContainer = container;
                    // Even a failed run must not leave the registry port held by this container.
                    Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                        if (boot.state.authSeedContainer != null) {
                            boot.docker.removeContainer(boot.state.authSeedContainer, null);
                        }
                    }));
                    ctx.note("temporary maven registry up");
                });
    }

    // --- seed images ------------------------------------------------------------------------------

    public Phase seedImage(String name) {
        return new Phase("seed-image-" + name, "build the seed image qits/" + name + ":latest", ctx -> {
            Path repo = boot.state.repoDir(name);
            ctx.status("fetching submodules of qits-" + name);
            boot.git.submodulesShallow(repo, ctx::log);

            String seedUi = PlatformModel.seedUiPath(name);
            if (!seedUi.isEmpty()) {
                // Seed services only need their APIs. Their Dockerfiles consume an already-built
                // SPA, and a clean checkout has no dist directory while the npm registry does not
                // exist yet. The normal pipeline builds the real client from the same commit.
                Path ui = repo.resolve(seedUi);
                Files.createDirectories(ui);
                Files.writeString(ui.resolve("index.html"),
                        "<!doctype html><html><body>qits bootstrap</body></html>\n",
                        StandardCharsets.UTF_8);
                ctx.log("  placeholder client at " + seedUi);
            }

            List<String> extra = new ArrayList<>();
            if ("gateway".equals(name)) {
                // A shipped gateway must say whether it authenticates; `local` is the
                // unauthenticated workstation variant. Never publish that image or its port.
                extra.addAll(List.of("--build-arg", "QITS_VARIANT=local"));
            }
            String dockerfile = SeedDockerfile.read(repo.resolve("docker/Dockerfile"));
            ctx.status("cold GraalVM native build of qits/" + name + " — ~4 GB RAM, no maven cache");
            ProcessResult result = boot.docker.buildFromStdin("qits/" + name + ":latest",
                    dockerfile, repo, extra, ctx::log);
            Boot.must(result, "build of qits/" + name + " failed");
        });
    }

    /**
     * The step images pipeline configs name. qits-oci is their single source of truth, but the
     * first ci-base cannot be built by the pipeline that needs ci-base to run.
     */
    public Phase stepImage(String name) {
        return new Phase("step-image-" + name, "build qits/build-images/" + name + ":latest", ctx -> {
            Path oci = boot.state.repoDir("oci");
            ProcessResult result = boot.docker.build(List.of(
                    "-t", "qits/build-images/" + name + ":latest",
                    "-f", oci.resolve(name).resolve("Dockerfile").toString(),
                    oci.toString()), ctx::log);
            Boot.must(result, "build of qits/build-images/" + name + " failed");
        });
    }

    /**
     * Brings up qits-platform-artifacts alone, so the Maven and npm publishes below have somewhere
     * to land before any pipeline exists. It STARTS one only when nothing is serving the registry
     * port yet — what this phase owes the ones after it is a store that answers, not a container it
     * created.
     */
    public Phase seedArtifactsStart() {
        return new Phase("seed-artifacts",
                "have qits-platform-artifacts serving for the Maven bootstrap", ctx -> {
            // By name and unconditionally: a crashed earlier run leaves the registry running, and
            // this run then skips the seed phase without ever learning the container's name.
            ctx.log("  removing the temporary maven registry, freeing port "
                    + boot.config.registryPort());
            boot.docker.removeContainer(AUTH_SEED_HTTP, ctx::log);
            boot.state.authSeedContainer = null;
            boot.docker.ensureNetwork(Boot.NETWORK, ctx::log);
            boot.docker.ensureVolume("qits-platform-artifacts-data", ctx::log);
            // Named after its wire alias, like every other seed container: this one is started by
            // hand rather than by compose, and the name it takes has to be the same one the compose
            // service would have claimed, or the two run side by side on the registry port.
            String artifacts = PlatformModel.wireAlias("platform-artifacts", boot.config.envName());
            // Whoever already holds the port, holds it. A seed beside a store that is up is
            // impossible — the bind answers "port is already allocated", exit 125, and the boot
            // stopped exactly there on the 2026-08-08 validation rerun — and pointless: a DEPLOYED
            // store publishes this very port from the same qits-platform-artifacts-data volume and
            // answers the same API, so it is strictly better than the seed this phase would have
            // started. This run's own seed container is asked for by name first, so a rerun still
            // reports it as itself rather than as the deployer's.
            Optional<String> serving = boot.docker.runningNames().contains(artifacts)
                    ? Optional.of(artifacts) : storeAlreadyServing();
            if (serving.isPresent()) {
                ctx.log("  " + serving.get() + " already serves port " + boot.config.registryPort()
                        + " from the same volume — no seed store to start");
                ctx.note(serving.get() + " serves :" + boot.config.registryPort());
            } else {
                boot.docker.removeContainer(artifacts, null);
                Boot.must(boot.docker.exec(ctx::log, "run", "-d", "--name", artifacts,
                                "--network", Boot.NETWORK,
                                "-p", "127.0.0.1:" + boot.config.registryPort() + ":8080",
                                "-e", "QUARKUS_DATASOURCE_ARTIFACTS_JDBC_URL=jdbc:h2:file:/data/artifacts/h2/artifacts",
                                "-e", "QITS_ARTIFACTS_BLOBS_DIR=/data/artifacts/blobs",
                                "-e", "QITS_CI_INTAKE_URL=http://"
                                        + PlatformModel.wireAlias("ci", boot.config.envName())
                                        + ":8080/ci/api/events/post-receive",
                                "-e", "QITS_REPOSITORIES_GIT_PUSH_TOKEN=" + boot.config.pushToken(),
                                "-e", "QITS_REPOSITORIES_GIT_PROTECT_DEFAULT_BRANCH=true",
                                "-v", "qits-platform-artifacts-data:/data",
                                "qits/platform-artifacts:latest"),
                        "the seed " + artifacts + " did not start");
            }
            // Always waited for, whoever is behind the port: every publish after this phase — the
            // two Maven ones, both npm ones, the daemon upload — needs the store answering, and a
            // phase that skipped the start still owes them that.
            boot.awaitHealth(ctx, serving.orElse(artifacts) + " on port "
                    + boot.config.registryPort(), boot.artifacts::health);
        });
    }

    /**
     * Who already holds the registry port, when the platform's own store does — the deployed
     * container's name if one is running, otherwise the service's plain name.
     * <p>
     * <b>The answer comes from the API, not from the container list.</b> A deployed store is named
     * {@code qits-pd-qits-platform-artifacts-<id8>}, and matching that shape alone would still miss
     * anything else the port could be behind. The artifacts API's own health is the honest
     * question: the temporary Maven registry is an nginx serving one mounted directory, so it
     * answers 404 there, while qits-platform-artifacts answers 200 whether it was started by this
     * bootstrap, by compose or by the deployer. The container list is then read only to name who it
     * is, which is what makes the phase log readable.
     * <p>
     * Both phases that bind the registry port ask this before they bind it. Neither can win that
     * bind, and neither needs to: the store on the other end has the same volume.
     */
    private Optional<String> storeAlreadyServing() {
        if (!boot.artifacts.ready()) {
            return Optional.empty();
        }
        String prefix = PlatformModel.pdNamePrefix("platform-artifacts", boot.config.envName());
        return Optional.of(boot.docker.runningNames().stream()
                .filter(name -> name.startsWith(prefix))
                .findFirst()
                .orElse(PlatformModel.repo("platform-artifacts")));
    }

    // --- the publishes the seed builds need -------------------------------------------------------

    /** The version the repo's root pom would publish: its first version element outside parent. */
    static String checkedOutVersion(Path repoDir) {
        try {
            String pom = Files.readString(repoDir.resolve("pom.xml"), StandardCharsets.UTF_8);
            String withoutParent = pom.replaceAll("(?s)<parent>.*?</parent>", "");
            java.util.regex.Matcher m =
                    java.util.regex.Pattern.compile("<version>([^<$]+)</version>").matcher(withoutParent);
            return m.find() ? m.group(1).trim() : null;
        } catch (IOException e) {
            return null;
        }
    }

    public Phase mavenPublish(String repoName, String artifactId, String title) {
        return new Phase("publish-" + artifactId, title, ctx -> {
            // The checkouts publish their real calver, and the registry refuses to overwrite a
            // released version (403) — so probe the version this checkout would publish, parsed
            // from its root pom, before starting a container. Found by the third proving run.
            String version = checkedOutVersion(boot.state.repoDir(repoName));
            if (version != null
                    && boot.artifacts.mavenPublished("eu/wohlben/qits", artifactId, version, "jar")) {
                ctx.skip(artifactId + " " + version + " already published");
            }
            String cid = create(ctx, List.of(
                    "docker", "create", "--network", Boot.NETWORK, "--user", "root",
                    "--entrypoint", "sh", "maven:3.9-eclipse-temurin-25",
                    "-c", "cd /src && mvn -B -ntp deploy -DskipTests -DaltDeploymentRepository="
                            + "qits::default::http://qits-platform-artifacts:8080/artifacts/maven/maven"));
            copyIn(ctx, boot.state.repoDir(repoName), cid);
            startAndReap(ctx, cid, artifactId + " publish failed");
        });
    }

    /**
     * The shared UI package, twice: the pinned version the checked-out lockfiles install, then
     * whatever the working tree is at now. Publish-if-absent makes both idempotent.
     */
    /**
     * Seed publishes are PINNED COMMITS ONLY. A working-tree publish under an already-released
     * version puts different bytes behind a version every lockfile pins by hash — EINTEGRITY in
     * every SPA build. The release replays own the released versions; unreleased work reaches the
     * registry through a release, never through the seed. Found by the v3 proving run.
     */
    public Phase uiComponentsPublish() {
        return new Phase("publish-ui-components", "publish the shared UI package into seed artifacts",
                ctx -> {
                    String script = """
                            set -eu
                            apk add --no-cache git >/dev/null
                            git config --global --add safe.directory '*'
                            git clone -q /src /src-004
                            cd /src-004
                            git checkout -q 9f9648482d6fe025cc7af9bd4496afab417f33f9
                            corepack enable
                            cat > /root/.npmrc <<EOF
                            registry=https://registry.npmjs.org/
                            @qits:registry=http://qits-platform-artifacts:8080/artifacts/npm/npm/
                            //qits-platform-artifacts:8080/artifacts/npm/npm/:_authToken=qits-bootstrap
                            EOF
                            pnpm install --frozen-lockfile
                            pnpm build
                            cd dist/qits-spa-ui-components
                            npm view @qits/ui-components@0.0.4 version >/dev/null 2>&1 || npm publish

                            """;
                    nodePublish(ctx, "spa-ui-components", script, "UI package publish failed");
                });
    }

    public Phase angularPublish() {
        return new Phase("publish-angular", "publish the Angular integration package into seed artifacts",
                ctx -> {
                    String script = """
                            set -eu
                            apk add --no-cache git >/dev/null
                            git config --global --add safe.directory '*'
                            git clone -q /src /src-001
                            cd /src-001
                            git checkout -q 3f405717f14f0942399340d84db4ef0ca3769101
                            corepack enable
                            cat > /root/.npmrc <<EOF
                            registry=https://registry.npmjs.org/
                            @qits:registry=http://qits-platform-artifacts:8080/artifacts/npm/npm/
                            //qits-platform-artifacts:8080/artifacts/npm/npm/:_authToken=qits-bootstrap
                            EOF
                            pnpm install --frozen-lockfile
                            pnpm build
                            version=$(node -p "require(\\"./dist/qits-integrations-angular/package.json\\").version")
                            npm view "@qits/angular@$version" version >/dev/null 2>&1 || npm publish ./dist/qits-integrations-angular

                            """;
                    nodePublish(ctx, "integrations-angular", script,
                            "Angular integration publish failed");
                });
    }

    private void nodePublish(PhaseContext ctx, String repoName, String script, String failure)
            throws Exception {
        String cid = create(ctx, List.of(
                "docker", "create", "--network", Boot.NETWORK, "--user", "root",
                "--entrypoint", "sh", "node:24-alpine", "-c", script));
        copyIn(ctx, boot.state.repoDir(repoName), cid);
        startAndReap(ctx, cid, failure);
    }

    // --- the ci-daemon binary ---------------------------------------------------------------------

    /**
     * A fully static musl native binary, built inside the builder image: docker cp carries the
     * source in and the binary out. container-build is off because we are already in the container
     * it would otherwise launch.
     */
    public Phase ciDaemon() {
        return new Phase("ci-daemon", "build the qits-ci-daemon binary (musl static native)", ctx -> {
            Path repo = boot.state.repoDir("ci-daemon");
            String dockerfile = SeedDockerfile.read(repo.resolve("docker/Dockerfile.musl-builder"));
            ctx.status("building the musl builder image");
            Boot.must(boot.docker.buildFromStdin("qits/graalvmce-musl-builder:jdk-25", dockerfile,
                            repo.resolve("docker"), List.of(), ctx::log),
                    "the musl builder image failed to build");

            // --entrypoint: the builder image entrypoints to native-image itself.
            String cid = create(ctx, List.of(
                    "docker", "create", "--user", "root", "--entrypoint", "bash",
                    "qits/graalvmce-musl-builder:jdk-25",
                    "-c", "cd /qits-build && ./mvnw -B -ntp -pl ci-daemon -am package -Dnative "
                            + "-DskipTests -Dquarkus.native.container-build=false"));
            Boot.must(boot.docker.exec(Duration.ofMinutes(30), ctx::log,
                    "cp", repo.toString(), cid + ":/qits-build"), "copying the daemon source in failed");
            ctx.status("cold musl native build of the ci-daemon");
            ProcessResult run = boot.docker.exec(Docker.BUILD_TIMEOUT,
                    ctx::log, "start", "-a", cid);
            if (!run.ok()) {
                boot.docker.removeContainer(cid, null);
                throw new IllegalStateException("ci-daemon build failed\n" + run.tailText(20));
            }
            Path out = boot.state.wrapperDir.resolve(".qits-bootstrap").resolve("qits-ci-daemon");
            Files.createDirectories(out.getParent());
            Boot.must(boot.docker.exec(Duration.ofMinutes(30), ctx::log,
                            "cp", cid + ":/qits-build/ci-daemon/target/qits-ci-daemon", out.toString()),
                    "copying the daemon binary out failed");
            boot.docker.exec(null, "rm", cid);

            boot.state.daemonBinary = out;
            boot.state.daemonSha = sha256(out);
            ctx.log("  ci-daemon digest: sha256:" + boot.state.daemonSha);
            ctx.note("digest " + shortSha(boot.state.daemonSha));
        });
    }

    // --- secrets, compose, run-args ---------------------------------------------------------------

    /**
     * Every static client ships without a secret and is unusable until a deployment gives it one.
     * Precedence: an explicit override, else what a previous run recorded, else a fresh random.
     */
    public Phase idpSecrets() {
        return new Phase("idp-secrets", "resolve the idp's client secrets and record the run state",
                ctx -> {
                    for (String client : PlatformModel.idpClients(boot.config.envName())) {
                        Optional<String> given = ConfigProvider.getConfig()
                                .getOptionalValue("qits.idp.client." + client + ".secret", String.class)
                                .filter(value -> !value.isBlank());
                        String kept = boot.state.secrets.get(client);
                        String origin;
                        String value;
                        if (given.isPresent()) {
                            value = given.get();
                            origin = "given";
                        } else if (kept != null && !kept.isBlank()) {
                            value = kept;
                            origin = "kept";
                        } else {
                            value = randomSecret();
                            origin = "generated";
                        }
                        boot.state.secrets.put(client, value);
                        ctx.log(String.format("  %-24s %s", client, origin));
                    }
                    BootstrapState state = new BootstrapState(
                            boot.state.wrapperDir.resolve(BootstrapState.FILE_NAME));
                    state.write(boot.state.daemonSha, boot.state.secrets);
                    ctx.log("  recorded in " + state.file());
                });
    }

    public Phase composeFile() {
        return new Phase("compose-file", "generate the seed compose file", ctx -> {
            try {
                Files.writeString(boot.state.composeFile, ComposeTemplate.compose(tokens()),
                        StandardCharsets.UTF_8);
            } catch (java.nio.file.AccessDeniedException e) {
                // Same migration relic as the state file: a pre-CLI bootstrap wrote it as root.
                Files.deleteIfExists(boot.state.composeFile);
                Files.writeString(boot.state.composeFile, ComposeTemplate.compose(tokens()),
                        StandardCharsets.UTF_8);
            }
            ctx.log("  " + boot.state.composeFile);
            ctx.note(boot.state.composeFile.getFileName().toString());
        });
    }

    /**
     * The deployer's per-application run arguments, as a config file on a named volume: quarkus
     * reads config/application.properties next to the binary, and a self-update's successor mounts
     * the same volume — which is the whole reason this is a file and not compose env.
     */
    public Phase pdRunArgs() {
        return new Phase("pd-run-args", "write the deployer's run-args config volume", ctx -> {
            boot.docker.ensureVolume("qits-deployments-config", ctx::log);
            String properties = ComposeTemplate.runArgs(tokens());
            // What the volume held BEFORE this write, as a DIGEST rather than as text: the file
            // carries the push token and every client secret, and reading it back would put both
            // on the screen and in the log.
            String before = configDigest();
            ProcessResult result = boot.docker.run(Cmd.of(List.of(
                            "docker", "run", "--rm", "-i",
                            "-v", "qits-deployments-config:/cfg",
                            "--entrypoint", "sh", "alpine/git",
                            "-c", "cat > /cfg/application.properties "
                                    + "&& chown 1001:0 /cfg/application.properties"))
                    .stdin(properties)
                    .mask(boot.config.pushToken())
                    .mask(boot.state.secrets.getOrDefault(
                            PlatformModel.wireAlias("ci", boot.config.envName()), ""))
                    // Both postgres passwords are in this file too: the deployer's own credential
                    // and the admin one it provisions every other application's database with.
                    .mask(orEmpty(boot.state.pgSuperuserPassword))
                    .mask(orEmpty(boot.state.pgDeploymentsPassword)), ctx::log);
            Boot.must(result, "writing the deployer's run-args failed");
            ctx.log("  " + properties.lines().filter(l -> l.startsWith("qits.platform.deployments.run-args")).count()
                    + " applications configured on the qits-deployments-config volume");
            if (!sha256(properties).equals(before)) {
                restartSeedDeployer(ctx);
            }
        });
    }

    /**
     * The digest of the run-args file already on the volume, or empty when there is none. Computed
     * inside a container because the volume has no path on the host, and with the same image the
     * write above uses so nothing extra is pulled.
     */
    private String configDigest() {
        ProcessResult result = boot.docker.run(Cmd.of(List.of(
                "docker", "run", "--rm",
                "-v", "qits-deployments-config:/cfg",
                "--entrypoint", "sh", "alpine/git",
                // The redirect is the "no file yet" case, which is the ordinary cold boot.
                "-c", "sha256sum /cfg/application.properties 2>/dev/null | cut -d' ' -f1")), null);
        for (String line : result.captured()) {
            String value = line.trim();
            if (value.matches("[0-9a-f]{64}")) {
                return value;
            }
        }
        return "";
    }

    /**
     * <b>The deployer reads its run-args ONCE, at its own boot.</b> A rerun that changes the file
     * therefore changes nothing for a deployer that is already running: it goes on deploying from
     * the previous boot's arguments, and compose will not help — the volume is unchanged as far as
     * it is concerned, so {@code up -d} leaves the container alone. That is how a qits-ci was
     * deployed on the first prod bootstrap without the addresses its step containers dial, and the
     * recovery was a restart by hand.
     * <p>
     * Only the SEED deployer, by its wire alias. A deployed one ({@code qits-pd-…}) picked the file
     * up when its own cutover started it, and restarting it here would interrupt whatever it is
     * deploying. Restarting an idle seed deployer costs seconds and nothing else, which is what
     * makes this rerun-safe.
     */
    private void restartSeedDeployer(PhaseContext ctx) {
        String name = PlatformModel.wireAlias("deployments", boot.config.envName());
        if (!boot.docker.runningNames().contains(name)) {
            ctx.log("  the run-args changed; no seed deployer is running, so none is holding "
                    + "the old ones");
            return;
        }
        ctx.log("  the run-args changed and " + name + " is older than the change — restarting it "
                + "so it deploys from the new ones");
        Boot.must(boot.docker.exec(Duration.ofMinutes(5), ctx::log, "restart", name),
                "restarting " + name + " after its run-args changed failed");
        ctx.note("run-args changed, " + name + " restarted");
    }

    /** The values both generated files are filled with. */
    Map<String, String> tokens() {
        String env = boot.config.envName();
        Map<String, String> values = new LinkedHashMap<>();
        values.put("ENV_NAME", env);
        // The same name in the spelling an env-var key takes, because the idp's per-client keys
        // embed the client id and a client id starts with the environment name:
        // QITS_IDP_CLIENT_PROD_QITS_CI_SECRET.
        values.put("ENV_KEY", PlatformModel.clientKey(env));
        values.put("COMPOSE_FILE", boot.state.composeFile == null ? "docker-compose.qits.yml"
                : boot.state.composeFile.getFileName().toString());
        values.put("PORT", String.valueOf(boot.config.port()));
        values.put("REGISTRY_PORT", String.valueOf(boot.config.registryPort()));
        values.put("PG_PORT", String.valueOf(boot.config.pgPort()));
        // Resolved by seed-postgres, which runs before both generated files are written.
        values.put("PG_SUPERUSER_PASSWORD", orEmpty(boot.state.pgSuperuserPassword));
        values.put("PG_DEPLOYMENTS_PASSWORD", orEmpty(boot.state.pgDeploymentsPassword));
        values.put("IDP", boot.config.idpIssuer());
        values.put("PUSH_TOKEN", boot.config.pushToken());
        values.put("MACHINE_REQUIRED", String.valueOf(boot.config.machineAuth()));
        // The OUTBOUND half, and a separate switch from the gate: quarkus-oidc-client ships
        // DISABLED, so a service given an issuer and a secret still posts BARE until this is set.
        values.put("MACHINE_CLIENT", String.valueOf(boot.config.machineAuth()));
        values.put("DOCKER_GID", boot.state.dockerGid);
        values.put("DAEMON_SHA", boot.state.daemonSha == null ? "" : boot.state.daemonSha);
        values.put("IDP_CLIENTS", String.join(",", PlatformModel.idpClients(env)));
        values.put("IDP_AUDIENCES", PlatformModel.idpAudiences(env));
        // Keyed by the APPLICATION, not by the client id: the id carries the environment name and
        // a placeholder cannot be spelled with a value the template does not know yet.
        for (String app : PlatformModel.IDP_CLIENT_APPS) {
            values.put("IDP_SECRET_" + PlatformModel.clientKey(app),
                    boot.state.secrets.getOrDefault(PlatformModel.wireAlias(app, env), ""));
        }
        return values;
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }

    // --- small helpers ----------------------------------------------------------------------------

    private String create(PhaseContext ctx, List<String> command) {
        ProcessResult result = boot.docker.run(Cmd.of(command).timeout(Duration.ofMinutes(30)), ctx::log);
        Boot.must(result, "docker create failed");
        List<String> lines = result.captured();
        if (lines.isEmpty()) {
            throw new IllegalStateException("docker create printed no container id");
        }
        return lines.getLast().trim();
    }

    private void copyIn(PhaseContext ctx, Path source, String cid) {
        Boot.must(boot.docker.exec(Duration.ofMinutes(30), ctx::log,
                "cp", source + "/.", cid + ":/src"), "copying " + source + " into the container failed");
    }

    private void startAndReap(PhaseContext ctx, String cid, String failure) {
        ProcessResult result = boot.docker.exec(Docker.BUILD_TIMEOUT, ctx::log, "start", "-a", cid);
        if (!result.ok()) {
            boot.docker.removeContainer(cid, null);
            throw new IllegalStateException(failure + "\n" + result.tailText(20));
        }
        boot.docker.exec(null, "rm", cid);
    }

    static String shortSha(String sha) {
        return sha == null ? "" : sha.substring(0, Math.min(12, sha.length()));
    }

    private static String sha256(Path file) throws Exception {
        return sha256(Files.readAllBytes(file));
    }

    /** The same digest sha256sum prints for the bytes a container's {@code cat} would write. */
    private static String sha256(String text) throws Exception {
        return sha256(text.getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256(byte[] bytes) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(bytes);
        StringBuilder hex = new StringBuilder(hash.length * 2);
        for (byte b : hash) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }

    /**
     * Random rather than a fixed default, which is where this parts ways with the push token: that
     * value has to be nameable in the docs that teach the escape hatch, and these are never typed
     * by anyone.
     */
    private static String randomSecret() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        StringBuilder hex = new StringBuilder();
        for (byte b : bytes) {
            hex.append(String.format("%02x", b));
        }
        return hex.substring(0, 32);
    }

    /** Used by the phase list to decide whether the seed is needed at all. */
    public boolean artifactsAnswering() {
        Http.Response response = boot.artifacts.health();
        return response.ok();
    }
}
