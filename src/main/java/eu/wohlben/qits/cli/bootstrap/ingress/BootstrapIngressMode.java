package eu.wohlben.qits.cli.bootstrap.ingress;

/**
 * <b>Where the disposable ingress listens, decided once per run and read everywhere.</b>
 * <p>
 * Three answers, and the third one exists because a cold host has no certificate. Public mode was
 * written for a RE-bootstrap: the machine keeps {@code qits-edge-letsencrypt}, the pair from the
 * last run is on it, and the ingress serves the domain over TLS on the same URL the real edge takes
 * over afterwards. On a machine that has never run this platform that volume is empty, the ingress
 * dies at startup on {@code NoSuchFileException: /cert/lets-encrypt.key}, nothing holds the host's
 * ports — and every seed image build, which resolves the qits jars through the address below, fails
 * with "Connection refused" seconds later. That is where the 2026-09-05 cold boot of a fresh Debian
 * host died, at the first seed image.
 * <p>
 * <b>So the mode is decided by what is on the volume rather than by configuration.</b> A certificate
 * pair present means TLS, exactly as before; an empty volume means the same public door on port 80
 * with no TLS at all. Nothing else changes: the Host header a port-80 request carries is the domain,
 * which is what {@link BootstrapIngressPolicy} checks, and every repository's
 * {@code .qits-maven-settings.xml} mirrors the {@code qits-maven} id to
 * {@code ${env.QITS_MAVEN_REPOSITORY_URL}} by exact id, which beats Maven's {@code external:http:*}
 * blocker — so plain HTTP on the domain resolves. The placeholder self-signed certificate is written
 * forty phases later and would not help Maven anyway.
 */
public enum BootstrapIngressMode {

    /** Published on loopback only: no domain, or a domain node told to stay private. */
    LOOPBACK,

    /** The domain on port 80, no TLS — a machine whose certificate volume is empty. */
    PUBLIC_HTTP,

    /** The domain on 80 and 443, serving the certificate pair the last run left behind. */
    PUBLIC_TLS;

    /** Does this mode bind the host's port 80 and answer to the domain's Host header? */
    public boolean isPublic() {
        return this != LOOPBACK;
    }

    /** The scheme every address of this mode carries. */
    public String scheme() {
        return this == PUBLIC_TLS ? "https" : "http";
    }

    /** What a recorded mode means, tolerating a state file written by an older run. */
    public static BootstrapIngressMode of(String recorded, BootstrapIngressMode fallback) {
        if (recorded == null || recorded.isBlank()) {
            return fallback;
        }
        try {
            return valueOf(recorded.strip());
        } catch (IllegalArgumentException unknown) {
            return fallback;
        }
    }
}
