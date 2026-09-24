package com.telcobright.seed.campaign.internal;

import com.telcobright.seed.campaign.api.Campaign;
import com.telcobright.seed.campaign.api.CampaignCounters;
import com.telcobright.seed.campaign.api.CampaignService;
import com.telcobright.seed.campaign.api.CampaignTask;
import com.telcobright.seed.campaign.api.Placement;
import com.telcobright.seed.campaign.api.TaskCharge;
import com.telcobright.seed.campaign.api.TaskState;
import com.telcobright.seed.campaign.api.ViewRequest;
import com.telcobright.seed.campaign.spi.CampaignStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The service: per-tenant live campaigns, the ranker, and the task ladder that keeps the counters and tells the store.
 * The store is called on the caller's thread and its failures are logged, never thrown: the task's life is decided
 * here, in memory; the row is a record of it.
 */
public final class DefaultCampaignService implements CampaignService {
    private static final Logger log = LoggerFactory.getLogger(DefaultCampaignService.class);

    private final CampaignStore store;
    private final Clock clock;
    private final ZoneId zone;
    private final PlacementRanker ranker;
    private final Map<String, TenantCampaigns> tenants = new ConcurrentHashMap<>();

    public DefaultCampaignService(CampaignStore store, Clock clock, ZoneId zone) {
        this.store = store;
        this.clock = clock;
        this.zone = zone;
        this.ranker = new PlacementRanker(zone);
    }

    @Override
    public void reloaded(String tenantId, List<Campaign> campaigns) {
        tenants.computeIfAbsent(tenantId, t -> new TenantCampaigns(t, zone)).reloaded(campaigns);
    }

    @Override
    public List<Placement> rank(ViewRequest view) {
        TenantCampaigns t = tenants.get(view.tenantId());
        return t == null ? List.of() : ranker.rank(t, view);
    }

    @Override
    public Optional<CampaignTask> claim(Placement p, ViewRequest view, String taskId, String subject, String clientRef) {
        LiveCampaign live = liveOf(view.tenantId(), p.campaign().id());
        if (live == null || !live.tryTake()) return Optional.empty();
        live.served(view.at(), zone);
        tenants.get(view.tenantId()).cap.count(p.campaign().id(), view.device(), view.at());
        CampaignTask task = newTask(p, view, taskId, subject, clientRef);
        tell(() -> { store.insertTask(task); store.bumpCounters(task.tenantId(), task.campaignId(), 0, 0, +1); }, task, "claim");
        return Optional.of(task);
    }

    @Override
    public CampaignTask answered(CampaignTask task) {
        CampaignTask t = task.answered(clock.instant());
        tell(() -> store.updateTask(t), t, "answered");
        return t;
    }

    @Override
    public CampaignTask complete(CampaignTask task, int watchedSec, TaskCharge charge, String cause) {
        if (task.state().terminal()) return task;
        CampaignTask t = task.completed(clock.instant(), watchedSec, charge, cause);
        LiveCampaign live = liveOf(t.tenantId(), t.campaignId());
        boolean reached = false;
        if (live != null) {
            live.pending.decrementAndGet();
            live.sentSinceLoad.incrementAndGet();
            reached = !live.row.unlimited() && live.sent() >= live.row.totalTaskCount() && !live.row.terminal();
            if (reached) live.row = live.row.withStatus("Complete");
        }
        boolean quotaReached = reached;
        tell(() -> {
            store.updateTask(t);
            store.bumpCounters(t.tenantId(), t.campaignId(), +1, 0, -1);
            if (quotaReached) store.markComplete(t.tenantId(), t.campaignId());
        }, t, "complete");
        if (quotaReached) log.info("campaign {} of tenant {} reached its quota and is Complete", t.campaignId(), t.tenantId());
        return t;
    }

    @Override
    public CampaignTask fail(CampaignTask task, int watchedSec, String cause) {
        if (task.state().terminal()) return task;
        CampaignTask t = task.failed(clock.instant(), watchedSec, cause);
        LiveCampaign live = liveOf(t.tenantId(), t.campaignId());
        if (live != null) {
            live.pending.decrementAndGet();
            live.failedSinceLoad.incrementAndGet();
        }
        tell(() -> { store.updateTask(t); store.bumpCounters(t.tenantId(), t.campaignId(), 0, +1, -1); }, t, "fail");
        return t;
    }

    @Override
    public Map<Integer, CampaignCounters> counters(String tenantId) {
        TenantCampaigns t = tenants.get(tenantId);
        Map<Integer, CampaignCounters> out = new LinkedHashMap<>();
        if (t == null) return out;
        Instant now = clock.instant();
        for (LiveCampaign live : t.all()) out.put(live.row.id(), live.counters(now, zone));
        return out;
    }

    @Override
    public Optional<Campaign> campaign(String tenantId, int campaignId) {
        LiveCampaign live = liveOf(tenantId, campaignId);
        return live == null ? Optional.empty() : Optional.of(live.row);
    }

    // ── internals ───────────────────────────────────────────────────────────

    private LiveCampaign liveOf(String tenantId, int campaignId) {
        TenantCampaigns t = tenants.get(tenantId);
        return t == null ? null : t.get(campaignId);
    }

    private CampaignTask newTask(Placement p, ViewRequest view, String taskId, String subject, String clientRef) {
        Campaign c = p.campaign();
        return new CampaignTask(taskId, view.tenantId(), c.id(), c.partnerId(), c.kind(),
            subject == null || subject.isBlank() ? (view.device() == null ? "unknown" : view.device()) : subject,
            p.creative().id(), view.fact(ViewRequest.ZONE), view.fact(ViewRequest.SITE), clientRef,
            TaskState.PROCESSING, clock.instant(), null, null, 0, null, null,
            Map.of("facts", view.facts(), "viewSeconds", p.viewSeconds(), "specificity", p.specificity()));
    }

    /** The store is told; a store that fails is logged with the task and the step, and the task's life goes on. */
    private static void tell(Runnable write, CampaignTask task, String step) {
        try {
            write.run();
        } catch (RuntimeException e) {
            log.error("campaign store failed at {} for task {} (campaign {}): {}", step, task.uniqueId(), task.campaignId(), e.toString());
        }
    }
}
