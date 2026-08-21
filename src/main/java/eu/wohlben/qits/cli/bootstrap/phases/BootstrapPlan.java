package eu.wohlben.qits.cli.bootstrap.phases;

import eu.wohlben.qits.cli.bootstrap.config.DomainName;
import eu.wohlben.qits.cli.bootstrap.engine.Phase;
import eu.wohlben.qits.cli.bootstrap.platform.PlatformModel;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

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
        // The bootstrap edge is the first public component. It owns the normal public door while
        // the seed is built, and it stays there across worker retries until platform-edge cuts it
        // over near the end of this plan.
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
            phases.add(seed.mavenSeed());
            // CORE order, and the Maven bootstrap that has to happen in the middle of it:
            // qits-ci consumes qits-eventstream from the Maven repository it will use in steady
            // state, so artifacts is brought up alone and the dependencies are published before
            // the CI image builds.
            // The edge is in the first half because it needs nothing from the platform: it has no
            // client to place a bundle for and no qits dependency to resolve, so its image builds
            // from Maven Central alone and is the platform's direct ingress.
            phases.add(seed.seedImage("platform-edge"));
            // THE BYTE PLANE'S THREE, together and here rather than after the publishes below: all
            // three are built out of qits-blobstore and qits-registries, which the maven-seed phase
            // put in the temporary registry before the first image was built. There is nothing they
            // could wait for — the real store does not exist until one of them is running.
            phases.add(seed.seedImage("platform-mirror"));
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
            // The integrations FIRST: qits-blobstore is written against qits-db-core — one of this
            // repository's three modules — since its DbRetry release (2026-08-13), and
            // qits-eventstream since 2026-08-11. A publish resolves its qits half from the store it
            // is deploying into, so the order here is the same order the maven seed runs in and for
            // the same reason.
            phases.add(seed.mavenPublish("integrations-quarkus", "qits-auth-core",
                    "publish qits-auth-core into seed artifacts"));
            // Then the byte-plane libraries in dependency order, for the reason
            // PlatformModel.RELEASE_PUBLISHERS spells: qits-registries is written against
            // qits-blobstore's entities.
            phases.add(seed.mavenPublish("blobstore", "qits-blobstore",
                    "publish qits-blobstore into seed artifacts"));
            phases.add(seed.mavenPublish("registries", "qits-registries-oci",
                    "publish qits-registries into seed artifacts"));
            phases.add(seed.mavenPublish("eventstream", "qits-eventstream",
                    "publish qits-eventstream into seed artifacts"));
            // The git host's event vocabulary, AFTER qits-eventstream because that is its only qits
            // dependency, and one module of qits-githost rather than the repository whole. Two
            // consumers need it out of the store: the ci image built four phases below, and
            // qits-projects, which the train deploys long before the git host's own deployment
            // could have published anything.
            phases.add(seed.mavenPublish("githost", "qits-githost-events",
                    "publish qits-githost-events into seed artifacts"));
            // The orchestrator's two libraries, LAST of the maven publishes and before every image
            // built out of them. It has to be last: the pair is built against qits-db-core,
            // qits-arch-rules and qits-auth-core (all three from qits-integrations-quarkus) and
            // against qits-eventstream, so every jar it resolves is already in the store.
            //
            // It has to be BEFORE seed-image-ci: ci pins qits-containers-client, and a step
            // container's image build resolves from the platform's own Maven registry. There is no
            // host ~/.m2 anywhere in this run to fall back on.
            phases.add(seed.mavenPublish("containers", "qits-containers-client",
                    "publish the qits-containers libraries into seed artifacts"));
            phases.add(seed.uiComponentsPublish());
            phases.add(seed.angularPublish());
            phases.add(seed.seedImage("ci"));
            phases.add(seed.seedImage("deployments"));
            phases.add(seed.seedImage("platform-idp"));
            // The orchestrator's own image, in the second half because its reactor resolves four
            // qits jars the publishes above put in the store. It has no client bundle to place: the
            // service serves machines and there is no SPA to stand in for.
            phases.add(seed.seedImage("containers"));
            // THE ALIAS TABLE'S OWNER, and it is built here for the same reason the four above
            // are: its reactor resolves qits-eventstream, qits-githost-events, qits-auth-core and
            // the qits-containers client out of the store the publishes above filled. It has a
            // client, so the seed places a placeholder bundle where its Dockerfile's `test -f`
            // looks — see PlatformModel.seedUiPath.
            phases.add(seed.seedImage("projects"));
            for (String image : List.of("ci-base", "maven-base", "userflows-base", "node-base",
                    "node-docker-base")) {
                phases.add(seed.stepImage(image));
            }
            phases.add(seed.ciDaemon());
        }

        // seed-postgres is inside BOTH arms above rather than here, and the byte-plane split is why:
        // qits-platform-mirror is started by hand in the middle of the build arm and refuses to boot
        // without its database, so the server has to answer before that phase — while a warm rerun
        // has no such phase and needs postgres only for the passwords both generated files carry.
        // One placement per arm, each the earliest point that arm needs.
        phases.add(seed.idpSecrets());
        DomainName.of(boot.config).ifPresent(name -> phases.add(seed.dnsHetznerSecret(name)));
        phases.add(seed.composeFile());
        phases.add(seed.pdExtras());
        // BEFORE the edge is started with a keystore, which is what the next phase does: a keystore
        // naming files that do not exist fails startup, so the volume has to hold a certificate
        // first. Only with a domain — without one the edge has no keystore at all.
        Optional<String> domain = DomainName.of(boot.config);
        domain.ifPresent(name -> phases.add(seed.placeholderCertificate(name)));
        phases.add(pipeline.seedStackUp());
        phases.add(pipeline.seedHealth());
        // The earliest point the idp answers, which is all this needs: the token is a row in
        // postgres, so it outlives every redeploy that follows. Once per installation — a rerun
        // that finds one recorded mints nothing.
        phases.add(pipeline.registerToken());
        // DNS-01 issuance belongs to the running edge now. It starts asynchronously after the seed
        // stack is healthy and renews there; bootstrap neither runs certbot nor holds a challenge.
        phases.add(pipeline.daemonPublish());
        // THE PROJECT EVERY REPOSITORY BELONGS TO, and it comes before the first bare rather than
        // after the sixth deployment. qits-projects is a seed service now, so the one thing that
        // has to happen before this run creates anything is that the `qits` project exists to
        // register it under: a storage id is a UUID, and a UUID resolves to nothing until the
        // pairing is a row in the alias table.
        phases.add(pipeline.qitsProject());
        phases.add(pipeline.gitRepositories());
        // Every deployable's gitlinks must be advertised before CI clones it. In particular,
        // qits-ci points at qits-spa-ci; pushing that SPA after the deployment train makes the
        // githost correctly refuse CI's request for an unadvertised object.
        phases.add(pipeline.releaseTrainPush());
        phases.add(pipeline.releaseTrainPreseed());
        for (String publisher : PlatformModel.RELEASE_PUBLISHERS) {
            phases.add(pipeline.releaseReplay(publisher));
        }
        phases.add(pipeline.environment());
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
        // builder is provably finished with: nothing above may build after it.
        phases.add(pipeline.teardownBootstrapBuilder());

        return List.copyOf(phases);
    }
}
