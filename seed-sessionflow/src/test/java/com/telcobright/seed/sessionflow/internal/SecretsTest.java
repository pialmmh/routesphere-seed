package com.telcobright.seed.sessionflow.internal;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P1-O item 2 · A route's password at rest (the architect's A4): {@code {pbkdf2}<iterations>$<base64 salt>$<base64 hash>}
 * (PBKDF2WithHmacSHA256, a 16-byte salt, a 32-byte hash, 210 000 iterations by default) recomputed, or the legacy plaintext of the SMS rows
 * compared in constant time. The vectors are pinned: PBKDF2-HMAC-SHA256's published ones ("password" / "salt") and one of this form,
 * computed outside the JVM (Python's hashlib) with a non-ASCII password — the JDK must hash the password's UTF-8 bytes. A plain
 * {@code equals} in place of the constant-time compare cannot be told by a test: the reviewer reads {@code verifyLegacy} by eye.
 */
class SecretsTest {

    private static byte[] hashPart(String stored) { return Base64.getDecoder().decode(stored.substring(stored.lastIndexOf('$') + 1)); }

    @Test
    void the_published_pbkdf2_hmac_sha256_vectors_come_out_of_the_writers_form() {
        byte[] salt = "salt".getBytes(StandardCharsets.US_ASCII);
        assertThat(HexFormat.of().formatHex(hashPart(Secrets.hash("password".toCharArray(), salt, 1))))
            .isEqualTo("120fb6cffcf8b32c43e7225256c4f837a86548c92ccc35480805987cb70be17b");
        assertThat(HexFormat.of().formatHex(hashPart(Secrets.hash("password".toCharArray(), salt, 2))))
            .isEqualTo("ae4d0c95af6b46d32d0adff928f06dd02a303f8ef3c251dfd6e2d85a95474c43");
        assertThat(HexFormat.of().formatHex(hashPart(Secrets.hash("password".toCharArray(), salt, 4096))))
            .isEqualTo("c5e478d59288c841aa530db6845c4c8d962893a001ce4e11a4963873aa98134a");
        assertThat(Secrets.hash("password".toCharArray(), salt, 1)).startsWith("{pbkdf2}1$c2FsdA==$");
    }

    @Test
    void a_value_of_this_form_hashed_outside_the_jvm_verifies_and_nothing_else_does() {
        String pinned = "{pbkdf2}1000$AAECAwQFBgcICQoLDA0ODw==$SCZ5vXO5ZjDsP0X74CHQxBwaYg7tl3QRGXb9r5OQbsI=";
        byte[] salt = new byte[16];
        for (int i = 0; i < 16; i++) salt[i] = (byte) i;
        assertThat(Secrets.hash("pässwörd-1".toCharArray(), salt, 1000)).isEqualTo(pinned);
        assertThat(Secrets.verify("pässwörd-1", pinned)).isTrue();
        assertThat(Secrets.verify("passwörd-1", pinned)).isFalse();
        assertThat(Secrets.verify("", pinned)).isFalse();
        assertThat(Secrets.verify(null, pinned)).isFalse();
        assertThat(Secrets.verify(pinned, pinned)).as("the stored value is not the password").isFalse();
    }

    @Test
    void the_legacy_plaintext_is_compared_exactly_and_a_blank_or_broken_value_verifies_nothing() {
        assertThat(Secrets.verify("plain-pw", "plain-pw")).isTrue();
        assertThat(Secrets.verify("plain-pw ", "plain-pw")).isFalse();
        assertThat(Secrets.verify("Plain-pw", "plain-pw")).isFalse();
        assertThat(Secrets.verify("", "")).as("no password set: nothing verifies").isFalse();
        assertThat(Secrets.verify(" ", " ")).isFalse();
        assertThat(Secrets.verify("x", null)).isFalse();
        assertThat(Secrets.verify("{pbkdf2}1$c2FsdA==", "{pbkdf2}1$c2FsdA==")).as("a broken hashed value is never compared as plaintext").isFalse();
        assertThat(Secrets.verify("a", "{pbkdf2}x$c2FsdA==$Eg+2z/z4syxD5yJSVsT4N6hlSMkszDVICAWYfLcL4Xs=")).isFalse();
        assertThat(Secrets.verify("password", "{pbkdf2}0$c2FsdA==$Eg+2z/z4syxD5yJSVsT4N6hlSMkszDVICAWYfLcL4Xs=")).as("0 iterations").isFalse();
        assertThat(Secrets.verify("password", "{pbkdf2}99999999$c2FsdA==$Eg+2z/z4syxD5yJSVsT4N6hlSMkszDVICAWYfLcL4Xs=")).as("a corrupt count").isFalse();
        assertThat(Secrets.verify("password", "{pbkdf2}1$c2FsdA==$Eg+2z/z4syxD5yJSVsT4N6hlSMkszDVICAWYfLcL4Xs=")).as("the published vector, c=1").isTrue();
    }

    @Test
    void the_writers_hash_takes_a_fresh_salt_and_the_default_iterations() {
        long started = System.nanoTime();
        String once = Secrets.hash("route-pw".toCharArray());
        long hashedMs = (System.nanoTime() - started) / 1_000_000;
        String twice = Secrets.hash("route-pw".toCharArray());
        assertThat(once).startsWith("{pbkdf2}210000$").isNotEqualTo(twice);
        assertThat(Base64.getDecoder().decode(once.split("\\$")[1])).hasSize(Secrets.SALT_BYTES);
        assertThat(hashPart(once)).hasSize(Secrets.HASH_BYTES);
        started = System.nanoTime();
        assertThat(Secrets.verify("route-pw", once)).isTrue();
        long verifiedMs = (System.nanoTime() - started) / 1_000_000;
        System.out.println("P1-O · PBKDF2WithHmacSHA256 at " + Secrets.DEFAULT_ITERATIONS + " iterations on this box: hash " + hashedMs
            + " ms, verify " + verifiedMs + " ms (every request that authenticates on a hashed row pays the verify)");
    }
}
