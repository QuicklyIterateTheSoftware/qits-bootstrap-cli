package eu.wohlben.qits.cli.bootstrap.ingress;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Shared only to keep the seed's stored fingerprint format explicit and testable. */
public final class BootstrapIngressHash {
    private BootstrapIngressHash() {}

    public static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
