package dev.nezz.rentguard.ledger;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** SHA-256 helpers. MessageDigest is not thread-safe, so callers that hash in a loop keep their own instance. */
public final class Hashes {

    private static final HexFormat HEX = HexFormat.of();

    private Hashes() {
    }

    public static MessageDigest newSha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every Java runtime", e);
        }
    }

    public static byte[] sha256(byte[] data) {
        return newSha256().digest(data);
    }

    public static String sha256Hex(String text) {
        return hex(sha256(text.getBytes(StandardCharsets.UTF_8)));
    }

    public static String hex(byte[] bytes) {
        return HEX.formatHex(bytes);
    }

    public static byte[] unhex(String hex) {
        return HEX.parseHex(hex);
    }

}
