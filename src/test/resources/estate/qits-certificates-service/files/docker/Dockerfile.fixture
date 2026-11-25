# qits-certificates as a GraalVM-native container IMAGE, the shape of qits-network-access': a Mandrel
# builder stage compiles the binary, an `sbom` stage holds its CycloneDX document, and a minimal
# runtime stage carries only the binary.
#
# Build from the repo root:
#
#     docker build -t qits/qits-certificates:latest -f docker/Dockerfile .
#
# THE CREDENTIAL IS REQUIRED: the reactor depends on qits-* jars published to the platform's own
# Maven repository and nowhere else, and both hosts answer 401 anonymously. The release recipe passes
# the two secrets below.
#
# THERE IS NO DATABASE. The one durable store is the private volume mounted at /work/ca
# (.config/qits/deployments.yml); the runtime stage creates that directory so a fresh named volume
# takes its owner and mode from the image.

ARG QITS_DOMAIN=wohlben.eu

# --- stage `build`: native-compile ----------------------------------------------------------------
# The builder JDK MUST be 25: native-image cannot read bytecode newer than its own JDK.
FROM mirror.qits.${QITS_DOMAIN}/quay/quarkus/ubi9-quarkus-mandrel-builder-image:jdk-25 AS build
USER root
WORKDIR /src

# The Maven distribution first, keyed only on the wrapper files.
COPY mvnw .
COPY .mvn/ .mvn/
RUN ./mvnw --version

# The whole reactor (`.dockerignore` keeps it cheap): `service` builds `domain` from source.
COPY . .

# -Dnative flips quarkus.native.enabled (the `native` profile in service/pom.xml). Tests are skipped:
# `./mvnw verify` on a clone is their gate. The secrets are file mounts read into this RUN's
# environment alone — never a layer, never the image history.
#
# cyclonedx.skipNotDeployed=false is LOAD-BEARING: the service module is not deployed as a jar, and
# the plugin skips such modules by default with a green build, so the sbom stage's COPY would fail.
ARG QITS_DOMAIN=wohlben.eu
RUN --mount=type=secret,id=qits-client-id \
    --mount=type=secret,id=qits-client-secret \
    export QITS_MAVEN_AUTH_USR="$(cat /run/secrets/qits-client-id 2>/dev/null || true)"; \
    export QITS_MAVEN_AUTH_PSW="$(cat /run/secrets/qits-client-secret 2>/dev/null || true)"; \
    export QITS_MAVEN_REPOSITORY_URL="https://registry.qits.$QITS_DOMAIN/artifacts/maven/maven"; \
    export QITS_MAVEN_CENTRAL_URL="https://mirror.qits.$QITS_DOMAIN/mirror/maven/central"; \
    ./mvnw -B -ntp -s .qits-maven-settings.xml -pl service -am package -Dnative -DskipTests \
      -Dqits.maven.repository.url="$QITS_MAVEN_REPOSITORY_URL" \
      -Dquarkus.native.native-image-xmx=4g \
      org.cyclonedx:cyclonedx-maven-plugin:2.9.1:makeBom \
      -DoutputFormat=json -DoutputName=sbom -DschemaVersion=1.6 \
      -Dcyclonedx.skipNotDeployed=false

# --- sbom -----------------------------------------------------------------------------------------
# Lifted out by the release step with `--opt target=sbom --output type=local`, with the same args and
# secrets as the image build, so every layer above is a cache hit.
FROM scratch AS sbom
COPY --from=build /src/service/target/sbom.json /sbom.json

# --- runtime --------------------------------------------------------------------------------------
FROM mirror.qits.${QITS_DOMAIN}/redhat/ubi9/ubi-minimal:9.6
WORKDIR /work

# curl, so the container answers the deployer's docker healthcheck. UBI minimal supplies
# curl-minimal; assert it rather than install the conflicting full package.
RUN curl --version >/dev/null

# `quarkus.package.output-name` names the binary `qits-certificates`; spelled again as failsafe's
# native.image.path in service/pom.xml. The three move together.
COPY --from=build --chown=1001:root /src/service/target/qits-certificates /work/qits-certificates
RUN chmod +x /work/qits-certificates

# THE CA DIRECTORY: the anchors' keys live here, on the `ca` volume. Owned by the runtime user and
# closed to everyone else (0700). Docker copies an image directory's owner and mode into a NEW named
# volume mounted over it, so the first deploy's volume starts out exactly so.
RUN mkdir -p /work/ca && chown 1001:root /work/ca && chmod 0700 /work/ca

EXPOSE 8080
USER 1001

# Quarkus binds 127.0.0.1 outside dev mode, which inside a container means nothing can reach it.
ENTRYPOINT ["/work/qits-certificates", "-Dquarkus.http.host=0.0.0.0"]
