package com.telcobright.seed.campaign.internal;

import com.telcobright.seed.campaign.api.Campaign;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** One tenant's campaigns in this process, keyed by campaign id, with the tenant's frequency cap. */
final class TenantCampaigns {

    final String tenantId;
    final FrequencyCap cap;
    private final ZoneId zone;
    private final Map<Integer, LiveCampaign> byId = new ConcurrentHashMap<>();

    TenantCampaigns(String tenantId, ZoneId zone) {
        this.tenantId = tenantId;
        this.zone = zone;
        this.cap = new FrequencyCap(zone);
    }

    /** Swap the rows; keep the live state of campaigns that are still there; forget the ones that left. */
    void reloaded(List<Campaign> fresh) {
        Map<Integer, Campaign> incoming = new ConcurrentHashMap<>();
        for (Campaign c : fresh) incoming.put(c.id(), c);
        byId.keySet().removeIf(id -> !incoming.containsKey(id));
        incoming.forEach((id, c) -> {
            LiveCampaign live = byId.get(id);
            if (live == null) byId.put(id, new LiveCampaign(c, zone));
            else live.reloaded(c);
        });
    }

    LiveCampaign get(int campaignId) { return byId.get(campaignId); }

    List<LiveCampaign> all() { return new ArrayList<>(byId.values()); }
}
