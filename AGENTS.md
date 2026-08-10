# AGENTS.md — qits-cli-bootstrap

## What this repository is

The qits platform's cold bootstrap, as a Quarkus command-mode CLI. It runs as a container on
`qits-net` with the host's docker socket mounted, shells docker and git, calls the platform's HTTP
APIs, and shows a live two-region terminal display of what it is doing. Started on the host it puts
itself in that container: same binary, image built from this checkout, exit code relayed.

## Read this first

**This CLI is the bootstrap. There is no second implementation to fall back to.** It has done
proven cold and warm bootstraps of the real platform, and `qits-local-up.sh` in the wrapper
repository (qits-qits) is now a shim that compiles this CLI and runs it. The 1298-line shell port
it replaced is in that repository's git history (`git log -- qits-local-up.sh`).

That changes what care means here:

- **The operational knowledge is in these comments and nowhere else** — ordering constraints, the
  409/PATCH reconcile, the dual-ref pushes with `-o qits.no-ci`, the one deploy ref both planes
  share, the wire aliases the seed containers are named after, the mirror-prefix rewrite, the
  release replays, the lost-event self-heal, the machine-token minting. They were ported from the
  script on purpose, and the script is no longer there to check them against. Do not thin them out.
- **A behaviour change is a change to the only bring-up path there is.** Prove it with a real
  bootstrap, not with reasoning about what the script used to do.

The README's "How it differs from the shell port" lists the deviations that running on the host
forced. Add to that list rather than deviating quietly.

## Layout

    engine/     the phase state machine: Phase, PhaseEngine, PhaseContext, Waiter
    proc/       ProcessRunner and friends: streaming, bounded tails, the full log
    ui/         Ui, the live TuiUi (JLine Display), the PlainUi fallback, the WebUi that keeps
                the run's state for the browser, and the CompositeUi that feeds them all
    web/        the browser view's three routes and the one page they serve
    config/     BootstrapConfig (@ConfigMapping, read from .env) and its command-line overrides
    platform/   what the platform is made of: PlatformModel, the generated compose and run-args,
                the seed-Dockerfile rewrite, the recorded state, thin Docker and Git facades
    api/        the platform's HTTP, with java.net.http, at the platform's own wire aliases
    phases/     the phases themselves and the plan that orders them
    host/       the half that runs OUTSIDE the container: the payload image's content tag, the
                docker run it composes, and the launcher that preflights, builds and relays

## Conventions

- **Plain Language everywhere** — code comments, commit messages, documentation, and every string
  the display prints. Say only what the reader needs, in as few words as possible.
- **A comment says why, not what.** The ported ones say why an order is what it is; keep that.
- **Every phase is visible and every wait is observable.** A phase that polls something remote must
  go through `Waiter.await`, which prints what is being polled, what was last seen, the elapsed
  time and the deadline. A silent wait is a bug in this repository, whatever it is waiting for.
  A wait on a ci build says more than that: `CiLogStream` relays the run's own output under a
  `  ci| ` prefix, so a stalled build looks different from a slow one. qits-ci serves no SSE and no
  websocket for run logs — following along is polling `GET /ci/api/runs/{runId}`, which answers with
  each step's output whole and bounded, so the relay subtracts by overlap rather than by length. It
  is a courtesy and never a dependency: reads that stop answering turn it off with one line.
  The deploy half of the same wait talks too: `DeployLogStream` relays the deployer's log lines
  about the repository being waited for under a `  pd| ` prefix, read from `docker logs
  --timestamps` because the events service is itself something this run deploys — half the waits
  happen before any feed exists, while the docker socket is always there. Same courtesy rule: a
  read that fails relays nothing and fails nothing.
- **An announcement the platform makes once, this program re-makes once.** The push's post-receive
  and the run's build-succeeded are both fire-and-forget, and both have been lost for real — the
  idp's post-receive died with the database cutover the qits-oci-postgresql deploy one phase
  earlier had caused (ci's pool was severed as the announcement arrived; no run was ever enqueued).
  A pushed sha with no run after a minute, and a green run with no deployment row after a minute,
  each get their announcement re-made exactly once inside the wait. One more attempt, never a retry
  loop: if the second announcement dies too, the phase warns at its timeout as before.
- **Every address this CLI dials is a wire alias, and there is no second set.** The run joins
  `qits-net` in its second phase and reaches the platform the way every other member does:
  `qits-platform-artifacts:8080`, the postgres alias on 5432, `qits-platform-idp:8080`, and ci and
  the deployer through `qits-platform-edge:8080` — the edge rather than the service, so the run
  keeps exercising the gateway's route table. Do NOT add a host-addressed mode beside it: one set of
  addresses is what keeps the branching out of the code. The published ports are still configured,
  because the GENERATED FILES and a person's browser use them, and because the host's docker daemon
  resolves `localhost:<registry port>` during seed builds — a container the CLI starts for the
  daemon's benefit needs both a publish and a network alias, and the temporary Maven registry is
  exactly that case.
  **qits-platform-dns is the one service dialled at its own alias rather than through the edge**, and
  that is not an inconsistency to tidy away: the gateway proxies HTTP and there is deliberately no
  route to the record API, so `qits-platform-dns:8080/dns` is the only address it has. Its other door
  is not HTTP at all — UDP and TCP on 8053, published on `QITS_DNS_PORT` — and nothing here speaks it.
- **One binary, two halves, ONE configuration contract.** Outside a container the binary launches
  itself inside one; inside it, it runs the phases. The host half reads the same `BootstrapConfig`
  and re-interprets no `QITS_*` value: the container's working directory is the launcher's, so
  `.env` is the same file and a relative path is the same path, and every state directory is
  bind-mounted at its own path so an absolute one is too. Do NOT give the launcher knobs of its own.
  The two names it sets — `QITS_IN_CONTAINER` and `QITS_WEB_BIND` — are plumbing, not knobs: the
  first says which half this is, the second says which half may hold the browser view's port, and a
  person sets neither.
- **`unwrap` must never remove what the run is standing on.** Its `containers` phase excludes the
  container it is running in, and the payload image is `qits-bootstrap:<content sha>` rather than
  `qits/…` so the image sweep leaves it. Both are the same bug — `docker rm -f` on yourself — and
  both are fixed by exclusion rather than by narrowing a pattern, because the patterns are what a
  machine's qits objects look like and must keep matching.
- **Phases are rerun-safe**, the same way the script's were: 409s tolerated, existing networks
  adopted, an already-attached container accepted, up-to-date pushes no-ops, publishes probed
  before they are made.
- **Rerun-safe is not rename-safe, and the `environment` phase is where that line is drawn.**
  `--platform-env` names the standing environment, which is also the PLATFORM environment: the one
  tier whose branch deploys the platform plane. Bootstrapping over a platform whose environment
  carries another name is **refused**, and the refusal is the feature. The name is inside every wire
  alias, every deployed container name and every recorded idp secret key, so a PATCH would leave the
  platform running as `<old>-qits-*` while every generated file addresses `<new>-qits-*` and the idp
  is handed clients nothing holds credentials for. This phase repairs none of that, and half of it
  is only repaired by redeploying everything. It stops instead, names what stands there, and points
  at `unwrap`. The old `List.of("qits", "dev")` rename was right for a migration with two known
  names and one destination; it is wrong for a knob that makes every name reachable.
  Moving the plane on a live platform is a PATCH on the deployer's `pd_environment.platform` — with
  the undeploy and redeploy that implies — and is deliberately not here.
- **`unwrap` keeps the retired platform's patterns forever.** Cleaning a pre-merge-back machine is
  in scope, so the qits-cd labels and the pre-rename name prefixes stay beside the current ones.
  Adding a pattern is how this changes; removing one is not.
- **A name in `PlatformModel` is the repository name without `qits-`, and it is load-bearing four
  times over**: the wrapper directory, the git-host repository, the seed image tag and the
  deployer's application key. The plane lives in the name itself (`platform-idp`,
  `platform-artifacts`) and the tier lives in the WIRE ALIAS derived from it
  (`prod-qits-ci`) — never the other way round.
- **A source this program cannot trust stops the boot.** It decides which sha the whole platform is
  built from, so a wrapper path that is not a checkout and a refresh that will not fast-forward are
  both failures, not log lines. What is ABSENT is a different question and has a different answer:
  a missing wrapper is cloned from the org by the `wrapper` phase, and a missing component checkout
  is cloned by `sources`. An EMPTY directory counts as absent — git leaves one at every gitlink of
  a wrapper cloned without its submodules, and it hides no local work — while a directory with
  anything in it still stops the boot.
- **A cold start is a supported start, and the wrapper is not special.** `curl … | bash` on a bare
  box has no checkout and no platform git host to clone one from, so the wrapper comes from the org
  like any other repository, into the working directory. Its submodules are deliberately NOT
  initialised: `sources` clones every platform repository from the org anyway, so
  `--recurse-submodules` there would clone the platform twice and put tens of minutes in front of
  the first phase. The launcher mounts **only paths that already exist** for the same family of
  reason — docker creates a missing bind source as root, and the run is a plain uid — so an absent
  wrapper is not mounted and is cloned inside the working directory, which is.
- **Failure stops the boot** (exit 2). A deployment that never landed is a warning (`ctx.warn`,
  exit 1) — the script's `overall=1` — because the applications behind it still deserve their turn.
- **Secrets never reach the screen or the log.** Put them through `Cmd.mask`. The browser view
  shows the same lines the screen does, so that one rule covers it too.
- **The displays are fed, never asked.** A new display implements `Ui` and is added to the
  `CompositeUi` in `UiFactory`; the engine knows nothing about how many there are. A display that
  throws must not end the boot — the composite swallows a watcher's failure on purpose.
- Parentless pom, Quarkus pinned to the platform's version.

## Build forms

    sdk env && ./mvnw package -Dnative -DskipTests   the binary people run
    ./mvnw clean verify                              the tests; packages nothing
    ./mvnw quarkus:dev -Dquarkus.args="…"            the working loop

**There is no jar — not in production, not in the image, and not as a dev loop.** The native binary
is the only form of this program, and three lines in the pom keep it that way, all outside the
native profile so an ordinary build cannot make one either: `quarkus.package.jar.enabled=false`,
`quarkus.build.skip=true`, and maven-jar-plugin's `default-jar` bound to phase `none`. The second
is not optional — Quarkus' build goal asked for neither a jar nor a binary fails the build with
"No artifact results were produced" — and the native profile turns it back on. Skipping the goal
in a JVM build loses no coverage: every `@QuarkusTest` augments the application anyway.
`clean package -Dnative` leaves the binary alone in `target/`. The loop while working on the CLI
is the tests and dev mode, neither of which packages anything.

`.sdkmanrc` names the GraalVM (25.0.2-graalce, the platform's pin), so `sdk env` sets JAVA_HOME and
nobody exports it by hand. Without sdkman, name it on the command line instead:
`JAVA_HOME=$HOME/.sdkman/candidates/java/25.0.2-graalce ./mvnw package -Dnative -DskipTests`.

The native profile carries every flag the native build needs, so that command takes no others. Run
`clean verify` BEFORE a native package, never after: clean wipes the runner.

**The payload image builds the second flavour of that binary: the same program, fully static
against musl.** Its builder stage is GraalVM CE plus a musl toolchain it installs itself, and the
flags reach the build through one seam — `-Dnative.extra-build-args`, which the native profile
appends to the flag it already carries. Adding a flag for the host build goes in the profile;
adding one for the image goes on the Dockerfile's mvn line. Naming `quarkus.native.additional-build-args` whole on a command line breaks
this: Quarkus reads it from one config source, so the profile's flag would vanish without a word.

Both halves of "static musl" are forced, and neither is a preference. A binary linked against a
host glibc starts on no alpine image, and a *statically* linked glibc binary resolves no names —
glibc reaches the name services through `dlopen` — while every address this CLI dials is a wire
alias on `qits-net`. A glibc-static payload would build clean and fail its first lookup.

## Tests

`./mvnw clean verify` must be green on a clone, with **no docker**. What is tested is what can be:
the engine (ordering, failure, warnings, skips, timing), the process runner (streaming, bounded
memory, exit codes, timeouts, masking), the configuration mapping from `.env` names, the plan's
shape and order, the compose and run-args generation, the seed-Dockerfile rewrite, the recorded
state file, the plain renderer's output, and **the launcher's `docker run` argv** — asserted whole,
because a flag dropped there is a bootstrap that gets further than it should before it breaks.

The phases that shell docker are deliberately not unit-tested — a fake docker daemon would prove
that the fake works. **A real bootstrap is their test**, and it is the gate a change to them has to
pass. `unwrap` then `bootstrap` on a machine whose volumes stayed is the cheap form of it: the
registry blobs and the databases survive, so the seed images rebuild from docker's layer cache and
the deployables are pulled rather than rebuilt.

## Gotchas

- Quarkus' own logging is turned down in `application.properties` because this program repaints the
  screen. Anything worth saying goes through the display or the run log.
- JLine's `Display` arithmetic breaks on wrapped lines, so every line is cut to the terminal width
  and subprocess ANSI escapes are stripped (`proc/Ansi`).
- **JLine is pinned to its `exec` provider** in `UiFactory`, and the dependency is `jline-terminal`
  rather than the aggregate `jline`. The providers that call libc cannot be compiled into a native
  image on this GraalVM: the jni one unpacks a `.so` from its class initializer, and the ffm one
  stops the analysis at `linkToNative`. Do not widen the dependency back, and do not let a Terminal
  be built without naming the provider.
- The output of a command is read on its own thread. Without that, the timeout is not a deadline: a
  command that prints nothing is waited on inside `readLine`, where no clock is looking.
- **The browser view's port is bound before the boot starts, so a port in use ends the run** with
  Quarkus' "Port 8480 seems to be in use". `QITS_WEB_PORT` moves it, `QITS_WEB=0` stops it binding
  at all. Two runs at once need one of the two.
- The browser view's page is a Java string constant, not a resource. A constant is in the native
  image by construction; a resource has to be registered and can be right on the classpath — under
  the tests, under dev mode — and missing in the binary. Keep it that way.
- The SSE stream is drained by a timer on the event loop, never written from the boot thread: a
  Vert.x response belongs to the context that made it. `WebUi.close` waits up to 600 ms while
  someone is connected so the last frames leave before the process does.
