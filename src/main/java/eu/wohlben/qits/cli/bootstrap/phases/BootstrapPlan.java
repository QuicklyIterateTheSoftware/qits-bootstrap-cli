package eu.wohlben.qits.cli.bootstrap.phases;

import eu.wohlben.qits.cli.bootstrap.engine.Phase;
import eu.wohlben.qits.cli.bootstrap.platform.PlatformModel;

import java.util.ArrayList;
import java.util.List;

/**
 * The boot, as an ordered list built from configuration. The order is the script's, and the
 * comments there say why each constraint exists; the list is built up front so the header's
 * "9 of 47" is a fact rather than an estimate.
 */
public final class BootstrapPlan {

    private BootstrapPlan() {
    }

    public static List<Phase> build(Boot boot) {
        SeedPhases seed = new SeedPhases(boot);
        PipelinePhases pipeline = new PipelinePhases(boot);
        List<Phase> phases = new ArrayList<>();

        phases.add(seed.preflight());
        // Before the first address is dialled, and after preflight, which is where the daemon is
        // proved reachable at all.
        phases.add(seed.joinNetwork());
        // THE ONE HOST RULE THIS PLATFORM CANNOT RUN WITHOUT, and it is here rather than at the end
        // because everything after it may dial the edge by name. Nothing is listening on that port
        // yet, which is the point: the rule is installed unconditionally so the landmine is
        // disarmed before the swarm ingress arms it.
        phases.add(seed.ipv6Loopback());
        // Beside it, and for the other half of the same reason: what this host does under memory
        // pressure is decided before the boot starts building, because the boot itself is the first
        // heavy workload on it. It warns rather than stops — the platform comes up either way, it
        // just has no early killer — so nothing after it depends on it.
        phases.add(seed.hostOom());
        // The bootstrap edge is the first public component. It owns the normal public door while
        // the seed is built, and it stays there across worker retries until the edge's seed deploy
        // retires it — the end of the local-build step. A rerun over a deployer-managed edge starts
        // none at all.
        phases.add(seed.bootstrapIngressPrepare());
        phases.add(seed.bootstrapIngressStart());
        // Before the sources, because the sources are read out of it — and on a cold machine there
        // is no wrapper to read. Skipped whenever there is one, which is every rerun.
        phases.add(seed.wrapper());
        phases.add(seed.sources());
        phases.add(seed.recordedState());

        if (boot.config.skipBuild()) {
            phases.add(seed.skipBuildGate());
            // Not a build, so a warm rerun still needs it: it resolves the passwords both generated
            // files carry, and the deployer refuses to boot without the database it names.
            phases.add(seed.seedPostgres());
        } else {
            // THE CONTRACT JARS FIRST, before anything is built (qits-889). Every seed service
            // build resolves its poms' qits coordinates from the qits-maven-seed volume and from
            // nothing else, test scope included, and two kinds of them no build here produces:
            // the providers' golden masters and the consumers' pacts. The edge pins fourteen pacts
            // and qits-idp's golden masters, and it is the first image; maven-seed itself builds
            // two contracts kits that COMPILE against golden masters. Both phases pack from the
            // checkouts' release tags into the file repository and dial no server, which is what
            // lets them run before any part of the platform is up. The store's copy of the golden
            // masters is publish-golden-masters, once there is a store.
            phases.add(seed.seedGoldenMasters());
            phases.add(seed.seedPacts());
            phases.add(seed.mavenSeed());
            // CORE order, and the Maven bootstrap that has to happen in the middle of it:
            // qits-ci consumes qits-eventstream from the Maven repository it will use in steady
            // state, so artifacts is brought up alone and the dependencies are published before
            // the CI image builds.
            // The edge is in the first half because it needs nothing from the platform: it has no
            // client to place a bundle for, and the qits jars it resolves are in the file repository
            // maven-seed has just filled, so it is the platform's direct ingress.
            phases.add(seed.seedImage("edge"));
            // THE BYTE PLANE'S THREE, together and here rather than after the publishes below: all
            // three are built out of qits-blobstore and qits-registries, which the maven-seed phase
            // put in the file repository before the first image was built. There is nothing they
            // could wait for — the real store does not exist until one of them is running.
            phases.add(seed.seedImage("mirror"));
            phases.add(seed.seedImage("artifacts"));
            phases.add(seed.seedImage("githost"));
            // Beside artifacts because it needs nothing either: the image is upstream postgres,
            // built from one FROM line, so it costs seconds rather than a native build.
            phases.add(seed.seedImage("oci-postgresql"));
            // The bus, in the first half for the same reason as the edge: a
            // clone of qits-events resolves against Maven Central alone. It declares no qits Maven
            // dependency and no <repositories>, and the @qits npm package its client needs is
            // stood in for by the placeholder bundle — so nothing here waits on the publishes
            // below.
            phases.add(seed.seedImage("events"));
            // BEFORE the mirror is started, because the mirror refuses to boot without its
            // database — and the mirror is started by hand, before any compose file exists.
            phases.add(seed.seedPostgres());
            // BEFORE seed-artifacts, and that order is what the two-endpoint topology costs: every
            // publish below resolves its third-party half — maven plugins, Maven Central, every
            // unscoped npm package — through the mirror's caches. A mirror that is not answering is
            // not a slow publish but a failed one.
            phases.add(seed.seedMirrorStart());
            phases.add(seed.seedArtifactsStart());
            // THE GOLDEN MASTERS INTO THE STORE, the moment there is one and before the first
            // library publish into it: those publishes resolve their qits half from the store, and
            // qits-eventstream's and qits-containers' contracts kits compile against golden masters.
            phases.add(seed.publishGoldenMasters());
            // The userflows library FIRST, because it resolves nothing of ours and seventeen root
            // poms resolve IT. Its scope is test, which changes nothing: `mvn package -DskipTests`
            // still resolves test-scoped dependencies, so a store without it fails every service
            // build on this platform.
            phases.add(seed.mavenPublish("userflows", "qits-userflows",
                    "publish qits-userflows into seed artifacts"));
            // The shared agent harness second, on the same terms: it resolves nothing of ours, and
            // both agent daemons' release replays resolve it from the store.
            phases.add(seed.mavenPublish("coding-agents", "qits-coding-agents",
                    "publish the shared agent harness into seed artifacts"));
            // The integrations next: the blob store is written against qits-db-core — one of this
            // repository's three modules — since its DbRetry release (2026-08-13), and
            // qits-eventstream since 2026-08-11. A publish resolves its qits half from the store it
            // is deploying into, so the order here is the same order the maven seed runs in and for
            // the same reason.
            phases.add(seed.mavenPublish("integrations-quarkus", "qits-auth-core",
                    "publish qits-auth-core into seed artifacts"));
            // Then the byte-plane library, ONE phase for what used to be two: the blob store is a
            // module of the qits-registries reactor since 2026-08-30, and this publish deploys the
            // reactor whole. So qits-blobstore reaches the store here, under its unchanged
            // coordinate and in the reactor's own order, and its retired repository is named
            // nowhere.
            phases.add(seed.mavenPublish("registries", "qits-registries-oci",
                    "publish the qits-registries libraries into seed artifacts"));
            phases.add(seed.mavenPublish("eventstream", "qits-eventstream",
                    "publish qits-eventstream into seed artifacts"));
            // The git host's event vocabulary, AFTER qits-eventstream because that is its only qits
            // dependency, and one module of qits-githost rather than the repository whole. Two
            // consumers need it out of the store: the ci image built four phases below, and
            // qits-projects, which the train deploys long before the git host's own deployment
            // could have published anything.
            phases.add(seed.mavenPublish("githost", "qits-githost-events",
                    "publish qits-githost-events into seed artifacts"));
            // qits-containers-javalib, on qits-eventstream's own terms: no qits dependency of its
            // own, so nothing above forces an order on it, but qits-containers-service now depends
            // on it — so it has to be published before the line below.
            phases.add(seed.mavenPublish("containers-driver", "qits-containers-driver",
                    "publish qits-containers-driver into seed artifacts"));
            // The orchestrator's library, LAST of the maven publishes and before every image built
            // out of it. It has to be last: it is built against qits-db-core, qits-arch-rules and
            // qits-auth-core (all three from qits-integrations-quarkus), qits-eventstream and
            // qits-containers-driver, so every jar it resolves is already in the store.
            //
            // It has to be BEFORE seed-image-ci: ci pins qits-containers-client, and a step
            // container's image build resolves from the platform's own Maven registry. There is no
            // host ~/.m2 anywhere in this run to fall back on.
            phases.add(seed.mavenPublish("containers", "qits-containers-client",
                    "publish the qits-containers client into seed artifacts"));
            // THE RUNNER LIBRARY, after every maven publish above and before the first image that
            // resolves it (qits-1547): qits-runner-javalib — the protocol and toolkit qits-system
            // and its runner compile against — is no deployable and no release publisher, so
            // nothing else would put it in a registry. The runner build resolves containers-driver,
            // pact-consumer and the golden masters out of the store, which is why it comes here.
            phases.add(seed.runnerPublish());
            phases.add(seed.uiComponentsPublish());
            phases.add(seed.angularPublish());
            // THE WIRE CONTRACT qits-ci COMPILES AGAINST, immediately before its image and for no
            // other consumer. It is not a library of the list above: it is one module of a daemon's
            // reactor, published at the version the ci checkout PINS and built from that release
            // tag, so it is no checkout publish and has no order among the others — it resolves
            // Maven Central alone. qits-ci-runner-protocol was its twin until the CI runner retired
            // (qits-1547); qits-ci pins it no more.
            // And the pin of the `qits` binary qits-ci's release steps download — one module of
            // qits-platform-access-cli, which is no repository of this platform's pipeline and is
            // cloned read-only for this and for its pacts (PlatformModel.READ_ONLY_REPOS). qits-ci
            // compiles against it, so on the same terms, beside it.
            phases.add(seed.protocolPublish("ci", "platform-access-cli",
                    "qits-platform-access-cli-binary", "qits.platform-access-cli-binary.version"));
            phases.add(seed.protocolPublish("ci", "ci-daemon", "qits-ci-daemon-protocol",
                    "qits.ci-daemon-protocol.version"));
            phases.add(seed.seedImage("ci"));
            phases.add(seed.seedImage("deployments"));
            phases.add(seed.seedImage("idp"));
            // The orchestrator's own image, in the second half because its reactor resolves four
            // qits jars the publishes above put in the store. It has no client bundle to place: the
            // service serves machines and there is no SPA to stand in for.
            phases.add(seed.seedImage("containers"));
            // THE ALIAS TABLE'S OWNER, and it is built here for the same reason the four above
            // are: its reactor resolves qits-eventstream, qits-githost-events, qits-auth-core and
            // the qits-containers client out of the store the publishes above filled. It has a
            // client, so the seed places a placeholder bundle where its Dockerfile's `test -f`
            // looks — see PlatformModel.seedUiPath.
            // ITS TWO DAEMONS' WIRE CONTRACTS, on qits-ci's terms (qits-889): qits-projects
            // compiles against qits-projects-daemon-protocol and qits-workspace-daemon-protocol at
            // the versions its root pom pins, built from those release tags. qits-system compiles
            // against the workspace daemon's contract too, at its own pin, so that one phase
            // publishes both consumers' versions, here before the first of them.
            phases.add(seed.protocolPublish("projects", "projects-daemon",
                    "qits-projects-daemon-protocol", "qits.projects-daemon-protocol.version"));
            phases.add(seed.protocolPublish(List.of("projects", "system"), "workspace-daemon",
                    "qits-workspace-daemon-protocol", "qits.workspace-daemon-protocol.version"));
            phases.add(seed.seedImage("projects"));
            // THE TARGETS' OWNER, a seed service since qits-1547 because every CI run is a lease on
            // one of its targets. Its reactor resolves qits-eventstream, qits-db-core and
            // qits-auth-core out of the store the publishes above filled, and the runner's wire
            // contract, which the phase before it publishes on qits-ci's daemon contract's terms —
            // at the version qits-system pins, built from that tag. It has a client, so the seed
            // places a placeholder bundle where its Dockerfile's `test -f` looks.
            phases.add(seed.protocolPublish("system", "system-runner-daemon",
                    "qits-system-runner-protocol", "qits.system-runner-protocol.version"));
            phases.add(seed.seedImage("system"));
            for (String image : SeedPhases.STEP_IMAGES) {
                phases.add(seed.stepImage(image));
            }
            // BEFORE the daemon build, because the builder image it starts with reads two
            // tarballs out of the store and a cold platform's store has neither.
            phases.add(seed.toolchainSeed());
            phases.add(seed.ciDaemon());
            // STRAIGHT AFTER IT, because it is built on the toolchain image that phase just made.
            // The target's runner is what executes every run of this boot from the first release
            // replay on, so its image has to exist before the seed stack is up — and only a build
            // arm can make it: a warm rerun starts the runner from the image the last one left.
            phases.add(seed.systemRunnerImage());
        }

        // seed-postgres is inside BOTH arms above rather than here, and the byte-plane split is why:
        // qits-platform-mirror is started by hand in the middle of the build arm and refuses to boot
        // without its database, so the server has to answer before that phase — while a warm rerun
        // has no such phase and needs postgres only for the passwords both generated files carry.
        // One placement per arm, each the earliest point that arm needs.
        // THE IDENTITY ORDER, AND EVERY GAP IN IT IS LOAD-BEARING. The seed services' idp clients
        // are created against a running idp rather than written into a generated file, so:
        // the bootstrap's own pair first, because the idp seeds its first database service client
        // FROM it; then the idp alone, because a client cannot be created at a service that is not
        // up; then the five clients; and only then the two generated files, because the seed
        // services' QITS_RESOURCE_IDP_CLIENT_SECRET values are not known until the idp has issued
        // them.
        phases.add(seed.idpBootstrapClient());
        phases.add(pipeline.seedIdp());
        phases.add(seed.idpClients());
        String domain = boot.config.requiredDomain();
        phases.add(seed.dnsHetznerSecret(domain));
        phases.add(seed.composeFile());
        phases.add(seed.pdExtras());
        // BEFORE the edge is started with a keystore, which is what the next phase does: a keystore
        // naming files that do not exist fails startup, so the volume has to hold a certificate
        // first.
        phases.add(seed.placeholderCertificate(domain));
        phases.add(pipeline.seedStackUp());
        phases.add(pipeline.seedHealth());
        // The earliest point the idp answers, which is all this needs: the token is a row in
        // postgres, so it outlives every redeploy that follows. Once per installation — a rerun
        // that finds one recorded mints nothing.
        phases.add(pipeline.registerToken());
        // DNS-01 issuance belongs to the running edge now. It starts asynchronously after the seed
        // stack is healthy and renews there; bootstrap neither runs certbot nor holds a challenge.
        //
        // THE IMAGES A RUN IS STARTED FROM, into the store — and it has to be a PUSH, through the
        // edge, which is why it waits for the seed stack. qits-ci pins a step image by asking the
        // store for its digest and refuses the run when the store has no such tag, so an image the
        // host daemon merely holds is an image no run may use. Before the first run there is, and
        // inside the publish half of the boot: it presents the publishing credential, which
        // publish-credential-release hands back two phases down.
        phases.add(pipeline.imagesPublish());
        // THE SEED IMAGES, AT THEIR REAL RELEASE TAG, and for the same reason the two images above
        // go into the store: the deployer pulls what the store answers for and nothing the host
        // merely holds. These are what the seed deploys below put live.
        phases.add(pipeline.seedImagesPublish());
        phases.add(pipeline.daemonPublish());
        // IMMEDIATELY AFTER THE LAST PUBLISH. The bootstrap is the one exception to "only CI may
        // publish", and the exception is bounded by handing the credential back here rather than
        // by anything the idp enforces. Everything below is a push, a deployment or a read.
        phases.add(pipeline.publishCredentialRelease());
        // THE PROJECT EVERY REPOSITORY BELONGS TO, and it comes before the first bare rather than
        // after the sixth deployment. qits-projects is a seed service now, so the one thing that
        // has to happen before this run creates anything is that the `qits` project exists to
        // register it under: a storage id is a UUID, and a UUID resolves to nothing until the
        // pairing is a row in the alias table.
        //
        // And both before the FIRST DEPLOYMENT, which is a few phases down now: the deployer reads
        // a release's spec name-addressed at its tag, and the edge needs the `qits` project to
        // project <app>.qits.<domain> at all.
        phases.add(pipeline.qitsProject());
        phases.add(pipeline.gitRepositories());
        // Before the first deployment too: without a designated platform environment the manual
        // door answers 202 and deploys nothing.
        phases.add(pipeline.environment());
        // STEP TWO OF FOUR: THE PLATFORM, UP AS IT RUNS IN STEADY STATE, BEFORE ANYTHING IS BUILT.
        // Step one is everything above — the local builds, out of the qits-maven-seed volume, with
        // the bootstrap ingress serving the progress page. Here the deployer puts each seed image
        // live at its real release tag through its manual door, and the edge's deployment, last,
        // retires the ingress. The deployer itself is not among them: its self-update stays last
        // of the train, after the configuration flip.
        for (String name : PlatformModel.SEED_DEPLOYED) {
            phases.add(pipeline.seedDeploy(name));
        }
        // THE DOOR THE TARGET DIALS, proved before it dials: the target's runner reaches
        // qits-system, the idp and the registry through the platform's own edge over a
        // certificate it has to trust, and so does the ci-daemon in every CI slot it starts.
        phases.add(pipeline.edgeReady());
        // STEP THREE: THE TARGET, AND IT HAS TO BE CONNECTED BEFORE THE FIRST RUN IS ASKED FOR.
        // Every CI run is a lease on a qits-system target since qits-1547, so every release
        // replay below is this host's target's or nobody's — a replay with no target is a lease
        // that stays QUEUED until its wait gives up, an hour later and once per publisher. Two
        // phases because they fail differently: the first starts a container, the second waits
        // for qits-system to say the target is connected and lifts the quarantine a new target
        // starts in. After the seed deploys, because the target registers with the qits-system
        // the platform runs in steady state — qits-system is a seed service for exactly this —
        // and it stays as the platform's own target: nothing below decommissions it. In both
        // arms, because a warm rerun finds the container stopped as often as it finds it running.
        // A platform whose targets are somebody else's is passed by — see
        // PipelinePhases.targetDecision. It replaced this host's CI runner here and its
        // workspaces runner after the train.
        phases.add(pipeline.localhostTarget());
        phases.add(pipeline.localhostTargetConnected());
        // STEP FOUR: THE FIRST BUILD. Every deployable's gitlinks must be advertised before CI
        // clones it. In particular, qits-ci points at qits-spa-ci; pushing that SPA after the
        // deployment train makes the githost correctly refuse CI's request for an unadvertised
        // object.
        phases.add(pipeline.releaseTrainPush());
        phases.add(pipeline.releaseTrainPreseed());
        for (String publisher : PlatformModel.RELEASE_PUBLISHERS) {
            phases.add(pipeline.releaseReplay(publisher));
        }
        // THE TRAIN, and for the nine seed-deployed applications it is a REdeployment: their
        // release run replaces the seed image under the same tag, and the deploy phase hands the
        // version over again once that run is green — see PipelinePhases.deploy.
        for (String deployable : PlatformModel.DEPLOYABLES) {
            phases.add(pipeline.deploy(deployable));
            // THE TWO PHASES THAT MOVE DEPLOYMENT CONFIGURATION INTO THE PLATFORM, and they sit
            // here rather than at the end of the train because both directions of the order are
            // load-bearing. AFTER qits-configuration's own deployment: a deployer told to read a
            // service that is not up refuses every deployment, that one included. BEFORE the rest
            // of the train, and in particular before qits-deployments' own self-update, which
            // inherits the url from its extras — a successor holding it over a service nobody
            // imported into would refuse everything after it. Every deployable below this line is
            // deployed from what the service serves, which is what proves the read.
            if (PipelinePhases.CONFIGURATION.equals(deployable)) {
                phases.add(pipeline.configurationImport());
                phases.add(pipeline.configurationFlip());
            }
            // THERE IS NO REGISTRATION PHASE HERE ANY MORE, and its absence is what seeding
            // qits-projects bought. Every repository's public address existed before the first
            // push — `git-repos`, forty phases above — so this deployment inherits an alias table
            // it does not have to be waited on to fill. It is still the deployment that matters
            // most to the addresses: qits-githost, six below it, closes the id-addressed scheme,
            // and by then this run has been name-addressing every push since its first.
        }
        phases.add(pipeline.summary());
        // LAST, AND AFTER THE SUMMARY ON PURPOSE. The summary phase only BUILDS the account —
        // BootstrapCommand prints it once the engine has run everything — so a phase below it still
        // reads before the closing text, and the reclaim is the run's own housekeeping rather than
        // part of what a person is told about the platform. It is also the only order in which the
        // seed caches are provably finished with: nothing above may build after it.
        phases.add(pipeline.teardownBootstrapBuilder());

        return List.copyOf(phases);
    }
}
