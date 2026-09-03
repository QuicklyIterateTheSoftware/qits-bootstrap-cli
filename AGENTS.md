# AGENTS.md — qits-bootstrap-cli

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
  409/PATCH reconcile, the quiet pushes with `-o qits.no-ci`, the release each deployable is
  restored to, the wire aliases the seed services are named after, the mirror-prefix rewrite, the
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
                the run's state for the browser, the CompositeUi that feeds them all, and the
                EventFeed that follows the platform's own events beside the boot's output
    web/        the browser view's three routes and the one page they serve
    config/     BootstrapConfig (@ConfigMapping, read from .env) and its command-line overrides
    platform/   what the platform is made of: PlatformModel, the generated stack file and extras,
                the seed-Dockerfile rewrite, the recorded state, thin Docker and Git facades
    api/        the platform's HTTP, with java.net.http, at the platform's own wire aliases
    phases/     the phases themselves and the plan that orders them
    host/       the half that runs OUTSIDE the container: the payload image's content tag, the
                docker run it composes, and the launcher that preflights, builds and relays

## Conventions

- **Bootstrap progress is a public, narrow status surface.** The payload publishes `0.0.0.0:8480`
  by default and only `GET /`, `GET /state.json`, and `GET /events` may answer without credentials.
  Keep its state/event text redacted. Do not turn it into a loopback-only default or a general
  proxy. Git remains the separate Basic/capability-gated bootstrap ingress.

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
  about the application being waited for under a `  pd| ` prefix, read from `docker logs
  --timestamps` because what it wants is the deployer's own account of one application — a log line,
  not an event — while the docker socket is always there. Same courtesy rule: a read that fails
  relays nothing and fails nothing.
- **An announcement the platform makes once, this program re-makes once — where one can still be
  lost.** A green release run announces one `SoftwareRelease` per published artifact, and the
  deployer is an application THIS BOOT redeploys — a subscriber mid-cutover catches up on its own
  sweep rather than at once — so a green run with no deployment row after a minute gets the release
  handed over exactly once inside the wait, through `POST /platform-deployments/api/events/
  software-released`. One more attempt, never a retry loop. `/events/build-succeeded` is gone from
  the deployer and 404s; nothing here may go looking for a sha-addressed intake again.
  **The PUSH half of that rule is retired, and its retirement is the byte-plane split's dividend.**
  qits-githost writes `SCMPublishCommit` to the eventstream outbox inside the push's own
  transaction and qits-ci consumes it durably, so a ci that was down, restarting or mid-cutover
  reads the push back. `POST /ci/api/events/post-receive` is gone from that service; a pushed sha
  with no run is now a consumer catching up, and the wait says so in one line rather than
  re-announcing into nothing.
- **Every address this CLI dials is a wire alias, and there is no second set.** The run joins
  `qits-net` in its second phase and reaches the platform the way every other member does:
  `<env>-qits-artifacts:8080`, `qits-platform-mirror:8080`, `<env>-qits-githost:8080`, the postgres
  alias on 5432, `qits-platform-idp:8080`, and ci and
  the deployer through `qits-platform-edge:8080` — the edge rather than the service, so the run
  keeps exercising the gateway's route table. Do NOT add a host-addressed mode beside it: one set of
  addresses is what keeps the branching out of the code. The host's own addresses are still
  configured, and they are now TWO ports and three NAMES: `QITS_PORT` is the edge, which every
  browser and every host-side client arrives at, and the byte plane is reached through it at
  `registry.<env>.localhost`, `mirror.<env>.localhost` and `githost.<env>.localhost` — **every
  method on all three behind a credential since 2026-08-14, reads included.** docker does the
  Distribution spec's Bearer dance (a 401 naming a realm, HTTP Basic to it, Bearer back); maven,
  npm and git send Basic and nothing else, and the edge validates it against the same issuer. What
  a machine presents is its own service client; what a PERSON presents is COMMISSIONED from the
  idp per workstation, and the closing report prints the one-liner that asks for one. The 8081/8082 knobs
  are SEED-ONLY now: a container the CLI starts for the daemon's benefit needs both a publish and a
  network alias, the temporary Maven registry and the two seed byte services are exactly that case,
  and all of them are gone by the first cutover. No deployed service of the byte plane publishes
  anything.
  **One service is dialled at its own alias rather than through the edge**, and it is not an
  inconsistency to tidy away — it is deliberately off the gateway's route table.
  `<env>-qits-containers:8080/containers` is the container orchestrator's, and it must stay unrouted:
  every caller is a machine on qits-net, every route of the service is behind the machine gate, and a
  gateway entry would put a socket-holding service behind the platform's public door. This CLI only
  polls its health — it starts no workload through it.
- **Every network this program creates is an ATTACHABLE OVERLAY, and preflight makes the daemon a
  swarm manager.** A swarm service cannot attach a local bridge — measured — and an attachable
  overlay carries plain `docker run` containers just as well: the ci steps, the workspace and agent
  containers, and this run itself, with DNS answering both ways. So preflight initialises an
  INACTIVE daemon with `docker swarm init` and stops on every other state with the state named
  (`pending`, `locked`, a WORKER), because initialising over somebody else's swarm tears a machine
  out of a cluster. An existing BRIDGE called `qits-net` stops the run too: it cannot be converted
  in place, and the platform is re-bootstrapped rather than migrated — `unwrap` removes it.
- **The edge publishes in swarm INGRESS mode, and that is what lets it redeploy itself.** Under
  `mode: host` a cutover is stop-first — the old task gives the port up before the new one takes it
  — and the successor's image is now pulled THROUGH the edge, so it would have to be down to come
  up. Ingress puts the port on the swarm rather than the task, so the predecessor keeps answering
  while its successor pulls, starts and passes health. Two host settings follow from it and neither
  comes from a run's own configuration: the daemon needs the two registry names in
  `insecure-registries` (preflight asks the daemon and WARNS), and the host needs
  `ip6tables … --dport <edge port> -j REJECT --reject-with tcp-reset` on `lo`, because a
  `*.localhost` name resolves to `::1` first and the ingress mesh is IPv4-only — the listener
  accepts and never answers, so clients hang instead of failing over. **The launcher installs the
  second one itself**: it probes `[::1]:<edge port>`, runs `ip6tables` when the connect succeeds,
  and re-probes to prove the rule took. On EVERY run, because the rule does not survive a reboot
  and a past run's is no evidence — one that was gone let a release run's SBOM upload hang forty
  minutes on 2026-09-02. Installing needs root, so a plain user gets the old warning instead; the
  closing report prints both steps.
- **A seed build resolves the platform's own jars from the SEED registry, and is TOLD so.** Every
  image this run builds — the seed images, the step images, the ci-daemon's musl builder — carries
  `--build-arg QITS_MAVEN_REPOSITORY_URL=http://localhost:<registry port>/artifacts/maven/maven`,
  set once on the `Docker` facade (`Boot.imageBuildArgs`) rather than at each call site. The
  repositories' own `ARG` default names the edge vhost, which is the address of a platform that is
  already up; a seed build runs before any edge exists. The default WAS this same loopback url
  until the vhost sweep, so the bootstrap passed nothing for months and then died at the
  qits-platform-mirror seed build with a connection refused. A build argument an image has no
  `ARG` for is a warning, which is why every build gets it rather than a chosen list.
- **The seed is a docker STACK, and four compose words do not survive the move.** The generated
  file carries no root `name:` and no `group_add:` — either one makes `docker stack deploy` refuse
  it outright, measured — no `container_name:` (ignored; a task's container is
  `qits_<service>.<slot>.<taskid>`) and no `restart:` (it is `deploy.restart_policy`). The two
  socket-holding seed services take the socket's group as their PRIMARY group, `user:
  "1001:<gid>"`, because a stack file has no way to say a supplementary one; their deployed
  successors get `--group-add` from the extras, which does. Two is the SEED's count: a third
  service holds the socket among the DEPLOYED ones — see the bullet below. **Every name-based check went with
  it**: a seed service is found through `docker service ls`, under `qits_<alias>` or the bare
  `<alias>`, never through `docker ps`. A container name is still the right question for a
  DEPLOYMENT — the deployer runs `docker run` containers named `qits-pd-…` until phase 3 of the
  swarm migration lands — so the two lists are asked together and neither replaced the other.
- **Never a seed service beside a deployed container.** That is the rule the stack file has no
  `depends_on` for, and swarm made its second half necessary: a compose sibling stayed down once a
  cutover removed its container, while a SERVICE's task is restarted within seconds. So a rerun
  leaves a deployer-managed application out of the FILE (a stack deploy takes no service list) and
  removes any seed service of it outright.
- **Deployment configuration is platform state, and the ORDER that moves it there can lose a boot
  in both directions.** qits-configuration holds each application's extras, and the deployer treats
  it as AUTHORITATIVE the moment it is given `QITS_PLATFORM_DEPLOYMENTS_EXTRAS_URL`: a read it
  cannot answer REFUSES the deployment rather than falling back to the file, which is the whole
  point — a stale fall-back is the failure this service exists to kill. So the plan runs
  `deploy-configuration`, then `configuration-import`, then `configuration-flip`, and each gap is
  load-bearing. Flip earlier and the deployer refuses every deployment left in the train, that
  service's own included. Flip later than qits-deployments' self-update and the successor comes up
  holding the url over a service nobody imported into. The seed stack therefore spells NONE of
  those keys — the seed deployer has to start without them — while the generated extras spell all
  of them, because a live `service update --env-add` alone is exactly the fix the old model kept
  reverting.
- **The extras file is BOOTSTRAP-ONLY, and after the flip it is unread.** It is what the seed
  deployer runs from before qits-configuration exists and what `configuration-import` imports whole,
  so the store and the file cannot disagree at the moment authority moves. From the flip on, the
  deployer reads the service and NOT the file — authoritative means sole, or a key deleted from the
  store comes back out of whatever nobody emptied on the volume. Editing it on a flipped platform
  changes nothing. Unsetting `QITS_PLATFORM_DEPLOYMENTS_EXTRAS_URL` is the rollback, and the file it
  falls back to may be months stale by then: re-render before leaning on it.
- **The file holds EXTRAS and nothing else.** The deployer's own settings — the ones spelled
  `qits.platform.deployments.<key>` rather than `...extras.<app>.<key>` — are env now,
  `QITS_PLATFORM_DEPLOYMENTS_*`, and each is spelled **twice**: on the seed stack's deployer service,
  which starts before any extras are read, and on the deployer's own extras block, which is what
  every self-update's successor inherits. Twice is not belt and braces since 2026-08-17: the update
  argv `--env-rm`s what the extras do not state, so a variable on the seed service alone would be
  gone at the deployer's first self-deploy. **The extras family is the one thing that cannot move to
  env**, and `ServiceExtras` says why — an underscore cannot tell `qits-ci`'s keys from
  `qits-ci-daemon`'s, because a dash and a dot both become one. Never render extras as env.
  The volume itself stays whatever happens: `config.json`, the credential `DOCKER_CONFIG` names, is a
  second file on it with a phase of its own. `demotion-rollout.md` is the hand procedure for a
  platform that is already flipped.
- **A DEPLOYMENT IS A RELEASE, and there is no ref that deploys.** qits-projects tags a release
  through qits-githost's primitives and publishes `SCMRelease`; the repository's own
  `ci-event-release.yml` builds that tag and publishes `qits/<app>:<version>`; qits-ci announces
  `SoftwareRelease`; qits-deployments enters a deployment request and pulls that image into the one
  designated platform environment. So this program seeds NO `environment/*` ref, derives none, and
  spells none in a generated file — the deployer's environments have no branch column to hold one.
  What a bring-up does instead is restore two SCM facts per deployable (main, and the newest release
  tag) and then say the one word that starts the release build. `environment-branch-retirement.md`
  is the hand procedure for a platform that still carries the old refs.
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
- **A repository has TWO identifiers and this program mints one of them.** The STORAGE id is
  qits-githost's key — an opaque UUID, `/git/<id>` is the address of the store, and the deployed git
  host serves that scheme to qits-projects' service client alone (`qits.githost.storage-client`,
  whose guard demands the client's self-role). The PUBLIC identity is `(projectId, repoName)`, and
  `/git/<projectId>/<repo>.git` is the one clone url there is — with the project's SLUG as the
  public spelling of the first segment, which qits-projects resolves as readily as the id.
  **qits-projects is a seed service, so the alias table answers before the first push.** `git-repos`
  does all three acts per repository, in this order and no other: mint the uuid, `PUT /git/<uuid>`,
  hand qits-projects the pair through the adopt route. The pairing is written to
  `.qits-bootstrap.env` before the bare is made and read back by `recorded-state`, so a resumed run
  addresses the bares it created — a uuid has nothing to re-derive it from, and `Boot.storageId` is
  the one place that mints and the one place that remembers.
  **`Boot.gitUrl` is the one place that decides which address a push uses**, and it flips once per
  boot in that phase; no phase may decide it for itself. The lifecycle PUT is the only thing this
  run ever addresses by id, and every PUSH is name-addressed — which is what puts `projectId` and
  `repoName` on each push's event. Two things hold the shape together and each is a boot that fails
  if it moves: everything after `deploy-githost` MUST be name-addressed, because that deployment
  closes the storage scheme behind it; and the guard is therefore spelled in the deployment EXTRAS
  and never on the seed stack — seeding qits-projects did not change that and could not, because
  the credential the PUTs present is the BOOTSTRAP's and the guard demands one other client's
  self-role. The same rule sends `qits.ci.projects-url` to ci's extras and not to the seed:
  configured, it replaces the git host's own listing, and a seed ci holding it would answer the
  release replays with an empty candidate list. `README.md`'s "Two coordinates, one seam" is the
  long form, including the two switches the seed qits-projects is started with.
- **The seed qits-projects starts with its self-seed HELD and its wrapper reconcile OFF, and both
  are load-bearing.** `QITS_STARTUP_SEED_ENABLED=false` on the stack, turned on by the
  `qits-project` phase: creating the `qits` project also creates the wrapper's origin on the git
  host, which needs a bearer the idp mints — and a self-seed that fired while the idp was still
  coming up would fail, roll its own transaction back (project row included) and not try again
  until the container restarted. `QITS_STARTUP_SEED_RECONCILE_REPOSITORIES=false` stays off for the
  whole seed window: under the UUID ruling no storage id is a name, so a wrapper reconcile against a
  platform whose repositories do not exist yet matches no entry and takes its remaining arm, which
  is to mirror every repository in from the org — minutes before this program has created a single
  bare, and from the forge rather than from the checkouts the run was told to build. The DEPLOYED
  container spells neither key, so its reconcile is the platform's first and every entry matches a
  row by alias. **That second key is a qits-projects config this repository depends on**; without it
  honoured there, a seed boot mirrors the estate in from GitHub.
- **Phases are rerun-safe**, the same way the script's were: 409s tolerated, existing networks
  adopted, an already-attached container accepted, up-to-date pushes no-ops, publishes probed
  before they are made.
- **Every push to the git host survives a bounded flux window** — `Boot.push`, retried every 5
  seconds for 90, through `Waiter` so each failed attempt is on the screen. The boot deploys the
  platform's own postgres mid-run and that cutover severs every connection pool for seconds:
  measured on 2026-08-11, phase 52's push of qits-platform-idp met phase 51's postgres and exited
  128, killing a 30-minute boot at 52/67. The retry is on ANY failure because a push is idempotent
  and rerun-safe; a real misconfiguration costs the window and then fails with git's own last
  words, the attempt count and the elapsed time. Push through `Boot.push`, never `git.push`: the
  token is masked there once, for every attempt.
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
- **A volume is data, config or CACHE, and the three sweeps differ.** `--with-data-volumes` is a
  cold boot of this platform's state, so it takes `qits-*-data` and `qits-maven-seed` and keeps
  `qits-*-config` and `qits-maven-cache`; `--with-volumes` takes every `qits-*` volume. The cache
  holds third-party jars the bootstrap's own maven containers pulled from Maven Central — one
  `/cache` mount named by `-Dmaven.repo.local`, shared by the seed, every publish and the
  ci-daemon build — and re-fetching that world on each re-bootstrap is what throttled this host on
  2026-08-11. **Every one of those scripts deletes `eu/wohlben/qits` out of the cache first**: a
  third-party jar is immutable at its version and a seed-built qits jar is not, because seed
  builds reuse calvers across runs.
- **A name in `PlatformModel` is the APPLICATION name without `qits-`, and it is load-bearing
  three times over**: the wire alias, the seed image tag and the deployer's application key.
  **The REPOSITORY is a second name, and `PlatformModel.REPOSITORIES` is where the two part
  company.** It used to be one name doing both jobs; the phase-2 renames moved the repository into
  the `<component>-<role>` grammar and moved nothing the running platform answers to, which is what
  `deployments.yml`'s `application:` key pins from the repository's own side. So `repo(name)`
  answers the git-host repository, the clone url, the `.gitmodules` entry and the checkout
  directory, while `application(name)` answers everything the platform is addressed by — and
  `wireAlias`, `pdNamePrefix` and the extras family are the only callers of the second. Getting
  them the wrong way round points the boot's own database at a host nothing serves.
  **The wrapper DIRECTORY is no longer one of them, and that is deliberate.** The wrapper moved
  from six archetype directories to `components/<component>/<repo>`, and a component is not
  derivable from a name — `qits-ci-frontend` belongs to component `qits-ci`. So the wrapper's own
  `.gitmodules` is the authority on where a repository sits: `GitModules` reads it, `RunState`
  looks each repository up by name, by the path's last segment and by the url's, and
  `PlatformModel.repoPath` is only the fallback for a wrapper this machine has not cloned yet.
  Nothing here spells a layout that a checkout could contradict. `PlatformModel.archetype` reads
  the path's first segment while it is one of the six, and derives from the NAME otherwise — the
  role suffix phase 2 renames into, then a table of today's names. The plane is DECIDED by
  `PlatformModel.PLATFORM_SERVICES` and
  usually also said by the name (`platform-idp`, `platform-mirror`); the tier lives in the WIRE
  ALIAS derived from it (`prod-qits-ci`) — never the other way round. **The list is the authority,
  not the spelling**: qits-deployments and qits-events joined the platform plane on 2026-08-17 and
  their APPLICATIONS still answer to a bare name that says no plane at all, while their
  repositories say it (`qits-events-platform-service`).
- **The host's docker socket is granted to THREE services, and every grant is an extras block
  somebody wrote on purpose.** qits-containers starts every workload on the host, qits-deployments
  is a deployer, and qits-platform-system owns the admin console's terminals. A fourth is a
  decision, not a mount: it needs `mounts[<i>]=bind:/var/run/docker.sock:…` AND
  `groups[0]=${DOCKER_GID}` — the group is what makes the socket usable by a container running as
  uid 1001 — plus a comment above the block saying why the power belongs there rather than behind
  an endpoint on one of the three. A socket holder that also PULLS needs a `config.json` of its
  own: `SeedPhases.dockerConfig` writes one per holder, with the hosts that holder pulls from (the
  registry vhost for the first two, the registry AND the mirror for qits-platform-system, whose
  glances image is the mirror's).
- **Nothing outside `PlatformModel` decides a wire alias, a client-id key or whether a service is
  told its tier.** All three change when an application moves plane, so `wireAlias`,
  `pdNamePrefix` and `PlatformModel.modelTokens` are the only places any of them is built — the
  generated stack and extras carry `${ALIAS_<APP>}`, `${CLIENT_KEY_<APP>}` and `${TIER_ENV_<APP>}`
  placeholders, and `BootstrapConfig`'s urls derive too. A concatenated copy is a peer dialling a
  name nothing answers to, or an audience the idp never mints, and both fail as a silent 401.
- **`QITS_ENVIRONMENT` is a statement of tier membership, and a platform service is handed none.**
  The deployer records a resource row per application under that environment, `orElse(null)`, and
  looks a platform-target service's rows up by the null key. Tell a platform service it has a tier
  and its rows land under one: its own first self-deploy finds nothing, reconciles instead, and
  rotates the database passwords this bootstrap issued, mid-boot. The line is a
  `${TIER_ENV_<APP>}` fragment that renders empty for the platform plane — never spelled by hand.
- **A source this program cannot trust stops the boot.** It decides which sha the whole platform is
  built from, so a wrapper path that is not a checkout and a refresh that will not fast-forward are
  both failures, not log lines. What is ABSENT is a different question and has a different answer:
  a missing wrapper is cloned from the org by the `wrapper` phase, and a missing component checkout
  is cloned by `sources`. An EMPTY directory counts as absent — git leaves one at every gitlink of
  a wrapper cloned without its submodules, and it hides no local work — while a directory with
  anything in it still stops the boot.
  **The org fallback is now allowed only where it hides nothing**, and `SeedPhases.localSourceRefusal`
  is where that line is drawn. A repository the wrapper DECLARES whose directory holds no checkout,
  in a wrapper whose other submodules ARE checked out, stops the boot and names
  `git submodule update --init <path>`: that is a half-initialised checkout, and cloning the org's
  last push instead would build last week's platform and say so in one line among thousands. A
  wrapper with NO submodule checked out is the cold start, and every repository of it comes from the
  org in silence, as it must.
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
- **The platform's own account of itself is a SECOND stream, never mixed into the first.**
  `EventFeed` polls qits-events through the edge on its own daemon thread for the whole run and
  feeds `Ui.event`, which the live display puts in a column of its own, the plain one marks
  `  ev| `, and the browser view puts beside the step output. Two reasons it is a poll and not the
  bus's socket: a subscriber is a durable consumer with a name, and this run is a spectator that
  comes and goes; and the service is one the boot DEPLOYS and redeploys, so there is nothing to
  stay connected to. Its rules are `CiLogStream`'s courtesy rule taken further — a read that does
  not answer says nothing and changes nothing, arriving and going away are both silent, and the
  feed starts at the HEAD of the log because a reseeded platform's history is not this run's.
  It must never be given a way to fail or slow a phase.
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
shape and order, the stack-file and extras generation, the stack and service argv, the
seed-Dockerfile rewrite, the recorded
state file, the plain renderer's output, and **the launcher's `docker run` argv** — asserted whole,
because a flag dropped there is a bootstrap that gets further than it should before it breaks.

The event feed is tested the same way the ci relay is: the pure parts here (one event to one line,
the column arithmetic, the watermark and the ids that stop a row arriving twice) and the rest on a
real bootstrap. **What only a real bootstrap proves** is that the edge routes `/events/api/events`
to a bus that answers this run unauthenticated, that the head-then-forward pair reads a live log the
way the query API's cursor is documented to, and that the column keeps up across the bus's own
redeployment.

The phases that shell docker are deliberately not unit-tested — a fake docker daemon would prove
that the fake works. **A real bootstrap is their test**, and it is the gate a change to them has to
pass. `unwrap` then `bootstrap` on a machine whose volumes stayed is the cheap form of it: the
registry blobs and the databases survive, so the seed images rebuild from docker's layer cache and
the deployables are pulled rather than rebuilt.

## Gotchas

- **`Http` builds a fresh client per request, and pooling must not come back.** A pooled
  connection is a cached answer to "who is this name", and every peer on qits-net is a container
  this run restarts or replaces. Measured: a poll that connected during qits-platform-idp's
  crash-restart landed on a wrong peer, the shared client reused that connection, and the wait
  read ninety seconds of 404 from a service that was healthy the whole time.

- Quarkus' own logging is turned down in `application.properties` because this program repaints the
  screen. Anything worth saying goes through the display or the run log.
- JLine's `Display` arithmetic breaks on wrapped lines, so every line is cut to the terminal width
  and subprocess ANSI escapes are stripped (`proc/Ansi`). **The lower region is two columns, so the
  cut is now PER COLUMN**: each side is cut to its own width and the left one is padded to a fixed
  split, because cutting the joined pair would cut the event column away rather than the overlong
  build line that caused it, and an unpadded left column would move the divider every frame. Under
  100 columns the split is not made at all — `TuiUi.eventColumn` answers 0 and the event pane is
  dropped, which is the only honest thing to do with forty characters.
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
