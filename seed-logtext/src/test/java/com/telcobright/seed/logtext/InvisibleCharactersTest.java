package com.telcobright.seed.logtext;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P1-M item 3 · what a terminal would reorder or hide is escaped as {@code \\uXXXX}: the WHOLE Unicode format category (Cf) — the bidi
 * controls, the marks, the zero-width characters, and the rest of Cf (the soft hyphen, the invisible operators, the interlinear
 * annotations, the TAG characters that can carry a hidden text); a character above U+FFFF as its two UTF-16 escapes. Letters, marks of
 * a script, symbols and emoji stay as they are.
 */
class InvisibleCharactersTest {

    @Test
    void theBidiControls_theMarks_andTheZeroWidthCharacters_areEscaped() {
        int[] named = {0x202A, 0x202B, 0x202C, 0x202D, 0x202E, 0x2066, 0x2067, 0x2068, 0x2069, 0x200E, 0x200F, 0x061C, 0x200B, 0x200C, 0x200D, 0x2060, 0xFEFF};
        for (int c : named) {
            assertThat(LogText.clean("a" + Character.toString(c) + "b")).as("U+%04X", c).isEqualTo(String.format("a\\u%04xb", c));
        }
    }

    @Test
    void theRestOfTheFormatCategory_isEscapedToo() {
        assertThat(LogText.clean("soft­hyphen")).isEqualTo("soft\\u00adhyphen");
        assertThat(LogText.clean("f⁡x")).isEqualTo("f\\u2061x");
        assertThat(LogText.clean("￹note￻")).isEqualTo("\\ufff9note\\ufffb");
        assertThat(LogText.clean("ok" + Character.toString(0xE0001) + Character.toString(0xE0041))).as("the TAG characters: two escapes each")
            .isEqualTo("ok\\udb40\\udc01\\udb40\\udc41");
    }

    @Test
    void aValueWhoseOnlyInvisibleCharacterIsAboveUFFFF_isStillEscaped() {
        assertThat(LogText.clean("ad-btcl-" + Character.toString(0xE0041) + "-1")).isEqualTo("ad-btcl-\\udb40\\udc41-1");
    }

    @Test
    void lettersMarksSymbolsAndEmoji_stayAsTheyAre() {
        String text = "বাংলা ✓ ünïcødé 😀 中文";
        assertThat(LogText.clean(text)).isSameAs(text);
    }

    @Test
    void anEscapeAboveUFFFF_isNeverCutInHalf() {
        assertThat(LogText.clean("x".repeat(188) + Character.toString(0xE0041))).isEqualTo("x".repeat(188) + "\\udb40\\udc41");
        assertThat(LogText.clean("x".repeat(189) + Character.toString(0xE0041))).isEqualTo("x".repeat(189) + "…");
    }
}
