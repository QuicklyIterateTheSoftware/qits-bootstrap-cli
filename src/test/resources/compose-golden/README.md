# compose-golden

The rendered deployment extras, one checked-in file per application.

    extras/<application>.properties

Each file is the block `ComposeTemplate.extras` generates for that application, whole and in
generation order, rendered from the canonical fixture in `ComposeTemplateTest.tokens()`:
environment `prod`, no domain, and fake secrets. `ComposeTemplateGoldenTest` compares each block
against its file byte for byte, normalising nothing but the trailing newline.

## Regenerating

    ./mvnw test -Dtest=ComposeTemplateGoldenTest -Dqits.golden.regenerate=true

It rewrites every file, removes the ones no application answers to any more, **and then fails**.
That is deliberate: a run that repaired its own expectations and went green would prove only that
the generator agrees with itself. Regenerate, read `git diff`, and commit the diff as the change it
is.

The values are fixture fakes and safe to commit. `PG_*_PASSWORD`, `IDP_SECRET_*` and `PUSH_TOKEN`
are the literals that test spells; no real platform's secrets ever pass through here.

## Why the blocks are files and not assertions

The extras a deployment starts with are content. Forty `contains(<literal>)` assertions spelling one
block's lines back at it prove nothing a whole-block comparison does not, and they cost a test edit
every time a value moves — which is how a generator ends up with a test suite that only ever agrees
with it.

What this buys is the migration wave. The epic moves per-service defaults out of
`ComposeTemplate.EXTRAS` and into each repository's own `.config/qits/configuration.yml`, batch by
batch. With the block goldened, one batch is one diff a reader can see whole. **An application that
loses its last key is a golden file DELETED** — a decision somebody made and reviewed, rather than a
test that quietly stopped asserting anything.

Two things are NOT here and must not move here:

- **What a golden is allowed to hold** is `ExtrasWiringGuardTest`'s question. A golden records what
  the generator says; the guard says which keys the generator is entitled to say at all. Regenerating
  a golden can never launder a key past the guard.
- **Whether the two generated files agree** is `ComposeTemplateTest`'s. The seed stack and the extras
  spell a deployer setting twice on purpose — the update argv `--env-rm`s what the extras do not
  state — and a golden of one file cannot say the other file matches it. Every seed-to-extras
  cross-check, every domain-token rendering and every second-environment rendering stays an
  assertion there, because a golden is one platform and those tests are about two.

## Arguments the goldens now carry

The tests these files replaced held decisions in their comments. The assertions were content; the
arguments were not, so they are kept here.

**qits-configuration is a platform service with a gate and nothing else.** Its image ships the bare
`qits-configuration` as the audience, and since the 2026-09-07 plane move that is the right name
rather than merely an un-tiered one — the line stays spelled because the idp is seeded from the same
derivation, and an address nobody states is an address nobody notices moving. No mount, no publish
and no datasource: `resources: postgresql:db` in its own `deployments.yml` provisions its store, and
a triple here would be an operator pin that outlives the deployer's next rotation. Its one network
alias is TRANSITIONAL: the live deployer and the live orchestrator hold `<env>-qits-configuration` in
their running environments and only take the new name at their next deploy, so the container claims
both until the epic's cutover feature drops it.

**The orchestrator holds one idp client per peer, and the AUDIENCE is what makes them four.** Its gc
run asks the deployer and ci for the pins, hands them to qits-artifacts and tells qits-containers
what to reclaim — four peers behind four different audiences, so one client with one audience would
be a run whose every step but one is a 401. All four present the orchestrator's OWN id, never a
borrowed one: a refused step has to name the service that was refused. The targets are wire aliases
in both shapes — the environment services carry the tier, the deployer and the configuration store
carry none — and `QITS_ORCHESTRATOR_TARGETS_CONFIGURATION_URL` is the one whose absence was silent:
the image's own default was a name nothing on qits-net answered to while the store was a tier's, so
every gc run's `pins.images` read failed with a connect error and the sweep was skipped rather than
refused.

**qits-platform-maintenance holds a client per GUARDED peer and reads both registries bare.** Three
peers — the catalog at qits-projects, the manifests at qits-githost, the bump at qits-ci — behind
three audiences. The registries it reads versions from are unguarded on qits-net, so it holds no
client for them and this file emits none: enabling one would be a token nothing asks for against an
audience nothing validates. `QITS_MAINTENANCE_ENVIRONMENT` is not `QITS_ENVIRONMENT` and must never
be confused with it — it records which environment's CI ran a bump, on a service that belongs to no
tier.

**The admin console is the THIRD holder of the host's docker socket**, after the container
orchestrator and the deployer. The alternative was an exec endpoint on qits-containers' machine API,
which would put a shell into any container behind a credential every service on qits-net can mint;
the power stays in one console behind `qits:admin` instead, and a console that owns the PTYs has to
hold the socket itself. Its glances image is a repo and a version so a bump is one value; `hub/` is
the mirror's docker.io upstream and the tag is pinned to the `-full` variant, the one carrying the
docker plugin glances lists containers with.

**qits-projects is told where to drive a release and with which credential.** It refuses to release
at all while it cannot name a git host, and both keys ship unset — a tier that does not release never
learns one. The ci client is what lets the release gate read qits-ci's active runs and cancel what it
supersedes. The fourth named client is the maintenance one, and the audience is what makes it a
fourth: the catalog refuses a bearer minted for ci or for the git host. That third address enriches
an announcement and switches nothing on — unset, unreachable, refused or 404 and the event simply
carries no such key — so a platform without it releases exactly as well.

**The deployer's own deployment inherits ONE triple, not two.** Its config volume is the self-update
handoff: without that mount the successor comes up with no configuration at all and every later
deployment loses its volumes and its datasource env. Its own store is spelled because nothing else
can spell it — the deployer is what injects everybody else's — while the eventstream outbox is
declared in its `deployments.yml`, so a running deployer provisions it for its successor and injects
the triple from the registry row that holds the current password. Pinning that second triple here
would win over the injection and outlive the next rotation.
