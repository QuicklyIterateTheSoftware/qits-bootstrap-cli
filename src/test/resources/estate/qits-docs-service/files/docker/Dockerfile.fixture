# qits-docs as a GraalVM-native container IMAGE — the platform's two-stage shape: a Mandrel
# builder stage compiles the binary, a minimal runtime stage carries only it.
#
# This file is not how the binary is built; it is how the binary is SHIPPED. `.sdkmanrc` names a
# GraalVM (25.0.2-graalce), so `sdk env && ./mvnw package -Dnative` produces
# target/qits-docs locally with no container involved. What this adds is the image around
# it — and, incidentally, a way for a machine with no GraalVM to still get a binary, since the
# builder stage brings its own native-image.
#
# Build from the repo root:
#
#     docker build \
#       --build-arg QITS_DOMAIN=wohlben.eu \
#       --secret id=qits-client-id,env=QITS_MAVEN_AUTH_USR \
#       --secret id=qits-client-secret,env=QITS_MAVEN_AUTH_PSW \
#       -t qits/qits-docs:latest -f docker/Dockerfile .
#
# A CREDENTIAL IS NOW REQUIRED, and it arrived with the userflows rollout for a reason that has
# nothing to do with credentials: this pom used to resolve everything from Maven Central — the
# header of .config/qits/ci-post-receive.yml said so at length — and it no longer does.
# `qits-userflows`, the TEST-scope jar the userflow story catalogue is written against, is published
# to the platform's own Maven repository and available from nowhere else. Test scope is not a
# reprieve, because this stage deliberately runs the suite (see the absent -DskipTests below) and
# Maven resolves the test classpath to compile it either way. The registry is a public host now —
# `registry.qits.$QITS_DOMAIN`, derived by the ARG below — reachable from a RUN step like any other
# address, but it answers 401 without a credential.
#
#   --build-arg QITS_DOMAIN   the public domain the two registry hosts are composed under. The ARG
#                             below turns it into -Dqits.maven.repository.url, which the pom's
#                             `qits-maven` repository reads and .qits-maven-settings.xml mirrors.
#
# THE CLIENT IS BUILT BEFORE THIS IMAGE, NOT IN IT — qits-gateway's deviation from the platform's
# Quinoa recipe, for its reason exactly. src/main/webui is qits-spa-docs and it depends on
# @qits/ui-components, which exists ONLY on the platform's own npm registry
# (registry.qits.$QITS_DOMAIN), and that answers 401 without a credential this stage is not handed.
# So the install happens where it can — the CI step container, or a developer's host — and this
# stage packages an already-built dist/ with Quinoa's install and build commands replaced by
# `--version`. An install that "worked" here without the credential would have resolved the @qits
# scope against npmjs.org, where those names do not exist.

# GLOBAL ARG, above the first FROM: this is the platform's public domain, and a build ARG
# declared after a FROM is invisible to FROM lines, only to the stage's own RUN/COPY. The
# release recipes pass --opt build-arg:QITS_DOMAIN; the default below is the live platform's.
ARG QITS_DOMAIN=wohlben.eu

# --- stage `build`: native-compile ---------------------------------------------------------------
# The builder JDK MUST be 25: native-image cannot read bytecode newer than its own JDK, and this
# project targets release 25.
#
# `mirror.qits.$QITS_DOMAIN/quay/…` is qits-mirror's pull-through cache of quay.io: the
# first build fetches from upstream and caches it, every later build is served from local disk. The
# tag is upstream's, unchanged — the mirror revalidates it, so `jdk-25` still moves when quay moves
# it.
FROM mirror.qits.${QITS_DOMAIN}/quay/quarkus/ubi9-quarkus-mandrel-builder-image:jdk-25 AS build
USER root
WORKDIR /src

# Fetch the Maven distribution first, keyed only on the wrapper files, so a source-only change does
# not re-download it.
COPY mvnw .
COPY .mvn/ .mvn/
RUN ./mvnw --version

# The whole tree, not a file list — qits-gateway's reasoning verbatim: a file list has to be edited
# every time the repo gains top-level content the build reads, nothing enforces that, and the
# failure lands far from the cause. `.dockerignore` is what keeps this cheap.
COPY . .

# -Dnative flips quarkus.native.enabled (this project carries its own `native` profile). Cap
# native-image RAM so the build is deterministic on small machines.
#
# NOTE THE ABSENT -DskipTests, which every sibling's builder stage carries. This is where the suite
# runs, and it is the only place it can: the CI step container has node and docker and no JDK, so a
# `./mvnw` line there fails on JAVA_HOME. The suite needs no docker and no running qits-artifacts —
# it is a handful of cases over the route grammar and the version parse — so it costs seconds and it
# stops a broken grammar here rather than at a deploy. What it DOES need since the userflows
# rollout is the platform's Maven repository, to resolve the test-scope jar; that is what the
# two flags in the header are for.
# The `cp` onto itself before the build is not redundant: Quinoa MOVES build-dir into
# target/quinoa/build, and overlayfs cannot rename a directory that still lives in a LOWER image
# layer. It answers EXDEV, the JDK falls back to its cross-file-store path, and that path refuses a
# non-empty directory — the build dies seconds in with DirectoryNotEmptyException naming the source
# and explaining nothing. Every sibling whose builder stage CREATES dist is immune by accident;
# ours arrives by COPY. It must be in THIS RUN, because a layer boundary is what it is undoing.
#
# The `test -f` stops a context with no bundle before the multi-minute native compile. A forgotten
# `npm run build` in the CI step is then a red build rather than a service that ships without its
# reading room.
#
# `-s .qits-maven-settings.xml` is what lets the `qits-maven` repository be resolved at all: an
# exact repository-id mirror is how the derived URL below reaches the pom's `qits-maven` repository.
#
# THE ABSENT -DskipITs IS NOT AN OVERSIGHT EITHER. pom.xml now sets `skipITs` false — this
# repository's integration tests are all userflow stories, and they are cheap — but failsafe binds
# to the integration-test phase, which is AFTER package. Stopping at `package` is what keeps this stage
# from launching the artifact it just compiled.
#
# --- and where the two qits jars and Maven Central come from --------------------------------------
# Both platform hosts are code plus one input, the public domain:
#
#   https://registry.qits.$QITS_DOMAIN/artifacts/maven/maven   hosted Maven (qits-artifacts)
#   https://mirror.qits.$QITS_DOMAIN/mirror/maven/central      Maven Central cache (qits-mirror)
#
# The RUN below derives both and exports them for that RUN alone; `.qits-maven-settings.xml` reads
# them as ${env.…}, mirroring the `qits-maven` repository id onto the first and Central onto the
# second. The CI recipe passes QITS_DOMAIN; the default is the live platform's.
ARG QITS_DOMAIN=wohlben.eu

# --- and how Maven authenticates --------------------------------------------------------------------
# Both hosts answer 401 anonymously, so every build that resolves through them needs a credential.
# The two secret mounts put the CI run's commissioned client into THIS `RUN`'s environment and
# nowhere else — not a layer, not the image history. That is why they are not build args: this image
# is pullable, and a build arg is recorded where anyone who pulls it can read it.
# `.qits-maven-settings.xml` presents them as the username and password of both mirrors.
#
# FILE FORM, NOT `env=`. The mounts are bare (`/run/secrets/<id>`, read into this command's own
# environment by the two `cat`s below) rather than the `env=` mount form they used to be: `env=`
# needs a dockerfile frontend that supports it, and under the platform's own buildkitd the frontend
# is the BUILDER's to pick rather than this file's, while the bare file mount is served identically
# by every frontend version. See qits-buildkit-plan.md in the wrapper.
#
# Absent secrets leave both variables empty — the `|| true` — so a hand build without them still
# starts, and fails at the first 401 with Maven naming the host. Pass your own client to build by
# hand: `--secret id=qits-client-id,env=… --secret id=qits-client-secret,env=…`.
RUN --mount=type=secret,id=qits-client-id \
    --mount=type=secret,id=qits-client-secret \
    export QITS_MAVEN_AUTH_USR="$(cat /run/secrets/qits-client-id 2>/dev/null || true)"; \
    export QITS_MAVEN_AUTH_PSW="$(cat /run/secrets/qits-client-secret 2>/dev/null || true)"; \
    export QITS_MAVEN_REPOSITORY_URL="https://registry.qits.$QITS_DOMAIN/artifacts/maven/maven"; \
    export QITS_MAVEN_CENTRAL_URL="https://mirror.qits.$QITS_DOMAIN/mirror/maven/central"; \
    d=src/main/webui/dist/qits-spa-docs/browser; \
    test -f "$d/index.html" || { \
      echo "No built client at $d." >&2; \
      echo "The build context must carry one: run 'npm ci && npm run build'" >&2; \
      echo "in src/main/webui first — this stage cannot, see the header." >&2; \
      exit 1; }; \
    cp -a "$d" "$d.layer" && rm -rf "$d" && mv "$d.layer" "$d" && \
    ./mvnw -B -ntp -s .qits-maven-settings.xml \
      org.cyclonedx:cyclonedx-maven-plugin:2.9.1:makeBom \
      -DoutputFormat=json -DoutputName=sbom -DschemaVersion=1.6 \
      -Dcyclonedx.skipNotDeployed=false \
      package -Dnative \
      -Dqits.maven.repository.url="$QITS_MAVEN_REPOSITORY_URL" \
      -Dquarkus.native.native-image-xmx=4g \
      -Dquarkus.quinoa.package-manager-install=true \
      -Dquarkus.quinoa.package-manager-install.node-version=22.22.0 \
      -Dquarkus.quinoa.package-manager-command.install=--version \
      -Dquarkus.quinoa.package-manager-command.ci=--version \
      -Dquarkus.quinoa.package-manager-command.build=--version
# makeBom goes FIRST, BEFORE `package`, and the order is LOAD-BEARING (qits-441). Quinoa's node
# install (package-manager-install=true, inside quarkus:build) runs the embedded frontend-plugin
# `VertxFileDownloader`, whose first line is `System.setProperty("https.protocols", "TLSv1.2")` —
# JVM-wide, for the rest of this Maven process. Every HTTPS connection Maven opens after it offers
# TLS 1.2 only. The public edge (mirror.qits.wohlben.eu, which a remote runner's build resolves
# Central through) speaks TLS 1.3 only, so each of those handshakes dies at once with `Received
# fatal alert: protocol_version`, and makeBom's plugin dependencies — resolved lazily, when the
# goal runs — fail as five "Failed to read artifact descriptor" lines with no HTTP status. The
# in-network mirror is plain http:// and never notices, which is why only EDGE-plane runs failed.
# Ahead of `package`, makeBom resolves before Quinoa has touched the property; the SBOM is the
# same, since it reads the dependency graph and not the build's output.
#
# cyclonedx.skipNotDeployed=false is LOAD-BEARING, measured on qits-artifacts' first SBOM release
# (2026.902.45658). `<packaging>quarkus</packaging>` skips maven-deploy — this project ships as this
# image, not as a jar — and the plugin SKIPS deploy-skipping modules by default, with a green build
# and one INFO line. The sbom stage's COPY then fails on a file nothing wrote. There is no
# deploy configuration anywhere in pom.xml to make that visible, which is exactly why the flag reads
# as redundant and is not. The property spelling matters too: the bare -DskipNotDeployed=false is
# silently ignored; only the cyclonedx.-prefixed one reaches the plugin.
#
# NOTE THE TARGET PATH BELOW. This repository is ONE module — no `service/`, no `-pl` on the lines
# above — so the document lands in /src/target beside the binary, where every sibling's is one level
# deeper.

# --- sbom ------------------------------------------------------------------------------------------
# The project's CycloneDX document, exported so the release step can PUT it to the artifacts store. A
# stage rather than a layer of the runtime image, and BEFORE the runtime stage on purpose: the last
# stage is what a plain `docker build` builds, and that must stay the image. The release step reaches
# this one with `--opt target=sbom --output type=local` using the SAME build-args and secrets as the
# image build, so every layer above is a cache hit and the export costs seconds — which matters more here
# than anywhere, since this stage's mvnw also runs the suite. `makeBom` rides on that one invocation
# rather than a second one, for the same reason: one RUN, one layer, one truth.
FROM scratch AS sbom
COPY --from=build /src/target/sbom.json /sbom.json

# --- runtime -------------------------------------------------------------------------------------
# `mirror.qits.$QITS_DOMAIN/redhat/…` is the same mirror, pointed at registry.access.redhat.com.
FROM mirror.qits.${QITS_DOMAIN}/redhat/ubi9/ubi-minimal:9.6
WORKDIR /work

# `quarkus.package.output-name` names the binary `qits-docs` (no -runner suffix), so the
# process is identifiable in `ps`/`docker top`.
COPY --from=build --chown=1001:root /src/target/qits-docs /work/qits-docs
RUN chmod +x /work/qits-docs

EXPOSE 8080
USER 1001

# The one address this service needs is configuration: QITS_DOCS_ARTIFACTS_URL. Its default
# is the qits-net alias and is right for an ordinary deployment — see README.md.
ENTRYPOINT ["/work/qits-docs", "-Dquarkus.http.host=0.0.0.0"]
