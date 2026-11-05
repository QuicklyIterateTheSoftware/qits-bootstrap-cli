# HOW THE RELEASED BINARY IS BUILT: `qits`, a fully static musl native image that runs on any Linux,
# glibc or musl (alpine) alike. The last stage is a `FROM scratch` holding the one file, and a build
# is an `--output type=local` of it.
#
# AND THE ONE IMAGE THIS REPOSITORY SHIPS, `--target service`: the MCP service
# (platform-access-mcp-service), the same commands served as MCP tools at /mcp. Its own build stage
# (`service-build`), a stage that runs the native runner's integration tests against it
# (`service-it`), its own SBOM stage (`service-sbom`) and a ubi-minimal runtime stage (`service`),
# all before `binary`, so a plain build still yields `qits`. The release step builds and pushes it
# beside the binary, from the same release (`.config/qits/release.yml`).
#
# The shape is qits-ci-daemon's: one build stage on the musl toolchain, two `FROM scratch` export
# stages (sbom, then binary, which stays the default target), the `ldd` check on the same RUN as the
# compile, and the CycloneDX document from the same mvnw call, so a release after a green fold is
# cache hits. That file carries the long reasons.
#
# THE TOOLCHAIN IS A STAGE OF THIS FILE, NOT A REGISTRY TAG. The first release request named
# `graalvmce-musl-builder:jdk-25` in the platform registry, and failed with "not found": only
# qits-ci-daemon's pipelines push that tag, and the bootstrap builds the image into the host's image
# store, not into the registry. So after a cold boot the tag is missing until qits-ci-daemon next
# folds or releases. Built here, the toolchain needs no other repository's run. Nothing is pushed:
# BuildKit uses the stage directly, and no second pipeline writes that tag.
#
# The `tarballs` and `toolchain` stages are qits-ci-daemon's docker/Dockerfile.musl-builder, line
# for line; that file says why GraalVM CE and not Mandrel, why a musl-gcc shim, and why ADD. Keep
# them identical: the platform builder then shares their cache with qits-ci-daemon's builds. A
# toolchain bump there is a bump here.
#
# THE TARBALL URLS ARE THE RECIPES'. The defaults name registry.qits.$QITS_DOMAIN, the public edge
# host; the recipes keep only the path after /artifacts/maven/maven and pass their own Maven root,
# as qits-ci-daemon's do. The bootstrap seeds both tarballs into the store on every boot.
#
# ON A REMOTE RUNNER (the EDGE plane, epic qits-441) THAT ROOT IS THE PUBLIC ONE, and the edge
# answers an anonymous read of it with 401. The ADD still works because BuildKit's HTTP source
# attaches `Authorization: Bearer <value>` from a session secret named `HTTP_AUTH_TOKEN_<url host>`
# when one exists, and sends nothing when none does; the recipes pass that secret, from the run's
# `$QITS_TOKEN`, only on the EDGE plane, so an in-network read stays anonymous. Nothing here changes
# for it. qits-ci-daemon's file carries the long reasons, and why not `RUN … curl`.
#
# A hand build off the platform passes the upstream URLs and base:
#
#     docker build --target binary --output type=local,dest=out -f docker/Dockerfile \
#       --build-arg TOOLCHAIN_BASE=quay.io/quarkus/ubi10-quarkus-graalvmce-builder-image:jdk-25 \
#       --build-arg MUSL_URL=https://more.musl.cc/11.2.1/x86_64-linux-musl/x86_64-linux-musl-native.tgz \
#       --build-arg ZLIB_URL=https://github.com/madler/zlib/releases/download/v1.3.1/zlib-1.3.1.tar.gz .
#
# or skips the toolchain stages with a toolchain image built from qits-ci-daemon's file:
#
#     docker build --build-arg BUILDER_IMAGE=qits/graalvmce-musl-builder:jdk-25 ...
#
# The host build (`./mvnw package -Dnative`) stays glibc-linked: it needs no musl toolchain, because
# the static flags are added here only.
# The platform's public domain; the release recipes pass `--opt build-arg:QITS_DOMAIN`. Global, so
# the FROM lines and the ARG defaults below can read it. REQUIRED, no default: qits-ci now injects
# this build arg into every step unconditionally, and a hand build must pass it too (the header's
# example does). An unset QITS_DOMAIN makes the `FROM mirror.qits.${QITS_DOMAIN}/...` lines below
# (TOOLCHAIN_BASE and the service runtime base) resolve to an invalid image reference, which
# BuildKit already refuses on its own; the `: "${QITS_DOMAIN:?...}"` guards on the RUN steps below
# exist to fail loudly at the step that actually needs the value, rather than let an empty
# substitution build a wrong URL silently.
ARG QITS_DOMAIN
ARG MUSL_URL=https://registry.qits.${QITS_DOMAIN}/artifacts/maven/maven/eu/wohlben/qits/toolchain/x86_64-linux-musl-native/11.2.1/x86_64-linux-musl-native-11.2.1.tgz
ARG ZLIB_URL=https://registry.qits.${QITS_DOMAIN}/artifacts/maven/maven/eu/wohlben/qits/toolchain/zlib/1.3.1/zlib-1.3.1.tar.gz
# mirror.qits.$QITS_DOMAIN is qits-mirror's pull-through cache, on the edge.
ARG TOOLCHAIN_BASE=mirror.qits.${QITS_DOMAIN}/quay/quarkus/ubi10-quarkus-graalvmce-builder-image:jdk-25
# A stage name by default. An image name instead skips the two toolchain stages.
ARG BUILDER_IMAGE=toolchain

FROM scratch AS tarballs
# An ARG before the first FROM is global; a stage that reads one names it again.
ARG MUSL_URL
ARG ZLIB_URL
ADD $MUSL_URL /musl.tgz
ADD $ZLIB_URL /zlib.tar.gz

FROM ${TOOLCHAIN_BASE} AS toolchain
USER root

RUN --mount=type=bind,from=tarballs,target=/tarballs \
    mkdir -p /opt/musl \
 && tar -xzf /tarballs/musl.tgz -C /opt/musl --strip-components=1 \
 && /opt/musl/bin/x86_64-linux-musl-gcc --version

# Only the target-prefixed names on PATH, so the system gcc stays the default.
RUN mkdir -p /opt/musl-shim \
 && for f in /opt/musl/bin/x86_64-linux-musl-*; do ln -s "$f" "/opt/musl-shim/$(basename "$f")"; done
ENV PATH=/opt/musl-shim:$PATH

# native-image links `-lz`, so zlib is built for the musl target into the same prefix.
RUN --mount=type=bind,from=tarballs,target=/tarballs \
    mkdir -p /tmp/zlib \
 && tar -xzf /tarballs/zlib.tar.gz -C /tmp/zlib --strip-components=1 \
 && cd /tmp/zlib \
 && CC=/opt/musl/bin/x86_64-linux-musl-gcc ./configure --static --prefix=/opt/musl \
 && make -j"$(nproc)" && make install \
 && rm -rf /tmp/zlib

FROM ${BUILDER_IMAGE} AS build
# A toolchain image built from qits-ci-daemon's file ends on user 1001, and the context arrives
# owned by root.
USER root
WORKDIR /qits-build

# .dockerignore keeps target/, .git and out/ out, so a stale host build cannot shadow this one.
COPY . .

# Maven Central through qits-mirror's cache, https://mirror.qits.$QITS_DOMAIN/mirror/maven/central,
# derived inside the mvnw RUN below from the one platform input. The host answers 401 anonymously,
# so the same RUN mounts the two CI secrets and exports them as QITS_MAVEN_AUTH_USR/PSW for
# `qits-central-proxy`; a hand build with no secrets leaves both empty and fails at the first 401.
#
# The platform's own Maven, https://registry.qits.$QITS_DOMAIN/artifacts/maven/maven, derived the same
# way, for the one artifact the reactor takes from it: qits-projects' golden masters jar, a TEST
# dependency of the commands (their consumer pact). `-DskipTests` still compiles the tests, so the
# jar is still resolved here; the same two secrets authenticate `qits-maven-network`.
ARG QITS_DOMAIN

# - additional-build-args-APPEND: the pom's native profile sets additional-build-args (Pkce's
#   run-time initialisation), and a second value for that key would replace it, not add to it.
# - container-build=false: this is already the container Quarkus would start.
# - native-image-xmx=4g bounds the compile, as the sibling daemons do.
# - `ldd`, not `file`: GraalVM writes a static-pie executable, and `file` never says "statically
#   linked". A glibc-linked binary does not start on an alpine image, so the check fails the build.
# - makeBom on the same call: the document describes the very resolve that made the bytes.
#   cyclonedx.skipNotDeployed=false because the application module is deployed to no Maven
#   repository (maven.deploy.skip), and the plugin skips such a project quietly (measured on
#   qits-artifacts-service's first release).
#
# THE PROGRAM AND WHAT IT IS BUILT FROM: `-pl platform-access-cli -am`. Since 2026-09-17 this
# repository is a reactor, and the binary lands in `platform-access-cli/target/qits` rather than
# `target/qits`. `-am` builds the commands library (`platform-access-commands`) the program depends
# on, and the root pom; the pin jar that tells qits-ci which binary to fetch is no part of the
# binary and is left out, and its own release step builds and deploys it. Nothing is deployed from
# in here. makeBom runs per module of that same reactor, and the application module's document is
# the one the sbom stage exports.
RUN --mount=type=secret,id=qits-client-id \
    --mount=type=secret,id=qits-client-secret \
    : "${QITS_DOMAIN:?QITS_DOMAIN build arg is required}"; \
    export QITS_MAVEN_AUTH_USR="$(cat /run/secrets/qits-client-id 2>/dev/null || true)"; \
    export QITS_MAVEN_AUTH_PSW="$(cat /run/secrets/qits-client-secret 2>/dev/null || true)"; \
    export QITS_MAVEN_CENTRAL_URL="https://mirror.qits.$QITS_DOMAIN/mirror/maven/central"; \
    export QITS_MAVEN_REPOSITORY_URL="https://registry.qits.$QITS_DOMAIN/artifacts/maven/maven"; \
    ./mvnw -B -ntp -s .qits-maven-settings.xml package -Dnative -DskipTests \
      -Dqits.maven.repository.url="$QITS_MAVEN_REPOSITORY_URL" \
      -pl platform-access-cli -am \
      -Dquarkus.native.container-build=false \
      -Dquarkus.native.additional-build-args-append=--static,--libc=musl \
      -Dquarkus.native.native-image-xmx=4g \
      org.cyclonedx:cyclonedx-maven-plugin:2.9.1:makeBom \
      -DoutputFormat=json -DoutputName=sbom -DschemaVersion=1.6 \
      -Dcyclonedx.skipNotDeployed=false \
 && ldd platform-access-cli/target/qits 2>&1 | grep -qE "statically linked|not a dynamic executable"

# `qits tui` in the binary, which is the only place JLine's provider choice can be proven: the tests
# run on a JVM, where every provider works. Started with no terminal — which is what a RUN is — it
# must say so and exit 2. The failure this catches is a binary that waits for a key nobody can
# press, so it runs under a timeout: a build that hangs here would otherwise hang forever.
RUN set -eu; \
    out=$(timeout 60 ./platform-access-cli/target/qits tui < /dev/null 2>&1 || echo "exit=$?"); \
    printf '%s\n' "$out"; \
    printf '%s' "$out" | grep -q "needs an interactive terminal"; \
    printf '%s' "$out" | grep -q "exit=2"

# THE MCP SERVICE'S NATIVE RUNNER, A SECOND COMPILE IN A SECOND STAGE. Not on the `build` RUN: one
# mvnw call over both modules would run two native-image processes in one RUN, and each is sized to
# the 4g step cap on its own. Everything else is `build`'s, argument for argument — the same COPY,
# the same two secrets, the same static musl flags and xmx — so the two stages share every layer up
# to the mvnw call. `-pl platform-access-mcp-service -am` builds the commands library and the root
# pom with it, not the CLI. makeBom on the same call, for the same reason as `build`'s, and
# skipNotDeployed=false for the same reason too: the service module is deployed to no Maven
# repository.
#
# THE SAME `ldd` CHECK AS THE BINARY'S. The runtime base is ubi-minimal and has a glibc, so a
# dynamic runner would start there; the check holds anyway, because a dynamic runner means the musl
# flags did not reach this compile, and then this is not the build the header describes. The runner
# is not started on this RUN; `service-it` below starts it under its tests and stops it again.
FROM ${BUILDER_IMAGE} AS service-build
USER root
WORKDIR /qits-build
COPY . .
ARG QITS_DOMAIN
RUN --mount=type=secret,id=qits-client-id \
    --mount=type=secret,id=qits-client-secret \
    : "${QITS_DOMAIN:?QITS_DOMAIN build arg is required}"; \
    export QITS_MAVEN_AUTH_USR="$(cat /run/secrets/qits-client-id 2>/dev/null || true)"; \
    export QITS_MAVEN_AUTH_PSW="$(cat /run/secrets/qits-client-secret 2>/dev/null || true)"; \
    export QITS_MAVEN_CENTRAL_URL="https://mirror.qits.$QITS_DOMAIN/mirror/maven/central"; \
    export QITS_MAVEN_REPOSITORY_URL="https://registry.qits.$QITS_DOMAIN/artifacts/maven/maven"; \
    ./mvnw -B -ntp -s .qits-maven-settings.xml package -Dnative -DskipTests \
      -Dqits.maven.repository.url="$QITS_MAVEN_REPOSITORY_URL" \
      -pl platform-access-mcp-service -am \
      -Dquarkus.native.container-build=false \
      -Dquarkus.native.additional-build-args-append=--static,--libc=musl \
      -Dquarkus.native.native-image-xmx=4g \
      org.cyclonedx:cyclonedx-maven-plugin:2.9.1:makeBom \
      -DoutputFormat=json -DoutputName=sbom -DschemaVersion=1.6 \
      -Dcyclonedx.skipNotDeployed=false \
 && ldd platform-access-mcp-service/target/qits-platform-access-mcp-service 2>&1 \
      | grep -qE "statically linked|not a dynamic executable"

# THE MCP SERVICE'S NATIVE RUNNER, UNDER ITS OWN TESTS (qits-1226): failsafe's *IT classes, which
# are @QuarkusIntegrationTest and start the runner `service-build` made as a process of its own —
# McpWireJsonIT first, which fails a runner whose Vert.x JSON codec was frozen at image build time
# with a bare ObjectMapper (every tool result then goes out with `"_meta":null` and
# `"structuredContent":null`, and MCP clients reject it). Whether a native build freezes it is not
# deterministic, so only the very bytes the image ships can be tested for it, and a JVM test never
# sees it. A red IT fails this RUN, so the buildctl build, so the release request's QA step.
#
# A STAGE OF ITS OWN, AND THE IMAGE TAKES ITS RUNNER FROM HERE. BuildKit builds only what the target
# reaches; `service` copying the runner out of this stage is what makes every `--target service`
# build run the tests, and makes the image the runner that passed them. The compile above stays its
# own cached layer: a re-run after a red IT is the tests again, never a second native compile. The
# release step's `service` build is the QA build's, args and secrets byte for byte, so it is cache
# hits here too and the tests do not run twice. `service-sbom` stays on `service-build`: the document
# describes the compile, which the tests do not change.
#
# NO SECOND COMPILE, NO RE-PACKAGE. The goals are named, not the lifecycle: `verify` would run the
# quarkus build goal again. `test-compile` over the same `-pl … -am` reactor is up to date (the test
# classes were compiled by `service-build`'s `-DskipTests` package) and is what lets the reactor
# hand the service module the commands module's classes; `-Dnative` is the service pom's native
# profile, which binds failsafe and points it at target/qits-platform-access-mcp-service, the runner
# target/quarkus-artifact.properties already names. The same secrets and the same derived addresses
# as `service-build`'s RUN, because the local repository that RUN filled has every dependency but
# what only a test run resolves — failsafe itself and its JUnit provider — and those come from the
# same two hosts. The runner listens on the RUN's own loopback, on quarkus.http.test-port's 8081.
FROM service-build AS service-it
ARG QITS_DOMAIN
RUN --mount=type=secret,id=qits-client-id \
    --mount=type=secret,id=qits-client-secret \
    : "${QITS_DOMAIN:?QITS_DOMAIN build arg is required}"; \
    export QITS_MAVEN_AUTH_USR="$(cat /run/secrets/qits-client-id 2>/dev/null || true)"; \
    export QITS_MAVEN_AUTH_PSW="$(cat /run/secrets/qits-client-secret 2>/dev/null || true)"; \
    export QITS_MAVEN_CENTRAL_URL="https://mirror.qits.$QITS_DOMAIN/mirror/maven/central"; \
    export QITS_MAVEN_REPOSITORY_URL="https://registry.qits.$QITS_DOMAIN/artifacts/maven/maven"; \
    ./mvnw -B -ntp -s .qits-maven-settings.xml -Dnative \
      -Dqits.maven.repository.url="$QITS_MAVEN_REPOSITORY_URL" \
      -pl platform-access-mcp-service -am \
      test-compile failsafe:integration-test failsafe:verify

# THE TAPES' KIT (epic qits-881): what `tapes/prepare.sh` needs to play the VHS tapes, exported with
# `--target tapes-kit --output type=local,dest=<dir>`. The tapes run the binary this file builds, so
# the kit is `build`'s own `qits` — the very bytes `binary` exports — beside the class path of the
# fake platform they talk to (FakePlatformMain, in the commands module's test classes):
#
#     qits            the static musl binary, from `build`
#     classes/        platform-access-commands' main classes, which the test classes use
#     test-classes/   its test classes and test resources (the fakes, the tapes' fixtures)
#     lib/            its test-scope dependency jars (the golden masters among them)
#
# A STAGE ON TOP OF `build`, NOT A SECOND BUILD. `build`'s `-DskipTests` package already compiled
# the test classes and resolved every test-scope jar into its local repository, so the one RUN here
# copies them out: `dependency:copy-dependencies`, pinned by version so no plugin metadata is
# resolved, with the same secrets and the same derived addresses as `build`'s RUN in case the
# plugin itself is not cached yet. The release-request step that plays the tapes passes the same
# build args and secrets as the QA build, so `build` is a cache hit and the kit costs this RUN only.
FROM build AS tapes-kit-build
ARG QITS_DOMAIN
RUN --mount=type=secret,id=qits-client-id \
    --mount=type=secret,id=qits-client-secret \
    : "${QITS_DOMAIN:?QITS_DOMAIN build arg is required}"; \
    export QITS_MAVEN_AUTH_USR="$(cat /run/secrets/qits-client-id 2>/dev/null || true)"; \
    export QITS_MAVEN_AUTH_PSW="$(cat /run/secrets/qits-client-secret 2>/dev/null || true)"; \
    export QITS_MAVEN_CENTRAL_URL="https://mirror.qits.$QITS_DOMAIN/mirror/maven/central"; \
    export QITS_MAVEN_REPOSITORY_URL="https://registry.qits.$QITS_DOMAIN/artifacts/maven/maven"; \
    ./mvnw -B -ntp -s .qits-maven-settings.xml \
      -Dqits.maven.repository.url="$QITS_MAVEN_REPOSITORY_URL" \
      -pl platform-access-commands \
      org.apache.maven.plugins:maven-dependency-plugin:3.8.1:copy-dependencies \
      -DincludeScope=test -DoutputDirectory=/tapes-kit/lib \
 && cp -r platform-access-commands/target/classes /tapes-kit/classes \
 && cp -r platform-access-commands/target/test-classes /tapes-kit/test-classes \
 && cp platform-access-cli/target/qits /tapes-kit/qits

FROM scratch AS tapes-kit
COPY --from=tapes-kit-build /tapes-kit/ /

# The CycloneDX document, for the release step's SBOM PUT. Before `binary`, so that stays the
# default. The application module's document: the closure that made the bytes.
FROM scratch AS sbom
COPY --from=build /qits-build/platform-access-cli/target/sbom.json /sbom.json

# The service's document, exported to out/service by the release step, which declares it as the
# image's `sbom:`. Same args and secrets as the image build, so it is a cache hit on that compile.
FROM scratch AS service-sbom
COPY --from=service-build /qits-build/platform-access-mcp-service/target/sbom.json /sbom.json

# THE IMAGE: qits/qits-platform-access-mcp-service, deployed as the application of the same name
# (`.config/qits/deployments.yml`). The runtime base and its shape are qits-ci-service's final
# stage: `mirror.qits.${QITS_DOMAIN}/redhat/…` is qits-mirror's pull-through cache pointed at
# registry.access.redhat.com, /work, user 1001, nothing installed. The runner keeps its
# `quarkus.package.output-name`, so `ps` shows what the edge routes to. It comes from `service-it`,
# not `service-build`: the same bytes, and the reason the tests run at all (see that stage).
FROM mirror.qits.${QITS_DOMAIN}/redhat/ubi9/ubi-minimal:9.6 AS service
WORKDIR /work
COPY --from=service-it --chown=1001:root \
     /qits-build/platform-access-mcp-service/target/qits-platform-access-mcp-service \
     /work/qits-platform-access-mcp-service
RUN chmod +x /work/qits-platform-access-mcp-service
EXPOSE 8080
USER 1001
# Quarkus binds 127.0.0.1 outside dev mode, which inside a container means nothing can reach it.
ENTRYPOINT ["/work/qits-platform-access-mcp-service", "-Dquarkus.http.host=0.0.0.0"]

# The artifact: `--output type=local,dest=out` writes out/qits. The export layout is unchanged by
# the reactor split on purpose — every recipe and every hand build reads `out/qits` and `/sbom.json`
# as before; only the paths inside the build stage moved.
FROM scratch AS binary
COPY --from=build /qits-build/platform-access-cli/target/qits /qits
