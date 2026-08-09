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
        // Before the sources, because the sources are read out of it — and on a cold machine there
        // is no wrapper to read. Skipped whenever there is one, which is every rerun.
        phases.add(seed.wrapper());
        phases.add(seed.sources());
        phases.add(seed.recordedState());

        if (boot.config.skipBuild()) {
            phases.add(seed.skipBuildGate());
        } else {
            phases.add(seed.authCoreSeed());
            // CORE order, and the Maven bootstrap that has to happen in the middle of it:
            // qits-ci consumes qits-eventstream from the Maven repository it will use in steady
            // state, so artifacts is brought up alone and the dependencies are published before
            // the CI image builds.
            phases.add(seed.seedImage("gateway"));
            // The edge is in the first half because it needs nothing from the platform: it has no
            // client to place a bundle for and no qits dependency to resolve, so its image builds
            // from Maven Central alone. Beside the gateway because that is what it fronts.
            phases.add(seed.seedImage("platform-edge"));
            phases.add(seed.seedImage("platform-artifacts"));
            // Beside artifacts because it needs nothing either: the image is upstream postgres,
            // built from one FROM line, so it costs seconds rather than a native build.
            phases.add(seed.seedImage("oci-postgresql"));
            phases.add(seed.seedArtifactsStart());
            phases.add(seed.mavenPublish("eventstream", "qits-eventstream",
                    "publish qits-eventstream into seed artifacts"));
            phases.add(seed.mavenPublish("integrations-quarkus", "qits-auth-core",
                    "publish qits-auth-core into seed artifacts"));
            phases.add(seed.uiComponentsPublish());
            phases.add(seed.angularPublish());
            phases.add(seed.seedImage("ci"));
            phases.add(seed.seedImage("deployments"));
            phases.add(seed.seedImage("platform-idp"));
            for (String image : List.of("ci-base", "maven-base", "userflows-base", "node-base",
                    "node-docker-base")) {
                phases.add(seed.stepImage(image));
            }
            phases.add(seed.ciDaemon());
        }

        // OUTSIDE the skip-build branch, and before idp-secrets. The deployer refuses to boot
        // without this database and the seed stack starts it three phases from now, so the server
        // has to answer before the compose file that addresses it is written. A warm rerun needs
        // it just as much: the passwords it resolves fill both generated files.
        phases.add(seed.seedPostgres());
        phases.add(seed.idpSecrets());
        phases.add(seed.composeFile());
        phases.add(seed.pdRunArgs());
        phases.add(pipeline.seedStackUp());
        phases.add(pipeline.seedHealth());
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
