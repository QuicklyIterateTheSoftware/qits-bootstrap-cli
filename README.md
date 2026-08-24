# qits-cli-bootstrap

Brings the qits platform up on your workstation's docker daemon, **through the platform's own
pipeline**, and tells you what it is doing while it does it.

It **is** `qits-local-up.sh` in the wrapper repository — that file is now a shim that compiles this
CLI and runs it. The choreography is the shell port's, step for step; what is new is that a
four-hour cold start is no longer four hours of silence.

    ┌ qits bootstrap · 41m12s elapsed · log qits-bootstrap-cli.log ──────────────┐
    │   … 31 earlier phases done                                                 │
    │   ✓ 32/59 wait for the seed services (1m20s)                               │
    │   ✓ 33/59 publish the ci-daemon binary to the registry (12s)  — 8d0f1a2b…  │
    │ ▸ 34/59 create the platform's repositories and register their names ⠹ 4s   │
    │      PUT http://prod-qits-githost:8080/git/6b0e…-9a5b-0001                 │
    │   25 phases pending — next: pre-seed release-train histories               │
    ├────────────────────────────────────────────────────────────────────────────┤
    │  qits-spa-deployments -> /git/1f0a…-4c3a  (created), registered            │
    │  qits-spa-observability -> /git/8b1f…-9a0c  (created), registered          │
    │  … the running step's own output, live                                     │
    └────────────────────────────────────────────────────────────────────────────┘

The same run is also served at the bootstrap edge while it runs — see
[The browser view](#the-browser-view).

The progress endpoint is deliberately public: only `GET /`, `GET /state.json`, and `GET /events`
(SSE) exist, and progress text is redacted before it is published. The durable supervisor itself
publishes no host port. The bootstrap edge also carries the seed Maven repository and bootstrap
Git route, both behind its run-scoped capability; there is no catch-all proxy.

## Two modes

    qits bootstrap     # bring the platform up (the default when no mode is given)
    qits unwrap        # take it off this machine again

`unwrap` removes the seed STACK, the qits-marked swarm services, containers, images and networks
— services before containers, because removing a service task's container removes nothing: swarm
starts another one. `docker stack rm qits` comes first and the compose-era `compose down` stays
beside it, for a machine carrying a platform bootstrapped before the swarm cutover. **The volumes
stay** — they hold the databases, the registry's blobs and the git host's repositories.
`unwrap --with-volumes` is the full clean slate; `unwrap --with-data-volumes` removes the
`qits-*-data` volumes (and `qits-maven-seed`) while keeping every `qits-*-config` one, which is
what a move onto another database needs and what keeps the push token, the client secrets and the
deployer's config; `unwrap --dry-run` lists what would go and removes nothing.
`qits-edge-letsencrypt` and `qits-edge-acme` match neither pattern and are therefore kept by
`--with-data-volumes` — a certificate is rate-limited to re-issue, and a database reset is no reason
to lose one. The second of the two holds certbot's own state: the ACME account key and the
certificate lineage, so a rerun renews rather than registering a fresh account every boot.
`qits-maven-cache` is kept by `--with-data-volumes` too, and named in the keep list rather than left to that rule: it is a cache of third-party jars
from Maven Central, not this platform's data, and re-fetching the dependency world on every
re-bootstrap is what got this host throttled. `--with-volumes` still takes both.

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

There is **no jar**, here or anywhere else — the binary is the only form of this program, and the
build makes no second one. While working on the CLI itself, the loop is the tests and dev mode:

    ./mvnw clean verify
    ./mvnw quarkus:dev -Dquarkus.args="unwrap --dry-run"

Dev mode and the binary behave the same, live display included: JLine is pinned to its `exec`
terminal provider, which shells `/bin/stty` rather than calling libc, because the providers that
call libc do not survive being compiled into a native image. One process per terminal, on a program
that already shells docker and git for a living.

It runs **in a container on `qits-net`**, with the host's docker socket mounted. Every address it
dials is a wire alias — `prod-qits-artifacts:8080`, `qits-platform-mirror:8080`,
`prod-qits-githost:8080`, the postgres alias on 5432, `qits-platform-idp:8080`, and qits-ci and
qits-deployments through `qits-platform-edge:8080` — and
the run joins the network itself, before it dials anything. There is no host-addressed mode beside
that one.

**You still run it on the host, and it puts itself in there.** See
[Two halves, one binary](#two-halves-one-binary): started outside a container it preflights docker,
builds `docker/Dockerfile.bootstrap` into a payload image tagged by its own content, runs itself
inside it and relays the exit code.

It also **finds the wrapper by itself**. This CLI lives at `cli/qits-cli-bootstrap` inside it, so
running from anywhere in the checkout is enough: the working directory is walked upwards for the
first `.gitmodules` naming the qits submodules. `QITS_WRAPPER_DIR` (or `--wrapper-dir`) still wins,
and preflight prints which of the two happened. The container is told the answer rather than
walking for it again.

And when there is no wrapper at all, it **clones one**. A bare machine has no checkout to run from
and no platform git host to clone one from — this run is what creates that host — so the wrapper
comes from the GitHub org, anonymously, into the working directory: `<cwd>/qits-qits`, made by the
`wrapper` phase, owned by whoever typed the command, and there to rerun from afterwards. Its
submodules are deliberately left uninitialised; the `sources` phase clones every platform
repository from the org anyway, so initialising them would clone the platform twice. A wrapper
that IS there is skipped, never refreshed: the sha it stands on is the operator's decision.

What it needs to run: a reachable docker daemon, roughly 4 GB of RAM free per native build it
starts (each bootstrap build is capped to 2 CPUs and 4 GB; at most two may run), and reach to quay.io, registry.access.redhat.com, docker.io and npm — a cold start cannot
pull through the mirror it is starting. **Nothing else**: git, the compose plugin (unwrap still
takes a pre-swarm platform down with it), `stty` and the CLI's own binary are in the payload image,
which the run builds for itself. What it needs to build:
the GraalVM `.sdkmanrc` names for the binary, any JDK 25 for the tests.

Cost, honestly: every seed image and every pipeline run is a cold GraalVM native build with no
maven cache. The first run is measured in hours. Reruns skip what exists —
`QITS_SKIP_BUILD=1` for the seed, and unchanged repositories push up to date and trigger nothing.

## Configuring it

Copy `.env.example` to `.env` in the directory you run from and edit. Quarkus reads `.env` as an
environment source, so every knob is also an ordinary environment variable under the same name —
the same names `qits-local-up.sh` read:

| knob | default | what it is |
| --- | --- | --- |
| `QITS_WRAPPER_DIR` | detected | the wrapper repository whose checkouts are the sources, and where the stack file and `.qits-bootstrap.env` land — found by walking up when unset, and cloned into `<cwd>/qits-qits` when this machine has none |
| `QITS_SRC` | `.qits-bootstrap-src` | where those checkouts are cloned to |
| `QITS_ORG_URL` | the GitHub org | where a repository with no local checkout is cloned from — the wrapper included, on a cold start. Read anonymously |
| `QITS_PORT` | `8080` | **the host's ONE HTTP port**, bound by qits-platform-edge in swarm INGRESS mode, and the door of the whole platform: a person's browser, and the registry, the mirror and the git host, which are reached at `registry.<env>.localhost:<this>`, `mirror.<env>.localhost:<this>` and `githost.<env>.localhost:<this>`. Every `*.localhost` name resolves to the loopback address by itself, so there is no hosts file to edit. The CLI dials the edge's alias instead |
| `QITS_REGISTRY_PORT` | `8081` | reserved for the registry break-glass helper. Normal bootstrap traffic reaches the temporary Maven repository through the capability-gated bootstrap edge; neither the seed nor deployed artifact service publishes this port |
| `QITS_MIRROR_PORT` | `8082` | reserved for mirror compatibility and break-glass configuration. The seed and deployed mirror remain private on `qits-net`; normal traffic reaches them through the active edge |
| `QITS_GIT_HOST_PORT` | `8083` | qits-githost's old host port. **Nothing publishes it any more** — neither the seed, which comes up inside the stack, nor the deployment. A person clones and pushes at `http://githost.<env>.localhost:<QITS_PORT>/git/<projectId>/<repo>.git` through the edge, where every method needs a bearer, reads included. The knob stays because this is the number to reopen by hand while an edge is being repaired. Nothing in the CLI dials it; every phase that pushes runs inside a container on qits-net |
| `QITS_DOMAIN` | unset | **the domain this platform serves.** Unset is a full platform with no public names: the edge stays on plain HTTP. Set, the edge gets ports 80 and 443 with a certificate slot on a volume, and a real Let's Encrypt certificate is ordered for the name. The management port 9000 is NOT published: the challenge-management endpoint is unauthenticated and a swarm publish cannot be loopback-only, so it is reached on qits-net. `QITS_PUBLIC_IP` is **mandatory** beside it. **This platform serves no dns** — the records live at whatever provider holds the domain, and they go in **before** the run. Lowercase, at least two labels, no trailing dot; a bad value stops the run before anything is built. `--domain` is the same knob for one run |
| `QITS_PUBLIC_IP` | unset | **this host's public IPv4 address, and mandatory whenever `QITS_DOMAIN` is set.** It is the data of every A record the domain needs at its dns provider, and the run cannot learn it, because it is a container behind a NAT. The closing report prints the records with it filled in, and the certificate order is answered over a name that carries it. Four dotted octets, no leading zeros; **a hostname is refused rather than resolved**, since the value becomes an A record a resolver later reads back. Set without a domain it is refused too, rather than ignored: there would be nothing to serve. `--public-ip` for one run |
| `QITS_ACME_MODE` | `staging` | **which Let's Encrypt directory the edge's certificate is ordered from**: `staging`, `production` or `off`. Staging by default and deliberately — production counts *failed* orders per registered domain per week, and the order most likely to fail is the first one, against records the world has not seen yet. Staging issues from an untrusted root, so a browser still refuses the certificate; it proves the records, the challenge and the reload for free. Flipping to production is one rerun with this set and **no redeploy**: the PEMs land on the `qits-edge-letsencrypt` volume under the same two names and the edge's TLS registry reloads them within the hour. `off` keeps the self-signed placeholder and prints the manual command. Ignored with no domain. `--acme-mode` for one run |
| `QITS_ACME_EMAIL` | `hostmaster@<domain>` | **the ACME account's contact**, where Let's Encrypt sends expiry warnings if a renewal ever stops happening. Derived rather than defaulted: `hostmaster` is the convention for the role that answers for a domain, so a platform with a domain has a working contact without a second knob to fill in. Set it when the mail for that domain is not read. `--acme-email` for one run |
| `QITS_DNS_HETZNER_TOKEN` | unset | Hetzner Cloud token used by the edge to create and remove DNS-01 TXT records. Required when a domain uses `staging` or `production`; keep it out of source control. |
| `QITS_DNS_HETZNER_SECRET` | unset | Name of an existing Swarm secret containing the Hetzner token. Preferred for repeat bootstraps; when set, bootstrap verifies and reuses it instead of requiring the plaintext token. |
| `QITS_SKIP_BUILD` | `0` | 1 = the seed images and the daemon binary exist; skip to the stack deploy and the pushes |
| `QITS_SHIP_MAINS` | `0` | 1 = build and deploy the local mains instead of RESTORING each deployable's and publisher's newest release tag. A boot restores by default: the deploy ref is moved to the release commit, so a main that is ahead deploys nothing. This is the dev loop's flag, and it is also why the 2026-08-08 accident cannot repeat — shipping unreleased code takes saying so. `--ship-mains` is the same knob for one run |
| `QITS_CI_CONCURRENT_BUILDS` | computed | **how many builds qits-ci runs at once, and it is sized by this host's memory rather than written down**: `max(1, floor((total GiB - 10) / 6))` — 1 on a 16 GB host, 2 from 22 GB, 3 from 28 GB. A step's `docker build` is served by the HOST daemon, so a GraalVM-native build runs OUTSIDE the step container's `QITS_CI_MEMORY_LIMIT=4g` and costs gigabytes of its own; two of them on a 16 GB host with no swap livelocked the machine on 2026-08-22, and the literal `2` this replaces is what wrote an operator's hand-set `1` back over on the next re-bootstrap. Set this to use a number of your own instead |
| `QITS_MACHINE_AUTH` | `1` | machine-token enforcement for ci, deployments and artifacts |
| `QITS_PUSH_TOKEN` | `local-dev` | the git host's push token — the documented escape hatch, not a secret |
| `QITS_DEPLOY_TIMEOUT` | `3600` | seconds to wait per application deployment |
| `QITS_RELEASE_TIMEOUT` | `3600` | seconds to wait per replayed release run |
| `QITS_HEALTH_TIMEOUT` | `120` | seconds to wait per seed service |
| `QITS_POLL_INTERVAL` | `10` | seconds between polls |
| `QITS_ENV_NAME` | `prod` | the environment, and the ONE deploy ref is its `environment/<name>`. It is the **platform environment** — the tier whose branch deploys the platform plane — and it is inside every wire alias, every deployed container name and every idp client id. `--platform-env` is the same knob for one run |
| `QITS_IDP_CLIENT_<ID>_SECRET` | generated | pin one idp client's secret instead of generating it |
| `QITS_PG_SUPERUSER_PASSWORD` | generated | pin postgres' superuser password. 16–64 hex, because it is assembled into SQL that cannot be parametrized. It applies at initdb only, so on an existing cluster the value in `.qits-bootstrap.env` is the only way in |
| `QITS_PG_DEPLOYMENTS_PASSWORD` | generated | the same, for the deployer's own role. This one converges on every rerun |
| `QITS_PG_DEPLOYMENTS_EVENTSTREAM_PASSWORD`, `QITS_PG_CI_PASSWORD`, `QITS_PG_CI_EVENTSTREAM_PASSWORD`, `QITS_PG_PLATFORM_IDP_PASSWORD`, `QITS_PG_EVENTS_PASSWORD`, `QITS_PG_ARTIFACTS_PASSWORD`, `QITS_PG_PLATFORM_MIRROR_PASSWORD`, `QITS_PG_GITHOST_PASSWORD`, `QITS_PG_GITHOST_EVENTSTREAM_PASSWORD`, `QITS_PG_CONTAINERS_PASSWORD`, `QITS_PG_CONTAINERS_EVENTSTREAM_PASSWORD` | generated | the same, for the core seed services' databases — the deployer's outbox among them, because the eventstream library's Flyway lineage needs a database of its own. Created once and never altered again: the deployer's resource registry owns them from the first pipeline deployment on |
| `QITS_TUI` | `1` | 0 = plain output even on a terminal |
| `QITS_WEB` | `1` | 0 = no browser view; the HTTP server never binds |
| `QITS_WEB_PORT` | `8480` | the browser view's port |
| `QITS_WEB_HOST` | `0.0.0.0` | who can reach the view — the host side of the publish (WSL2 browsers need non-loopback); `127.0.0.1` keeps it off the LAN |
| `QITS_TAIL_LINES` | `2000` | lines of the running step kept for the body |
| `QITS_EVENTS_FEED` | `1` | 0 = do not follow the platform's own events beside the step's output |
| `QITS_LOG_FILE` | `qits-bootstrap-cli.log` | the full log of every command |

`--wrapper-dir`, `--skip-build`, `--ship-mains`, `--no-tui`, `--platform-env` and `--domain` answer
the same questions for one run.

Two names in that spelling are **not yours to set**: `QITS_IN_CONTAINER` and `QITS_WEB_BIND`. The
launcher sets them for the payload and nobody sets them by hand — see below.

`--platform-env <name>` is for a FIRST boot. Bootstrapping over a platform whose environment has
another name is **refused**, not honoured as a rename: the name is inside every wire alias, every
deployed container name and every recorded idp secret, so a PATCH would leave the platform running
as `<old>-qits-*` while every generated file addresses `<new>-qits-*`. The phase names what stands
there and points at `unwrap`. Moving the plane on a live platform is a PATCH on the deployer's
`pd_environment.platform`, with the undeploy and redeploy that implies, and nothing here does it.

`--domain <domain>` and `--public-ip <ipv4>` are checked before the payload image is built, because
both values leave this machine: they become a certificate request to Let's Encrypt and the records a
person types at a dns provider, and a rerun with the spelling fixed undoes neither. A domain without
an address is refused, and so is an address without a domain.

**DNS IS NOT THIS PLATFORM'S, and the records go in before the run.** At whatever provider holds
`<domain>`, as A records for `<QITS_PUBLIC_IP>`:

    @        the apex — the browser door, and no wildcard covers it
    *        every <env>.<domain> gateway
    *.*      every <app>.<env>.<domain> vhost

A wildcard per depth rather than a record per name, which is what the edge's routing actually needs:
it reads at most the first two labels of a Host header, so `<env>.<domain>` and
`<app>.<env>.<domain>` are covered for every environment and every app there will ever be, and adding
either needs no dns step. The apex is written out because no wildcard matches an apex. Do it first,
because the certificate order is answered over the public name — and the closing report prints the
same set back with the address filled in.

The run then **orders the certificate**. The edge is not an ACME client — the Quarkus TLS extension gives
it a challenge route on the main listener and, on the unpublished management port, one slot to fill
and a way to re-read its certificate files — so the protocol is run by a transient certbot container
on qits-net, with hooks that fill and empty that slot. The PEMs land on the `qits-edge-letsencrypt`
volume under the two filenames the edge's keystore names, owned by uid 1001, and the run posts the
reload so they are live at once rather than within the hour.

Staging by default (`QITS_ACME_MODE`). Staging issues from an untrusted root, so a browser still
refuses the certificate — it proves the records, the challenge and the reload without spending
production's weekly *failure* limit, and the order most likely to fail is the first one against
records that have not propagated. **Flipping to production is one rerun with
`QITS_ACME_MODE=production` and no redeploy.** A rerun leaves a matching certificate alone, and never
replaces a production certificate with a staging one.

**One name: the apex.** The edge holds a single challenge at a time and refuses a second, while
certbot answers every name of a multi-name order before the CA validates any of them — so a SAN
certificate over the environment and app vhosts cannot be ordered through this endpoint. The apex is
the name that matters: it is the browser's door and the passkey's relying party. Covering the rest
wants a wildcard, a wildcard wants DNS-01, and DNS-01 wants a TXT record written at the dns provider
mid-order — so it needs a provider API this program has no hook for yet.

**A failed order warns and the boot goes on**, like the register token's mint. The edge keeps its
self-signed placeholder — 443 answers, browsers refuse it — and the closing report prints the retry
with the mode and the contact already filled in:

    quarkus tls lets-encrypt issue-certificate --staging \
      --domain=<domain> --email=<QITS_ACME_EMAIL> \
      --management-url=http://qits-platform-edge:9000

That command is the manual equivalent and the documented fallback; it runs from a container on
qits-net rather than from the host, because the management port is unpublished for the reason the
knob table gives. Renewal is a rerun, or `renew-certificate` with the same management URL — the PEMs
are replaced under the same two filenames and the TLS registry reloads them within the hour, so
neither is a redeploy.

## Two halves, one binary

The phases only ever run on `qits-net`, and you only ever type one command. Started with no
`QITS_IN_CONTAINER`, the binary is the **launcher**: it preflights what only the host can answer,
builds the payload image, runs itself inside it and returns whatever the run returned. Started with
the marker — which is what the launcher sets — it is the **payload**, and it runs the phases.

    docker daemon: reachable
    swarm: active
    wrapper: /home/dev/qits-qits  (detected from /home/dev/qits-qits/cli/qits-cli-bootstrap)
    payload built from: /home/dev/qits-qits/cli/qits-cli-bootstrap
    payload image: qits-bootstrap:23105f76262d (built already)
    $ docker run --rm --name qits-bootstrap-cli …

On a bare machine the third line reads `wrapper: /home/dev/qits-qits  (not on this machine yet)`
and the fourth names the clone of this CLI the run was started from — the wrapper's own submodule
is where the image is built from when there is a wrapper, and a checkout of this repository at or
above the working directory (or beside it) is what answers on a cold one.

**Two host settings this program warns about and cannot make.** Both come from the byte plane
moving behind the edge, and both are standing configuration of the machine rather than of a run:

    "insecure-registries": ["registry.<env>.localhost:8080", "mirror.<env>.localhost:8080"]
    sudo ip6tables -I INPUT -i lo -p tcp --dport 8080 -j REJECT --reject-with tcp-reset

The first goes in `/etc/docker/daemon.json`, and the daemon needs a restart to read it: both names
speak plain HTTP, and docker's built-in exemption is for the loopback ADDRESSES rather than names
that resolve to them — without the entries every push and pull fails with "server gave HTTP
response to HTTPS client". The payload's preflight asks the DAEMON what it allows (not the file,
which a daemon may not have re-read) and warns without stopping: the fix can be made while the run
goes on, and the closing report prints the line again.

The second is the launcher's own check, because only it is on the host. A `*.localhost` name
resolves to `::1` first, and swarm's ingress mesh is IPv4-only: the listener ACCEPTS the v6
connection and never serves it, so curl, docker, git, maven and npm all hang rather than fail over.
The reset makes each one fall back at once. A host-mode publish never showed this — docker-proxy
bound both families — so it arrives with ingress, and the rule does **not** survive a reboot.

**Swarm is reported here and repaired inside.** The line above is the state this run started from;
the payload's own preflight is what acts on it. An `inactive` daemon — one in nobody's swarm — is
made a single-node swarm with `docker swarm init`. Every other state is somebody else's answer and
stops the run with the state named: `pending` is a join in flight, `locked` needs its unlock key,
and `active` without the control plane is a WORKER that takes its orders from a manager elsewhere.
It matters because `qits-net` is an **attachable overlay** now: a swarm service cannot attach a
bridge, and a machine that is not a manager can create no overlay. On a host with several
interfaces `docker swarm init` refuses to choose an address to advertise, and the run says so with
the command to run by hand — `docker swarm init --advertise-addr <ip>`. It cannot pick one itself:
it is a container, so the routes it sees are docker's rather than the host's.

**The seed is a docker STACK**, deployed with
`docker stack deploy --resolve-image never -c <file> qits` — `never` because every image in it is a
local `qits/*:latest` tag no registry can resolve. Five things about the generated file follow from
that, each measured on this host rather than assumed:

- **No `name:` key and no `group_add:` key.** Either one refuses the whole file. The stack name is
  the deploy command's argument, and the two socket-holding seed services — the deployer and the
  container orchestrator — take the socket's group as their PRIMARY group (`user: "1001:<gid>"`,
  1001 being the images' own user). Their deployed successors get `--group-add` from the extras,
  which is where supplementary groups still work. Two is the SEED's count, not the platform's:
  qits-platform-system holds a socket too and has no seed block at all.
- **No `container_name:`.** A stack ignores it and names the container
  `qits_<service>.<slot>.<taskid>`. What answers a wire alias is the SERVICE, which resolves under
  `qits_<alias>` and under the bare `<alias>` both — so every address in the file is unchanged, and
  every check that asked `docker ps` for a name now asks `docker service ls`.
- **`restart: unless-stopped` is `deploy.restart_policy`**, and no service asks for an
  `update_config`: swarm's default update order is stop-first, which is the only order these
  services can take — each holds a volume or a host port a second task would collide on.
- **One published port in the whole file.** The edge's, and it is `mode: ingress` — the swarm holds
  the port, so an edge cutover is start-first and its successor pulls its own image through the
  predecessor, which a stop-first door could not do now that every pull goes through it.
  **The byte plane publishes nothing**: qits-artifacts, qits-platform-mirror and
  qits-githost are reached from the host at `<app>.<env>.localhost:<QITS_PORT>` through the edge,
  which authenticates EVERY method on all three names, reads included, since the flip landed on
  2026-08-14. docker does the Distribution spec's Bearer dance — a 401 naming a realm, HTTP Basic
  to that realm, `Authorization: Bearer` back — while maven, npm and git send Basic and nothing
  else, which the edge validates against the same issuer. A machine presents its own service
  client; a person's workstation credential is COMMISSIONED from the idp, and the closing report
  prints the one-liner that asks for one. **The platform's postgres publishes nothing either**: its one consumer was this
  CLI's cold-boot DDL, which dials the wire alias on 5432 like everything else. An operator with a
  `psql` goes in through `docker exec`. No publish binds loopback — neither mode has an ip field.
- **User sessions use canonical SSO, and one session covers every service host.** Both generated
  formats seed the edge's own IdP client (`<env>-qits-edge`) and enable the session gate. They
  configure one WebAuthn/login origin — `http://<env>.localhost:<port>` locally or
  `https://<domain>` in domain mode — plus the return-host allow-list: the environment authority
  and `*.` of it, exactly one extra label on the same port, with the apex leading it in domain
  mode. That wildcard is what carries a login onto `<app>.<env>.<authority>`, where each service
  serves its own UI. The cookie is scoped to the parent both sides share — the domain, or
  `<env>.localhost` locally, because bare `localhost` is a public suffix and a cookie scoped to it
  is dropped. The edge strips that named cookie before proxying to a machine's own routes. The boot
  mints the one-time token the first account registers with, and a passkey made on an older local
  platform — rp id `localhost` — asserts on neither door and has to be registered again.
- **A rerun deploys a SUBSET by leaving services out of the file**, because `docker stack deploy`
  takes no service list. Nothing is pruned — what the file omits is the deployer's — and a seed
  service whose application the deployer has taken over is removed outright: swarm restarts a task
  whose container was removed, so leaving it standing is a second holder of the alias for good.

**The image is addressed by its content** — a digest of `pom.xml`, `mvnw`, `.mvn/`, `src/main/` and
the Dockerfile — so a rerun with an unchanged checkout finds it built and starts in a second, and a
changed source is a different image that cannot be run stale by mistake. It is a two-stage build: a
GraalVM stage makes the native binary from the checkout, the runtime stage is plain alpine with the
docker CLI trio and git. Nothing is copied out of `target/`, so the image builds on a machine that
has never run maven — which is the machine this program is for. Its repository is `qits-bootstrap`,
deliberately outside `unwrap`'s `qits/` image sweep: it is the image a running `unwrap` is executing
from, and keeping it is what makes the next bootstrap start in seconds.

**Buildx is in the image, and preflight checks it is.** The builder is chosen by the CLIENT, and a
client without buildx does not fail — it falls back to the legacy builder, which reads build flags
differently and says nothing. That would make every seed image the product of a different builder
with no line anywhere recording it, so preflight asks `docker buildx version` and stops the run.

**The binary in it is fully static against musl**, and both halves of that are forced. A binary
linked against this host's glibc starts on no alpine image at all, and a *statically* linked glibc
binary resolves no names — glibc reaches the name services through `dlopen`, which a static binary
has no loader for, while every address this CLI dials is a wire alias on `qits-net`. musl compiles
its resolver in. The Dockerfile's header says the rest, including why the builder stage is GraalVM
CE and adds its own musl toolchain; `daemons/qits-ci-daemon/docker/Dockerfile.musl-builder` in the
wrapper is where that reasoning was first written down. This repository copies it rather than
sharing it, because the image has to build from a git URL on a machine that has seen no platform.

The run itself, flag by flag:

| | why |
| --- | --- |
| `--rm --name qits-bootstrap-cli` | one bootstrap at a time, nameable while it runs (`docker logs`), gone when it ends |
| `-it`, only with a terminal | the live display needs a tty; against a pipe it is docker's "the input device is not a TTY", and `UiFactory` falls back to plain lines by itself |
| `-v /var/run/docker.sock:…` | every phase is a docker command against the HOST's daemon |
| `--user <uid>:<gid>` and `--group-add <socket group>` | the clones and the generated files stay the user's own rather than arriving root-owned in their checkout, and git reads those checkouts without "dubious ownership". A plain uid is in no groups inside a container, so the socket's group has to be added |
| `-v <path>:<path>` per state directory | the wrapper (checkouts, `.qits-bootstrap.env`, the compose file), the working directory (`.env`), `QITS_SRC` (the clones) and the log. Mounted at their own path, so a `QITS_*` value means the same file on both sides — and mounted at all because the payload's build contexts and `docker cp` sources are read by the CLIENT, inside. **Only paths that already exist**: docker creates a missing bind source as a root-owned directory, and this run is a plain uid. A wrapper that is not there yet is therefore not mounted — it is cloned inside the container, into the working directory, which is |
| `-w <the launcher's directory>` | `.env` is the same file, and a relative `QITS_SRC` or `QITS_LOG_FILE` lands where it would have on the host |
| `-e QITS_…` by NAME | docker copies each value across, so the postgres passwords and client secrets never reach a command line, the screen or the log |
| `-e HOME=/tmp`, `-e TZ=…` | a uid with no `/etc/passwd` entry has no home, and a container with no `/etc/localtime` logs in UTC while the launcher logs local |
| `-p [<host>:]<port>:<port>` | the browser view, unless `QITS_WEB=0`. `QITS_WEB_HOST` answers who may reach it, and inside a container that boundary is the publish |
| no `--network` | `qits-net` may not exist yet on a cold machine. The run's second phase creates it — as an attachable overlay — and attaches this container itself |

**Exit codes are a contract and pass through untouched**: 2 a phase failed, 1 a deployment never
landed, 0 clean.

## The display

**The header** is where the boot is. One compact line per finished phase (`✓ 18/59 build the seed
image qits/ci:latest (11m02s)`), the running phase with a spinner and its elapsed time, and a count
of what is left with the next one named. Under the running phase, when it is waiting on something
remote, one line saying **what is being polled, what the last poll saw, how long it has been, and
when it gives up**:

    waiting for a deployment row for qits-stt at 3f8ca71 — ci run RUNNING, deployment no row yet
      · 6m20s elapsed, gives up after 1h00m (at 15:42:10)

That line is the point of this program. A bootstrap spends most of its life waiting, and a wait
you cannot see is indistinguishable from a hang.

**The body** is two columns.

    ──────────────────────────────────────────────┬─ platform events ────────────────
    [INFO] Building qits-platform-idp 2026.802…   │ 14:22:01 SCMRelease qits-stt 1.4.0
      ci| [INFO] performing analysis…             │ 14:22:07 BuildSuccessful qits-stt
      pd| Registered qits-observability in prod   │ 14:24:19 SoftwareRelease qits/qi…

**On the left**, the merged stdout and stderr of the step running right now — the maven, docker and
npm output, plus the `ci|` and `pd|` relays — as a rolling tail, repainted on a 250 ms timer.
Everything, tail included, also goes to `qits-bootstrap-cli.log`; memory stays bounded whatever a
build prints.

**On the right**, what the PLATFORM says it is doing, as qits-events published it: one line per
event, with the local clock, the event's name and the most telling thing its payload carries. It
turns a boot from a list of steps into cause and effect — the push, the run it started, the release
it published, the deployment that followed — announced by the services themselves rather than
inferred from whatever phase is waiting.

The feed is a poll every four seconds against `GET /events/api/events` through the edge, on its own
daemon thread, from the moment the boot starts. **qits-events is a service this boot deploys and
redeploys**, so for the first phases it does not exist and later it goes away and comes back; there
is no connect and no disconnect, only reads that answer and reads that do not, and nothing is said
either way. It starts at the HEAD of the log — the feed is what is happening now, not the history a
reseeded platform still holds — and it can neither fail nor slow a phase. `QITS_EVENTS_FEED=0`
turns it off.

The split is fixed for a given terminal width, so the divider does not move under the reader: the
event column is a third of the screen, kept between 36 and 60 characters, and each column's lines
are cut to their own width. **Under 100 columns there is no second column** — half of eighty reads
as neither — and the event pane is dropped rather than the layout corrupted.

**Not a terminal?** A pipe, a dumb `TERM` or `--no-tui` gets plain sequential lines with the same
phase markers and the same wait lines, throttled so an hour-long wait does not bury the log in its
own clock. A line stream has no second column, so the platform's events are interleaved where they
arrived under an `  ev| ` prefix, beside `  ci| ` and `  pd| `.

**A failure** stops the boot, paints the failing tail and the exit code, and says which phase it
was. Exit codes: `0` all good, `1` something warned (a deployment that never landed — the script's
`overall=1`), `2` a phase failed and stopped the run.

## The browser view

The same run, in a browser, through the bootstrap edge from its first phase on the network:

    http://<bootstrap ingress host>:<bootstrap ingress port>

It is printed on the first line of every run. The page is the terminal display in HTML — the phase
list with the finished ones dimmed, the running one with its elapsed time counting, the wait line,
the pending count — and under it the same two columns, the running step's output beside the
platform's events, each scrolling and sticky at the bottom unless you scroll up. Below 720px they
stack rather than shrink. It costs the boot nothing: the engine appends to a bounded state, and
each connection reads what is new four times a second.

It is not instead of the terminal display, it is beside it: **both** are fed every event, so a
bootstrap started over ssh can be watched from a browser, on a phone, or by someone else. `unwrap`
serves it too.

Three doors, no assets, nothing to fetch — the page is one self-contained file, which matters for a
program whose whole job is that the platform is not up yet:

| | |
| --- | --- |
| `GET /` | the page |
| `GET /events` | the run as it happens: one `snapshot` on connect, then `phase`, `status`, `line`, `ev` (a platform event) and `done` as server-sent events, with a comment every 15s so nothing in the middle calls it dead |
| `GET /state.json` | the same state in one answer, for `curl` |

`QITS_WEB_PORT` remains the supervisor's private port (default `8480`); it is not published.
`QITS_WEB=0` turns the view and therefore its bootstrap-edge route off.

The edge is retained when a worker fails or is retried. Its persisted, owner-readable capability is
reused by the next worker, and it is removed only after the real `qits-platform-edge` deployment
is healthy.

## What it does, in order

Built from configuration at startup, so the count in the header is real. A cold boot is 76 phases;
`QITS_SKIP_BUILD=1` drops the seed builds and puts two in their place — the skip gate and postgres —
for 49. `QITS_DOMAIN` adds two more, marked below.

| | phase |
| --- | --- |
| 1–7 | preflight (docker, buildx, the swarm — initialised when the daemon is in none — git, where the wrapper is, and which domain — if any — this platform serves); join `qits-net`, the attachable overlay every address after it needs; **clone the wrapper repository when this machine has none** — skipped whenever it has one; clone or refresh the 47 platform repositories **and stand each one at this boot's identity** — its newest release tag in a restore, but only where the output CARRIES a version (a deployable or a release publisher); the step-image sources and the SPA seed sources stay on main, as does everything under `--ship-mains` and anything never released; read `.qits-bootstrap.env` |
| 8 | seed the qits libraries the byte plane is built from — `qits-blobstore`, `qits-registries`, `qits-eventstream`, `qits-githost-events` (one module of qits-githost: the vocabulary its consumers need, not its service), `qits-auth-core` — into a temporary file repository served over HTTP, which is what breaks the first-boot cycle: three of the images below are built out of jars only this platform will ever publish. Its maven container, every publish container below and the ci-daemon build share one download cache — the `qits-maven-cache` volume, mounted at `/cache` and named by `-Dmaven.repo.local` — so Maven Central is read once per machine rather than once per phase. Each of those scripts starts by deleting `eu/wohlben/qits` out of it: third-party bytes are immutable at their version and ours are not, because seed builds reuse calvers across runs |
| 9–14 | seed images `qits/platform-edge`, `qits/platform-mirror`, `qits/artifacts`, `qits/githost`, `qits/oci-postgresql`, `qits/events` — the six that need nothing from a running platform |
| 15 | start postgres on a generated superuser password recorded before it first boots, and create over JDBC every database the seed stack needs: the deployer's own and its outbox's, qits-ci's own and its outbox's, qits-platform-idp's, qits-events', qits-artifacts', qits-platform-mirror's, qits-githost's own and its outbox's, qits-projects' THREE (its rows, the epics beside them and its outbox), qits-containers' own and its outbox's, and the edge's pair. Five are outboxes because the eventstream library keeps its own Flyway lineage and cannot share a database with its host. Everything else is provisioned by the deployer from the `resources:` line in each repository's deployments.yml |
| 16 | have qits-platform-mirror serving, because every publish below resolves its third-party half through it — Maven Central, npmjs — and a cache that is not up is not a slow publish but a failed one. It cannot pull through itself: its own image was built minutes ago with the mirror prefixes rewritten to the direct upstreams |
| 17 | have qits-artifacts serving, so there is somewhere to publish to (the seed one holds the registry port on 127.0.0.1 for the builds that run `--network host`) |
| 18–25 | publish `qits-blobstore`, `qits-registries`, `qits-eventstream`, `qits-githost-events`, `qits-auth-core`, the two `qits-containers` libraries (`core` and `client`, the modules its consumers pin — never its service, which is a native image nobody resolves), `@qits/ui-components`, `@qits/angular`. The git host's vocabulary is here because two consumers need it out of the store long before the git host's own deployment could publish anything: the `qits/ci` image three phases below, and the `qits/projects` image beside it — both are seed images, and both resolve it from this store |
| 26–30 | seed images `qits/ci`, `qits/deployments`, `qits/platform-idp`, `qits/containers`, `qits/projects` — the five built out of jars the publishes above put in the store. The last is the alias table's owner, in the seed since 2026-08-21 so that a repository has a public address before the first push |
| 31–35 | the five step images from qits-oci |
| 36 | the ci-daemon musl static binary, and its digest |
| 37 | resolve the idp's client secrets (given, kept, generated) and record the run state |
| 38–39 | generate the seed stack file; write the deployer's per-application extras onto its config volume. That file holds EXTRAS and nothing else — what the deployer configures itself with is `QITS_PLATFORM_DEPLOYMENTS_*` env, spelled on the seed service and on the deployer's own extras block alike |
| — | **with `QITS_DOMAIN` only**: write a self-signed placeholder certificate onto the `qits-edge-letsencrypt` volume, unless one is already there. It is before the stack starts because the edge's keystore names those files and a keystore whose files are missing fails startup. The real one replaces it two phases later, and this is what the edge keeps if that order does not go through |
| 40–41 | `docker stack deploy` the seed (only what the deployer does not already manage — the rest is left out of the FILE, since a stack deploy takes no service list, and any seed SERVICE of a deployer-managed application is removed); wait for the idp, the edge, the gateway, the store, the mirror, the git host, the alias table (qits-projects, whose readiness is the three databases it refuses to boot without), ci, the deployer, the bus and the container orchestrator — all on qits-net. The orchestrator is polled at its own alias: it has no gateway route and must not have one, because every caller is a machine and a route would put a socket-holding service behind the platform's public door |
| 42 | mint the ONE-TIME token the first account registers with (`POST /idp/api/register-tokens`, as the edge's own static client) and record it in `.qits-bootstrap.env`. Once per installation: a rerun that finds `IDP_REGISTER_TOKEN` there mints nothing, because every call makes another key to an admin account. A refusal WARNS and the boot goes on — nothing this platform runs waits on a person registering |
| — | **with `QITS_DOMAIN` only**: order the edge's Let's Encrypt certificate for the apex, in a transient certbot container on qits-net whose hooks fill and empty the edge's one challenge slot over `http://qits-platform-edge:9000/q/lets-encrypt/challenge`. The PEMs are copied onto `qits-edge-letsencrypt` as uid 1001 and `POST .../certs` makes them live at once. Here because the edge has to be holding port 80, and the name has to resolve to this host already — which is the dns provider's job, before the run. Skipped when the volume already holds a matching certificate that is not near expiry, and **a production certificate is never replaced by a staging one**. A failure WARNS and the boot goes on — the records may simply not have propagated |
| 43 | publish the ci-daemon binary, version-addressed by its digest |
| 44 | the `qits` project. qits-projects' own startup self-seed creates it — nothing else may, or one platform holds two projects of one name — and this phase RELEASES that self-seed (`QITS_STARTUP_SEED_ENABLED`, held on the stack because creating the project's wrapper origin needs a bearer the idp mints) and then waits for the project to appear |
| 45–47 | create the 47 repositories on qits-githost **under a minted UUID** and register each (uuid, name) pair with qits-projects before the next one is touched, recording the pairing in `.qits-bootstrap.env` as it goes; push the seeded repositories to it; pre-seed the seeded histories with `-o qits.no-ci`. The seeded push is here rather than at the end because every deployable's gitlinks have to be advertised before ci clones it. The lifecycle PUT is the only thing this run addresses by id — **every push of the boot is `/git/<projectId>/<repo>`** — see [Two coordinates, one seam](#two-coordinates-one-seam) |
| 48–54 | replay each publisher the platform pins by **pushing its release tag**, and wait for the run the tag starts. The release recipes select on `SCMPublishTag`, so the push is the whole trigger — nothing is announced by hand, and in particular no `SCMRelease`: that word means "a version is NEW", and saying it here woke the release train against a platform whose qits-workspaces is still fifteen phases away. Four of the seven are the Maven and npm packages the wrapper's builds install; three are docker images — `qits/workspace-base`, then `qits/workspace` and `qits/projects-daemon` + `qits/project-agent`. **The base goes first and that order is load-bearing**: both daemon builds pull it at a pinned version, and the base's own replay is what puts it in the registry. A publisher with no release tag reachable from main STOPS the boot, which is right: a pin nobody has minted has nothing to dangle. A tag the git host already has moves no ref, so it announces nothing and the phase is SKIPPED — the registry holds that version from the boot that first pushed it |
| 55 | reconcile the `prod` environment in qits-deployments by PATCH — never delete, which would tear down the platform |
| 56–74 | one phase per deployable: push `main` quietly, push the newest release tag, and move `environment/<name>` **to that tag's commit** — the boot RESTORES, so a main that is ahead of the release deploys nothing. `--ship-mains` points the deploy ref at main's head instead, which is what the boot always did and what shipped an unreleased stack by accident on 2026-08-08. A deployable with no release tag falls back to main's head and warns. Then wait for the CI run and the deployment. qits-oci-postgresql is second: it is the deployer's own database, so its cutover must never be queued beside a consumer's. qits-containers is immediately before qits-ci, because ci runs every pipeline step as a container it asks that service for. qits-platform-edge is second to last: it is the host's one door, and every other service is behind it — its publish is `mode: ingress`, so the swarm holds the port and the successor pulls its own image through the predecessor rather than needing the door it is replacing. qits-configuration is FOURTH, after postgres and the idp, because the two phases below are what the rest of the train deploys through. qits-platform-orchestrator is LATE, after every peer its technical processes call — the store, the container orchestrator, ci and the deployer — because it holds a scheduler and a run that starts inside a peer's cutover fails against a service being replaced. qits-platform-maintenance follows it for the same reason: it reads qits-projects, qits-githost, qits-artifacts and the mirror and asks ci for a bump, so every peer it reads is above it, and it holds a scheduler of its own |
| 60–61 | **deployment configuration becomes platform state.** Import the extras this boot rendered into qits-configuration — the whole properties file, unchanged, idempotent — then point the RUNNING deployer at it with `QITS_PLATFORM_DEPLOYMENTS_EXTRAS_URL` and the `configuration` oidc client. From here the deployer reads each application's configuration from the service and REFUSES a deployment it cannot read, so both sides of the order are load-bearing: flipped before the import, it would refuse every deployment left in the train; not flipped before qits-deployments deploys itself, the successor would come up holding the url over a service nobody filled. The file on the config volume is untouched, and from the flip on it is UNREAD: the service is the sole source, or a key deleted from the store would come back out of a file nobody emptied. It stays what the cold boot deploys from and what the import is rendered from; unsetting the url is the rollback, and by then the file may be stale |
| 75 | the closing report |
| 76 | **reclaim the builder.** `docker buildx rm qits-bootstrap-builder-v4` — the container and its state volume, 13.7 GB measured on wohlben.eu — then `qits-maven-seed` and the `qits-maven-cache` download cache when nothing holds them. The builder is BOOTSTRAP-TIME ONLY: every build after this run goes through qits-containers to the host's default builder, and the next bootstrap creates a new one. Last because nothing above it may build after it, and because the phase above only BUILDS the closing account — it is printed once every phase has run. `QITS_KEEP_BUILDER=1` skips it: a re-bootstrap without the warm cache rebuilds the seed images cold and re-fetches Maven Central, ten to twenty minutes more, which is the dev loop's price and not a server's. It removes no IMAGES — a dangling image carries no record of the tag it held, so nothing here can tell one of ours from one of somebody else's; attributed image deletion is qits-containers' gc endpoint, driven by qits-platform-orchestrator with the pin set in hand |

Five things every deploy phase does that are easy to miss: it pushes `main` quietly
(`-o qits.no-ci`) so a second cold native build is not queued for the same sha; it pushes the
release tag quietly too, which starts nothing — a deployable's release recipe fires on `SCMRelease`,
not on a tag — and stamps the commit the deploy ref is about to name; it FORCES the deploy-ref push
when restoring, because a machine whose last boot shipped mains carries that ref ahead of the
release and a rewind is a non-fast-forward (`--ship-mains` keeps the plain push, so the dev loop
cannot rewind anything); it replays the `build-succeeded` event once when a run is green but no
deployment row appeared after a minute — the announcement is fire-and-forget and has been lost for
real; and when the push is up to date and the only run at that sha is RED, it says so and waits,
because a red run means there is no image and replaying the build event would only buy an
`IMAGE_MISSING` row.

While a deploy phase waits it also talks: the CI run's own output is relayed under `ci|` (a poll of
the run, not a feed — qits-ci serves none), and the deployer's account of this repository —
registered, deployed, failed — is relayed from its container log under `pd|`. The push before them
prints git's own output inline. Silence during a wait is therefore always the platform being
silent, never the display.

Phase 38 restarts the seed deployer when the extras it just wrote differ from what the volume
held. The deployer reads that file once, at its own boot, so a rerun that changes it changes
nothing for a container that is already running.

Phases 6 and 17 are the two that bind the registry port, and both ask first whether qits-artifacts
is already serving — by GET on the artifacts API's own health at the store's wire alias, which the
temporary nginx does not answer to. On a platform whose store is deployed, the answer is yes: that
container reads the same database and answers the same API, so phase 6 skips and phase 17 waits for
it instead of starting a seed beside it. The port collision that used to make the check mandatory
is gone with the store's publish — a deployed store binds nothing on the host now — so what the two
phases prevent is a duplicate rather than a `port is already allocated`.

The temporary registry of phase 6 has **two consumers and two addresses**, which is the shape every
container the bootstrap starts for the daemon's benefit has: the CLI dials it on `qits-net` by its
container name, and the seed image builds resolve Maven through `localhost:<registry port>` on the
host, because a build runs with `--network host` against the host's daemon.

**Every image this run builds is told that address, and none of them may infer it.** The
repositories declare `ARG QITS_MAVEN_REPOSITORY_URL` and their maven settings read it, and the
committed default names the edge vhost — a platform that is RUNNING, which during a seed build it is
not. So `--build-arg QITS_MAVEN_REPOSITORY_URL=http://localhost:<registry port>/artifacts/maven/maven`
rides every build this program starts: the seed images, the step images and the ci-daemon's musl
builder. It sits on the Docker facade rather than at each call site, because the answer is the same
for every image and a build added later would inherit nothing. The default happened to be this same
url until the vhost sweep, which is how a boot rode it for months and then died at
qits-platform-mirror with a connection refused.

## Two coordinates, one seam

A repository on this platform has **two** identifiers and only one of them is public.

- The **storage id** is qits-githost's key, and it is a minted **UUID** — opaque, with nothing in it
  that anyone above the seam says. `/git/<storage id>` is the address of the store, and the deployed
  git host serves that scheme to qits-projects' own service client and to nobody else —
  `qits.githost.storage-client` names the client and the guard demands its self-role
  (`clients/<id>`, which qits-idp stamps into that client's bearers and no other's). A caller
  holding a storage url is a defect, not a shortcut.
- The **public identity** is `(projectId, repoName)`. `/git/<projectId>/<repo>.git` is the one clone
  and push url there is — for CI, the daemons, a deploy push and a person alike — and qits-projects'
  alias table is its only authority. The git host resolves it per request through
  `qits.projects.name-resolver-url` and remembers nothing. The first segment may be the project's
  **slug**, which is the public spelling the closing report prints (`/git/qits/qits-ci.git`); the id
  is matched first and always works, so machine paths keep using it.

**This program creates the bares, so it mints the ids — and it registers each pair before it pushes
to it.** qits-projects is a seed service, so the alias table answers minutes before the first push
rather than fourteen deploy phases later. Three consequences, and each is a phase boundary:

1. **`qits-project` comes before the first bare.** It waits for the `qits` project qits-projects'
   own startup self-seed creates — nothing else may create it, or one platform ends up with two
   projects of one name — and it is the phase that releases that self-seed (below).
2. **`git-repos` does three acts per repository and does them in one order.** Mint the UUID, `PUT
   /git/<uuid>`, then register the pair through
   `POST /projects/api/projects/{projectId}/repositories/adopt` — which is idempotent on the storage
   id, so a rerun costs one request per repository. The pairing is written to `.qits-bootstrap.env`
   *before* the bare is made and read back by `recorded-state`: a UUID has nothing to re-derive it
   from, so a run that lost it would address bares it never created.
3. **Every push of the run is name-addressed.** The lifecycle PUT is the only thing this program
   ever addresses by id. Name-addressed pushes are what put `projectId` and `repoName` on the push's
   event, which is what gives every later build a `QITS_CI_PROJECT_ID` and a sibling submodule url
   that resolves — and what lets qits-ci's trigger selection match the release replays by name.

**The guard is staged, and the staging is the placement of one key.** The seed git host does not
carry `qits.githost.storage-client` at all — it cannot, because this program creates every
repository on it over the storage scheme, and the credential those PUTs present is the BOOTSTRAP's
while the guard demands one other client's self-role. Seeding qits-projects did not change that and
could not. The key is spelled only in the deployment extras, so the platform this run leaves behind
is guarded and the run itself is not locked out of its own first phase. A rerun meets the guard at
`git-repos` and asks qits-projects what each name resolves to before it asks the store for anything.

**The seed qits-projects is started with two switches off, and each one is a boot that fails
without it.**

- `QITS_STARTUP_SEED_ENABLED=false`, turned on by the `qits-project` phase. Creating the project
  also creates the wrapper's origin on the git host, which needs a bearer the idp mints — and the
  idp is a seed service starting in the same second. A self-seed that fired first would fail, roll
  its own transaction back (project row included) and not try again until the container restarted.
  The phase turns it on once `seed-health` has watched the idp answer.
- `QITS_STARTUP_SEED_RECONCILE_REPOSITORIES=false`, for the whole seed window. The self-seed's
  second half reconciles the wrapper's `.gitmodules`: an entry it holds no row for is looked up on
  the git host **by name**, and under this ruling no storage id is a name — so on a fresh platform
  every entry misses and the reconcile takes its remaining arm, which is to mirror all 47
  repositories in from the org. That would run minutes before this program has created a single
  bare, and the platform's history would come from the forge rather than from the checkouts the boot
  was told to build. The DEPLOYED container spells neither key: its reconcile is the platform's
  first, and by then every entry matches a row by alias.

**This second key is a qits-projects configuration this repository depends on.** It is the one
thing WP-L could not arrange from this side: without it honoured there, a seed boot mirrors the
estate in from GitHub before the bootstrap has created anything.

## Two planes, one branch

`main` is the integration trunk of every repository and deploys nothing. What deploys is
`environment/<name>` — for BOTH planes, because both ask a green build the same question: does an
environment listen to this ref. `platform/main` is retired.

A bootstrap points that ref at the commit of each deployable's **newest release tag**, so a boot is
a RESTORE of the last released platform rather than a shipment of whatever your checkouts hold.
`--ship-mains` points it at main's head instead; that is the dev loop, and it now has to be asked
for.

**The seed is built from that same commit**, and it has to be. A seed container is scaffolding, but
it is scaffolding that touches the platform's data: a seed qits-ci built from main applies main's
Flyway migrations, and the released successor the train deploys minutes later refuses to start
against a schema ahead of it — `Detected applied migration not resolved locally`, measured on the
first restore-default boot, where it crash-looped until it was unpicked by hand. So the `sources`
phase stands the checkout at its release tag and every later phase reads that tree: one commit per
repository per boot, seed and successor alike.

**Only where the output carries a version, though**, and that scope is the other half of the rule. A
deployable and a release publisher each have a last release the platform can state and consumers
pin. Everything else that gets seeded — qits-oci's step images, the SPA sources behind the
placeholder bundles, the ci-daemon binary published by digest — is rebuilt from source every boot
and pinned by nobody, so its tags go stale without anyone noticing. Measured the next run: qits-oci's
newest tag was three days behind main and predated the passwd-backed `build` user its step images
grew when steps stopped running as root, so the seed maven-base built from it could not launch a
step declaring `user: build` — "unable to find user build: no matching entries in passwd file".
Those repositories stay on main in both modes.

| plane | applications | wire alias | container |
| --- | --- | --- | --- |
| environment | ci, projects, observability, workspaces, stt, configuration, artifacts, githost, docs, containers, oci-postgresql | `<env>-qits-<app>` | `qits-pd-<env>-qits-<app>-<id8>` |
| platform | platform-edge, platform-idp, platform-mirror, deployments, events, platform-orchestrator, platform-maintenance, platform-system | `qits-<app>` | `qits-pd-qits-<app>-<id8>` |

**qits-deployments and qits-events moved to the platform plane on 2026-08-17**, and their aliases
are the bare repository names rather than `qits-platform-*` ones: the repositories are renamed after
the local proof, not before it. The deployer moved because an environment is becoming a
cross-environment entity — one tier gating another — which cannot live inside one tier's deployer;
the bus moved because which broker a service dials WAS the scope, so a platform deployer on a
per-tier bus could announce to one tier only.

**qits-platform-orchestrator joined the plane on 2026-08-21.** It runs the platform's technical
processes — multi-step jobs that only send requests to peers, the first being the unified
deletion run — and what such a run reclaims is one MACHINE's image store, volumes and build
cache, however many tiers share it. Two per-tier copies would be two schedulers pruning the same
docker daemon on their own clocks, each blind to what the other pinned. It holds a named idp
client per peer (qits-artifacts, qits-containers, qits-ci, the deployer), because a bearer minted
for one audience is refused by the rest.

**qits-platform-maintenance joined the plane on 2026-08-22.** It holds the dependency inventory of
every repository in the catalog — what each manifest pins, what the latest version is, which
upgrades are pending — and asks qits-ci to apply them on a maintenance branch. What it inventories
is one catalog's, so a per-tier copy would scan the same repositories twice and push the same
branch on two clocks; which CI applies a bump IS a tier's, and that is one configured address
(`QITS_MAINTENANCE_TARGETS_CI_URL`) rather than a second instance. It holds a named idp client per
GUARDED peer — qits-projects, qits-githost, qits-ci — and the wildcard `project=*` claim, because
ci's trigger route demands every project even for a bump that names one repository. The registries
it reads versions from are unguarded on `qits-net`, so it holds no client for them. It is **not** a
seed service: nothing calls it, so nothing waits on it.

**qits-platform-system joined the plane on 2026-08-23, and it is the THIRD holder of the host's
docker socket** — after qits-containers and the deployer. It is the base system panels: the host,
the swarm, this node's containers, a live `glances` terminal and a shell into any container, so
operators stop falling back to ssh. What it shows is a MACHINE, which has no per-tier half, and two
copies would be two boot sweeps deleting each other's terminal containers. The socket is granted in
its extras block, deliberately and on the record: the alternative was an exec endpoint on
qits-containers' machine API, which would put a shell into any container behind a credential every
service on `qits-net` can mint. It stays behind `qits:admin` in one admin console instead, and a
console that owns the PTYs has to hold the socket itself. Its idp client is a DOCKER CREDENTIAL
first — the glances image is pulled through the platform mirror, so its `config.json` is the one the
bootstrap writes with two hosts rather than one. It calls no peer, holds no store, and is **not** a
seed service.

A **platform** service is one instance for the whole platform, joined to every environment's
networks, belonging to no tier — so it appears in no per-environment deployment listing, and the
CLI watches docker for it instead: a container under the prefix above running the image tagged with
the sha it pushed. An environment service is watched through its deployment row.

The **wire alias** is the address peers dial and the name a cutover finds its predecessor by. The
seed SERVICES are named after it — a stack ignores `container_name`, and a service resolves under
`qits_<alias>` and under the bare alias both — which is what makes the generated stack file's own
addresses right from the first second rather than from the first cutover.

**qits-platform-edge binds the host's only HTTP port** and dispatches each request from its
projected deployment endpoints. The three byte-plane names route straight to their services,
because a docker client and a git client own their own roots (`/v2`, `/git`) and cannot be given a
path prefix. An authoritative route miss is a 404; a projection that has not caught up is a 503.

**qits-deployments owns both halves**: the topology (environments, services, links) and the
execution (deployment rows, the health-gated cutover, the rollback pins). It is the merge-back of
qits-cd and qits-serviceregistry, and both are superseded — the bootstrap still creates their
repositories and pushes their histories, and deploys neither.

## How it differs from the shell port

`qits-local-up.sh` was a POSIX shell script run as a throwaway `docker:cli` container with the
docker socket and the wrapper mounted. It is in the wrapper repository's git history.

Same phases, same order, same idempotence, and the same network position: this CLI is a container
on `qits-net` too, so it reaches the platform at the same wire aliases the script did. One
deliberate difference is left, and it is the one the socket coming back did not undo:

- **No `/out` mount and no worktree gitdir contortions.** The wrapper's checkouts are read where
  they are, and the stack file and `.qits-bootstrap.env` are written back beside them. That holds
  because every path this program puts on a docker command line is read by the CLIENT — a build
  context and a `-f -` Dockerfile are packed and sent, `docker cp` reads the source here, and
  `docker stack deploy -c` is parsed here.

Two things about the addressing are worth stating plainly:

- **ci and the deployer are dialled at the EDGE**, `qits-platform-edge:8080`, not at their own
  aliases — the edge and the gateway's route table are the path every other client takes, and a
  bootstrap that stopped exercising it would stop noticing when it breaks. Calls made while the
  edge, the gateway or artifacts is mid-cutover are expected and retried rather than fatal.
- **qits-platform-idp publishes no host port on purpose** and sits on no gateway route:
  `/idp/token` behind an unauthenticated gateway is a token vending machine. It used to be reached
  by a throwaway `curlimages/curl` container per call, borrowing the network position this CLI now
  holds. The platform's exposure is unchanged, which is the property worth keeping.

Four additions:

- **The container is the program's own doing.** The script was started as a `docker:cli` container
  by the shim around it; this binary is started on the host and puts itself inside an image it
  builds. Nothing outside has to know the mounts, the socket group or the marker, so there is one
  place where the run's shape is decided and it is under test.
- A platform service is only counted live when its container is not `unhealthy`. The shell port's
  docker-based check once counted a `running/unhealthy` idp as live, and the rollback that followed
  looked like a success.
- **A source that cannot be trusted stops the boot.** The script, and this CLI until 2026-08-08,
  answered a wrapper path that was not a checkout by cloning from GitHub instead, and a refresh
  that would not fast-forward by building what was already on disk. Both produced a working-looking
  run of the wrong commit. Now: an ABSENT directory still falls back to the org URL — not every
  repository in the model has to be a submodule of this wrapper — and so does an EMPTY one, which
  is what git leaves at a gitlink whose submodule was never checked out. A directory that holds
  something and is not a checkout, or a refresh that fails, ends the run and names the fix.
- **A machine with nothing on it is enough.** The script had to be run from a wrapper checkout,
  and so did this CLI until now: no checkout was "no wrapper directory at …" and a stopped boot.
  A cold machine cannot have one — the git host that would serve it is what the bootstrap creates —
  so the `wrapper` phase clones qits-qits from the org into the working directory and the run
  carries on with it. Without its submodules, on purpose: `sources` clones each repository from the
  org anyway. `curl … | bash` now ends with a platform and with a checkout to rerun from.

## Status

**The only bring-up path there is.** The shell port is retired; `qits-local-up.sh` is the shim in
front of this CLI.

**The 2026-08-08 rerun fixes are NOT yet proven by a real bootstrap.** The first prod bootstrap ran
that day and found three places where the CLI warned and moved on and a person finished the job by
hand with raw API calls: a stale RED run was read as an outcome instead of a reason to re-announce
the push, the seed deployer kept the configuration it had cached at its boot and deployed a qits-ci
without them, and nothing spelled `QITS_OBSERVABILITY_URL`, so every exporter dialled a name the
rename had killed. The validation rerun of those three then stopped at phase 8 with `port is
already allocated`: the seed store phases predate the skip-when-deployed rule the seed stack
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
