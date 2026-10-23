package eu.wohlben.qits.cli.bootstrap.phases;

import com.fasterxml.jackson.databind.JsonNode;
import eu.wohlben.qits.cli.bootstrap.api.Http;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The phases' own readers of a platform answer, opened to the consumer pacts in
 * {@code eu.wohlben.qits.cli.bootstrap.contracts}. Test code only: the readers stay package-private
 * in the phases, and a pact row asserts what the phase itself would make of an answer rather than
 * a copy of that logic.
 */
public final class PhaseReads {

    /** The name the runner phases give the runner they own. */
    public static final String LOCALHOST_RUNNER = PipelinePhases.LOCALHOST_RUNNER;

    /** The bearer {@code seed-health}'s auth-plane probes present: one no idp issued. */
    public static final Map<String, String> PROBE_BEARER = PipelinePhases.PROBE_BEARER;

    private PhaseReads() {
    }

    /** The release replays' and deploys' {@code SCMRelease}, as the phase composes it for ci. */
    public static String releaseEvent(String projectId, String repositoryId, String repoName,
                                      String version, String commitSha) {
        return PipelinePhases.releaseEvent(projectId, repositoryId, repoName, version, commitSha);
    }

    /** {@code qits-project}: the {@code qits} project's id in a listing. */
    public static Optional<String> qitsProjectId(Http.Response listing) {
        return PipelinePhases.qitsProjectId(listing);
    }

    /** {@code environment} and {@code summary}: every project slug in a listing. */
    public static List<String> projectSlugs(Http.Response listing) {
        return PipelinePhases.projectSlugs(listing);
    }

    /** {@code git-repos}: the storage id a by-name answer resolves to, or null. */
    public static String storageIdIn(Http.Response answer) {
        return PipelinePhases.storageIdIn(answer);
    }

    /** {@code runner-connected}: the row once runner {@code id} is connected, or null. */
    public static JsonNode ciRunnerConnected(Http.Response listing, String id) {
        return PipelinePhases.runnerConnection(listing, id).value();
    }

    /** {@code target-connected}: the same for qits-system's target listing. */
    public static JsonNode targetConnected(Http.Response listing, String id) {
        return PipelinePhases.targetConnection(listing, id).value();
    }

    /** {@code target-localhost}: the decision a listing makes, on a cold host whose CI is ours. */
    public static String targetClaim(Http.Response listing) {
        return PipelinePhases.targetDecision(
                eu.wohlben.qits.cli.bootstrap.api.SystemApi.targetsIn(listing), Optional.empty(),
                PipelinePhases.RunnerClaim.DECLARE).claim().name();
    }
}
