package com.telcobright.seed.logtext;

import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.io.StringWriter;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P1-L item 1 · the ONE cleaner of what the seed logs from a request: every control character escaped (C0, DEL, C1, U+2028, U+2029), a
 * value at most 200 characters and a failure's text 1000, "…" when cut — never inside an escape or a character; a clean value as it is.
 */
class LogTextTest {

    @Test
    void aCleanValue_isGivenBackAsItIs() {
        String id = "ad-btcl-a4:5e:60:12:34:56-1729771200000-7";
        assertThat(LogText.clean(id)).isSameAs(id);
        assertThat(LogText.clean(null)).isEqualTo("null");
    }

    @Test
    void lineBreaksAndTabs_areWrittenAsTheirEscapes() {
        assertThat(LogText.clean("a\nb\rc\td")).isEqualTo("a\\nb\\rc\\td");
    }

    @Test
    void everyOtherControl_andTheLineAndParagraphSeparators_areWrittenAsTheirCodes() {
        assertThat(LogText.clean("x\u0000\u001b\u007f\u0085\u009b  y")).isEqualTo("x\\u0000\\u001b\\u007f\\u0085\\u009b\\u2028\\u2029y");
        assertThat(LogText.clean("ünïcødé ✓ 😀")).as("letters, symbols and emoji stay").isEqualTo("ünïcødé ✓ 😀");
    }

    @Test
    void aLongValue_isCutAt200_withAnEllipsis() {
        assertThat(LogText.clean("x".repeat(200))).isEqualTo("x".repeat(200));
        assertThat(LogText.clean("x".repeat(201))).isEqualTo("x".repeat(200) + "…");
        assertThat(LogText.clean("x".repeat(100_000))).hasSize(201);
    }

    @Test
    void anEscapeOrACharacter_isNeverCutInHalf() {
        assertThat(LogText.clean("x".repeat(198) + "\n")).isEqualTo("x".repeat(198) + "\\n");
        assertThat(LogText.clean("x".repeat(199) + "\n")).isEqualTo("x".repeat(199) + "…");
        assertThat(LogText.clean("x".repeat(195) + " ")).isEqualTo("x".repeat(195) + "…");
        assertThat(LogText.clean("x".repeat(199) + "😀")).as("never a lone surrogate").isEqualTo("x".repeat(199) + "…");
    }

    @Test
    void aFailuresText_isEscaped_andCutAt1000() {
        assertThat(LogText.failureText(new IllegalStateException("bad\nline"))).isEqualTo("java.lang.IllegalStateException: bad\\nline");
        assertThat(LogText.failureText("y".repeat(2000))).hasSize(LogText.FAILURE_CAP + 1).endsWith("…");
    }

    @Test
    void aFailureForTheStack_keepsItsStack_withEveryTextInItsChainCleaned() {
        IllegalStateException cause = new IllegalStateException("inner\nFORGED");
        RuntimeException failure = new RuntimeException("outer\r\nFORGED", cause);

        Throwable cleaned = LogText.cleaned(failure);
        StringWriter printed = new StringWriter();
        cleaned.printStackTrace(new PrintWriter(printed));

        assertThat(cleaned.getStackTrace()).isEqualTo(failure.getStackTrace());
        assertThat(printed.toString().lines()).noneMatch(l -> l.startsWith("FORGED"))
            .anyMatch(l -> l.equals("java.lang.RuntimeException: outer\\r\\nFORGED"))
            .anyMatch(l -> l.equals("Caused by: java.lang.IllegalStateException: inner\\nFORGED"));
        assertThat(LogText.cleaned(null)).isNull();
    }

    @Test
    void aFailureThatIsItsOwnCause_orAVeryLongChain_ends() {
        RuntimeException deep = new RuntimeException("0");
        for (int i = 1; i < 50; i++) deep = new RuntimeException(String.valueOf(i), deep);
        int depth = 0;
        for (Throwable t = LogText.cleaned(deep); t != null; t = t.getCause()) depth++;
        assertThat(depth).isEqualTo(9);
    }
}
