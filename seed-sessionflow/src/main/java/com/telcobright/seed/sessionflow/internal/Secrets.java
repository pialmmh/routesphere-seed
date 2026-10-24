package com.telcobright.seed.sessionflow.internal;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * A route's password at rest and its check (P1-O, the architect's A4) — two forms, one verifier, no new dependency:
 *
 * <pre>
 *   {pbkdf2}&lt;iterations&gt;$&lt;base64 salt&gt;$&lt;base64 hash&gt;     PBKDF2WithHmacSHA256, a 16-byte salt, a 32-byte hash (210 000 iterations by
 *                                                     default): what a route writer stores ({@link #hash}) — the ad lane's officer road
 *   anything else                                     the LEGACY form of the SMS lane's {@code route_metadata.extra2}: the password in
 *                                                     plaintext, compared in constant time on its UTF-8 bytes
 * </pre>
 *
 * A stored value that is blank, or a {@code {pbkdf2}} value that does not parse, verifies nothing (fail closed). Neither form is ever
 * printed: this class logs nothing, and no exception it lets out carries a password.
 */
public final class Secrets {

    /** The mark of the hashed form. */
    public static final String PBKDF2 = "{pbkdf2}";
    public static final int DEFAULT_ITERATIONS = 210_000;
    public static final int SALT_BYTES = 16;
    public static final int HASH_BYTES = 32;
    /** A hashed value whose iteration count is outside 1 … this verifies nothing (a corrupt row must not cost a minute of CPU). */
    static final int MAX_ITERATIONS = 5_000_000;

    private static final String ALGORITHM = "PBKDF2WithHmacSHA256";
    private static final SecureRandom RANDOM = new SecureRandom();

    private Secrets() {}

    /** The hashed form of {@code password} for a writer to store: a fresh random salt, the default iterations. The array is not kept. */
    public static String hash(char[] password) {
        byte[] salt = new byte[SALT_BYTES];
        RANDOM.nextBytes(salt);
        return hash(password, salt, DEFAULT_ITERATIONS);
    }

    /** The hashed form with a given salt and iteration count (the pinned test vector; a writer uses {@link #hash(char[])}). */
    public static String hash(char[] password, byte[] salt, int iterations) {
        byte[] derived = derive(password, salt, iterations, HASH_BYTES);
        return PBKDF2 + iterations + "$" + Base64.getEncoder().encodeToString(salt) + "$" + Base64.getEncoder().encodeToString(derived);
    }

    /** True when {@code presented} is the password {@code stored} keeps — the hashed form recomputed, the legacy form compared in constant time. */
    public static boolean verify(String presented, String stored) {
        if (presented == null || stored == null || stored.isBlank()) return false;
        return stored.startsWith(PBKDF2) ? verifyHashed(presented, stored) : verifyLegacy(presented, stored);
    }

    private static boolean verifyLegacy(String presented, String stored) {
        return MessageDigest.isEqual(presented.getBytes(StandardCharsets.UTF_8), stored.getBytes(StandardCharsets.UTF_8));
    }

    private static boolean verifyHashed(String presented, String stored) {
        Hashed hashed = Hashed.parse(stored);
        if (hashed == null) return false;
        char[] password = presented.toCharArray();
        try {
            return MessageDigest.isEqual(derive(password, hashed.salt, hashed.iterations, hashed.hash.length), hashed.hash);
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    private static byte[] derive(char[] password, byte[] salt, int iterations, int hashBytes) {
        PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, hashBytes * 8);
        try {
            return SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("this JVM has no " + ALGORITHM + " (" + e.getClass().getSimpleName() + ")");
        } finally {
            spec.clearPassword();
        }
    }

    /** {@code {pbkdf2}<iterations>$<salt>$<hash>} taken apart; null = not that form, or a part that does not parse. */
    private record Hashed(int iterations, byte[] salt, byte[] hash) {
        static Hashed parse(String stored) {
            String[] parts = stored.substring(PBKDF2.length()).split("\\$", -1);
            if (parts.length != 3) return null;
            try {
                int iterations = Integer.parseInt(parts[0]);
                byte[] salt = Base64.getDecoder().decode(parts[1]);
                byte[] hash = Base64.getDecoder().decode(parts[2]);
                if (iterations < 1 || iterations > MAX_ITERATIONS || salt.length == 0 || hash.length == 0 || hash.length > 64) return null;
                return new Hashed(iterations, salt, hash);
            } catch (IllegalArgumentException notTheForm) {
                return null;
            }
        }
    }
}
