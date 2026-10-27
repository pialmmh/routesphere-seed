package com.telcobright.seed.switchledger.spi;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;

/**
 * C2 · Who may use a root's cached ledger over the application's roads ({@code CachedLedgerRoads}). The application decides: it checks
 * the caller's token against its own secret — read from its secret store, never from the seed. A road asks this first; a caller it says
 * no to is answered 403 before any row is read.
 */
@FunctionalInterface
public interface LedgerGuard {

    /** May the caller holding {@code token} (null = none sent) use the cached ledger of {@code root}? */
    boolean allows(String token, String root);

    /**
     * A guard over ONE shared service token, kept as a salted SHA-256 (the house rule for a check on every request: a generated secret,
     * a fast hash, never a slow KDF): a caller is allowed when SHA-256(salt + token) equals {@code tokenSha256}, compared in constant
     * time; any root this application serves.
     */
    static LedgerGuard ofTokenHash(byte[] salt, byte[] tokenSha256) {
        byte[] s = Objects.requireNonNull(salt, "salt").clone(), wanted = Objects.requireNonNull(tokenSha256, "tokenSha256").clone();
        if (wanted.length != 32) throw new IllegalArgumentException("a SHA-256 is 32 bytes, not " + wanted.length);
        return (token, root) -> token != null && MessageDigest.isEqual(sha256(s, token), wanted);
    }

    /** SHA-256 of the salt, then the token's UTF-8 bytes. */
    static byte[] sha256(byte[] salt, String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(salt);
            return digest.digest(token.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("this JVM has no SHA-256", e);
        }
    }
}
