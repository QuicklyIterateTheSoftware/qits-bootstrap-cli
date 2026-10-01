package eu.wohlben.qits.cli.bootstrap.config;

import io.quarkus.runtime.configuration.DurationConverter;
import io.smallrye.config.EnvConfigSource;
import io.smallrye.config.SmallRyeConfig;
import io.smallrye.config.SmallRyeConfigBuilder;

import java.time.Duration;
import java.util.Map;

/**
 * The configuration a test sees, built the way the running program's is: environment names, and
 * Quarkus' own Duration converter so that a bare "3600" means an hour here too.
 */
public final class TestConfig {

    private TestConfig() {
    }

    public static BootstrapConfig from(Map<String, String> env) {
        return config(env).getConfigMapping(BootstrapConfig.class);
    }

    /**
     * The whole config rather than the mapping, with {@link BootstrapConfig} registered — so its
     * {@code @WithDefault} values are in it exactly as they are in the running program.
     */
    public static SmallRyeConfig config(Map<String, String> env) {
        return new SmallRyeConfigBuilder()
                .withMapping(BootstrapConfig.class)
                .withConverter(Duration.class, 200, new DurationConverter())
                .withSources(new EnvConfigSource(env, 300) {
                })
                .build();
    }
}
