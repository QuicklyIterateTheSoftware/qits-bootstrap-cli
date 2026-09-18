package eu.wohlben.qits.cli.bootstrap.api;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the manual door's answer is read for, and it is one question: did any pipeline select this
 * release?
 * <p>
 * The two answers take different roads in the bring-up — a run to wait for, or a release handed
 * straight to the deployer — so reading an empty list as "started something" would leave a phase
 * waiting out its whole hour on a build nobody ever queued.
 * <p>
 * The second question below is the one asked BEFORE the door: has the release pipeline already run
 * green at this commit, so that there is nothing left to publish? Its identity is the run's config
 * path, and this is where that path is pinned to the file qits-ci really stamps.
 */
class CiApiTest {

    @Test
    void theRunsATriggerStartedAreTheOnesItNames() {
        assertThat(CiApi.triggeredRunIds(new Http.Response(200,
                "{\"eventId\":\"e1\",\"runIds\":[\"r1\",\"r2\"],\"repositoriesRead\":19}")))
                .containsExactly("r1", "r2");
    }

    /** 200 with nothing in it: every candidate was read and no trigger file selected the event. */
    @Test
    void aTriggerThatSelectedNothingStartedNothing() {
        assertThat(CiApi.triggeredRunIds(new Http.Response(200,
                "{\"eventId\":\"e1\",\"runIds\":[],\"repositoriesRead\":19}")))
                .isEmpty();
    }

    /** A refusal is not an empty run list — the caller decides what to do about it, not this. */
    @Test
    void aRefusedTriggerNamesNoRuns() {
        assertThat(CiApi.triggeredRunIds(new Http.Response(503, "the candidate read timed out")))
                .isEmpty();
        assertThat(CiApi.triggeredRunIds(new Http.Response(0, "connection refused"))).isEmpty();
    }

    /**
     * <b>The replay's skip, pinned against the run row qits-ci really writes today.</b> A release
     * run is composed from {@code .config/qits/release.yml} and stamped with that path, so that is
     * what the config-path identity has to be asked by. While this constant still named the retired
     * {@code ci-event-release.yml} the match could not succeed for any repository on the estate —
     * green or not, every replay rebuilt what the registry already held, and a half-hour image
     * build per deployable is what a dead skip costs a boot.
     */
    @Test
    void aGreenComposedReleaseRunAtTheShaIsTheSkip() {
        CiApi ci = new CiApi(new CannedRuns("""
                {"runs":[
                  {"id":"r1","triggerType":"EVENT","status":"SUCCESS",
                   "configPath":".config/qits/release.yml","commitSha":"abc123"}
                ]}"""), "http://ci");

        assertThat(ci.greenReleaseRunAt("repo-1", "abc123")).isTrue();
    }

    /**
     * The other three rows of the same listing, each wrong in one column: a bump run at the SAME
     * sha (every event run is recorded at main's head, so the sha collides — which is the whole
     * reason the config path is the identity), a red release run, and a release run of another
     * commit.
     */
    @Test
    void nothingElseAnswersForAGreenRelease() {
        CiApi ci = new CiApi(new CannedRuns("""
                {"runs":[
                  {"id":"r1","triggerType":"EVENT","status":"SUCCESS",
                   "configPath":".config/qits/ci-platform-event-maintenance-bump.yml",
                   "commitSha":"abc123"},
                  {"id":"r2","triggerType":"EVENT","status":"FAILED",
                   "configPath":".config/qits/release.yml","commitSha":"abc123"},
                  {"id":"r3","triggerType":"EVENT","status":"SUCCESS",
                   "configPath":".config/qits/release.yml","commitSha":"def456"}
                ]}"""), "http://ci");

        assertThat(ci.greenReleaseRunAt("repo-1", "abc123")).isFalse();
    }

    /** A read that did not answer says nothing about the registry, so it is not a skip. */
    @Test
    void anUnreadableListingIsNotAGreenRun() {
        CiApi ci = new CiApi(new CannedRuns(new Http.Response(503, "mid cutover")), "http://ci");

        assertThat(ci.greenReleaseRunAt("repo-1", "abc123")).isFalse();
    }

    /** Answers the run listing with a canned body and remembers what was asked for. */
    static class CannedRuns extends Http {
        private final Response answer;

        CannedRuns(String body) {
            this(new Response(200, body));
        }

        CannedRuns(Response answer) {
            this.answer = answer;
        }

        @Override
        public Response get(String url, Map<String, String> headers) {
            return answer;
        }
    }
}
