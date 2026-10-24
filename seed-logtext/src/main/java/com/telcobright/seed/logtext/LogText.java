package com.telcobright.seed.logtext;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * P1-L / P1-M · The ONE place where a value the seed logs from a request is made fit for a log line — at the seed's BOTTOM (seed-logtext,
 * no dependency of its own), so every module reaches it: seed-campaign sits below seed-sessionflow, whose {@code api.LogText} delegates here. A session id is built from what the request
 * carries (the ad's: {@code ad-<tenant>-<mac>-<ms>-<n>}), a reserve's reference from the session id, a lane's failure may quote the
 * request: a CR or LF in any of them would write a forged line of its own. Every control character — C0 (CR, LF, TAB, …), DEL, C1 and
 * the line and paragraph separators U+2028 / U+2029 — is written as a visible escape ({@code \n} → {@code \\n}, {@code \r} → {@code \\r},
 * {@code \t} → {@code \\t}, the others {@code \\uXXXX}); a value is at most {@value #CAP} characters, a failure's text at most
 * {@value #FAILURE_CAP}, with "…" when cut — never in the middle of an escape or a character. A clean value is given back as it is.
 */
public final class LogText {

    /** The most characters of one value (an id, a reference, a door, a lane's words) in a log line; "…" when cut. */
    public static final int CAP = 200;
    /** The most characters of a failure's text (its class and message; each cause's in a stack). */
    public static final int FAILURE_CAP = 1000;
    static final String CUT = "…";
    private static final int MOST_CAUSES = 8;

    private LogText() {}

    /** P1-M · How a password inside a url is printed. */
    public static final String MASK = "***";

    /** P1-M · A parameter whose NAME says a password (password, passwd, pwd, secret — inside a longer name too), case-blind, in a query or after ';'. */
    private static final Pattern PASSWORD_PARAMETER = Pattern.compile("(?i)([?&;])([^=&;#?]*(?:password|passwd|pwd|secret)[^=&;#?]*)=([^&;#]*)");

    /**
     * P1-M · A url as a log may print it: the password of its userinfo ({@code redis://:secret@host}, {@code redis://user:secret@host},
     * {@code jdbc:mysql://user:secret@host}) and the value of every parameter whose name says a password, passwd, pwd or secret — in the
     * query ({@code ?password=…}, {@code &pwd=…}) or after a ';' ({@code ;password=…}), case-blind — are written {@value #MASK}; the rest
     * as it is. ONLY for printing: the url used to connect is the one written. Null for null.
     */
    public static String maskPassword(String url) {
        if (url == null) return null;
        return PASSWORD_PARAMETER.matcher(maskTheUserinfoPassword(url)).replaceAll("$1$2=" + Matcher.quoteReplacement(MASK));
    }

    /** The userinfo's password: from the first ':' after "//" to the authority's last '@'; a userinfo without ':' has none. */
    private static String maskTheUserinfoPassword(String url) {
        int scheme = url.indexOf("://");
        if (scheme < 0) return url;
        int from = scheme + 3, to = endOfTheAuthority(url, from);
        int at = url.lastIndexOf('@', to - 1);
        if (at < from) return url;
        int colon = url.indexOf(':', from);
        if (colon < 0 || colon > at) return url;
        return url.substring(0, colon + 1) + MASK + url.substring(at);
    }

    private static int endOfTheAuthority(String url, int from) {
        for (int i = from; i < url.length(); i++) {
            char c = url.charAt(i);
            if (c == '/' || c == '?' || c == '#' || c == ';') return i;
        }
        return url.length();
    }

    /** The value as ONE line can say it: control characters escaped, at most {@value #CAP} characters. */
    public static String clean(Object value) { return clean(String.valueOf(value), CAP); }

    /**
     * A failure's words — a {@code Throwable}'s {@code toString}, a ledger's answer — or any long text a line carries whole (a campaign
     * store's change, P1-M) for a line: escaped, at most {@value #FAILURE_CAP} characters.
     */
    public static String failureText(Object words) { return clean(String.valueOf(words), FAILURE_CAP); }

    /**
     * A failure for the log's stack: the same stack, every text in its chain (its own and up to 8 causes') cleaned — a lane's failure may
     * quote the request. Null for null.
     */
    public static Throwable cleaned(Throwable failure) { return cleaned(failure, 0); }

    static String clean(String text, int cap) {
        if (isFit(text, cap)) return text;
        StringBuilder out = new StringBuilder(Math.min(text.length(), cap) + CUT.length());
        for (int i = 0; i < text.length(); ) {
            int c = text.codePointAt(i);
            int width = isControl(c) ? escapeWidth(c) : Character.charCount(c);
            if (out.length() + width > cap) return out.append(CUT).toString();
            if (isControl(c)) appendEscape(out, c); else out.appendCodePoint(c);
            i += Character.charCount(c);
        }
        return out.toString();
    }

    private static boolean isFit(String text, int cap) {
        if (text.length() > cap) return false;
        for (int i = 0; i < text.length(); i++) if (isControl(text.charAt(i))) return false;
        return true;
    }

    /** C0, DEL and C1 ({@link Character#isISOControl}), and the line and paragraph separators. */
    private static boolean isControl(int c) { return Character.isISOControl(c) || c == 0x2028 || c == 0x2029; }

    private static int escapeWidth(int c) { return c == '\n' || c == '\r' || c == '\t' ? 2 : 6; }

    private static void appendEscape(StringBuilder out, int c) {
        switch (c) {
            case '\n' -> out.append("\\n");
            case '\r' -> out.append("\\r");
            case '\t' -> out.append("\\t");
            default -> out.append(String.format("\\u%04x", c));
        }
    }

    private static Throwable cleaned(Throwable failure, int depth) {
        if (failure == null) return null;
        Throwable cause = failure.getCause() == failure || depth >= MOST_CAUSES ? null : cleaned(failure.getCause(), depth + 1);
        CleanedFailure out = new CleanedFailure(clean(failure.toString(), FAILURE_CAP), cause);
        out.setStackTrace(failure.getStackTrace());
        return out;
    }

    /** A failure as the log may print it: the original's words, cleaned, and the original's stack. */
    private static final class CleanedFailure extends RuntimeException {
        CleanedFailure(String words, Throwable cause) { super(words, cause); }

        @Override public String toString() { return getMessage(); }
    }
}
