package com.telcobright.seed.sessionflow.samples.ad;

/** What goes back to the phone — the answer of the road in hand (the channel's outbound type): the HTTP status, what, a detail. */
public record AdOut(int status, String what, String detail) {
    /** The hand-over: {@code 200 AdSessionView} with the creative to play. */
    public static AdOut view(AdCreative c)          { return new AdOut(200, "AdSessionView", c.creativeId()); }
    /** No ad for this request: {@code 204} with {@code X-Ad-Reason}. */
    public static AdOut noAd(String reason)         { return new AdOut(204, "X-Ad-Reason", reason); }
    public static AdOut ok(String road)             { return new AdOut(200, road, null); }
    /** The view's verdict: {@code 200 AdVerdict}, the seconds watched. */
    public static AdOut verdict(int watchedSeconds) { return new AdOut(200, "AdVerdict", "watched=" + watchedSeconds); }

    @Override public String toString() { return status + " " + what + (detail == null ? "" : " " + detail); }
}
