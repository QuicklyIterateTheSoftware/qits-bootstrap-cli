package eu.wohlben.qits.cli.bootstrap.workstation;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GitOriginTest {
    @Test
    void bindsCredentialToNormalizedHttpOrigin() {
        // User Git is pinned to the edge origin, which is where Basic oauth2 is authenticated.
        assertThat(GitOrigin.normalize("HTTP://LOCALHOST:8080/git/repo"))
                .isEqualTo("http://localhost:8080");
        assertThat(GitOrigin.fromGitRequest("https", "git.example.test"))
                .isEqualTo("https://git.example.test");
    }

    @Test
    void refusesNonHttpOrigins() {
        assertThatThrownBy(() -> GitOrigin.normalize("ssh://git.example.test/repo"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
