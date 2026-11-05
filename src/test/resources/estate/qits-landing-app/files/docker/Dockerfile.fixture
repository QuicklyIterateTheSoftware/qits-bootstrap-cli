# qits-landing — an Angular SSR application that serves itself, in one image and with no JVM.
#
# THIS BUILD IS SELF-CONTAINED, and that is the `app` archetype's one deliberate divergence from
# java-service's shape. `.config/qits/release.yml`'s release slot runs `buildctl` and nothing else —
# no pre-built `dist/` is COPY'd in — so the `npm ci` and the `npm run build` happen HERE. The
# consequence is the point: the image a person builds with `docker build` on a workstation and the
# image CI builds through the platform builder are produced by the same instructions, so there is no
# build that only one of them can run.
#
# The context is the repository ROOT and this file is found under `docker/`, which is what
# `buildctl --local context=. --local dockerfile=docker` says. `docker build -f docker/Dockerfile .`
# from the repository root is the workstation spelling of the same two facts.
#
# WHAT THE RELEASE STEP HANDS THE BUILDER, and why each arrives the way it does:
#
#   ARG QITS_DOMAIN             the platform's public domain (qits-731). The two npm roots — the
#                               hosted registry serving the @qits scope and the npmjs cache — are
#                               both code under it, derived in the RUN below.
#   --secret id=qits-npm-token  a CREDENTIAL. A build arg is written into the image history and this
#                               image is pullable, so a token passed that way would ship with it.
#
# THE SECRET IS MOUNTED AS A FILE (`/run/secrets/<id>`), never `env=`: the `env=` mount form needs a
# dockerfile frontend the BUILDER picks, and under the platform builder the frontend is the
# builder's choice rather than this file's. The bare file mount is served identically by every
# frontend version. qits-ci-service's own Dockerfile writes that call out at length.
#
# AN ABSENT OR EMPTY SECRET IS NO LONGER A SUPPORTED STATE: both registries answer 401 anonymously
# (qits-731), so a run that mints no token fails the install rather than falling back to an anonymous
# read. The release step always writes the file, empty or not; this Dockerfile still writes NO
# `_authToken` line at all when it is empty, rather than one with nothing after `Bearer `, because
# that at least fails with npm naming the host instead of a silently wrong credential.

# ---------------------------------------------------------------------------------------------
# BUILD STAGE
#
# node 24, not the workstation's 22, and the floor is Angular 22's own: `@angular/cli@22` declares
# `node: ^22.22.3 || ^24.15.0 || >=26.0.0`. The `24-alpine` tag can only move forward inside major
# 24, so it can never drift below 24.15.0 — which is what makes a floating minor safe here where a
# floating MAJOR would not be. Nothing in this file may depend on node 22: this image is built by CI
# on a node-24 builder and by a person on whatever their docker daemon pulls, and the workstation's
# node is neither.
FROM node:24-alpine AS build

# ONE INPUT: the platform's public domain (qits-731). Both npm roots are code under it — the hosted
# registry serving the @qits scope and the npmjs pull-through cache — derived in the RUN below, not
# read off a per-environment address. `wohlben.eu` is the live platform's, so a bare `docker build`
# on a workstation needs no flag.
ARG QITS_DOMAIN=wohlben.eu

WORKDIR /src

# The whole tree, minus what `.dockerignore` withholds — `node_modules`, `dist` and, load-bearingly,
# the committed `.npmrc`, which would outrank the `~/.npmrc` written below and send every request to
# a registry this build has no credential for.
COPY . .

# THE LOCKFILE IS INSTALLED EXACTLY AS COMMITTED (qits-731): package-lock.json and .npmrc are
# published through the public hosts already, the same pair this build derives below, so there is no
# origin left to rewrite. `npm ci` needs only a credential, which arrives from the secret mount.
#
# THE `_authToken` KEY MUST MATCH THE REGISTRY URL EXACTLY, TRAILING SLASH INCLUDED. npm looks the
# token up under the scheme-less form of the registry it is about to dial and does not normalise the
# two ends together, so a dropped slash is a silent anonymous install (measured on npm 10.9.8;
# npm-library.yml's header carries it). `#*:` strips the scheme whether it is http or https.
#
# THE MIRROR GETS THE TOKEN TOO: it no longer answers anonymous reads (2026-09-29).
RUN --mount=type=secret,id=qits-npm-token \
    hosted_url="https://registry.qits.$QITS_DOMAIN/artifacts/npm/npm/"; \
    proxy_url="https://mirror.qits.$QITS_DOMAIN/npm/npmjs/"; \
    token=$(cat /run/secrets/qits-npm-token 2>/dev/null || true); \
    ( umask 077; \
      printf 'registry=%s\n@qits:registry=%s\n' \
        "$proxy_url" "$hosted_url" > ~/.npmrc; \
      [ -n "$token" ] && printf '%s:_authToken=%s\n%s:_authToken=%s\n' \
        "${hosted_url#*:}" "$token" \
        "${proxy_url#*:}" "$token" >> ~/.npmrc; ) ; \
    npm ci --no-audit --no-fund && npm run build

# ---------------------------------------------------------------------------------------------
# RUNTIME STAGE
#
# THE BUNDLE CARRIES NO node_modules, AND THAT IS MEASURED RATHER THAN HOPED FOR. `outputMode:
# server` bundles every third-party dependency — express and @angular/ssr included — into
# `server/server.mjs`; the only bare specifiers left in it are node builtins (`node:module`, `path`,
# `process`, `url`). Verified by running the built server from a directory with no `node_modules` in
# or above it. So there is no `npm ci --omit=dev` here, and adding one would install a tree nothing
# imports.
#
# If a future change externalises a dependency, this is the line that breaks — loudly, at
# `ERR_MODULE_NOT_FOUND` on the first boot, which the health gate turns into a rolled-back
# deployment rather than a silent one.
FROM node:24-alpine AS runtime

# CURL IS PART OF THE DEPLOYABLE, NOT A CONVENIENCE. The deployer's swarm health gate is the shell
# string `curl -fsS http://localhost:8080<health_path> || exit 1` and it runs INSIDE this container,
# not from outside it — see SwarmDeploymentDriverTest in qits-deployments-platform-service. So
# `health_path: /landing/health` in `.config/qits/deployments.yml` and this line are one decision
# spelled twice: the path is only reachable if the binary that dials it is in the image. Without it
# the probe exits 127 on every interval, `--update-failure-action rollback` fires, and the
# deployment is killed as unhealthy after the server has come up and logged that it is listening.
#
# THIS IS THE FIRST IMAGE ON THE ESTATE THAT HAS TO SAY IT, which is why nobody had hit it before.
# Every sibling runs `ubi-minimal`, which ships curl, so the gate's assumption has always been paid
# for by the base image. `node:24-alpine` carries busybox — `wget` is there, `curl` is not at all —
# and an `-app` is the first role whose base is not the Red Hat one.
#
# AND IT IS NOT SOLVED WITH `health_cmd:` + wget. `deployments.yml` already argues that case:
# `health_cmd:` exists for a deployable with NO HTTP surface at all (postgres, which has neither
# curl nor anything on 8080). A node server answers a path, so it states a path — and an image that
# states a path owes the probe its binary.
RUN apk add --no-cache curl

# NON-ROOT, like every other image on the estate. The node images ship a `node` user (uid 1000)
# already, so there is nothing to create — and the bundle is read-only to it, which is right: this
# process writes nothing.
WORKDIR /app
COPY --from=build --chown=root:root /src/dist/qits-landing-app ./

ENV NODE_ENV=production

# 8080, AND IT IS THE SAME 8080 `src/server.ts` DEFAULTS TO. `DeploymentSpecParser`'s
# `DEFAULT_UPSTREAM_PORT` is 8080, so `.config/qits/deployments.yml` states no `upstream_port:` and
# the three agree by arithmetic rather than by a key that can drift. Stated as ENV as well as in the
# code so `docker inspect` answers the question too.
ENV PORT=8080
EXPOSE 8080

USER node

# `server/server.mjs` is what `outputMode: server` emits, under the angular.json project name. The
# path is spelled a second time in package.json's `serve:ssr` script; they move together.
CMD ["node", "server/server.mjs"]
