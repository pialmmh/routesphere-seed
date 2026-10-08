package com.telcobright.seed.sessionflow.testkit;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A clock a test moves by hand: time stands still until the test says how much passed. With it a test of a deadline
 * (the admission budget, a slow ledger) is exact and waits for nothing.
 */
public final class ManualClock extends Clock {

    private final AtomicLong nowMs;
    private final ZoneId zone;

    public ManualClock(Instant start, ZoneId zone) {
        this.nowMs = new AtomicLong(start.toEpochMilli());
        this.zone = zone;
    }

    /** Let {@code millis} pass. */
    public void advance(long millis) { nowMs.addAndGet(millis); }

    @Override public ZoneId getZone() { return zone; }

    @Override public Clock withZone(ZoneId other) { return new ManualClock(instant(), other); }

    @Override public long millis() { return nowMs.get(); }

    @Override public Instant instant() { return Instant.ofEpochMilli(nowMs.get()); }
}
