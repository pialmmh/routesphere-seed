package com.telcobright.seed.sessionflow.api;

/**
 * P1-L · The ONE cleaner of a value a request carries, before it goes into a log line: control characters escaped, at most {@value #CAP}
 * characters, a failure's text {@value #FAILURE_CAP}, its stack cleaned.
 *
 * <p>P1-M · A thin DELEGATE: the cleaner lives in the seed's bottom module, {@code seed-logtext} ({@link com.telcobright.seed.logtext.LogText}),
 * so every seed module reaches it — seed-campaign sits below seed-sessionflow. This class keeps its name, its package and its signatures, so
 * no lane's import breaks; it adds nothing of its own.
 */
public final class LogText {

    /** The most characters of one value in a log line; "…" when cut. */
    public static final int CAP = com.telcobright.seed.logtext.LogText.CAP;
    /** The most characters of a failure's text. */
    public static final int FAILURE_CAP = com.telcobright.seed.logtext.LogText.FAILURE_CAP;

    private LogText() {}

    /** The value as ONE line can say it — {@link com.telcobright.seed.logtext.LogText#clean}. */
    public static String clean(Object value) { return com.telcobright.seed.logtext.LogText.clean(value); }

    /** A failure's words for a line — {@link com.telcobright.seed.logtext.LogText#failureText}. */
    public static String failureText(Object words) { return com.telcobright.seed.logtext.LogText.failureText(words); }

    /** A failure for the log's stack, every text in its chain cleaned — {@link com.telcobright.seed.logtext.LogText#cleaned}. */
    public static Throwable cleaned(Throwable failure) { return com.telcobright.seed.logtext.LogText.cleaned(failure); }

    /** P1-M · A url as a log may print it, a password inside it masked — {@link com.telcobright.seed.logtext.LogText#maskPassword}. */
    public static String maskPassword(String url) { return com.telcobright.seed.logtext.LogText.maskPassword(url); }
}
