package eu.wohlben.qits.cli.bootstrap.phases;

import com.fasterxml.jackson.databind.JsonNode;
import eu.wohlben.qits.cli.bootstrap.api.Http;

import java.util.List;
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

    private PhaseReads() {
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

    /** {@code workspaces-runner-connected}: the same for qits-workspaces' listing. */
    public static JsonNode workspacesRunnerConnected(Http.Response listing, String id) {
        return PipelinePhases.workspacesRunnerConnection(listing, id).value();
    }
}
