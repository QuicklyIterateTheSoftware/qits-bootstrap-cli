package eu.wohlben.qits.cli.bootstrap.config;

import java.time.Duration;
import java.util.Optional;

/**
 * The configured values with a few command-line answers on top. Only the knobs a person changes
 * per invocation are overridable; everything else belongs in {@code .env}, where it is recorded
 * rather than retyped.
 */
public class OverridableConfig implements BootstrapConfig {

    private final BootstrapConfig base;
    private String wrapperDir;
    private Boolean skipBuild;
    private Boolean tui;
    private String platformEnv;

    public OverridableConfig(BootstrapConfig base) {
        this.base = base;
    }

    public OverridableConfig wrapperDir(String value) {
        this.wrapperDir = value;
        return this;
    }

    public OverridableConfig skipBuild(Boolean value) {
        this.skipBuild = value;
        return this;
    }

    public OverridableConfig tui(Boolean value) {
        this.tui = value;
        return this;
    }

    /** {@code --platform-env}; blank is no answer, so {@code .env} keeps it. */
    public OverridableConfig platformEnv(String value) {
        this.platformEnv = value == null || value.isBlank() ? null : value.strip();
        return this;
    }

    @Override
    public Optional<String> wrapperDir() {
        return wrapperDir != null ? Optional.of(wrapperDir) : base.wrapperDir();
    }

    @Override
    public boolean skipBuild() {
        return skipBuild != null ? skipBuild : base.skipBuild();
    }

    @Override
    public boolean tui() {
        return tui != null ? tui : base.tui();
    }

    @Override
    public String orgUrl() {
        return base.orgUrl();
    }

    @Override
    public String src() {
        return base.src();
    }

    @Override
    public int port() {
        return base.port();
    }

    @Override
    public int registryPort() {
        return base.registryPort();
    }

    @Override
    public Duration deployTimeout() {
        return base.deployTimeout();
    }

    @Override
    public Duration releaseTimeout() {
        return base.releaseTimeout();
    }

    @Override
    public Duration healthTimeout() {
        return base.healthTimeout();
    }

    @Override
    public Duration pollInterval() {
        return base.pollInterval();
    }

    @Override
    public String pushToken() {
        return base.pushToken();
    }

    @Override
    public boolean machineAuth() {
        return base.machineAuth();
    }

    @Override
    public String envName() {
        return platformEnv != null ? platformEnv : base.envName();
    }

    @Override
    public String curlImage() {
        return base.curlImage();
    }

    @Override
    public String logFile() {
        return base.logFile();
    }

    @Override
    public int tailLines() {
        return base.tailLines();
    }

    @Override
    public boolean web() {
        return base.web();
    }

    @Override
    public int webPort() {
        return base.webPort();
    }

    @Override
    public String webHost() {
        return base.webHost();
    }
}
