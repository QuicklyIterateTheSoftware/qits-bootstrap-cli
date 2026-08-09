# qits-cli-bootstrap

Brings the qits platform up on your workstation's docker daemon, **through the platform's own
pipeline**, and tells you what it is doing while it does it.

It **is** `qits-local-up.sh` in the wrapper repository — that file is now a shim that compiles this
CLI and runs it. The choreography is the shell port's, step for step; what is new is that a
four-hour cold start is no longer four hours of silence.

    ┌ qits bootstrap · 41m12s elapsed · log qits-bootstrap-cli.log ──────────────┐
    │   … 18 earlier phases done                                                 │
    │   ✓ 26/48 wait for the seed services (1m20s)                               │
    │   ✓ 27/48 publish the ci-daemon binary to the registry (12s)  — 8d0f1a2b…  │
    │ ▸ 28/48 create the platform's repositories on the git host   ⠹ 4s          │
    │      PUT http://127.0.0.1:8081/artifacts/git/qits-spa-ci                   │
    │   20 phases pending — next: pre-seed release-train histories               │
    ├────────────────────────────────────────────────────────────────────────────┤
    │  qits-spa-deployments -> /artifacts/git/qits-spa-deployments  (created)    │
    │  qits-spa-observability -> /artifacts/git/qits-spa-observability (created) │
    │  … the running step's own output, live                                     │
    └────────────────────────────────────────────────────────────────────────────┘

The same run is also served to a browser at `http://localhost:8480` while it runs — see
[The browser view](#the-browser-view).

## Two modes

    qits bootstrap     # bring the platform up (the default when no mode is given)
    qits unwrap        # take it off this machine again

`unwrap` removes the qits-marked containers, images and networks. **The volumes stay** — they hold
the databases, the registry's blobs and the git host's repositories. `unwrap --with-volumes` is the
full clean slate; `unwrap --with-data-volumes` removes the `qits-*-data` volumes (and
`qits-maven-seed`) while keeping every `qits-*-config` one, which is what a move onto another
database needs and what keeps the push token, the client secrets and the deployer's run-args;
`unwrap --dry-run` lists what would go and removes nothing.

It sweeps **both** deployer label namespaces — the current one and the retired qits-cd's — because
taking a platform that was never bootstrapped since the merge-back off a machine is what unwrap is
for, forever.

## Running it

From the wrapper repository, which compiles this CLI first and passes everything through:

    ./qits-local-up.sh                     # bootstrap
    ./qits-local-up.sh unwrap --dry-run    # any mode or flag below

That is the everyday door. It recompiles only when the sources are newer than the binary
(`QITS_CLI_BUILD=always` or `never` overrides), and it pins the wrapper directory, the clones and
the log, so the run is the same from any working directory.

The binary directly, which is what the shim ends up running — a workstation needs no JDK for it:

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
| `QITS_PORT` | `8080` | the host's ONE published port, bound by qits-platform-edge; the CLI reaches ci and the deployer through it and the gateway behind it |
| `QITS_REGISTRY_PORT` | `8081` | qits-platform-artifacts' host port: the registry, the artifacts API and the git host |
| `QITS_PG_PORT` | `5433` | the platform's postgres on 127.0.0.1, which is how this CLI creates roles and databases. 5433 so a postgres already on the workstation is not a bind conflict; in-network consumers dial 5432 |
| `QITS_SKIP_BUILD` | `0` | 1 = the seed images and the daemon binary exist; skip to compose and the pushes |
| `QITS_MACHINE_AUTH` | `1` | machine-token enforcement for ci, deployments and platform-artifacts |
| `QITS_PUSH_TOKEN` | `local-dev` | the git host's push token — the documented escape hatch, not a secret |
| `QITS_DEPLOY_TIMEOUT` | `3600` | seconds to wait per application deployment |
| `QITS_RELEASE_TIMEOUT` | `1800` | seconds to wait per replayed release run |
| `QITS_HEALTH_TIMEOUT` | `120` | seconds to wait per seed service |
| `QITS_POLL_INTERVAL` | `10` | seconds between polls |
| `QITS_ENV_NAME` | `prod` | the environment, and the ONE deploy ref is its `environment/<name>`. It is the **platform environment** — the tier whose branch deploys the platform plane — and it is inside every wire alias, every deployed container name and every idp client id. `--platform-env` is the same knob for one run |
| `QITS_IDP_CLIENT_<ID>_SECRET` | generated | pin one idp client's secret instead of generating it |
| `QITS_PG_SUPERUSER_PASSWORD` | generated | pin postgres' superuser password. 16–64 hex, because it is assembled into SQL that cannot be parametrized. It applies at initdb only, so on an existing cluster the value in `.qits-bootstrap.env` is the only way in |
| `QITS_PG_DEPLOYMENTS_PASSWORD` | generated | the same, for the deployer's own role. This one converges on every rerun |
| `QITS_PG_CI_PASSWORD`, `QITS_PG_CI_EVENTSTREAM_PASSWORD`, `QITS_PG_PLATFORM_IDP_PASSWORD` | generated | the same, for the two core seed services' databases. Created once and never altered again: the deployer's resource registry owns them from the first pipeline deployment on |
| `QITS_TUI` | `1` | 0 = plain output even on a terminal |
| `QITS_WEB` | `1` | 0 = no browser view; the HTTP server never binds |
| `QITS_WEB_PORT` | `8480` | the browser view's port |
| `QITS_WEB_HOST` | `0.0.0.0` | what it binds (WSL2 browsers need non-loopback); `127.0.0.1` keeps it off the LAN |
| `QITS_TAIL_LINES` | `2000` | lines of the running step kept for the body |
| `QITS_LOG_FILE` | `qits-bootstrap-cli.log` | the full log of every command |
| `QITS_CURL_IMAGE` | `curlimages/curl:latest` | how the CLI reaches qits-platform-idp, which publishes no host port |

`--wrapper-dir`, `--skip-build`, `--no-tui` and `--platform-env` answer the same questions for one
run.

`--platform-env <name>` is for a FIRST boot. Bootstrapping over a platform whose environment has
another name is **refused**, not honoured as a rename: the name is inside every wire alias, every
deployed container name and every recorded idp secret, so a PATCH would leave the platform running
as `<old>-qits-*` while every generated file addresses `<new>-qits-*`. The phase names what stands
there and points at `unwrap`. Moving the plane on a live platform is a PATCH on the deployer's
`pd_environment.platform`, with the undeploy and redeploy that implies, and nothing here does it.

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

## The browser view

The same run, in a browser, from phase 1:

    http://localhost:8480

It is printed on the first line of every run. The page is the terminal display in HTML — the phase
list with the finished ones dimmed, the running one with its elapsed time counting, the wait line,
the pending count — and under it the running step's output, scrolling and sticky at the bottom
unless you scroll up. It costs the boot nothing: the engine appends to a bounded state, and each
connection reads what is new four times a second.

It is not instead of the terminal display, it is beside it: **both** are fed every event, so a
bootstrap started over ssh can be watched from a browser, on a phone, or by someone else. `unwrap`
serves it too.

Three doors, no assets, nothing to fetch — the page is one self-contained file, which matters for a
program whose whole job is that the platform is not up yet:

| | |
| --- | --- |
| `GET /` | the page |
| `GET /events` | the run as it happens: one `snapshot` on connect, then `phase`, `status`, `line` and `done` as server-sent events, with a comment every 15s so nothing in the middle calls it dead |
| `GET /state.json` | the same state in one answer, for `curl` |

Knobs: `QITS_WEB_PORT` (default `8480` — 8080 is the edge, 8081 is the artifacts service, and 8090 is
taken often enough to be a poor default), `QITS_WEB_HOST` (default `0.0.0.0` — on WSL2 the
Windows-side browser cannot reliably reach a WSL-loopback bind; set `127.0.0.1` to keep the view
off the LAN), and `QITS_WEB=0`, which turns it off entirely — no server, no port.

**A port already in use stops the CLI before the boot starts**, with Quarkus saying which port it
was. That is the one thing to know: a second run while one is going needs `QITS_WEB_PORT=8481` or
`QITS_WEB=0`.

**Proxying it through qits-gateway** — for a bootstrap watched from another machine — is a possible
follow-up, not something this does. It would need two things: the gateway container reaching the
host, `--add-host=host.docker.internal:host-gateway` plus a route entry pointing at it, and the
gateway's own handling of a streamed response checked first. The page already sends
`X-Accel-Buffering: no` and asks for no transform; a proxy that buffers anyway turns a live view
into a page that arrives when the run is over, so that is what to verify before wiring it.

## What it does, in order

Built from configuration at startup, so the count in the header is real. A cold boot is 51 phases;
`QITS_SKIP_BUILD=1` drops phases 4–22 and keeps the other 33.

| | phase |
| --- | --- |
| 1–3 | preflight (docker, git, and where the wrapper is); clone or refresh the 29 platform repositories; read `.qits-bootstrap.env` |
| 4 | seed `qits-auth-core` for the first artifacts build (a temporary file repository, served over HTTP, that breaks the first-boot cycle) |
| 5–8 | seed images `qits/gateway`, `qits/platform-edge`, `qits/platform-artifacts`, `qits/oci-postgresql` |
| 9 | have qits-platform-artifacts serving the registry port, so there is somewhere to publish to |
| 10–13 | publish `qits-eventstream`, `qits-auth-core`, `@qits/ui-components`, `@qits/angular` |
| 14–16 | seed images `qits/ci`, `qits/deployments`, `qits/platform-idp` |
| 17–21 | the five step images from qits-oci |
| 22 | the ci-daemon musl static binary, and its digest |
| 23 | start postgres on a generated superuser password recorded before it first boots, and create over JDBC the four databases the seed stack needs: the deployer's, qits-ci's own and its outbox's, and qits-platform-idp's. Everything else is provisioned by the deployer from the `resources:` line in each repository's deployments.yml |
| 24 | resolve the idp's client secrets (given, kept, generated) and record the run state |
| 25–26 | generate the seed compose file; write the deployer's run-args onto its config volume |
| 27–28 | start the seed stack (only what the deployer does not already manage); wait for the idp, the edge on the host port, the gateway on qits-net, artifacts, ci and the deployer |
| 29 | publish the ci-daemon binary, version-addressed by its digest |
| 30–31 | create the 29 repositories on the git host; pre-seed the seeded histories with `-o qits.no-ci` |
| 32–35 | replay the release pipeline of each publisher the wrapper builds install, and wait for each run |
| 36 | reconcile the `prod` environment in qits-deployments by PATCH — never delete, which would tear down the platform |
| 37–49 | one phase per deployable: push `main` quietly and `environment/<name>` for real, then wait for the CI run and the deployment. qits-oci-postgresql is second: it is the deployer's own database, so its cutover must never be queued beside a consumer's. qits-platform-edge is second to last: it is the host port, so its cutover takes this program's own door away for a beat |
| 50–51 | push the seeded repositories; the closing report |

Three things every deploy phase does that are easy to miss: it pushes `main` quietly
(`-o qits.no-ci`) so a second cold native build is not queued for the same sha; it replays the
`build-succeeded` event once when a run is green but no deployment row appeared after a minute —
the observed lost-event failure; and when the push is up to date and the only run at that sha is
RED, it re-announces the push to qits-ci once and waits for a run newer than the red one. A red run
means there is no image, so replaying the build event would only buy an `IMAGE_MISSING` row — a
rerun has to ask for the BUILD.

Phase 26 restarts the seed deployer when the run-args it just wrote differ from what the volume
held. The deployer reads that file once, at its own boot, so a rerun that changes it changes
nothing for a container that is already running.

Phases 4 and 9 are the two that bind the registry port, and both ask first whether
qits-platform-artifacts is already serving it — by GET on the artifacts API's own health, which the
temporary nginx does not answer. On a platform whose store is deployed, the answer is yes: the
deployed container publishes the same port from the same volume, so phase 4 skips and phase 8 waits
for that store instead of starting a seed beside it. Binding it anyway is `port is already
allocated`, exit 125, and a stopped boot.

## Two planes, one branch

`main` is the integration trunk of every repository and deploys nothing. What deploys is
`environment/<name>` — for BOTH planes, because both ask a green build the same question: does an
environment listen to this ref. `platform/main` is retired.

| plane | applications | wire alias | container |
| --- | --- | --- | --- |
| environment | gateway, ci, deployments, events, projects, observability, workspaces, stt | `<env>-qits-<app>` | `qits-pd-<env>-qits-<app>-<id8>` |
| platform | platform-edge, platform-idp, platform-artifacts, platform-docs | `qits-platform-<x>` | `qits-pd-qits-platform-<x>-<id8>` |

A **platform** service is one instance for the whole platform, joined to every environment's
networks, belonging to no tier — so it appears in no per-environment deployment listing, and the
CLI watches docker for it instead: a container under the prefix above running the image tagged with
the sha it pushed. An environment service is watched through its deployment row.

The **wire alias** is the address peers dial and the name a cutover finds its predecessor by. The
seed containers are named after it, which is what makes the generated compose file's own addresses
right from the first second rather than from the first cutover.

**qits-platform-edge binds the host's only published port** and hands each request to the gateway
of the environment its Host name names. qits-gateway publishes nothing: it was on the platform
plane only because it used to bind that port.

**qits-deployments owns both halves**: the topology (environments, services, links) and the
execution (deployment rows, the health-gated cutover, the rollback pins). It is the merge-back of
qits-cd and qits-serviceregistry, and both are superseded — the bootstrap still creates their
repositories and pushes their histories, and deploys neither.

## How it differs from the shell port

`qits-local-up.sh` was a POSIX shell script run as a throwaway `docker:cli` container with the
docker socket and the wrapper mounted. It is in the wrapper repository's git history.

Same phases, same order, same idempotence. Three deliberate differences, all from running on the
host rather than as a container on `qits-net`:

- **No `/out` mount and no worktree gitdir contortions.** The wrapper's checkouts are read where
  they are, and the compose file and `.qits-bootstrap.env` are written back beside them.
- **The platform is reached through its published doors**: qits-platform-artifacts on
  `127.0.0.1:8081`, qits-ci and qits-deployments through the one port qits-platform-edge binds on
  `127.0.0.1:8080` and the gateway behind it. Calls made while the edge, the gateway or artifacts
  is mid-cutover are expected and retried rather than fatal.
- **qits-platform-idp publishes no host port on purpose**, so the CLI borrows a network position: one
  throwaway `curlimages/curl` container on `qits-net` per call. The platform's exposure is
  unchanged, which is the property worth keeping.

Two additions:

- A platform service is only counted live when its container is not `unhealthy`. The shell port's
  docker-based check once counted a `running/unhealthy` idp as live, and the rollback that followed
  looked like a success.
- **A source that cannot be trusted stops the boot.** The script, and this CLI until 2026-08-08,
  answered a wrapper path that was not a checkout by cloning from GitHub instead, and a refresh
  that would not fast-forward by building what was already on disk. Both produced a working-looking
  run of the wrong commit. Now: an ABSENT wrapper directory still falls back to the org URL — not
  every repository in the model has to be a submodule of this wrapper — but a directory that exists
  and is not a checkout, or a refresh that fails, ends the run and names the fix.

## Status

**The only bring-up path there is.** The shell port is retired; `qits-local-up.sh` is the shim in
front of this CLI.

**The 2026-08-08 rerun fixes are NOT yet proven by a real bootstrap.** The first prod bootstrap ran
that day and found three places where the CLI warned and moved on and a person finished the job by
hand with raw API calls: a stale RED run was read as an outcome instead of a reason to re-announce
the push, the seed deployer kept the run-args it had cached at its boot and deployed a qits-ci
without them, and nothing spelled `QITS_OBSERVABILITY_URL`, so every exporter dialled a name the
rename had killed. The validation rerun of those three then stopped at phase 8 with `port is
already allocated`: the seed store phases predate the skip-when-deployed rule the compose stack
already had. All four are fixed above and `./mvnw clean verify` is green; the gate is the next
rerun.

**The 2026-08-08 platform re-model reached a real platform** — one environment named `prod`, one
deploy ref, six renamed repositories, a platform plane of four, and qits-platform-edge in front of
the gateway. It got there with the three hand-recoveries above, so the re-model's own proof is the
same rerun the fixes are waiting on. The runs below are the pre-re-model ones.

- 2026-08-06: green cold bootstrap of the real platform in 22m29s, ten applications healthy, after
  15 fixes found by the proving runs. Warm cycle the same evening: `unwrap` 11s, `bootstrap` 3m29s.
- 2026-08-07: two `unwrap` + `bootstrap` cycles, the retirement's own proof. The first found the
  stale-CI-row bug (a red row from the evening before failed a phase in zero seconds). The second,
  run through the shim with that fixed, finished clean in 3m44s — 43 phases of 45, 2 skipped as
  already published, no phase warnings, all ten applications healthy, every gateway route serving,
  `/workspaces/` checked in a browser. `unwrap` removes the seed images but not docker's build
  cache, which is why the rebuilds behind that number cost seconds rather than minutes.
