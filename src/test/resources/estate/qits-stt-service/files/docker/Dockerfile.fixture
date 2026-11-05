# qits-stt as a GraalVM-native container IMAGE — the same shape as qits-gateway's and
# qits-workspace-daemon's docker/Dockerfile: a Mandrel builder stage compiles the binary, a minimal
# runtime stage carries only it.
#
# This file is not how the binary is built; it is how the binary is SHIPPED. `.sdkmanrc` names a
# GraalVM (25.0.2-graalce), so `sdk env && ./mvnw package -Dnative` produces `service/target/qits-stt`
# in about 40 seconds with no container involved. What this adds is the image around it — and a way
# for a machine with no GraalVM to get a binary at all, since the builder stage brings its own
# native-image.
#
# Build from the repo root:
#
#     docker build -t qits/stt:latest -f docker/Dockerfile .
#
# --- READ THIS BEFORE DEPLOYING: the binary is not the whole service ------------------------------
# stt is HOST-SIDE by design (README.md, "Why this is its own service"): the transcription itself is
# NVIDIA Parakeet under a resident python process that this service spawns and talks to over pipes.
# So the runtime stage below is deliberately not the usual "the binary and nothing else" — a python
# interpreter is part of this service's runtime, and no config value substitutes for it.
#
#   python3.12  `TranscriptionService.ensureReady()` shells `<python> -m venv <home>/venv` and then
#               `<home>/venv/bin/pip install --quiet onnx-asr[cpu,hub]`; `SpeechWorker` runs
#               `<home>/venv/bin/python <home>/transcribe_worker.py`. Absent, the service still
#               starts and still serves /stt/q/**, and only POST /stt/api/transcriptions fails — a
#               500 per request, deferred until someone actually speaks.
#
#               It is 3.12 and not `python3` because ubi9's `python3` is 3.9, and onnx-asr does not
#               publish for it: `pip install onnx-asr[cpu,hub]` under 3.9 fails with "Could not find
#               a version that satisfies the requirement ... (from versions: none)", which reads as
#               a missing package rather than a too-old interpreter. Since ubi9 gives 3.12 no
#               `/usr/bin/python3`, QITS_SPEECH_PYTHON below names it outright; leaving the default
#               to find a `python3` that this image does not have would only move the failure.
#
# What is NOT here, and cannot be: the venv, the ~183 MB of wheels, and the ~700 MB Parakeet/silero
# model pull from the Hugging Face hub. Those are built under `qits.speech.home` on first use, so
# **this container needs outbound access to PyPI and to huggingface.co** the first time it
# transcribes. Pre-seeding them into the image was considered and is pointless: `qits.speech.home`
# has to be a mounted volume (below), and a volume mount shadows whatever the image had at that
# path. A deployment that cannot reach either registry pre-seeds the VOLUME instead — README.md,
# "Operating it".
#
# --- and the trap that goes with it: qits.speech.home has no usable default here -------------------
# application.properties ships `qits.speech.home=${user.home}/.qits/speech`. UID 1001 has no
# /etc/passwd entry in ubi-minimal, and a native image resolves `user.home` through getpwuid(2), NOT
# from $HOME — so it resolves to the literal `?` and the venv, the worker script and the WAV staging
# dir all try to land in `/work/?/.qits/speech`, which 1001 cannot create. Setting `ENV HOME` does
# not fix that; it is deliberately not set, and neither is an /etc/passwd line for 1001.
#
# The reason to leave it broken rather than make it boot: unlike qits-artifacts, which dies at boot
# on the same `?`, nothing here touches the path until the FIRST TRANSCRIPTION (warmup-on-start is
# off by default, on purpose). A passwd entry would make this image come up healthy and then rebuild
# a 183 MB venv and re-download 700 MB of model into the container's writable layer on every single
# redeploy, silently. So a deployment says where that state lives:
#
#   docker run -v qits-stt-data:/data -e QITS_SPEECH_HOME=/data/speech qits/stt:latest
#
# `docker run` with no environment is still legitimate and still worth doing — it is what catches a
# packaging defect, and what caught the empty-`defaultValue` class of config bug in the daemon. It
# comes up and listens; it just cannot transcribe.
#
# The platform's public domain. Global (declared before the first FROM) so every FROM line can read
# it; the release recipes pass `--opt build-arg:QITS_DOMAIN`.
ARG QITS_DOMAIN=wohlben.eu

# --- stage `build`: native-compile ----------------------------------------------------------------
# The builder JDK MUST be 25: native-image cannot read bytecode newer than its own JDK, and this
# project targets release 25.
#
# `mirror.qits.$QITS_DOMAIN/quay/…` is qits-mirror's pull-through mirror of quay.io: the
# first build fetches the image from upstream and caches it, every later build is served from local
# disk. The tag is upstream's, unchanged — the mirror revalidates it, so `jdk-25` still moves when
# quay moves it.
FROM mirror.qits.${QITS_DOMAIN}/quay/quarkus/ubi9-quarkus-mandrel-builder-image:jdk-25 AS build
USER root
WORKDIR /src

# Fetch the Maven distribution first, keyed only on the wrapper files, so a source-only change does
# not re-download it.
COPY mvnw .
COPY .mvn/ .mvn/
RUN ./mvnw --version

# The whole reactor, not a module list. `-am` builds `service`'s dependency from source —
# `qits-stt-domain` is a 1.0.0-SNAPSHOT this repo never publishes (no distributionManagement, no
# <repositories>), so a builder container with an empty ~/.m2 has nowhere else to get it. Copying
# module by module would mean editing this file every time one is added, and forgetting to would
# fail the build with an unresolved dependency rather than anything that names the real cause.
# `.dockerignore` is what keeps this cheap — without it this line drags in the ~84 MB of target/
# already sitting here, and a stale one can shadow what this stage compiles.
COPY . .

# qits-auth-core is published in the platform Maven repository. Both platform hosts are code plus
# one input, the public domain:
#
#   https://registry.qits.$QITS_DOMAIN/artifacts/maven/maven   hosted Maven (qits-artifacts)
#   https://mirror.qits.$QITS_DOMAIN/mirror/maven/central      Maven Central cache (qits-mirror)
#
# The RUN below derives both and exports them for that RUN alone; `.qits-maven-settings.xml` reads
# them as ${env.…}, mirroring the `qits-maven` repository id onto the first and Central onto the
# second. The CI recipe passes QITS_DOMAIN; the default is the live platform's.
ARG QITS_DOMAIN=wohlben.eu

# `-pl service -am` names the APP, which is stable; it is not a module list and does not need
# editing when a module is added. (It is also not free to omit here the way qits-artifacts omits it:
# `domain`'s jandex/quarkus lifecycle runs either way, but selecting the app is what keeps a future
# non-dependency module — a fixture generator, a docs module — out of the image build.)
#
# -Dnative flips quarkus.native.enabled (the `native` profile lives in service/pom.xml, since only
# that module is an application). Cap native-image RAM so the build is deterministic on small
# machines.
#
# Tests are skipped because `./mvnw verify` on a clone is their gate (AGENTS.md) and re-running them
# here proves the same thing more slowly — while importing the known `Port already bound: 8081`
# @QuarkusTest flake into every image build. Stopping at `package` rather than `verify` also keeps
# the profile's `skipITs=false` from mattering.
#
# THE TWO SECRET MOUNTS ARE IN FILE FORM, WHICH IS A CHANGE AND A DELIBERATE ONE. They used to be
# `env=` mounts, which have the frontend put the value straight into the instruction's environment —
# convenient, and coupled to a dockerfile-frontend version. Under the platform builder the frontend
# is the BUILDER's to choose, not this file's, and the bare file mount (`/run/secrets/<id>`, read
# into the command's own environment) is the shape every frontend version serves identically. Same
# property either way: the credential is in this `RUN` and nowhere else — not a layer, not the image
# history, which is why it is never a `--build-arg`. Absent secrets leave both variables empty
# (`|| true` is every hand build's state), which `.qits-maven-settings.xml` reads exactly as it
# reads unset — and it reads them as the `qits-maven-network` server, sent only if the registry ever
# answers 401.
RUN --mount=type=secret,id=qits-client-id \
    --mount=type=secret,id=qits-client-secret \
    QITS_MAVEN_AUTH_USR="$(cat /run/secrets/qits-client-id 2>/dev/null || true)" \
    QITS_MAVEN_AUTH_PSW="$(cat /run/secrets/qits-client-secret 2>/dev/null || true)" \
    QITS_MAVEN_REPOSITORY_URL="https://registry.qits.$QITS_DOMAIN/artifacts/maven/maven" \
    QITS_MAVEN_CENTRAL_URL="https://mirror.qits.$QITS_DOMAIN/mirror/maven/central" \
    ./mvnw -B -ntp -s .qits-maven-settings.xml -pl service -am package -Dnative -DskipTests \
      -Dqits.maven.repository.url="https://registry.qits.$QITS_DOMAIN/artifacts/maven/maven" \
      -Dquarkus.native.native-image-xmx=4g \
      org.cyclonedx:cyclonedx-maven-plugin:2.9.1:makeBom \
      -DoutputFormat=json -DoutputName=sbom -DschemaVersion=1.6 \
      -Dcyclonedx.skipNotDeployed=false
# makeBom RIDES ON THE INVOCATION ABOVE rather than getting a second one. A second `./mvnw` would be
# a second layer on top of a native compile, and a re-resolve of the same reactor to produce a
# document the first run already had the model for. One RUN, one layer, one truth.
#
# cyclonedx.skipNotDeployed=false is LOAD-BEARING, measured in qits-artifacts-service on its first
# release: `service` is <packaging>quarkus</packaging> and ships as this image rather than as a jar,
# so it skips maven-deploy — and the plugin SKIPS deploy-skipping modules by default, with a green
# build and one INFO line. The sbom stage's COPY then fails on a file nothing wrote. The spelling
# matters too: a bare -DskipNotDeployed=false is silently ignored, and only the cyclonedx.-prefixed
# property reaches the plugin.
#
# WHAT THIS DOCUMENT IS AND IS NOT. It is the resolved Maven closure of the `service` module — the
# jars this binary is compiled from. It is NOT the venv: onnx-asr and its ~183 MB of wheels are
# pip's, fetched on first transcription into `qits.speech.home`, and no Maven-side plugin can see
# them. A consumer auditing the python side reads the venv, not this.

# --- sbom -----------------------------------------------------------------------------------------
# The service module's CycloneDX document, exported so the release step can PUT it to the platform's
# SBOM store. A stage rather than a layer of the runtime image, and BEFORE the runtime stage on
# purpose: the last stage is what a plain `docker build` builds, and that must stay the image.
# `.config/qits/ci-event-release.yml` reaches this one with `--opt target=sbom --output
# type=local` using the SAME args and secrets as the image build, so every layer above is a cache
# hit in the same builder and the export costs seconds rather than a second native compile.
FROM scratch AS sbom
COPY --from=build /src/service/target/sbom.json /sbom.json

# --- runtime --------------------------------------------------------------------------------------
# `mirror.qits.$QITS_DOMAIN/redhat/…` is the same mirror, pointed at registry.access.redhat.com.
FROM mirror.qits.${QITS_DOMAIN}/redhat/ubi9/ubi-minimal:9.6
WORKDIR /work

# See the header for why this is not optional. `python3.12-pip` is what makes `-m venv` produce a
# working `venv/bin/pip` — ensurepip has no bundled wheel without it, and the bootstrap would fail
# on the pip invocation rather than on venv creation.
RUN microdnf -y --setopt=install_weak_deps=0 --nodocs install python3.12 python3.12-pip \
 && microdnf -y clean all

# `quarkus.package.output-name` names the binary `qits-stt` (no -runner suffix), so the process is
# identifiable in `ps`/`docker top`. That name is spelled a second time in service/pom.xml as
# failsafe's `native.image.path`; the two move together.
COPY --from=build --chown=1001:root /src/service/target/qits-stt /work/qits-stt
RUN chmod +x /work/qits-stt

# The mount point for `qits.speech.home`, created here so it is OWNED. Docker seeds a fresh named
# volume from whatever the image has at the mount path, ownership included; with no such directory
# it makes one root-owned 0755, and the venv bootstrap dies on a permission error that reads like a
# bug in this image and is not one. Group-writable and group-root because a ubi image is meant to
# survive being assigned an arbitrary UID with GID 0. (A bind mount still takes its ownership from
# the host — nothing in an image can fix that.)
RUN mkdir -p /data/speech && chown -R 1001:root /data && chmod -R 775 /data

# Both of these are named here rather than left to a deployment, and unlike the sibling services
# nothing in this image is deliberately left unconfigured: qits-stt has no database. The prototype
# argument that makes qits-artifacts/-ci/-projects refuse to boot without a datasource has no
# subject here — there is no SQL, only a venv and a model cache on disk — so the honest behaviour is
# for it to work.
#
# `qits.speech.home` ships as ${user.home}/.qits/speech, which under USER 1001 resolves to the
# literal `?` (a native image reads user.home via getpwuid(2), and 1001 has no passwd entry), so the
# venv bootstrap would try to create `?/.qits/speech`.
#
# HF_HOME is the one that is easy to miss. The worker downloads ~700 MB from the Hugging Face hub,
# and huggingface_hub defaults its cache to $HOME/.cache/huggingface — with no home that is `/.cache`,
# which uid 1001 cannot create. TranscriptionService passes `Map.of()` as the child environment, so
# the python process inherits this one; setting it here is what reaches it. Pointing it inside the
# same volume also means the model survives a container recreate rather than being re-downloaded.
ENV QITS_SPEECH_HOME=/data/speech \
    HF_HOME=/data/speech/huggingface

# Names the interpreter this image actually installs, because ubi9 ships 3.12 without a
# `/usr/bin/python3` alias — see the header. This is the venv CREATOR only; every later call goes
# through `<home>/venv/bin/python`, so changing it after a venv exists changes nothing.
ENV QITS_SPEECH_PYTHON=/usr/bin/python3.12

# One port for both route stacks — /stt/api/** (JAX-RS) and /stt/q/** (openapi, swagger-ui) are two
# prefixes on one listener, not two listeners.
EXPOSE 8080
USER 1001

# Quarkus binds 127.0.0.1 outside dev mode, which inside a container means nothing can reach it —
# not the gateway, not a peer on qits-net.
ENTRYPOINT ["/work/qits-stt", "-Dquarkus.http.host=0.0.0.0"]
