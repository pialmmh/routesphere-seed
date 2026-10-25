package com.telcobright.seed.sessionflow.internal;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.concurrent.atomic.LongAdder;

/**
 * A route's secret at rest and its check (P1-O; the architect's A4 as amended by A4′) — three forms, one verifier, no new dependency:
 *
 * <pre>
 *   {sha256}&lt;base64 salt&gt;$&lt;base64 SHA-256(salt ‖ UTF-8 secret)&gt;    a MACHINE secret the system generated (16-byte salt): ONE SHA-256
 *                                                               and a constant-time compare per check — what every writer stores
 *                                                               ({@link #newMachineSecret} → {@link #hashMachineSecret})
 *   {pbkdf2}&lt;iterations&gt;$&lt;base64 salt&gt;$&lt;base64 hash&gt;              still VERIFIED (compatibility), written by NOTHING: a slow KDF must
 *                                                               not guard a credential checked on every request — ~0.1 s of CPU per
 *                                                               check, and a CPU amplifier for anyone sending wrong secrets
 *   anything else                                               the LEGACY plaintext of the SMS rows ({@code route_metadata.extra2}),
 *                                                               compared in constant time on its UTF-8 bytes
 * </pre>
 *
 * A route's credentials are machine secrets (32 random bytes), never a human's password: a fast salted hash is enough, and the check costs
 * microseconds. An unknown user name is checked against a decoy ({@link #compareWithNobody}) — the same one SHA-256 a wrong secret costs, so
 * "no such user" and "wrong secret" do equal work. A stored value that is blank, or a marked value that does not parse, verifies nothing
 * (fail closed). Nothing here logs, and no exception it lets out carries a secret.
 */
public final class Secrets {

    /** The mark of a machine secret's form. */
    public static final String SHA256 = "{sha256}";
    /** The mark of the compatibility form (verified, never written). */
    public static final String PBKDF2 = "{pbkdf2}";
    public static final int SALT_BYTES = 16;
    public static final int MACHINE_SECRET_BYTES = 32;
    /** A {@code {pbkdf2}} value whose iteration count is outside 1 … this verifies nothing (a corrupt row must not cost a minute of CPU). */
    static final int MAX_ITERATIONS = 5_000_000;

    private static final Base64.Encoder B64 = Base64.getEncoder();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String DECOY = hashMachineSecret(newMachineSecret());
    private static final LongAdder DECOY_COMPARES = new LongAdder();

    private Secrets() {}

    // ── the writers' side ─────────────────────────────────────────────────

    /** A new machine secret: 32 bytes from SecureRandom, base64url without padding (43 characters). Returned once; never logged. */
    public static String newMachineSecret() {
        byte[] secret = new byte[MACHINE_SECRET_BYTES];
        RANDOM.nextBytes(secret);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
    }

    /** The {@code {sha256}} form of a machine secret, with a fresh 16-byte salt: what a route writer stores in {@code extra2}. */
    public static String hashMachineSecret(String secret) {
        byte[] salt = new byte[SALT_BYTES];
        RANDOM.nextBytes(salt);
        return hashMachineSecret(secret, salt);
    }

    /** The same with a given salt (the pinned test vector). */
    static String hashMachineSecret(String secret, byte[] salt) {
        return SHA256 + B64.encodeToString(salt) + "$" + B64.encodeToString(sha256(salt, secret));
    }

    // ── the check ─────────────────────────────────────────────────────────

    /** True when {@code presented} is the secret {@code stored} keeps — by its form's own check (see the class's table). */
    public static boolean verify(String presented, String stored) {
        if (presented == null || stored == null || stored.isBlank()) return false;
        if (stored.startsWith(SHA256)) return verifySha256(presented, stored);
        if (stored.startsWith(PBKDF2)) return verifyPbkdf2(presented, stored);
        return MessageDigest.isEqual(presented.getBytes(StandardCharsets.UTF_8), stored.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * An unknown user name: the one SHA-256 and compare a wrong machine secret costs, against a decoy — so "no such user" and "wrong secret"
     * do equal work. Always false; counted ({@link #decoyCompares}).
     */
    public static boolean compareWithNobody(String presented) {
        DECOY_COMPARES.increment();
        verifySha256(presented == null ? "" : presented, DECOY);
        return false;
    }

    /** How many unknown user names were checked against the decoy since start (the operations' view; a test's). */
    public static long decoyCompares() { return DECOY_COMPARES.sum(); }

    private static boolean verifySha256(String presented, String stored) {
        String[] parts = stored.substring(SHA256.length()).split("\\$", -1);
        if (parts.length != 2) return false;
        try {
            byte[] salt = Base64.getDecoder().decode(parts[0]);
            byte[] hash = Base64.getDecoder().decode(parts[1]);
            return salt.length > 0 && hash.length > 0 && MessageDigest.isEqual(sha256(salt, presented), hash);
        } catch (IllegalArgumentException notTheForm) {
            return false;
        }
    }

    private static byte[] sha256(byte[] salt, String secret) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(salt);
            return digest.digest(secret.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("this JVM has no SHA-256");
        }
    }

    // ── the compatibility form: verified, written by nobody ────────────────

    private static boolean verifyPbkdf2(String presented, String stored) {
        Pbkdf2 hashed = Pbkdf2.parse(stored);
        if (hashed == null) return false;
        char[] password = presented.toCharArray();
        try {
            return MessageDigest.isEqual(derive(password, hashed.salt, hashed.iterations, hashed.hash.length), hashed.hash);
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    /** The {@code {pbkdf2}} form — for the compatibility tests' vectors only: no writer stores it (A4′). */
    static String pbkdf2(char[] password, byte[] salt, int iterations) {
        return PBKDF2 + iterations + "$" + B64.encodeToString(salt) + "$" + B64.encodeToString(derive(password, salt, iterations, 32));
    }

    private static byte[] derive(char[] password, byte[] salt, int iterations, int hashBytes) {
        PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, hashBytes * 8);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("this JVM has no PBKDF2WithHmacSHA256 (" + e.getClass().getSimpleName() + ")");
        } finally {
            spec.clearPassword();
        }
    }

    /** {@code {pbkdf2}<iterations>$<salt>$<hash>} taken apart; null = not that form, or a part that does not parse. */
    private record Pbkdf2(int iterations, byte[] salt, byte[] hash) {
        static Pbkdf2 parse(String stored) {
            String[] parts = stored.substring(PBKDF2.length()).split("\\$", -1);
            if (parts.length != 3) return null;
            try {
                int iterations = Integer.parseInt(parts[0]);
                byte[] salt = Base64.getDecoder().decode(parts[1]);
                byte[] hash = Base64.getDecoder().decode(parts[2]);
                if (iterations < 1 || iterations > MAX_ITERATIONS || salt.length == 0 || hash.length == 0 || hash.length > 64) return null;
                return new Pbkdf2(iterations, salt, hash);
            } catch (IllegalArgumentException notTheForm) {
                return null;
            }
        }
    }
}
