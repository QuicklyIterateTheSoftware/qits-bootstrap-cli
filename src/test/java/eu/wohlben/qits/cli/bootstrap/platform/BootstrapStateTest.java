package eu.wohlben.qits.cli.bootstrap.platform;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A rotated client secret locks every already-deployed service out until it too is redeployed, so
 * what a previous run recorded has to survive this one.
 * <p>
 * <b>ONE identity key is recorded now</b>, the bootstrap's own: every other client is created
 * against the running idp and its secret kept in qits-deployments' registry. The spelling did not
 * change with the count, which is what lets a machine that has bootstrapped before keep the pair it
 * already has.
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
        assertThat(reread.bootstrapSecret("qits-ci")).contains("aaa");
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
        assertThat(state.bootstrapSecret("qits-ci")).contains("aaa");
        assertThat(state.bootstrapSecret("qits-cd")).contains("bbb");
        assertThat(state.bootstrapSecret("qits-gateway")).isEmpty();
    }

    @Test
    void aFirstBootHasNothingToKeep() throws Exception {
        BootstrapState state = new BootstrapState(temp.resolve(BootstrapState.FILE_NAME));
        state.read();

        assertThat(state.exists()).isFalse();
        assertThat(state.daemonSha()).isEmpty();
        assertThat(state.bootstrapSecret("qits-ci")).isEmpty();
    }

    /**
     * <b>ONE key, under the spelling it always had.</b> A client id is a wire alias, so it follows
     * the environment name and only the caller knows it — this class writes the id it was handed,
     * and the env-var spelling of it is what a machine that has bootstrapped before already holds.
     */
    @Test
    void writesTheOneClientSecretTheRunResolved() throws Exception {
        Path file = temp.resolve(BootstrapState.FILE_NAME);

        new BootstrapState(file).write("digest", "prod-qits-bootstrap", "one");

        String text = Files.readString(file);
        assertThat(text).contains("DAEMON_SHA=digest");
        assertThat(text).contains("IDP_SECRET_PROD_QITS_BOOTSTRAP=one");
    }

    /**
     * <b>A retired per-client secret is left where it is</b> rather than swept. It is dead — that
     * client's credential is qits-deployments' registry's now — but it costs a line, and the merge
     * keeps unknown keys by construction rather than by a rule somebody has to remember.
     */
    @Test
    void anOldMachinesRetiredClientSecretsAreLeftAlone() throws Exception {
        Path file = temp.resolve(BootstrapState.FILE_NAME);
        Files.writeString(file, """
                IDP_SECRET_PROD_QITS_CI=old-ci
                IDP_SECRET_PROD_QITS_BOOTSTRAP=old-bootstrap
                """);

        new BootstrapState(file).write("digest", "prod-qits-bootstrap", "kept");

        String text = Files.readString(file);
        assertThat(text).contains("IDP_SECRET_PROD_QITS_CI=old-ci");
        assertThat(text).contains("IDP_SECRET_PROD_QITS_BOOTSTRAP=kept");
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

        new BootstrapState(file).write("digest", "prod-qits-bootstrap", "one");

        BootstrapState afterSecrets = new BootstrapState(file);
        afterSecrets.read();
        assertThat(afterSecrets.value("PG_SUPERUSER_PASSWORD")).contains("aaaa1111bbbb2222");
        assertThat(afterSecrets.value("PG_DEPLOYMENTS_PASSWORD")).contains("cccc3333dddd4444");
        assertThat(afterSecrets.bootstrapSecret("prod-qits-bootstrap")).contains("one");

        // And the other way: a later postgres write keeps the digest and the secrets.
        BootstrapState again = new BootstrapState(file);
        again.read();
        again.put("PG_SUPERUSER_PASSWORD", "eeee5555ffff6666");
        again.write();

        BootstrapState last = new BootstrapState(file);
        last.read();
        assertThat(last.daemonSha()).contains("digest");
        assertThat(last.bootstrapSecret("prod-qits-bootstrap")).contains("one");
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

        new BootstrapState(file).write("digest", "prod-qits-bootstrap", "one");

        BootstrapState rerun = new BootstrapState(file);
        rerun.read();
        assertThat(rerun.registerToken()).contains("rt-0123456789");
        assertThat(rerun.bootstrapSecret("prod-qits-bootstrap")).contains("one");
    }

    @Test
    void aWrittenFileReadsBackTheSame() throws Exception {
        Path file = temp.resolve(BootstrapState.FILE_NAME);
        String client = PlatformModel.bootstrapClientId("prod");
        new BootstrapState(file).write("abc", client, "secret-" + client);

        BootstrapState reread = new BootstrapState(file);
        reread.read();

        assertThat(reread.daemonSha()).contains("abc");
        assertThat(reread.bootstrapSecret(client)).contains("secret-" + client);
    }
}
