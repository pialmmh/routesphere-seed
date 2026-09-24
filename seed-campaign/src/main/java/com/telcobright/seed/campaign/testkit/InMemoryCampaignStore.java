package com.telcobright.seed.campaign.testkit;

import com.telcobright.seed.campaign.api.Campaign;
import com.telcobright.seed.campaign.api.CampaignTask;
import com.telcobright.seed.campaign.spi.CampaignStore;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** The store in memory: what the tests (and a dev profile) use. Everything it was told is inspectable. */
public final class InMemoryCampaignStore implements CampaignStore {

    private final Map<String, List<Campaign>> campaigns = new ConcurrentHashMap<>();
    public final Map<String, CampaignTask> tasks = new ConcurrentHashMap<>();
    public final List<String> writes = new CopyOnWriteArrayList<>();
    public final Map<Integer, int[]> counters = new ConcurrentHashMap<>();   // campaignId → {sent, failed, pending}
    public final List<Integer> completed = new CopyOnWriteArrayList<>();
    public volatile RuntimeException failWith;

    public InMemoryCampaignStore seed(String tenantId, Campaign... rows) {
        campaigns.computeIfAbsent(tenantId, t -> new CopyOnWriteArrayList<>()).addAll(List.of(rows));
        return this;
    }

    @Override
    public List<Campaign> campaigns(String tenantId) {
        return new ArrayList<>(campaigns.getOrDefault(tenantId, List.of()));
    }

    @Override
    public void insertTask(CampaignTask task) {
        maybeFail();
        tasks.put(task.uniqueId(), task);
        writes.add("insert " + task.uniqueId());
    }

    @Override
    public void updateTask(CampaignTask task) {
        maybeFail();
        tasks.put(task.uniqueId(), task);
        writes.add("update " + task.uniqueId() + " " + task.state());
    }

    @Override
    public void bumpCounters(String tenantId, int campaignId, int sentDelta, int failedDelta, int pendingDelta) {
        maybeFail();
        int[] c = counters.computeIfAbsent(campaignId, k -> new int[3]);
        synchronized (c) { c[0] += sentDelta; c[1] += failedDelta; c[2] += pendingDelta; }
        writes.add("bump " + campaignId + " " + sentDelta + "/" + failedDelta + "/" + pendingDelta);
    }

    @Override
    public void markComplete(String tenantId, int campaignId) {
        maybeFail();
        completed.add(campaignId);
        writes.add("complete " + campaignId);
    }

    private void maybeFail() {
        RuntimeException e = failWith;
        if (e != null) throw e;
    }
}
