package com.telcobright.seed.naming.api;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P1-G item 4 · the ONE naming rule as a table both repositories run (PC-0022 item 5, §9.4): {@code bootstrap/naming-cases.tsv} is
 * prime-context's {@code src/test/resources/bootstrap/naming-cases.tsv} at framework-bootstrap 8f8d922, copied BYTE-IDENTICAL (sha256
 * {@code 3dfb24f2414f593fd98b7372098f0dffb2c7885ae58109171878ed851e378fab}): 23 cases of {@code tierDb} · {@code parentOf} · {@code isTierOf},
 * with the seed root btcl and without one. prime-context's {@code NamingCasesTest} holds its {@code RootNaming} to the same file; this one
 * holds {@link TenantNames}. The rule is prime-context's: a case answered differently is reported, neither side changed alone.
 */
class NamingCasesTest {

    static final String CASES = "/bootstrap/naming-cases.tsv";
    static final String PINNED_SHA256 = "3dfb24f2414f593fd98b7372098f0dffb2c7885ae58109171878ed851e378fab";

    @Test
    void theTable_isPrimeContextsByteForByte() throws IOException, NoSuchAlgorithmException {
        assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytesOf(CASES)))).isEqualTo(PINNED_SHA256);
    }

    @Test
    void tenantNames_answersEveryCaseAsPrimeContextsRootNaming() throws IOException {
        List<String> cases = casesOf(new String(bytesOf(CASES), StandardCharsets.UTF_8));
        List<String> answeredOtherwise = new ArrayList<>();
        for (String line : cases) {
            String answer = answerOf(line.split("\t", -1));
            if (!answer.equals(line.split("\t", -1)[6])) answeredOtherwise.add(line.replace('\t', ' ') + " -> " + answer);
        }
        assertThat(cases).as("the table's cases").hasSize(23);
        assertThat(answeredOtherwise).as("cases TenantNames answers otherwise than prime-context's RootNaming").isEmpty();
    }

    /** One row: seedRoot · ask · root · parentDb · partnerId · tierDb · answer ("-" = none). */
    private static String answerOf(String[] row) {
        TenantNames names = TenantNames.withSeedRoot(none(row[0]));
        return switch (row[1]) {
            case "tierDb" -> names.tierDb(row[2], none(row[3]), Integer.parseInt(row[4]));
            case "parentOf" -> names.parentOf(row[2], row[5]).orElse("-");
            case "isTierOf" -> names.isTierOf(row[2], row[5]) ? "yes" : "no";
            default -> throw new IllegalStateException("an ask the table does not have: " + row[1]);
        };
    }

    private static List<String> casesOf(String table) {
        return table.lines().filter(line -> !line.isBlank() && !line.startsWith("#")).toList();
    }

    private static String none(String word) { return "-".equals(word) ? null : word; }

    private static byte[] bytesOf(String resource) throws IOException {
        try (InputStream in = NamingCasesTest.class.getResourceAsStream(resource)) {
            if (in == null) throw new IOException("no " + resource + " on the test classpath: prime-context's naming table is not copied here");
            return in.readAllBytes();
        }
    }
}
