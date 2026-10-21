package com.telcobright.seed.sessionflow.spi;

/**
 * Where a session's record (the SDR: the application's own row — the ad's {@code ad_sdr}, the WiFi's session record) goes, once per
 * session at the finish, after the CDR and the task. statewalk 4.0 has no SDR sink of its own (removed in the 3.3 → 4.0 migration): the
 * base writes the application's {@code buildSdr} here. A null SDR is not written. A sink that throws loses nothing of the session: the
 * step is guarded, the fault is on the history.
 */
@FunctionalInterface
public interface SdrSink {

    SdrSink NONE = (sessionId, sdr) -> { };

    void write(String sessionId, Object sdr);
}
