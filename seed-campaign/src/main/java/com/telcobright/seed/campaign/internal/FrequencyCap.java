package com.telcobright.seed.campaign.internal;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * How many times a device was served a campaign today — in memory, per tenant. A restart forgets it, by design:
 * the cap protects the viewer from repetition, it is not money. Yesterday's counts are dropped lazily at the day change.
 */
final class FrequencyCap {

    private final ZoneId zone;
    private volatile LocalDate day;
    private final Map<String, AtomicInteger> served = new ConcurrentHashMap<>();

    FrequencyCap(ZoneId zone) {
        this.zone = zone;
        this.day = LocalDate.now(zone);
    }

    int servedToday(int campaignId, String device, Instant at) {
        rollDay(at);
        if (device == null) return 0;
        AtomicInteger n = served.get(key(campaignId, device));
        return n == null ? 0 : n.get();
    }

    void count(int campaignId, String device, Instant at) {
        rollDay(at);
        if (device == null) return;
        served.computeIfAbsent(key(campaignId, device), k -> new AtomicInteger()).incrementAndGet();
    }

    private void rollDay(Instant at) {
        LocalDate today = at.atZone(zone).toLocalDate();
        if (!today.equals(day)) {
            synchronized (this) {
                if (!today.equals(day)) { served.clear(); day = today; }
            }
        }
    }

    private static String key(int campaignId, String device) { return campaignId + "|" + device.toLowerCase(); }
}
