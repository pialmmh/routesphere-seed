package com.telcobright.seed.sessionflow.samples.ad;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** The campaigns' view quotas — the sample's stand-in for seed-campaign's claim (the ad's last gate: claimed after every tier reserved). */
public final class AdQuota {

    private final Map<Integer, AtomicInteger> left = new ConcurrentHashMap<>();

    public AdQuota quota(int campaignId, int views) { left.put(campaignId, new AtomicInteger(views)); return this; }

    /** One view of the campaign's quota, or false when it is gone (a campaign with no quota set is unlimited). */
    public boolean claim(int campaignId) {
        AtomicInteger n = left.get(campaignId);
        return n == null || n.getAndUpdate(v -> v > 0 ? v - 1 : v) > 0;
    }

    public int left(int campaignId) { AtomicInteger n = left.get(campaignId); return n == null ? -1 : n.get(); }
}
