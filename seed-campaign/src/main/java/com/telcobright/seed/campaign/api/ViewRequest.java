package com.telcobright.seed.campaign.api;

import java.time.Instant;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * One request for a task from the pull side (an ad view): whose tenant, the facts a campaign targets
 * ({@code zone}, {@code site}, {@code district}, {@code gw}, …), the device (for the frequency cap) and when.
 */
public record ViewRequest(String tenantId, Map<String, String> facts, String device, Instant at) {

    public static final String ZONE = "zone";
    public static final String SITE = "site";
    public static final String DISTRICT = "district";
    public static final String GATEWAY = "gw";

    public ViewRequest {
        Map<String, String> m = new HashMap<>();
        facts.forEach((k, v) -> { if (v != null && !v.isBlank()) m.put(k.toLowerCase(Locale.ROOT), v.trim()); });
        facts = Map.copyOf(m);
        if (at == null) at = Instant.now();
    }

    public String fact(String dimension) { return facts.get(dimension); }

    public static ViewRequest at(String tenantId, String zone, String site, String district, String gw, String device, Instant at) {
        Map<String, String> f = new HashMap<>();
        f.put(ZONE, zone);
        f.put(SITE, site);
        f.put(DISTRICT, district);
        f.put(GATEWAY, gw);
        return new ViewRequest(tenantId, f, device, at);
    }
}
