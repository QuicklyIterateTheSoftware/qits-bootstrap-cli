# Demoting the extras file on a platform that is already flipped

The hand procedure for wohlben.eu, or any platform that ran the flip before this change existed.
Nothing here is automated: a fresh bootstrap gets all of it from the generated stack and extras, and
this file is for the machine that is already running.

Read it in order. Every step is safe to repeat.

## What changes, and why the order matters

Two things landed together:

1. **The deployer's extras source is now SOLE.** With `QITS_PLATFORM_DEPLOYMENTS_EXTRAS_URL` set,
   `config/application.properties` on `qits-deployments-config` is no longer read for extras at all.
   It used to be layered underneath, which is why a key deleted from qits-configuration kept coming
   back.
2. **A service update removes env keys nothing states.** A variable on a live service that the
   application's extras no longer name is `--env-rm`'d by its next deployment. Never this
   component's own four (`QITS_ENVIRONMENT`, `QITS_APPLICATION`, `OTEL_RESOURCE_ATTRIBUTES` and its
   `QUARKUS_` twin) and never anything under `QITS_RESOURCE_`.

The second one is what makes the order below load-bearing. **Anything the running deployer holds as
env that its own extras do not state is removed at its next self-deploy** — its git-host url
included, and a deployer with no git-host url deploys nothing and says only "timeout".

So: put the value in the STORE first, then on the running service, then delete from the file, and
only then deploy the deployer.

## Step 1 — put the deployer's own settings in qits-configuration

`qits-deployments` is an application in the store like any other, and after this its entries have to
carry every `QITS_PLATFORM_DEPLOYMENTS_*` variable the deployer needs. One is new:

| was, in `config/application.properties` | becomes, as an entry on `qits-deployments` |
| --- | --- |
| `qits.platform.deployments.git-host-url=http://dev-qits-githost:8080` | `env.QITS_PLATFORM_DEPLOYMENTS_GIT_HOST_URL=http://dev-qits-githost:8080` |

Write it, from any host that reaches the platform's network:

```sh
curl -fsS -X PUT \
  -H 'X-Qits-User: operator' -H 'X-Qits-Roles: qits:admin' \
  -H 'Content-Type: text/plain' \
  --data-binary 'http://dev-qits-githost:8080' \
  http://dev-qits-configuration:8080/configuration/api/applications/qits-deployments/entries/env.QITS_PLATFORM_DEPLOYMENTS_GIT_HOST_URL
```

Then **read the resolved document back and check it against the running deployer's environment**.
This is the step that prevents the outage, so do not skip it:

```sh
# what the store will serve the deployer's own next deployment
curl -fsS -H 'X-Qits-User: operator' -H 'X-Qits-Roles: qits:admin' \
  http://dev-qits-configuration:8080/configuration/api/applications/qits-deployments/resolved

# what the live service carries today
docker service inspect --format \
  '{{range .Spec.TaskTemplate.ContainerSpec.Env}}{{println .}}{{end}}' dev-qits-deployments
```

Every key in the second list must appear in the first, EXCEPT:

- `QITS_ENVIRONMENT`, `QITS_APPLICATION`, `OTEL_RESOURCE_ATTRIBUTES`,
  `QUARKUS_OTEL_RESOURCE_ATTRIBUTES` — this component writes them itself, every deployment;
- anything starting with `QITS_RESOURCE_` — resource provisioning injects those.

Anything else that is only on the live service is a value that will be **removed** at the next
deployment. Add it to the store, or accept losing it, deliberately. The ones to look hardest at are
`QITS_PLATFORM_DEPLOYMENTS_EXTRAS_URL` and the five `QUARKUS_OIDC_CLIENT_CONFIGURATION_*` keys: the
flip put them on the live service AND in the file, so confirm the STORE has them too. A deployer that
loses its extras-url comes back reading the file it was demoted from, on a green deployment, with
nothing said.

Do the same comparison for every other application before its next deployment — a hand
`service update --env-add` applied over the last weeks is exactly what this change reverts:

```sh
for s in $(docker service ls --format '{{.Name}}'); do
  echo "== $s"
  docker service inspect --format \
    '{{range .Spec.TaskTemplate.ContainerSpec.Env}}{{println .}}{{end}}' "$s"
done
```

## Step 2 — add the env to the RUNNING seed deployer

The store answers the deployer's next SUCCESSOR. The instance running now was started by the seed
stack and reads its own env, so it needs the same value live:

```sh
docker service update \
  --env-add QITS_PLATFORM_DEPLOYMENTS_GIT_HOST_URL=http://dev-qits-githost:8080 \
  qits_dev-qits-deployments      # or dev-qits-deployments, whichever `docker service ls` shows
```

This restarts the deployer's task — a few seconds during which no build-succeeded is acted on. The
bus redelivers, so nothing is lost; do it when no deployment is in flight anyway.

Wait for it, then confirm the value took:

```sh
docker service ps --filter desired-state=running qits_dev-qits-deployments
docker service inspect --format \
  '{{range .Spec.TaskTemplate.ContainerSpec.Env}}{{println .}}{{end}}' qits_dev-qits-deployments \
  | grep QITS_PLATFORM_DEPLOYMENTS_
```

`QITS_PLATFORM_DEPLOYMENTS_GIT_HOST_URL`, `QITS_PLATFORM_DEPLOYMENTS_POSTGRES_ADMIN_PASSWORD`,
`QITS_PLATFORM_DEPLOYMENTS_REGISTRY_AUTH` and `QITS_PLATFORM_DEPLOYMENTS_EXTRAS_URL` should all be
there.

## Step 3 — prove the deployer still deploys, on something cheap

Do not make the deployer's own self-deploy the first proof of the new configuration. Replay any
small application's deployment and watch it go ACTIVE:

```sh
docker service logs --tail 50 qits_dev-qits-deployments
```

A `git-host-url` that did not take shows as a spec read that times out, on every deployment.

## Step 4 — delete the demoted line from the file

Only now, and only this one line:

```sh
docker run --rm -v qits-deployments-config:/cfg alpine/git sh -c \
  "sed -i '/^qits\\.platform\\.deployments\\.git-host-url=/d' /cfg/application.properties"
```

What may be deleted from `/cfg/application.properties`:

- **every line that is not `qits.platform.deployments.extras.*` or a comment** — today that is the
  one `git-host-url` line and nothing else. Check before deleting:

  ```sh
  docker run --rm -v qits-deployments-config:/cfg alpine/git sh -c \
    "grep -v '^#' /cfg/application.properties | grep -v '^$' \
     | grep -v '^qits\\.platform\\.deployments\\.extras\\.'"
  ```

  An empty answer means the file is extras-only and there is nothing left to move.

What must **NOT** be deleted:

- **the extras lines themselves.** They are the cold-boot source: a deployer that starts before
  qits-configuration is up has nothing else, and a re-bootstrap of this machine deploys the whole
  train ahead of the flip from them. They are unread while the flip holds, and that is not the same
  as unnecessary.
- **`/cfg/config.json`.** A separate file on the same volume — the docker credential
  `DOCKER_CONFIG` names. Nothing in this rollout touches it, and without it every pull is refused at
  the edge.
- **the volume.** It carries both files and is mounted by the deployer's own extras.

## Step 5 — release and deploy the two repositories, deployer last

qits-deployments and qits-cli-bootstrap both changed. Cut releases through the release door
(a direct main push deploys code with a stale version identity), then deploy in this order:

1. **qits-deployments** — the demotion is in the deployer itself. Its self-deploy is the first
   deployment that runs the new update argv, so step 1's comparison has to be done before it.
2. **qits-cli-bootstrap** — nothing is deployed; it takes effect at the next bootstrap or the next
   `pd-extras` write. A rerun of the bootstrap rewrites the file without the demoted line and
   restates the env on the seed service, which makes steps 1, 2 and 4 idempotent rather than
   necessary a second time.

After the deployer's own deployment, read its environment back one last time and confirm nothing was
removed that it needed:

```sh
docker service inspect --format \
  '{{range .Spec.TaskTemplate.ContainerSpec.Env}}{{println .}}{{end}}' dev-qits-deployments
```

## Rollback

`docker service update --env-rm QITS_PLATFORM_DEPLOYMENTS_EXTRAS_URL <deployer service>` returns the
deployer to the file — and the file is only as current as the last `pd-extras` write. If the store
has moved since, re-render it with a bootstrap rerun, or export what the store holds, before relying
on it. Rolling back also needs the deployer image rolled back past this change, since the demotion is
in the code as well as in the configuration.
