package com.telcobright.seed.campaign.internal;

import com.telcobright.seed.logtext.LogText;
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
        if (!takeOne(p, view)) return Optional.empty();
        CampaignTask task = newTask(p, view, taskId, subject, clientRef);
        tell(() -> { store.insertTask(task); store.bumpCounters(task.tenantId(), task.campaignId(), 0, 0, +1); }, task, "claim");
        return Optional.of(task);
    }

    /** ARCH-0093, layer 1 → 2: the runner's minted task becomes the core's — its row made PROCESSING under no campaign; no counter moves. */
    @Override
    public CampaignTask handedToCore(CampaignTask minted) {
        requireUnassigned(minted, "handedToCore()");
        CampaignTask processing = minted.processing();
        tell(() -> store.insertTask(processing), processing, "handed to the core");
        return processing;
    }

    /** ARCH-0093, the core's claim: the quota and the cap as {@link #claim} takes them, then the row's UPDATE and the one counter move. */
    @Override
    public Optional<CampaignTask> assign(CampaignTask task, Placement p, ViewRequest view) {
        requireUnassigned(task, "assign()");
        if (!takeOne(p, view)) return Optional.empty();
        CampaignTask assigned = task.assigned(p.campaign(), p.creative(), placementDetail(p));
        tell(() -> { store.updateTask(assigned); store.bumpCounters(assigned.tenantId(), assigned.campaignId(), 0, 0, +1); }, assigned, "assign");
        return Optional.of(assigned);
    }

    /** One slot of the campaign's quota and one count of the device's cap — what claim and assign share. False = the quota or the cap went. */
    private boolean takeOne(Placement p, ViewRequest view) {
        LiveCampaign live = liveOf(view.tenantId(), p.campaign().id());
        if (live == null || !live.tryTake()) return false;
        live.served(view.at(), zone);
        tenants.get(view.tenantId()).cap.count(p.campaign().id(), view.device(), view.at());
        return true;
    }

    private static void requireUnassigned(CampaignTask task, String verb) {
        if (task.assigned()) throw new IllegalArgumentException(verb + " takes a task no campaign has taken; " + task.uniqueId() + " names campaign " + task.campaignId());
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
        CampaignTask done = task.completed(clock.instant(), watchedSec, charge, cause);
        return done.assigned() ? recordCompleted(done) : recordUnassigned(done);
    }

    @Override
    public CampaignTask fail(CampaignTask task, int watchedSec, String cause) {
        if (task.state().terminal()) return task;
        CampaignTask lost = task.failed(clock.instant(), watchedSec, cause);
        return lost.assigned() ? recordFailed(lost) : recordUnassigned(lost);
    }

    @Override
    public CampaignTask closed(CampaignTask closedTask) {
        if (!closedTask.state().terminal()) {
            throw new IllegalArgumentException("closed() takes a terminal task row (SENT or FAILED); " + closedTask.uniqueId() + " is " + closedTask.state());
        }
        if (!closedTask.assigned()) return recordUnassigned(closedTask);
        return closedTask.state() == TaskState.SENT ? recordCompleted(closedTask) : recordFailed(closedTask);
    }

    /** A row no campaign took (ARCH-0093: a request the core refused): the row alone — no campaign's counters, no quota, no campaign to close. */
    private CampaignTask recordUnassigned(CampaignTask t) {
        tell(() -> store.updateTask(t), t, "closed under no campaign");
        return t;
    }

    /** A completed row: the counters, the store, and the campaign closes itself when its quota is reached. */
    private CampaignTask recordCompleted(CampaignTask t) {
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

    /** A failed row: the counters and the store. */
    private CampaignTask recordFailed(CampaignTask t) {
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
        Map<String, Object> detail = new java.util.HashMap<>(placementDetail(p));
        detail.put("facts", view.facts());
        return new CampaignTask(taskId, view.tenantId(), c.id(), c.partnerId(), c.kind(),
            subject == null || subject.isBlank() ? (view.device() == null ? "unknown" : view.device()) : subject,
            p.creative().id(), view.fact(ViewRequest.ZONE), view.fact(ViewRequest.SITE), clientRef,
            TaskState.PROCESSING, clock.instant(), null, null, 0, null, null, detail);
    }

    /** What the claim learns of the placement, kept on the row's detail: the seconds the view must last and why the campaign ranked where it did. */
    private static Map<String, Object> placementDetail(Placement p) {
        return Map.of("viewSeconds", p.viewSeconds(), "specificity", p.specificity());
    }

    /** The store is told; a store that fails is logged with the task and the step, and the task's life goes on. */
    private static void tell(Runnable write, CampaignTask task, String step) {
        try {
            write.run();
        } catch (RuntimeException e) {
            log.error("campaign store failed at {} for task {} (campaign {}): {}", step, LogText.clean(task.uniqueId()), task.campaignId(), LogText.failureText(e));
        }
    }
}
