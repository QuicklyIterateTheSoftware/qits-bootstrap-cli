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
    void writesEveryClientEvenTheOnesPhaseOneDoesNotUse() throws Exception {
        Path file = temp.resolve(BootstrapState.FILE_NAME);
        Map<String, String> secrets = new LinkedHashMap<>();
        secrets.put("qits-ci", "one");
        secrets.put("qits-cd", "two");

        new BootstrapState(file).write("digest", secrets);

        String text = Files.readString(file);
        assertThat(text).contains("DAEMON_SHA=digest");
        assertThat(text).contains("IDP_SECRET_QITS_CI=one");
        assertThat(text).contains("IDP_SECRET_QITS_CD=two");
        for (String client : PlatformModel.IDP_CLIENTS) {
            assertThat(text).contains("IDP_SECRET_" + PlatformModel.clientKey(client) + "=");
        }
    }

    @Test
    void aWrittenFileReadsBackTheSame() throws Exception {
        Path file = temp.resolve(BootstrapState.FILE_NAME);
        Map<String, String> secrets = new LinkedHashMap<>();
        PlatformModel.IDP_CLIENTS.forEach(client -> secrets.put(client, "secret-" + client));
        new BootstrapState(file).write("abc", secrets);

        BootstrapState reread = new BootstrapState(file);
        reread.read();

        assertThat(reread.daemonSha()).contains("abc");
        for (String client : PlatformModel.IDP_CLIENTS) {
            assertThat(reread.secret(client)).contains("secret-" + client);
        }
    }
}
