package eu.wohlben.qits.cli.bootstrap.platform;

import java.nio.file.Path;

/**
 * <b>The estate as {@code src/test/resources/estate/} records it</b> — the wrapper's repositories'
 * declarations, copied by that directory's {@code refresh.sh}. Every test that needs to know what
 * the platform is made of reads it here, derived the way the boot derives it, rather than from a
 * list of its own.
 */
public final class EstateFixture {

    /** The fixture directory, relative to the module root surefire runs in. */
    public static final Path DIR = Path.of("src/test/resources/estate");

    private static Estate estate;

    private EstateFixture() {
    }

    public static CheckoutProjectSource.Checkouts checkouts() {
        return CheckoutProjectSource.Checkouts.fixture(DIR);
    }

    /** Derived once per test JVM: the fixture does not change under a run. */
    public static synchronized Estate estate() {
        if (estate == null) {
            estate = Estate.derive(checkouts());
        }
        return estate;
    }
}
