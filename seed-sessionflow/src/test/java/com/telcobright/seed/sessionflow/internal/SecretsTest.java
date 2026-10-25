package com.telcobright.seed.sessionflow.internal;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P1-O items 2 / 2b · A route's secret at rest (the architect's A4 as amended by A4′): a MACHINE secret's {@code {sha256}<salt>$<hash>}
 * checked with ONE SHA-256 and a constant-time compare — what every writer stores (the secret generated: 32 random bytes, base64url); the
 * {@code {pbkdf2}} form still verified for compatibility but written by nobody; the legacy plaintext of the SMS rows compared in constant time;
 * an unknown user name checked against a decoy (the same work as a wrong secret). The vectors are pinned, computed outside the JVM (Python's
 * hashlib), and PBKDF2-HMAC-SHA256's published ones. A plain {@code equals} in place of a constant-time compare cannot be told by a test:
 * the reviewer reads {@code verify} by eye. The cost of each check is measured and printed (the report carries it).
 */
class SecretsTest {

    private static byte[] hashPart(String stored) { return Base64.getDecoder().decode(stored.substring(stored.lastIndexOf('$') + 1)); }

    private static byte[] salt0to15() {
        byte[] salt = new byte[16];
        for (int i = 0; i < 16; i++) salt[i] = (byte) i;
        return salt;
    }

    @Test
    void a_machine_secrets_sha256_form_is_pinned_and_verifies_the_secret_and_nothing_else() {
        String pinned = "{sha256}AAECAwQFBgcICQoLDA0ODw==$uNjADXi9i2U9KdGFmwfEFB68CcW16yASwqAea+1Jyr4=";
        assertThat(Secrets.hashMachineSecret("machine-secret-1", salt0to15())).isEqualTo(pinned);
        assertThat(Secrets.verify("machine-secret-1", pinned)).isTrue();
        assertThat(Secrets.verify("machine-secret-2", pinned)).isFalse();
        assertThat(Secrets.verify("", pinned)).isFalse();
        assertThat(Secrets.verify(null, pinned)).isFalse();
        assertThat(Secrets.verify(pinned, pinned)).as("the stored value is not the secret").isFalse();
        String second = "{sha256}//79/Pv6+fj39vX08/Lx8A==$GjI/xo/pdTpPyB1gj0o3BunQxHBrrh3y4VrjOZ/19X0=";
        assertThat(Secrets.verify("Zx9_-k3Qm8vR2tY7uL0pN4wB6cE1hJ5gF8dS3aK7lM2", second)).as("a base64url secret, another salt").isTrue();
        assertThat(Secrets.verify("x", "{sha256}AAECAwQFBgcICQoLDA0ODw==")).as("no hash part").isFalse();
        assertThat(Secrets.verify("x", "{sha256}not base64$also not")).isFalse();
    }

    @Test
    void a_new_machine_secret_is_32_random_bytes_in_base64url_and_each_hash_takes_a_fresh_salt() {
        String secret = Secrets.newMachineSecret();
        assertThat(secret).hasSize(43).matches("[A-Za-z0-9_-]+");
        assertThat(Base64.getUrlDecoder().decode(secret)).hasSize(Secrets.MACHINE_SECRET_BYTES);
        assertThat(Secrets.newMachineSecret()).isNotEqualTo(secret);
        String once = Secrets.hashMachineSecret(secret), twice = Secrets.hashMachineSecret(secret);
        assertThat(once).startsWith("{sha256}").isNotEqualTo(twice);
        assertThat(Base64.getDecoder().decode(once.substring(8, once.indexOf('$')))).hasSize(Secrets.SALT_BYTES);
        assertThat(hashPart(once)).hasSize(32);
        assertThat(Secrets.verify(secret, once)).isTrue();
        assertThat(Secrets.verify(secret, twice)).isTrue();
    }

    @Test
    void an_unknown_user_is_checked_against_a_decoy_and_counted() {
        long before = Secrets.decoyCompares();
        assertThat(Secrets.compareWithNobody("whatever")).isFalse();
        assertThat(Secrets.compareWithNobody(null)).isFalse();
        assertThat(Secrets.decoyCompares()).isEqualTo(before + 2);
    }

    @Test
    void the_compatibility_pbkdf2_form_still_verifies_its_published_and_pinned_vectors() {
        byte[] salt = "salt".getBytes(StandardCharsets.US_ASCII);
        assertThat(HexFormat.of().formatHex(hashPart(Secrets.pbkdf2("password".toCharArray(), salt, 1))))
            .isEqualTo("120fb6cffcf8b32c43e7225256c4f837a86548c92ccc35480805987cb70be17b");
        assertThat(HexFormat.of().formatHex(hashPart(Secrets.pbkdf2("password".toCharArray(), salt, 2))))
            .isEqualTo("ae4d0c95af6b46d32d0adff928f06dd02a303f8ef3c251dfd6e2d85a95474c43");
        assertThat(HexFormat.of().formatHex(hashPart(Secrets.pbkdf2("password".toCharArray(), salt, 4096))))
            .isEqualTo("c5e478d59288c841aa530db6845c4c8d962893a001ce4e11a4963873aa98134a");
        String pinned = "{pbkdf2}1000$AAECAwQFBgcICQoLDA0ODw==$SCZ5vXO5ZjDsP0X74CHQxBwaYg7tl3QRGXb9r5OQbsI=";
        assertThat(Secrets.pbkdf2("pässwörd-1".toCharArray(), salt0to15(), 1000)).as("the JDK hashes the password's UTF-8 bytes").isEqualTo(pinned);
        assertThat(Secrets.verify("pässwörd-1", pinned)).isTrue();
        assertThat(Secrets.verify("passwörd-1", pinned)).isFalse();
        assertThat(Secrets.verify("password", "{pbkdf2}1$c2FsdA==$Eg+2z/z4syxD5yJSVsT4N6hlSMkszDVICAWYfLcL4Xs=")).as("the published vector, c=1").isTrue();
        assertThat(Secrets.verify("password", "{pbkdf2}0$c2FsdA==$Eg+2z/z4syxD5yJSVsT4N6hlSMkszDVICAWYfLcL4Xs=")).as("0 iterations").isFalse();
        assertThat(Secrets.verify("password", "{pbkdf2}99999999$c2FsdA==$Eg+2z/z4syxD5yJSVsT4N6hlSMkszDVICAWYfLcL4Xs=")).as("a corrupt count").isFalse();
        assertThat(Secrets.verify("a", "{pbkdf2}x$c2FsdA==$Eg+2z/z4syxD5yJSVsT4N6hlSMkszDVICAWYfLcL4Xs=")).isFalse();
    }

    @Test
    void the_legacy_plaintext_is_compared_exactly_and_a_blank_value_verifies_nothing() {
        assertThat(Secrets.verify("plain-pw", "plain-pw")).isTrue();
        assertThat(Secrets.verify("plain-pw ", "plain-pw")).isFalse();
        assertThat(Secrets.verify("Plain-pw", "plain-pw")).isFalse();
        assertThat(Secrets.verify("", "")).as("no secret set: nothing verifies").isFalse();
        assertThat(Secrets.verify(" ", " ")).isFalse();
        assertThat(Secrets.verify("x", null)).isFalse();
        assertThat(Secrets.verify("{sha256}x", "{sha256}x")).as("a broken marked value is never compared as plaintext").isFalse();
    }

    /** The cost of one check, p50 and p99 over many (after a warm-up): microseconds for a machine secret, ~0.1 s for the old KDF. */
    @Test
    void the_cost_of_a_check_is_measured() {
        String secret = Secrets.newMachineSecret(), stored = Secrets.hashMachineSecret(secret);
        String sha256 = percentiles(() -> Secrets.verify(secret, stored), 20_000);
        String wrong = percentiles(() -> Secrets.verify("wrong-secret", stored), 20_000);
        String decoy = percentiles(() -> Secrets.compareWithNobody("no-such-user's-secret"), 20_000);
        String legacy = percentiles(() -> Secrets.verify("plain-pw", "plain-pw"), 20_000);
        String pbkdf2Stored = Secrets.pbkdf2("route-pw".toCharArray(), new byte[16], 210_000);
        long started = System.nanoTime();
        assertThat(Secrets.verify("route-pw", pbkdf2Stored)).isTrue();
        long pbkdf2Ms = (System.nanoTime() - started) / 1_000_000;
        System.out.println("P1-O · the cost of one check on this box (p50 / p99, microseconds): {sha256} right " + sha256 + ", wrong " + wrong
            + ", the unknown user's decoy " + decoy + ", legacy plaintext " + legacy + "; {pbkdf2} at 210 000 iterations (compatibility only) "
            + pbkdf2Ms + " ms per check");
        assertThat(Secrets.verify(secret, stored)).isTrue();
    }

    private static String percentiles(Supplier<Boolean> check, int times) {
        for (int warm = 0; warm < 5_000; warm++) check.get();
        long[] nanos = new long[times];
        for (int i = 0; i < times; i++) {
            long started = System.nanoTime();
            check.get();
            nanos[i] = System.nanoTime() - started;
        }
        Arrays.sort(nanos);
        return String.format("%.1f / %.1f", nanos[times / 2] / 1000.0, nanos[times * 99 / 100] / 1000.0);
    }
}
