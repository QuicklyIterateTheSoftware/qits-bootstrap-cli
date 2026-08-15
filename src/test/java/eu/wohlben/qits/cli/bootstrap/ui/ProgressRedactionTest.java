package eu.wohlben.qits.cli.bootstrap.ui;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ProgressRedactionTest {

    @Test
    void removesNamedAndAuthorizationCredentialValuesBeforePublication() {
        String value = ProgressRedaction.redact("token=top-secret password: hunter2 Bearer abc.def.ghi");

        assertThat(value).contains("token=***", "password:***", "Bearer ***")
                .doesNotContain("top-secret", "hunter2", "abc.def.ghi");
    }

    @Test
    void stateSnapshotsNeverContainCredentialLikeOutput() {
        BootState state = new BootState(50);
        state.output("secret=do-not-publish Authorization: bearer-value");
        state.event("password: another-value");

        assertThat(state.snapshotJson()).contains("secret=***", "Authorization:***", "password:***")
                .doesNotContain("do-not-publish", "bearer-value", "another-value");
    }
}
