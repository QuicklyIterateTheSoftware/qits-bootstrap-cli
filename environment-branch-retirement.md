# Retiring `environment/*` on a platform that is already running

The hand procedure for wohlben.eu, or any platform bootstrapped before deployment became
release-driven. Nothing here is automated: a fresh bootstrap seeds no `environment/*` ref and
generates the release-request wiring, and this file is for the machine that is already running.

Read it in order. Every step is safe to repeat.

## What changed, and why the order matters

A deployment used to be entered by a PUSH: a green build of `environment/<name>` announced
`BuildSuccessful` to qits-deployments, which matched the ref against an environment's `branch`
column and deployed `qits/<app>:<sha>`. That whole path is gone.

A deployment is entered by a RELEASE now:

1. qits-projects folds the release request's sources onto `release/<id>`, stamps a CalVer, commits
   the manifest bump, creates the tag and deletes the backing branch — all through qits-githost's
   git primitives — and publishes `SCMRelease`.
2. The repository's own `.config/qits/ci-event-release.yml` selects that event, builds the tag and
   publishes `qits/<app>:<version>`. qits-ci announces one `SoftwareRelease` per declared artifact.
3. qits-deployments enters a deployment request from the `docker` one and pulls
   `qits/<app>:<version>` into the designated platform environment (`pd_environment.platform`).
4. `main` is finalized AFTER the deployment lands, not before it.

So the order below is: **wire the executor first, prove a release deploys, and only then delete the
refs.** Deleting first costs nothing on a working platform and everything on one whose release flow
has not been proved — the old path would be gone with no new one serving.

`pd_environment.branch` needs no step of its own: the deployer's own migration drops the column, and
its create and update requests no longer carry the field. An older sender's `branch` is ignored
rather than refused, which is the one thing worth knowing about it — sending one is silent.

**The service names below are this platform's.** Read `dev-qits-*` for whatever
`docker service ls` shows; a platform bootstrapped under another `--platform-env` carries that name
in every environment service's alias.

## Step 1 — give qits-projects the two addresses and the ci client

The release executor refuses to release at all while it cannot name a git host, and both keys ship
UNSET: a tier that is not meant to release never learns one. Add them as entries on the
`qits-projects` application, exactly as `demotion-rollout.md` adds the deployer's own:

| key | value on this platform |
| --- | --- |
| `env.QITS_PROJECTS_RELEASE_REQUESTS_GITHOST_URL` | `http://dev-qits-githost:8080` |
| `env.QITS_PROJECTS_RELEASE_REQUESTS_CI_URL` | `http://dev-qits-ci:8080` |
| `env.QUARKUS_OIDC_CLIENT_CI_CLIENT_ENABLED` | `true` |
| `env.QUARKUS_OIDC_CLIENT_CI_AUTH_SERVER_URL` | `http://qits-platform-idp:8080/idp` |
| `env.QUARKUS_OIDC_CLIENT_CI_CLIENT_ID` | `dev-qits-projects` |
| `env.QUARKUS_OIDC_CLIENT_CI_GRANT_OPTIONS_CLIENT_AUDIENCE` | `dev-qits-ci` |
| `env.QUARKUS_OIDC_CLIENT_CI_CREDENTIALS_SECRET` | the recorded `IDP_SECRET_PROJECTS` |

The secret is the one in `.qits-bootstrap.env` on the wrapper checkout — the same value the
`githost` client already uses, because both are the same service identity asking for two different
audiences.

```sh
curl -fsS -X PUT \
  -H 'X-Qits-User: operator' -H 'X-Qits-Roles: qits:admin' \
  -H 'Content-Type: text/plain' \
  --data-binary 'http://dev-qits-githost:8080' \
  http://dev-qits-configuration:8080/configuration/api/applications/qits-projects/entries/env.QITS_PROJECTS_RELEASE_REQUESTS_GITHOST_URL
```

…and one more per row above. Then read the resolved document back and compare it against the running
service, exactly as `demotion-rollout.md` step 1 says: **a key on the live service that the store
does not hold is removed at the next deployment**, this application's own release wiring included.

```sh
curl -fsS -H 'X-Qits-User: operator' -H 'X-Qits-Roles: qits:admin' \
  http://dev-qits-configuration:8080/configuration/api/applications/qits-projects/resolved

docker service inspect --format \
  '{{range .Spec.TaskTemplate.ContainerSpec.Env}}{{println .}}{{end}}' dev-qits-projects
```

`QITS_PROJECTS_RELEASE_REQUESTS_WORKSPACES_URL` is the one to look for and REMOVE: it addressed
qits-workspaces' release door, which no longer exists, and the key is unread.

Add the same values to the RUNNING service so this instance can release before its next deployment:

```sh
docker service update \
  --env-add QITS_PROJECTS_RELEASE_REQUESTS_GITHOST_URL=http://dev-qits-githost:8080 \
  --env-add QITS_PROJECTS_RELEASE_REQUESTS_CI_URL=http://dev-qits-ci:8080 \
  --env-add QUARKUS_OIDC_CLIENT_CI_CLIENT_ENABLED=true \
  --env-add QUARKUS_OIDC_CLIENT_CI_AUTH_SERVER_URL=http://qits-platform-idp:8080/idp \
  --env-add QUARKUS_OIDC_CLIENT_CI_CLIENT_ID=dev-qits-projects \
  --env-add QUARKUS_OIDC_CLIENT_CI_GRANT_OPTIONS_CLIENT_AUDIENCE=dev-qits-ci \
  --env-add QUARKUS_OIDC_CLIENT_CI_CREDENTIALS_SECRET=<IDP_SECRET_PROJECTS> \
  --env-rm QITS_PROJECTS_RELEASE_REQUESTS_WORKSPACES_URL \
  dev-qits-projects
```

## Step 2 — drop the entry branch from qits-workspaces

`QITS_WORKSPACES_RELEASE_ENTRY_BRANCH` named the ref that service fast-forwarded after writing a
release commit. The door left qits-workspaces and the ref is retired, so the key is unread — but an
extras entry is exactly where a dead key survives, because every self-deploy writes it back.

```sh
curl -fsS -X DELETE \
  -H 'X-Qits-User: operator' -H 'X-Qits-Roles: qits:admin' \
  http://dev-qits-configuration:8080/configuration/api/applications/qits-workspaces/entries/env.QITS_WORKSPACES_RELEASE_ENTRY_BRANCH

docker service update --env-rm QITS_WORKSPACES_RELEASE_ENTRY_BRANCH dev-qits-workspaces
```

## Step 3 — prove a release deploys, before anything is deleted

Cut one release request through qits-projects and watch it all the way to an ACTIVE deployment.
Pick something cheap — qits-docs or qits-stt, never the deployer or the edge:

```sh
curl -fsS -X POST -H 'Content-Type: application/json' \
  -H 'X-Qits-User: operator' -H 'X-Qits-Roles: qits:admin' \
  -d '{"branch":"main","summary":"prove the release flow"}' \
  http://dev-qits-projects:8080/projects/api/repositories/<repoId>/release-requests
```

Poll the request until its `state` is `RELEASED` and its `version` is set, then watch the deployment
row appear:

```sh
curl -fsS -H 'X-Qits-User: qits-bootstrap' -H 'X-Qits-Roles: qits-platform:admin' \
  'http://dev-qits-deployments:8080/platform-deployments/api/deployments?environmentId=<id>'
```

The row to see is `status: ACTIVE` with the `version` the request answered with. `IMAGE_MISSING`
means the release build did not publish `qits/<app>:<version>` — a repository whose
`ci-event-release.yml` still pushes only `:$QITS_CI_SHA`, or one carrying no release recipe at all.
Fix that first; it is the whole of what the new flow depends on.

## Step 4 — delete the live `environment/*` refs

Only now, and on BOTH remotes: the platform git host, and the org the repositories are backed up to.
A ref left behind is not dangerous, it is misleading — somebody pushes to it expecting a deployment
and nothing happens, silently, which is the failure this flow was rebuilt to remove.

List what is still there, per repository:

```sh
for repo in $(curl -fsS -H "Authorization: Bearer $TOKEN" \
    http://dev-qits-projects:8080/projects/api/projects/qits/repositories \
    | python3 -c 'import json,sys; [print(r["name"]) for r in json.load(sys.stdin)["repositories"]]'); do
  echo "== $repo"
  git -c http.extraHeader="Authorization: Bearer $TOKEN" \
    ls-remote --heads "http://githost.dev.localhost:8080/git/qits/$repo.git" 'refs/heads/environment/*'
done
```

`$TOKEN` is a commissioned credential's access token — the closing report of a bootstrap prints how
to mint one. Then delete each ref that answered:

```sh
git -c http.extraHeader="Authorization: Bearer $TOKEN" \
  push "http://githost.dev.localhost:8080/git/qits/$repo.git" \
  --delete environment/prod -o qits.token=<QITS_PUSH_TOKEN>
```

And on the org remote, where qits-projects' backup mirrors put a copy of every ref:

```sh
git push git@github.com:<org>/$repo.git --delete environment/prod
```

**Nothing in the bootstrap deletes a ref**, and nothing will: this program creates and advances
refs, and a program that deletes branches on somebody's git host is a different kind of tool. A
rerun after this step simply never recreates them.

## Step 5 — sweep the mentions

A dead ref in a document outlives the ref itself. Grep the estate for it and fix what is left:

```sh
grep -rn "environment/" --include='*.md' --include='*.yml' --include='*.properties' \
  --include='*.java' . | grep -v '\.git/'
```

What stays is a RETIREMENT NOTE — a comment saying the ref is gone and why. What must go is anything
that still reads as an instruction: a `deploy_branches:` key, a documented "push
`environment/prod` to deploy", a configuration default naming one.

## Rollback

There is none worth writing, and that is the honest answer: the branch column is dropped in the
deployer's schema, `BuildAnnouncements` and the `build-succeeded` intake are deleted from its code,
and qits-workspaces' release door is gone from its. Rolling `environment/*` back is rolling back
four repositories' images and one migration. What IS reversible is step 1 — unset the two
`release-requests` addresses and qits-projects refuses to release, which is a platform that deploys
nothing new rather than one that deploys the wrong thing.
