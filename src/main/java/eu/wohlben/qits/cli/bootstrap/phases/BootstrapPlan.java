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
            phases.add(seed.seedImage("artifacts"));
            phases.add(seed.seedArtifactsStart());
            phases.add(seed.mavenPublish("eventstream", "qits-eventstream",
                    "publish qits-eventstream into seed artifacts"));
            phases.add(seed.mavenPublish("integrations-quarkus", "qits-auth-core",
                    "publish qits-auth-core into seed artifacts"));
            phases.add(seed.uiComponentsPublish());
            phases.add(seed.angularPublish());
            phases.add(seed.seedImage("ci"));
            phases.add(seed.seedImage("cd"));
            phases.add(seed.seedImage("idp"));
            phases.add(seed.seedImage("serviceregistry"));
            for (String image : List.of("ci-base", "maven-base", "userflows-base", "node-base",
                    "node-docker-base")) {
                phases.add(seed.stepImage(image));
            }
            phases.add(seed.ciDaemon());
        }

        phases.add(seed.idpSecrets());
        phases.add(seed.composeFile());
        phases.add(seed.cdRunArgs());
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
