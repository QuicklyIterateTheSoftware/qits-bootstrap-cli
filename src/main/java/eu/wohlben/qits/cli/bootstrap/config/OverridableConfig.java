package eu.wohlben.qits.cli.bootstrap.config;

import java.time.Duration;

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

    @Override
    public String wrapperDir() {
        return wrapperDir != null ? wrapperDir : base.wrapperDir();
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
        return base.envName();
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
}
