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
    private Boolean shipMains;
    private Boolean tui;
    private String platformEnv;
    private String domain;
    private String publicIp;
    private String acmeMode;
    private String acmeEmail;

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

    /** {@code --ship-mains}; unset is no answer, so {@code .env} keeps it. */
    public OverridableConfig shipMains(Boolean value) {
        this.shipMains = value;
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

    /** {@code --domain}; blank is no answer, so {@code .env} keeps it. */
    public OverridableConfig domain(String value) {
        this.domain = value == null || value.isBlank() ? null : value.strip();
        return this;
    }

    /** {@code --public-ip}; blank is no answer, so {@code .env} keeps it. */
    public OverridableConfig publicIp(String value) {
        this.publicIp = value == null || value.isBlank() ? null : value.strip();
        return this;
    }

    /** {@code --acme-mode}; blank is no answer, so {@code .env} keeps it. */
    public OverridableConfig acmeMode(String value) {
        this.acmeMode = value == null || value.isBlank() ? null : value.strip();
        return this;
    }

    /** {@code --acme-email}; blank is no answer, so {@code .env} keeps it. */
    public OverridableConfig acmeEmail(String value) {
        this.acmeEmail = value == null || value.isBlank() ? null : value.strip();
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
    public boolean shipMains() {
        return shipMains != null ? shipMains : base.shipMains();
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

    /** The mirror's host door: everything third-party, cached. */
    @Override
    public int mirrorPort() {
        return base.mirrorPort();
    }

    /** The githost's loopback diagnostic port; user Git goes through the edge. */
    @Override
    public int gitHostPort() {
        return base.gitHostPort();
    }

    @Override
    public Optional<String> domain() {
        return domain != null ? Optional.of(domain) : base.domain();
    }

    @Override
    public Optional<String> publicIp() {
        return publicIp != null ? Optional.of(publicIp) : base.publicIp();
    }

    @Override
    public String acmeMode() {
        return acmeMode != null ? acmeMode : base.acmeMode();
    }

    @Override
    public Optional<String> acmeEmail() {
        return acmeEmail != null ? Optional.of(acmeEmail) : base.acmeEmail();
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
    public String logFile() {
        return base.logFile();
    }

    @Override
    public boolean inContainer() {
        return base.inContainer();
    }

    @Override
    public int tailLines() {
        return base.tailLines();
    }

    @Override
    public boolean eventsFeed() {
        return base.eventsFeed();
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
