# qits-cli-bootstrap

Brings the qits platform up on your workstation's docker daemon, **through the platform's own
pipeline**, and tells you what it is doing while it does it.

It replaces `qits-local-up.sh` in the wrapper repository. The choreography is that script's,
step for step; what is new is that a four-hour cold start is no longer four hours of silence.

    ┌ qits bootstrap · 41m12s elapsed · log qits-bootstrap-cli.log ──────────────┐
    │   … 18 earlier phases done                                                 │
    │   ✓ 26/47 wait for the seed services (1m20s)                               │
    │   ✓ 27/47 publish the ci-daemon binary to the registry (12s)  — 8d0f1a2b…  │
    │ ▸ 28/47 create the platform's repositories on the git host   ⠹ 4s          │
    │      PUT http://127.0.0.1:8081/artifacts/git/qits-spa-ci                   │
    │   19 phases pending — next: pre-seed release-train histories               │
    ├────────────────────────────────────────────────────────────────────────────┤
    │  qits-spa-artifacts -> /artifacts/git/qits-spa-artifacts  (created)        │
    │  qits-spa-observability -> /artifacts/git/qits-spa-observability (created) │
    │  … the running step's own output, live                                     │
    └────────────────────────────────────────────────────────────────────────────┘

## Two modes

    qits bootstrap     # bring the platform up (the default when no mode is given)
    qits unwrap        # take it off this machine again

`unwrap` removes the qits-marked containers, images and networks. **The volumes stay** — they hold
the databases, the registry's blobs and the git host's repositories. `unwrap --with-volumes` is the
full clean slate; `unwrap --dry-run` lists what would go and removes nothing.

## Running it

A native binary, so a workstation needs no JDK to run it:

    sdk env                                  # .sdkmanrc names the GraalVM; sets JAVA_HOME
    ./mvnw package -Dnative -DskipTests
    ./target/qits-cli-bootstrap-1.0.0-SNAPSHOT-runner bootstrap

Without sdkman, name the GraalVM by hand instead of the first line:

    JAVA_HOME=$HOME/.sdkman/candidates/java/25.0.2-graalce ./mvnw package -Dnative -DskipTests

Copy that one file wherever it is wanted. The build takes about half a minute and the binary is
roughly 45 MB.

While working on the CLI itself, the jar is the faster loop:

    ./mvnw clean verify
    java -jar target/qits-cli-bootstrap-1.0.0-SNAPSHOT-runner.jar unwrap --dry-run
    ./mvnw quarkus:dev -Dquarkus.args="unwrap --dry-run"

Both forms behave the same, live display included: JLine is pinned to its `exec` terminal provider,
which shells `/bin/stty` rather than calling libc, because the providers that call libc do not
survive being compiled into a native image. One process per terminal, on a program that already
shells docker and git for a living.

It runs **on the host**, not in a container: it reads the wrapper repository's checkouts where they
are and shells the host's docker and git. Nothing needs the docker socket mounted anywhere.

It also **finds the wrapper by itself**. This CLI lives at `cli/qits-cli-bootstrap` inside it, so
running from anywhere in the checkout is enough: the working directory is walked upwards for the
first `.gitmodules` naming the qits submodules. `QITS_WRAPPER_DIR` (or `--wrapper-dir`) still wins,
and preflight prints which of the two happened.

What it needs to run: a reachable docker daemon with the compose plugin, `git`, `stty`, roughly
4 GB of RAM free per native build it starts, and reach to quay.io, registry.access.redhat.com,
docker.io and npm — a cold start cannot pull through the mirror it is starting. What it needs to
build: the GraalVM `.sdkmanrc` names for the binary, any JDK 25 for the jar and the tests.

Cost, honestly: every seed image and every pipeline run is a cold GraalVM native build with no
maven cache. The first run is measured in hours. Reruns skip what exists —
`QITS_SKIP_BUILD=1` for the seed, and unchanged repositories push up to date and trigger nothing.

## Configuring it

Copy `.env.example` to `.env` in the directory you run from and edit. Quarkus reads `.env` as an
environment source, so every knob is also an ordinary environment variable under the same name —
the same names `qits-local-up.sh` read:

| knob | default | what it is |
| --- | --- | --- |
| `QITS_WRAPPER_DIR` | detected | the wrapper repository whose checkouts are the sources, and where the compose file and `.qits-bootstrap.env` land — found by walking up when unset |
| `QITS_SRC` | `.qits-bootstrap-src` | where those checkouts are cloned to |
| `QITS_ORG_URL` | the GitHub org | fallback for a repository with no local checkout |
| `QITS_PORT` | `8080` | the gateway's host port; the CLI reaches ci and cd through it |
| `QITS_REGISTRY_PORT` | `8081` | qits-artifacts' host port: the registry, the artifacts API and the git host |
| `QITS_SKIP_BUILD` | `0` | 1 = the seed images and the daemon binary exist; skip to compose and the pushes |
| `QITS_MACHINE_AUTH` | `1` | machine-token enforcement for ci, cd, artifacts and serviceregistry |
| `QITS_PUSH_TOKEN` | `local-dev` | the git host's push token — the documented escape hatch, not a secret |
| `QITS_DEPLOY_TIMEOUT` | `3600` | seconds to wait per application deployment |
| `QITS_RELEASE_TIMEOUT` | `1800` | seconds to wait per replayed release run |
| `QITS_HEALTH_TIMEOUT` | `120` | seconds to wait per seed service |
| `QITS_POLL_INTERVAL` | `10` | seconds between polls |
| `QITS_ENV_NAME` | `dev` | the environment, deployed from `environment/<name>` |
| `QITS_IDP_CLIENT_<ID>_SECRET` | generated | pin one idp client's secret instead of generating it |
| `QITS_TUI` | `1` | 0 = plain output even on a terminal |
| `QITS_TAIL_LINES` | `2000` | lines of the running step kept for the body |
| `QITS_LOG_FILE` | `qits-bootstrap-cli.log` | the full log of every command |
| `QITS_CURL_IMAGE` | `curlimages/curl:latest` | how the CLI reaches the two services with no host port |

`--wrapper-dir`, `--skip-build` and `--no-tui` answer the same questions for one run.

## The display

**The header** is where the boot is. One compact line per finished phase (`✓ 8/47 build the seed
image qits/ci:latest (11m02s)`), the running phase with a spinner and its elapsed time, and a count
of what is left with the next one named. Under the running phase, when it is waiting on something
remote, one line saying **what is being polled, what the last poll saw, how long it has been, and
when it gives up**:

    waiting for a deployment row for qits-stt at 3f8ca71 — ci run RUNNING, deployment no row yet
      · 6m20s elapsed, gives up after 1h00m (at 15:42:10)

That line is the point of this program. A bootstrap spends most of its life waiting, and a wait
you cannot see is indistinguishable from a hang.

**The body** is the merged stdout and stderr of the step running right now — the maven, docker and
npm output — as a rolling tail, repainted on a 250 ms timer. Everything, tail included, also goes
to `qits-bootstrap-cli.log`; memory stays bounded whatever a build prints.

**Not a terminal?** A pipe, a dumb `TERM` or `--no-tui` gets plain sequential lines with the same
phase markers and the same wait lines, throttled so an hour-long wait does not bury the log in its
own clock.

**A failure** stops the boot, paints the failing tail and the exit code, and says which phase it
was. Exit codes: `0` all good, `1` something warned (a deployment that never landed — the script's
`overall=1`), `2` a phase failed and stopped the run.

## What it does, in order

Built from configuration at startup, so the count in the header is real. A cold boot is 47 phases;
`QITS_SKIP_BUILD=1` drops phases 4–21 and keeps everything else.

| | phase |
| --- | --- |
| 1–3 | preflight; clone or refresh the 26 platform repositories; read `.qits-bootstrap.env` |
| 4 | seed `qits-auth-core` for the first artifacts build (a temporary file repository, served over HTTP, that breaks the first-boot cycle) |
| 5–6 | seed images `qits/gateway`, `qits/artifacts` |
| 7 | start the seed qits-artifacts alone, so there is somewhere to publish to |
| 8–11 | publish `qits-eventstream`, `qits-auth-core`, `@qits/ui-components`, `@qits/angular` |
| 12–15 | seed images `qits/ci`, `qits/cd`, `qits/idp`, `qits/serviceregistry` |
| 16–20 | the five step images from qits-oci |
| 21 | the ci-daemon musl static binary, and its digest |
| 22 | resolve the idp's client secrets (given, kept, generated) and record the run state |
| 23–24 | generate the seed compose file; write cd's run-args onto the `qits-cd-config` volume |
| 25–26 | start the seed stack (only what cd does not already manage); wait for idp, serviceregistry, gateway, artifacts, ci, cd |
| 27 | publish the ci-daemon binary, version-addressed by its digest |
| 28–29 | create the 26 repositories on the git host; pre-seed the release-train histories with `-o qits.no-ci` |
| 30–33 | replay the release pipeline of each publisher the wrapper builds install, and wait for each run |
| 34 | reconcile the `dev` environment by PATCH — never delete, which would tear down the platform |
| 35–45 | one phase per deployable: push both refs, wait for the CI run and the deployment |
| 46–47 | push the release-train repositories; the closing report |

Two things every deploy phase does that are easy to miss: it pushes the **non-deploying** ref
quietly (`-o qits.no-ci`) so a second cold native build is not queued for the same sha, and it
replays the `build-succeeded` event once when a run is green but no deployment row appeared after a
minute — the observed lost-event failure.

## How it differs from the script

Same phases, same order, same idempotence. Three deliberate differences, all from running on the
host rather than as a container on `qits-net`:

- **No `/out` mount and no worktree gitdir contortions.** The wrapper's checkouts are read where
  they are, and the compose file and `.qits-bootstrap.env` are written back beside them.
- **The platform is reached through its published doors**: qits-artifacts on
  `127.0.0.1:8081`, qits-ci and qits-cd through the gateway's route table on `127.0.0.1:8080`.
  Calls made while the gateway or artifacts is mid-cutover are expected and retried rather than
  fatal.
- **qits-idp and qits-serviceregistry publish no host port on purpose**, so the CLI borrows a
  network position for them: one throwaway `curlimages/curl` container on `qits-net` per call. The
  platform's exposure is unchanged, which is the property worth keeping.

One addition: a singleton is only counted live when its container is not `unhealthy`. The script's
docker-based check once counted a `running/unhealthy` idp as live, and the rollback that followed
looked like a success.

## Status

**The script remains the reference until this CLI has done a proven cold bootstrap.** See
`AGENTS.md`.
