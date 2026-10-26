package com.telcobright.seed.sessionflow.api;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.telcobright.rtc.domainmodel.LevelAdmission;
import com.telcobright.rtc.domainmodel.mysqlentity.Partner;
import com.telcobright.rtc.domainmodel.nonentity.Tenant;
import com.telcobright.seed.campaign.api.CampaignTask;
import com.telcobright.seed.sessionflow.internal.TaskJson;
import com.telcobright.statewalk.session.SessionContext;
import com.telcobright.statewalk.session.events.SignalingFailed;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Everything ONE session knows, whatever the application (§B): statewalk's {@link SessionContext} (the facts, the answer, the end cause,
 * the times, the attempts, the outcome, the history) plus the routesphere lifecycle's own — the root, the task, the candidates, the tiers
 * with their reserves and settlements, the chosen candidate, the route plan. The base's handler and the application's flow hold NO
 * session (pool rule): this object is the only place a session keeps state, and the registry drops it when the session ends.
 *
 * <p><b>Persisted, and small (§L.6).</b> statewalk writes this object to the session's row (Jackson; a MariaDB {@code TEXT} row fails over
 * 64 KB): ids and amounts only. The live objects the walk works on — the root's snapshot ({@link #tree}), the tenants and partners of the
 * tiers ({@link #levels}, {@link #entryTenant}, {@link #partner}), the closed spans — are {@code @JsonIgnore}: their persisted pictures
 * are {@link #root} + {@link #treeVersion} and {@link #tiers}, and a restored session takes them back from the root's snapshot
 * ({@code RoutesphereFlow} does it before it touches money). An application's subclass follows the same rule: public fields of words,
 * numbers and records; nothing that holds a tenant tree.
 */
public class RoutesphereContext extends SessionContext {

    // ── (a) the root ────────────────────────────────────────────────────────

    /** The ROOT tenant the session's ingress identified (the rule's tenant code). Null = nobody's: the session fails NO_TENANT. */
    public volatile String root;
    /** The version of the root's snapshot the session started with (a reload does not move a running session). */
    public volatile long treeVersion;
    /** The root's snapshot the session walks — every lookup is scoped to it. Live only: re-read by {@link #root} after a restore. */
    @JsonIgnore public transient volatile RootTree tree;
    /** The refusal the session met before admission (NO_TENANT, TENANT_UNAVAILABLE, the task's own); admission answers it. */
    public volatile String startRefusal;
    /** When the base took the session, on the kit's clock — the CDR's start and the PDD's origin. */
    public volatile long createdAtMs;

    // ── the task ────────────────────────────────────────────────────────────

    /** {@code VOICE} | {@code SMS} | {@code AD} | {@code WIFI} — what kind of session this is. */
    public volatile String taskType;
    /**
     * The session's task row (the owner, 2026-10-09: every session kind takes a CampaignTask in), closed by the base at the finish, once.
     * Persisted as its picture ({@code TaskJson.TaskLine}: statewalk's plain mapper cannot write the task's {@code Instant}s), so a
     * session restored from its row closes its own task.
     */
    @JsonSerialize(converter = TaskJson.Out.class)
    @JsonDeserialize(converter = TaskJson.In.class)
    public volatile CampaignTask task;
    public volatile boolean taskClosed;
    public volatile String originatingCallingNumber;
    public volatile String originatingCalledNumber;
    /** After the digit rules. Null = the same as the originating number. */
    public volatile String terminatingCallingNumber;
    public volatile String terminatingCalledNumber;
    /** Where the session came in (the ingress address). The call summary groups by it: a small set, never an end device. */
    public volatile String callerIp;
    /** Where the session went out. */
    public volatile String receiverIp;
    public volatile String codec;
    /** The wire the signaling speaks ({@code ESL}, {@code SMPP}, {@code HTTP}). Null = unnamed. */
    public volatile String protocol;

    // ── the candidates ──────────────────────────────────────────────────────

    /** Who may serve this session, in failover order (INIT: {@code selectCandidates}). */
    @JsonTypeInfo(use = JsonTypeInfo.Id.CLASS)
    public volatile List<Candidate> candidates = List.of();
    /** The candidate admitted. Null = none (yet). */
    @JsonTypeInfo(use = JsonTypeInfo.Id.CLASS)
    public volatile Candidate chosen;
    /** The index of {@link #chosen} in {@link #candidates}; -1 = none. */
    public volatile int candidateIndex = -1;
    public volatile int candidatesTried;
    public volatile boolean admitted;
    /** The refusal admission saw last — the reject cause when nothing was admitted. */
    public volatile String lastRefusal;
    /**
     * P1-O · Why the candidate being admitted has no leaf, when the lane's helper knew it better than {@code PARTNER_NOT_FOUND}: the route
     * credentials refused ({@code CREDENTIALS_REJECTED}). Cleared before each candidate's {@code identifyPartner}; never a password.
     */
    public volatile String identificationCause;
    /** The ledger itself failed while a tier was reserved. Never a customer cause. */
    public volatile String systemFault;
    /** When this session's admission budget ends (epoch ms, the kit's clock). 0 = not admitting yet. */
    public volatile long admissionDeadlineMs;
    /** The admission budget ran out and a candidate that pays was not tried (or not finished) because of it. */
    public volatile boolean budgetSpent;
    /** The admission is a dry run: nothing is reserved. */
    public volatile boolean dryRun;

    // ── the tiers ───────────────────────────────────────────────────────────

    /** The leaf tier's database name (kept from the last candidate tried, for a refused session's CDR). */
    public volatile String entryTenantDb;
    /** The partner that pays at the leaf tier, by id. */
    public volatile Integer entryPartnerId;
    /** The leaf tier as the walk found it (live). */
    @JsonIgnore public transient volatile Tenant entryTenant;
    /** The partner that pays at the leaf (live). */
    @JsonIgnore public transient volatile Partner partner;
    /** One admission per tier, the leaf first — every tier reserved (live: the ledger's and the CDR's working copy). */
    @JsonIgnore public transient volatile List<LevelAdmission> levels = List.of();
    /** The tiers the candidate being admitted has reserved so far (live, admission only). */
    @JsonIgnore transient volatile List<LevelAdmission> walking = new ArrayList<>();
    /** L2b · The money engine's plan of the candidate under admission (seed-prepaid's: every tier rated, held at once); the base never reads it. */
    @JsonIgnore public transient volatile Object moneyPlan;
    /** The candidate being admitted (live, admission only) — read it through {@link #candidateBeingAdmitted()}. */
    @JsonIgnore transient volatile Candidate trying;
    /** The persisted picture of {@link #levels}: ids and amounts, in the same order. */
    public volatile List<TierLine> tiers = List.of();
    /** One settlement per tier, in the order of {@link #levels}. Empty until the reserves were closed. */
    public volatile List<TierSettlement> settlements = List.of();
    /** Every reserve of this session was settled or given back, exactly once. */
    public volatile boolean reservesClosed;
    /** The reserve series a tier closed mid-session (O4: the next account took over), in order (live). */
    @JsonIgnore public transient volatile List<ClosedSpan> closedSpans = List.of();
    /** The persisted picture of {@link #closedSpans}, in the same order (P1-F2: a restore keeps the spans' numbers, records and seconds). */
    public volatile List<SpanLine> spans = List.of();
    /** When the LIVE span of the rotating tier began (the last rotation). 0 = the session's own activation. */
    public volatile long spanStartedAtMs;

    // ── routing ─────────────────────────────────────────────────────────────

    public volatile String incomingRoute;
    public volatile String outgoingRoute;
    /** The supplier: the partner of the outgoing route. */
    public volatile Integer outPartnerId;
    /** The hops step (d) found, and the cursor on the one being tried. Null = not routed yet. */
    public volatile RoutePlan routePlan;
    /** The last signaling failure before the answer (CONNECTING → FAILED). */
    public volatile SignalingFailed failure;

    // ── the life ────────────────────────────────────────────────────────────

    /** The first progress report of the signaling (ringing): the PDD. 0 = none. */
    public volatile long progressAtMs;
    /** The billed duration in seconds. The settlement fixes it once; the CDR carries the same number. */
    public volatile double durationSec;
    /** When this session was restored from the store (0 = never): {@code billedUnits} sees it (§L.3, the owner's business decision). */
    public volatile long restoredAtMs;
    /** The base gave the service its teardown (once per session). */
    public volatile boolean tornDown;
    /** The CDR of this session went to the sink. */
    public volatile boolean cdrPublished;
    /** Every step of this session is timed and written to its history (the debug switch does it for every session). */
    public volatile boolean traced;
    /** The budget child's cut, armed for the end of a partial window (C14). Live only. */
    @JsonIgnore public transient volatile ScheduledFuture<?> balanceCut;
    /**
     * The session's money lock: the budget child's renewals and the final state's settlement run on two cells (two FIFO chains in
     * statewalk 4), so the money of one session is touched under this lock — never a {@code synchronized} monitor (a blocking ledger
     * call would pin the virtual thread's carrier).
     */
    @JsonIgnore public final transient ReentrantLock money = new ReentrantLock();

    // ── reading it ──────────────────────────────────────────────────────────

    /** The session's own id in its domain (the facts'), or null before {@code SessionStart}. The CDR's call id and the ledger's key. */
    public String sessionId() { return facts == null ? null : facts.sessionId(); }

    /**
     * The candidate the admission walk is on right now — what step (d) {@code routing} routes by (it runs inside the walk, before
     * {@link #chosen} is set), and what any admission hook may read. Live, never persisted; null outside admission (before it, after the
     * candidate was admitted — then {@link #chosen} names it — and after every candidate was refused).
     */
    public Candidate candidateBeingAdmitted() { return trying; }

    /**
     * L2a · The tier the candidate being admitted has just been held on — recorded on the walk, so a later refusal gives it back. The money
     * engine's {@code admit} calls it (the engine lives outside this package since the money moved to seed-prepaid; the walk itself stays
     * the base's, live and never persisted).
     */
    public void walked(LevelAdmission tier) { walking.add(tier); }

    /** The service was established (a call answered, an ad viewed, an SMS submitted). */
    public boolean answered() { return answer != null; }

    /** When the service was established (epoch ms); 0 = never. */
    public long answeredAtMs() { return answer == null ? 0 : answer.atMs(); }

    /** The number of the live span: 1 when nothing rotated. */
    public int spanNo() { return closedSpans.size() + 1; }

    /** The seconds of the live span: the billed duration less the closed spans' seconds. */
    public double spanSeconds() {
        double closed = 0;
        for (ClosedSpan span : closedSpans) closed += span.seconds();
        return Math.max(0, durationSec - closed);
    }

    /** The called number billing rates on: after the digit rules, else as dialed. */
    public String billingCalledNumber() {
        return terminatingCalledNumber != null ? terminatingCalledNumber : originatingCalledNumber;
    }

    /** The live tiers changed (admission, a renewal, a rotation): their persisted picture — the tiers and the closed spans — follows. */
    public void keepTiers() {
        List<TierLine> lines = new ArrayList<>(levels.size());
        for (LevelAdmission level : levels) lines.add(TierLine.of(level));
        tiers = List.copyOf(lines);
        List<SpanLine> closed = new ArrayList<>(closedSpans.size());
        for (ClosedSpan span : closedSpans) closed.add(SpanLine.of(span));
        spans = List.copyOf(closed);
    }
}
