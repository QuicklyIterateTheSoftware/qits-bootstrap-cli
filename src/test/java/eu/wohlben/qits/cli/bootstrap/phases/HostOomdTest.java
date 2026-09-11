package eu.wohlben.qits.cli.bootstrap.phases;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The script runs on the host and cannot be run here, so what is tested is what it SAYS it writes
 * and how its verdict is read — the two halves a typo would otherwise take to a real machine.
 */
class HostOomdTest {

    private static final String SCRIPT = HostOomd.script();

    @Test
    void itWritesBothSliceRulesAndTheOmitListTheTicketNamed() {
        assertThat(SCRIPT)
                .contains("/etc/systemd/system/system.slice.d/50-qits-oomd.conf")
                .contains("ManagedOOMMemoryPressure=kill")
                .contains("ManagedOOMMemoryPressureLimit=50%")
                .contains("/etc/systemd/system/-.slice.d/50-qits-oomd.conf")
                .contains("ManagedOOMSwap=kill")
                .contains("ManagedOOMPreference=omit");
        // containerd is the load-bearing one: every container's shim lives in its cgroup.
        assertThat(HostOomd.OMIT_UNITS).contains("containerd.service", "docker.service");
        assertThat(SCRIPT).contains(String.join(" ", HostOomd.OMIT_UNITS));
    }

    @Test
    void itLeavesAnyExistingSwapAndAnyExistingSwapfileAlone() {
        assertThat(SCRIPT)
                .contains("swap already on")
                .contains("/swapfile exists and is not on — left alone")
                // Never taken without room to spare for it.
                .contains("(8 + 8) * 1024 * 1024");
    }

    @Test
    void itAppendsSwappinessRatherThanOwningTheHostsFile() {
        // 99-qits.conf is the host's file and may hold settings this program knows nothing about.
        assertThat(SCRIPT).contains("echo 'vm.swappiness=20' >> /etc/sysctl.d/99-qits.conf");
        assertThat(SCRIPT).contains("grep -qs '^vm.swappiness' /etc/sysctl.d/99-qits.conf");
    }

    /**
     * The script is built by string substitution and run once, on a host, an hour of boot away from
     * anybody watching. {@code sh -n} is the cheapest thing that reads it the way that host will.
     */
    @Test
    void theScriptParsesAsPosixShell() throws Exception {
        Path file = Files.createTempFile("qits-oom", ".sh");
        Files.writeString(file, SCRIPT);
        Process sh = new ProcessBuilder("/bin/sh", "-n", file.toString())
                .redirectErrorStream(true).start();
        String output = new String(sh.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(sh.waitFor()).describedAs(output).isZero();
        Files.deleteIfExists(file);
    }

    @Test
    void aHostWithNoSystemdIsSaidOnceAndLeftAlone() {
        HostOomd.State state = HostOomd.read(List.of("qits-oom: systemd=absent"));

        assertThat(state.systemdless()).isTrue();
        assertThat(state.complete()).isFalse();
        assertThat(state.describe()).isEqualTo("no systemd — nothing to configure");
    }

    @Test
    void theVerdictIsReadOffTheHostsOwnAnswer() {
        HostOomd.State state = HostOomd.read(List.of(
                "qits-oom: wrote /etc/systemd/system/system.slice.d/50-qits-oomd.conf",
                "qits-oom: state oomd=active pressure=kill limit=50.00% swap-kill=kill "
                        + "swap=/swapfile"));

        assertThat(state.complete()).isTrue();
        assertThat(state.swap()).isEqualTo("/swapfile");
        assertThat(state.describe())
                .isEqualTo("oomd active, system.slice kill at 50.00%, swap /swapfile");
    }

    @Test
    void oomdRunningWithNoSwapIsNotCovered() {
        HostOomd.State state = HostOomd.read(List.of(
                "qits-oom: state oomd=active pressure=kill limit=50.00% swap-kill=kill swap="));

        assertThat(state.complete()).isFalse();
        assertThat(state.describe()).endsWith("swap none");
    }

    @Test
    void theLastVerdictWins() {
        HostOomd.State state = HostOomd.read(List.of(
                "qits-oom: state oomd=inactive pressure= limit= swap-kill= swap=",
                "qits-oom: state oomd=active pressure=kill limit=50.00% swap-kill=kill "
                        + "swap=/swapfile"));

        assertThat(state.oomd()).isEqualTo("active");
    }
}
