package eu.wohlben.qits.cli.bootstrap.workstation;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/** PKCE values are deliberately generated per login and never persisted. */
public record Pkce(String verifier, String challenge) {
    private static final SecureRandom RANDOM = new SecureRandom();

    public static Pkce create() {
        byte[] bytes = new byte[48];
        RANDOM.nextBytes(bytes);
        String verifier = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            return new Pkce(verifier, Base64.getUrlEncoder().withoutPadding().encodeToString(digest));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the JDK", impossible);
        }
    }

    public static String state() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
