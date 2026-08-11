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
            phases.add(seed.seedImage("gateway"));
            // The edge is in the first half because it needs nothing from the platform: it has no
            // client to place a bundle for and no qits dependency to resolve, so its image builds
            // from Maven Central alone. Beside the gateway because that is what it fronts.
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
            // The nameserver is in the first half for the same reason as the edge: a clone of that
            // repository builds green on its own — no qits Maven dependency, no client bundle — so
            // its image needs nothing this run has not got yet.
            phases.add(seed.seedImage("platform-dns"));
            // The bus, in the first half for the same reason as the edge and the nameserver: a
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
            // The byte-plane libraries first and in dependency order, for the reason
            // PlatformModel.RELEASE_PUBLISHERS spells: qits-registries is written against
            // qits-blobstore's entities.
            phases.add(seed.mavenPublish("blobstore", "qits-blobstore",
                    "publish qits-blobstore into seed artifacts"));
            phases.add(seed.mavenPublish("registries", "qits-registries-oci",
                    "publish qits-registries into seed artifacts"));
            // The integrations BEFORE qits-eventstream, because qits-eventstream is written against
            // qits-db-core — one of this repository's three modules — since 2026-08-11. A publish
            // resolves its qits half from the store it is deploying into, so the order here is the
            // same order the maven seed runs in and for the same reason.
            phases.add(seed.mavenPublish("integrations-quarkus", "qits-auth-core",
                    "publish qits-auth-core into seed artifacts"));
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
        phases.add(seed.composeFile());
        phases.add(seed.pdRunArgs());
        // BEFORE the edge is started with a keystore, which is what the next phase does: a keystore
        // naming files that do not exist fails startup, so the volume has to hold a certificate
        // first. Only with a domain — without one the edge has no keystore at all.
        Optional<String> domain = DomainName.of(boot.config);
        domain.ifPresent(name -> phases.add(seed.placeholderCertificate(name)));
        phases.add(pipeline.seedStackUp());
        phases.add(pipeline.seedHealth());
        // After the nameserver has answered, because this writes to it. A zone is what makes it
        // answer for the domain at all; the records need a public address this run cannot know.
        domain.ifPresent(name -> phases.add(seed.dnsZone(name)));
        phases.add(pipeline.daemonPublish());
        phases.add(pipeline.gitRepositories());
        phases.add(pipeline.releaseTrainPreseed());
        for (String publisher : PlatformModel.RELEASE_PUBLISHERS) {
            phases.add(pipeline.releaseReplay(publisher));
        }
        phases.add(pipeline.environment());
        for (String deployable : PlatformModel.DEPLOYABLES) {
            phases.add(pipeline.deploy(deployable));
        }
        phases.add(pipeline.releaseTrainPush());
        phases.add(pipeline.summary());

        return List.copyOf(phases);
    }
}
