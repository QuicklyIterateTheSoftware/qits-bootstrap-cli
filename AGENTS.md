# AGENTS.md — qits-cli-bootstrap

## What this repository is

The qits platform's cold bootstrap, as a Quarkus command-mode CLI. It runs on the host, shells
docker and git, calls the platform's HTTP APIs, and shows a live two-region terminal display of
what it is doing.

## Read this first

**`qits-local-up.sh` in the wrapper repository (qits-qits) is still the reference.** This CLI is a
port of it, and it has NOT yet done a proven cold bootstrap. Until it has:

- The script is what a real bring-up should use when the two disagree.
- Every behaviour change here has to be justified against that script's comments, which carry the
  operational knowledge — ordering constraints, the 409/PATCH reconcile, the dual-ref pushes with
  `-o qits.no-ci`, the singleton and environment deploy refs, the mirror-prefix rewrite, the
  release replays, the lost-event self-heal, the machine-token minting. Those comments were ported
  with the code on purpose. Do not thin them out.
- When the first cold bootstrap succeeds, say so here and in the README, and record what differed.

Known deviations from the script, all consequences of running on the host, are listed in the
README under "How it differs from the script". Add to that list rather than deviating quietly.

## Layout

    engine/     the phase state machine: Phase, PhaseEngine, PhaseContext, Waiter
    proc/       ProcessRunner and friends: streaming, bounded tails, the full log
    ui/         Ui, the live TuiUi (JLine Display), the PlainUi fallback
    config/     BootstrapConfig (@ConfigMapping, read from .env) and its command-line overrides
    platform/   what the platform is made of: PlatformModel, the generated compose and run-args,
                the seed-Dockerfile rewrite, the recorded state, thin Docker and Git facades
    api/        the platform's HTTP, with java.net.http; InNetworkHttp for the two services that
                publish no host port
    phases/     the phases themselves and the plan that orders them

## Conventions

- **Plain Language everywhere** — code comments, commit messages, documentation, and every string
  the display prints. Say only what the reader needs, in as few words as possible.
- **A comment says why, not what.** The ported ones say why an order is what it is; keep that.
- **Every phase is visible and every wait is observable.** A phase that polls something remote must
  go through `Waiter.await`, which prints what is being polled, what was last seen, the elapsed
  time and the deadline. A silent wait is a bug in this repository, whatever it is waiting for.
- **Phases are rerun-safe**, the same way the script's are: 409s tolerated, existing networks
  adopted, up-to-date pushes no-ops, publishes probed before they are made.
- **Failure stops the boot** (exit 2). A deployment that never landed is a warning (`ctx.warn`,
  exit 1) — the script's `overall=1` — because the applications behind it still deserve their turn.
- **Secrets never reach the screen or the log.** Put them through `Cmd.mask`.
- Parentless pom, Quarkus pinned to the platform's version, plain JVM, no native profile.

## Tests

`./mvnw clean verify` must be green on a clone, with **no docker**. What is tested is what can be:
the engine (ordering, failure, warnings, skips, timing), the process runner (streaming, bounded
memory, exit codes, timeouts, masking), the configuration mapping from `.env` names, the plan's
shape and order, the compose and run-args generation, the seed-Dockerfile rewrite, the recorded
state file, and the plain renderer's output.

The phases that shell docker are deliberately not unit-tested — a fake docker daemon would prove
that the fake works. They are proven by a real bootstrap, which is the gate this repository has not
passed yet.

## Gotchas

- Quarkus' own logging is turned down in `application.properties` because this program repaints the
  screen. Anything worth saying goes through the display or the run log.
- JLine's `Display` arithmetic breaks on wrapped lines, so every line is cut to the terminal width
  and subprocess ANSI escapes are stripped (`proc/Ansi`).
- The output of a command is read on its own thread. Without that, the timeout is not a deadline: a
  command that prints nothing is waited on inside `readLine`, where no clock is looking.
