package eu.wohlben.qits.cli.bootstrap.platform;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A rotated client secret locks every already-deployed service out until it too is redeployed, so
 * what a previous run recorded has to survive this one.
 */
class BootstrapStateTest {

    @TempDir
    Path temp;

    /**
     * <b>The storage id a repository's bare is under has to survive a rerun</b> — a run that decided
     * it afresh would create a second bare and leave every history the platform stands on in the
     * first. The map is keyed the way every other line here is: the env-var spelling of the name.
     */
    @Test
    void repositoryStorageIdsSurviveARerun() throws Exception {
        Path file = temp.resolve(BootstrapState.FILE_NAME);
        Files.writeString(file, """
                REPO_ID_QITS_CI=qits-ci
                REPO_ID_QITS_SPA_GITHOST=8b1f0f0e-9a0c-4c3a-9a5b-000000000001
                """);

        BootstrapState state = new BootstrapState(file);
        state.read();

        assertThat(state.repositoryId("qits-ci")).contains("qits-ci");
        assertThat(state.repositoryId("qits-spa-githost"))
                .contains("8b1f0f0e-9a0c-4c3a-9a5b-000000000001");
        assertThat(state.repositoryId("qits-events")).isEmpty();
    }

    /** A write merges, so recording one repository never forgets the rest of the run's memory. */
    @Test
    void recordingAStorageIdKeepsEverythingElseTheFileHolds() throws Exception {
        Path file = temp.resolve(BootstrapState.FILE_NAME);
        Files.writeString(file, """
                DAEMON_SHA=8d0f1a2b3c4d5e6f
                IDP_SECRET_QITS_CI=aaa
                REPO_ID_QITS_CI=qits-ci
                """);

        BootstrapState first = new BootstrapState(file);
        first.read();
        first.putRepositoryId("qits-events", "qits-events");
        first.write();

        BootstrapState reread = new BootstrapState(file);
        reread.read();
        assertThat(reread.daemonSha()).contains("8d0f1a2b3c4d5e6f");
        assertThat(reread.secret("qits-ci")).contains("aaa");
        assertThat(reread.repositoryId("qits-ci")).contains("qits-ci");
        assertThat(reread.repositoryId("qits-events")).contains("qits-events");
    }

    @Test
    void readsWhatAPreviousRunRecorded() throws Exception {
        Path file = temp.resolve(BootstrapState.FILE_NAME);
        Files.writeString(file, """
                DAEMON_SHA=8d0f1a2b3c4d5e6f
                IDP_SECRET_QITS_CI=aaa
                IDP_SECRET_QITS_CD="bbb"
                """);

        BootstrapState state = new BootstrapState(file);
        state.read();

        assertThat(state.daemonSha()).contains("8d0f1a2b3c4d5e6f");
        assertThat(state.secret("qits-ci")).contains("aaa");
        assertThat(state.secret("qits-cd")).contains("bbb");
        assertThat(state.secret("qits-gateway")).isEmpty();
    }

    @Test
    void aFirstBootHasNothingToKeep() throws Exception {
        BootstrapState state = new BootstrapState(temp.resolve(BootstrapState.FILE_NAME));
        state.read();

        assertThat(state.exists()).isFalse();
        assertThat(state.daemonSha()).isEmpty();
        assertThat(state.secret("qits-ci")).isEmpty();
    }

    @Test
    void writesWhateverTheRunResolvedRatherThanAListOfItsOwn() throws Exception {
        // A client id is a wire alias, so the set follows the environment name and only the caller
        // knows it. This class writes the map it was handed.
        Path file = temp.resolve(BootstrapState.FILE_NAME);
        Map<String, String> secrets = new LinkedHashMap<>();
        secrets.put("prod-qits-ci", "one");
        secrets.put("qits-platform-artifacts", "two");

        new BootstrapState(file).write("digest", secrets);

        String text = Files.readString(file);
        assertThat(text).contains("DAEMON_SHA=digest");
        assertThat(text).contains("IDP_SECRET_PROD_QITS_CI=one");
        assertThat(text).contains("IDP_SECRET_QITS_PLATFORM_ARTIFACTS=two");
    }

    @Test
    void aKeyThisClassKnowsNothingAboutSurvivesARewrite() throws Exception {
        // The file is the run's whole memory and several phases write it. A write that rebuilt it
        // from its own caller's keys would delete the others'.
        Path file = temp.resolve(BootstrapState.FILE_NAME);
        Files.writeString(file, "SOMETHING_AN_OLDER_RUN_WROTE=keep-me\n");

        BootstrapState state = new BootstrapState(file);
        state.read();
        state.put("PG_SUPERUSER_PASSWORD", "0123456789abcdef");
        state.write();

        BootstrapState reread = new BootstrapState(file);
        reread.read();
        assertThat(reread.value("SOMETHING_AN_OLDER_RUN_WROTE")).contains("keep-me");
        assertThat(reread.value("PG_SUPERUSER_PASSWORD")).contains("0123456789abcdef");
        assertThat(reread.value("NEVER_WRITTEN")).isEmpty();
    }

    @Test
    void thePostgresPasswordsSurviveTheIdpSecretsWriteAndTheOtherWayRound() throws Exception {
        // seed-postgres records its passwords minutes before idp-secrets writes the client
        // secrets, and idp-secrets writes through a state object that never read the file. A
        // password on the data volume but not in this file locks the next rerun out.
        Path file = temp.resolve(BootstrapState.FILE_NAME);
        BootstrapState postgres = new BootstrapState(file);
        postgres.read();
        postgres.put("PG_SUPERUSER_PASSWORD", "aaaa1111bbbb2222");
        postgres.put("PG_DEPLOYMENTS_PASSWORD", "cccc3333dddd4444");
        postgres.write();

        Map<String, String> secrets = new LinkedHashMap<>();
        secrets.put("prod-qits-ci", "one");
        new BootstrapState(file).write("digest", secrets);

        BootstrapState afterSecrets = new BootstrapState(file);
        afterSecrets.read();
        assertThat(afterSecrets.value("PG_SUPERUSER_PASSWORD")).contains("aaaa1111bbbb2222");
        assertThat(afterSecrets.value("PG_DEPLOYMENTS_PASSWORD")).contains("cccc3333dddd4444");
        assertThat(afterSecrets.secret("prod-qits-ci")).contains("one");

        // And the other way: a later postgres write keeps the digest and the secrets.
        BootstrapState again = new BootstrapState(file);
        again.read();
        again.put("PG_SUPERUSER_PASSWORD", "eeee5555ffff6666");
        again.write();

        BootstrapState last = new BootstrapState(file);
        last.read();
        assertThat(last.daemonSha()).contains("digest");
        assertThat(last.secret("prod-qits-ci")).contains("one");
        assertThat(last.value("PG_SUPERUSER_PASSWORD")).contains("eeee5555ffff6666");
    }

    /**
     * <b>The recorded register token is what makes minting a once-per-installation act.</b> Every
     * call to the idp mints another key to an admin account, so the run that finds this key mints
     * nothing — and it survives the client-secret write beside it, like every other key here.
     */
    @Test
    void theRegisterTokenIsRememberedSoASecondIsNeverMinted() throws Exception {
        Path file = temp.resolve(BootstrapState.FILE_NAME);
        BootstrapState first = new BootstrapState(file);
        first.read();
        assertThat(first.registerToken()).isEmpty();
        first.put(BootstrapState.REGISTER_TOKEN_KEY, "rt-0123456789");
        first.write();

        Map<String, String> secrets = new LinkedHashMap<>();
        secrets.put("prod-qits-edge", "one");
        new BootstrapState(file).write("digest", secrets);

        BootstrapState rerun = new BootstrapState(file);
        rerun.read();
        assertThat(rerun.registerToken()).contains("rt-0123456789");
        assertThat(rerun.secret("prod-qits-edge")).contains("one");
    }

    @Test
    void aWrittenFileReadsBackTheSame() throws Exception {
        Path file = temp.resolve(BootstrapState.FILE_NAME);
        Map<String, String> secrets = new LinkedHashMap<>();
        PlatformModel.idpClients("prod").forEach(client -> secrets.put(client, "secret-" + client));
        new BootstrapState(file).write("abc", secrets);

        BootstrapState reread = new BootstrapState(file);
        reread.read();

        assertThat(reread.daemonSha()).contains("abc");
        for (String client : PlatformModel.idpClients("prod")) {
            assertThat(reread.secret(client)).contains("secret-" + client);
        }
    }
}
