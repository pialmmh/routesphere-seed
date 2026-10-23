package com.telcobright.seed.sessionflow.api;

import com.telcobright.rtc.domainmodel.LevelAdmission;
import com.telcobright.rtc.domainmodel.PartnerType;
import com.telcobright.rtc.domainmodel.mysqlentity.Partner;
import com.telcobright.rtc.domainmodel.nonentity.Tenant;
import com.telcobright.seed.sessionflow.dependencies.RefundPolicy;
import com.telcobright.seed.sessionflow.dependencies.RoutesphereKit;
import com.telcobright.seed.sessionflow.internal.BudgetChild;
import com.telcobright.statewalk.registry.InternalEventResolver;
import com.telcobright.statewalk.session.Outcome;
import com.telcobright.statewalk.session.Route;
import com.telcobright.statewalk.session.SessionHandler;
import com.telcobright.statewalk.session.SessionTimings;
import com.telcobright.statewalk.session.Verdict;
import com.telcobright.statewalk.session.events.Established;
import com.telcobright.statewalk.session.events.ServiceEnd;
import com.telcobright.statewalk.session.events.SessionStart;
import com.telcobright.statewalk.session.events.SignalingFailed;
import com.telcobright.statewalk.session.events.SignalingProgress;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * The ONE base flow of every routesphere application — the ad, the ESL call, the WiFi session, the SMS (P1; design
 * {@code routesphere-flow.html} §2). statewalk 4.0 owns the states ({@code SessionSupervisor}: IDLE · INIT · ADMITTING · ROUTING ·
 * CONNECTING · ACTIVE · SUCCEEDED | FAILED, final); this class owns the routesphere lifecycle and implements every handler call the
 * library makes, FINAL; the application answers only its own questions, through the owner's methods (a)–(g) and the smaller hooks.
 *
 * <pre>
 *   library call (state)          the base                                              the owner's methods (■ = abstract: every lane answers it)
 *   every call                    the lane's ONE name: statewalk's table, the log        name■ · serviceGroup■ (the CDR's group)
 *   onStart       IDLE → INIT     the root by the lane's door, its snapshot, the task   (a) identifyTenant■ · buildTask
 *   prepare       INIT            the candidates; the budget child when it renews        selectCandidates · renewsBudget
 *   admit         ADMITTING       per candidate: the leaf, the tiers leaf → root,        (c) identifyEntryPartner■ · (b) iterateResellerTenants
 *                                 per tier check · authorize · root rules · reserve      (c) identifyPartner · (e) reserveBalance → rateAtLevel■
 *                                 the route at the root, the last gate                   (d) routing■ · confirmAdmission
 *   nextHop       ROUTING         the plan's current hop                                 (d) routing■ (made once, at admission)
 *   retry         CONNECTING      by cause: the same hop · the next hop · the end        (f) handleRerouting → rerouteActionFor■
 *   onActive      ACTIVE          the budget child starts on statewalk's SessionActive    onServiceStarted
 *   settle        SUCCEEDED/ans.  per tier: delivered → rate × units, else the refund    (e) finalizeCost → chargeAtSettle, billedUnits,
 *                                 rule; the rest back                                        delivered■, settleOutcome, refundPolicy■
 *   release       FAILED, never   every reserve back (the refund rule decides an offered one)
 *   record        both finals     teardown, the slot, CDR per tier, the task, the SDR    (g) finishSession → onTeardown, cdrPerTier,
 *                                 — then the registry resets every machine to its pool        buildSdr, onEnded
 * </pre>
 *
 * <p><b>Every lane states its rules (■, the owner's word of 2026-10-23).</b> What no lane may inherit from another is abstract: its name,
 * its CDR group, who pays, where it goes, its rate, what "delivered" means, its refund rule and its failover by cause — an SMS or an ad
 * lane that forgot one would be billed like a call. Each one's javadoc gives the call's answer as the example. The money stays shared
 * with its defaults ({@code reserveBalance}, {@code finalizeCost}, {@code settleOutcome}, {@code identifyPartner}); the tier walk
 * (b), the finish (g) and every statewalk call are final.
 *
 * <p><b>The pool rule.</b> ONE flow object serves every session of its application, on many threads: its only field is the
 * {@link RoutesphereKit}. A step keeps nothing in a field; what it learns goes onto the session's context.
 *
 * <p><b>The rules of a step.</b> A step that refuses returns its cause (a {@link SessionCause} word, or the application's own) as a
 * {@link Verdict#reject}; {@link Verdict#ok()} passes. A step must not throw: the base guards every hook — a throw at admission refuses
 * the candidate with {@code INTERNAL_ERROR}, a throw in a money or record step is logged with the session's identity and the session
 * still ends (statewalk's hook discipline).
 *
 * <p><b>The root first (§C).</b> Every lookup of a session — the entry partner, the partner map, the tier walk, the rates, the CDR's
 * topic — is scoped to the root its ingress named, in the snapshot the session started with (§D).
 *
 * @param <C> the application's context
 */
public abstract class RoutesphereFlow<C extends RoutesphereContext> implements SessionHandler<C> {

    /** The base's own log; an application logs through its own. */
    protected static final Logger log = LoggerFactory.getLogger(RoutesphereFlow.class);

    /** The session's ingress named no served root (operator model §5: never a default root). */
    public static final String NO_TENANT = "NO_TENANT";

    /** The only field (pool rule): the trees per root, the rules, the ledger, the sinks, the clock, the settings. */
    protected final RoutesphereKit kit;

    protected RoutesphereFlow(RoutesphereKit kit) { this.kit = Objects.requireNonNull(kit, "kit"); }

    /** What the host handed in. */
    public final RoutesphereKit kit() { return kit; }

    /**
     * The application's name — the REGISTERED supervisor name ({@code AdSupervisor}, {@code CallSupervisor}, …): statewalk gives each
     * lane its own table by it (§L.4), and it tags the log lines.
     */
    public abstract String name();

    /** The CDR's service group. 0 = billing detects it (a voice call). 30 = an ad view, taken as given. */
    protected abstract int serviceGroup(C ctx);

    @Override
    public final SessionTimings timings() { return kit.settings().timings(); }

    // ═════════════════════════════════════════════════════════════════════════════════════════════
    // What the library calls, when — FINAL
    // ═════════════════════════════════════════════════════════════════════════════════════════════

    /** IDLE → INIT: the root by the ingress (a), the root's snapshot, the application's task. */
    @Override
    public final void onStart(C ctx, SessionStart start) {
        ctx.createdAtMs = kit.clock().millis();
        ctx.root = rootOf(ctx);
        if (ctx.root == null) { refuseAtStart(ctx, NO_TENANT); return; }
        if (!takeTheRootsSnapshot(ctx)) { refuseAtStart(ctx, SessionCause.TENANT_UNAVAILABLE); return; }
        String refusal = safely(ctx, "buildTask", () -> step(ctx, "BUILD_TASK", () -> buildTask(ctx)), SessionCause.INTERNAL_ERROR);
        if (refusal != null) refuseAtStart(ctx, refusal);
    }

    /** INIT: the candidates, in failover order; the budget child when the service renews (spawned here, started at ACTIVE). */
    @Override
    public final void prepare(C ctx, InternalEventResolver resolver) {
        kit.counters().launched.incrementAndGet();
        if (ctx.startRefusal != null) return;
        ctx.candidates = candidatesOf(ctx);
        if (renewsBudget()) resolver.spawnChild(BudgetChild.TYPE, BudgetChild.contextFor(ctx));
    }

    /** ADMITTING: the first candidate every tier admits is the session's; a refused candidate gives every reserve back. */
    @Override
    public final Verdict admit(C ctx) {
        if (ctx.startRefusal != null) return Verdict.reject(ctx.startRefusal);
        Verdict verdict = AdmissionChain.of(this).admitFirstCandidate(ctx);
        ctx.keepTiers();
        return verdict;
    }

    /** ROUTING (d): the plan's current hop — the plan step (d) made at admission; null = NO_ROUTE. */
    @Override
    public final Route nextHop(C ctx) {
        RoutePlan plan = planOf(ctx);
        return plan == null ? null : plan.current();
    }

    /** CONNECTING (f): the attempt is recorded, then the application's rule decides; a retry never re-admits, never re-reserves. */
    @Override
    public final boolean retry(C ctx, SignalingFailed failed) {
        recordFailedAttempt(ctx, failed);
        return safely(ctx, "handleRerouting", () -> handleRerouting(ctx, failed), false);
    }

    /** CONNECTING: the first progress report is the PDD; the session stays CONNECTING. */
    @Override
    public final void onProgress(C ctx, SignalingProgress progress) {
        if (ctx.progressAtMs == 0) ctx.progressAtMs = kit.clock().millis();
        guarded(ctx, "onSignalingProgress", () -> onSignalingProgress(ctx, progress));
    }

    /** CONNECTING → ACTIVE: the answer is on the context (the library put it there). */
    @Override
    public final void onEstablished(C ctx, Established answer) { guarded(ctx, "onAnswered", () -> onAnswered(ctx, answer)); }

    /** ACTIVE: the service runs; the budget child already started on statewalk's {@code SessionActive} (G-1). */
    @Override
    public final void onActive(C ctx) { guarded(ctx, "onServiceStarted", () -> onServiceStarted(ctx)); }

    /** ACTIVE → SUCCEEDED: the service ended (the library kept the cause). */
    @Override
    public final void onEnd(C ctx, ServiceEnd end) { guarded(ctx, "onServiceEnded", () -> onServiceEnded(ctx, end)); }

    /** CONNECTING → FAILED: the last signaling failure, no retry. */
    @Override
    public final void onFailed(C ctx, SignalingFailed failed) {
        ctx.failure = failed;
        guarded(ctx, "onSignalingFailed", () -> onSignalingFailed(ctx, failed));
    }

    /** The final state, ANSWERED (e): every tier is finalized — charged or refunded — once, synchronously. */
    @Override
    public final void settle(C ctx, Outcome outcome) {
        bringBackTheLiveTiers(ctx);
        SessionSettlement.of(this).settle(ctx, outcome);
    }

    /** FAILED, never answered: every reserve back — the refund rule decides only a session offered and never consumed. */
    @Override
    public final void release(C ctx) {
        bringBackTheLiveTiers(ctx);
        SessionSettlement.of(this).release(ctx);
    }

    /** Both finals (g): the finish; then the registry resets every machine of the session and returns it to its pool. */
    @Override
    public final void record(C ctx, Outcome outcome) {
        bringBackTheLiveTiers(ctx);
        finishSession(ctx, outcome);
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════════
    // The owner's methods (a)–(g)
    // ═════════════════════════════════════════════════════════════════════════════════════════════

    /**
     * (a) ■ Every lane names the ROOT operator that owns this door — "root level tenant identification logic will vary for call, sms, wifi"
     * (R1): the call by its ESL socket ({@link #byEslSocket}), the ad and the SMS by the HTTP listener ({@link #byListener}), the WiFi by its
     * VLAN ({@link #byVlan}), the SMS's Kafka door by its topic ({@link #byTopic}) — each helper asks prime-context's identification rules.
     * Null = nobody's: the session is refused {@code NO_TENANT}, never given a default root.
     */
    protected abstract String identifyTenant(RoutesphereFacts facts);

    /** (a) helper · The root of an HTTP listener (the local address the request arrived on); null = no rule names it. */
    protected final String byListener(String ip, int port) { return kit.lookups().byListener(ip, port); }

    /** (a) helper · The same, the address written {@code <ip>:<port>}. */
    protected final String byListener(String ipAndPort) { return kit.lookups().byListener(ipAndPort); }

    /** (a) helper · The root of the FreeSWITCH connection a CHANNEL_PARK came from; null = no rule names it. */
    protected final String byEslSocket(String host, int port) { return kit.lookups().byEslSocket(host, port); }

    /** (a) helper · The same, the socket written {@code <host>:<port>}. */
    protected final String byEslSocket(String hostAndPort) { return kit.lookups().byEslSocket(hostAndPort); }

    /** (a) helper · The root of a WiFi gateway and its S-VLAN; null = no rule names it. */
    protected final String byVlan(String gateway, int svlan) { return kit.lookups().byVlan(gateway, svlan); }

    /** (a) helper · The root of the Kafka topic a message was consumed from; null = no rule names it. */
    protected final String byTopic(String name) { return kit.lookups().byTopic(name); }

    /** (a) helper · The root of an ingress as the facts carry it ({@code facts.ingress()}); null = no door, or no rule names it. */
    protected final String byIngress(Ingress ingress) { return kit.lookups().byIngress(ingress); }

    /** (b) Leaf → reseller → … → root, one tier at a time, inside the session's own tree: the base rule, never overridden. */
    protected final List<Tier> iterateResellerTenants(Tier leaf) { return leaf.ancestorChain(); }

    /**
     * (c) Who pays for this candidate, found in which tier of the session's OWN tree (the lowest in-partner first): a call from the
     * wire, an ad from the chosen campaign's advertiser. Null = nobody ({@code PARTNER_NOT_FOUND}, or a free candidate runs with no
     * tier). {@link #entryOfPartner} finds a partner by id in the session's tree.
     */
    protected abstract Tier identifyEntryPartner(C ctx, Candidate candidate);

    /**
     * (c) Above the leaf: the partner that pays at {@code tier} for the tenant below it — the call switch's live rule: {@code res_<id>}
     * → partner {@code <id>} of this tier, else the partner named as the tenant below ({@link #resellerPartnerOf}).
     */
    protected Partner identifyPartner(Tier tier) { return resellerPartnerOf(tier.below().tenant(), tier.tenant()); }

    /**
     * (d) Where the session goes: a call — the dialplan at the root, hops in order; an ad — the creative of the candidate; an SMS — the
     * SMSC. Asked once per candidate INSIDE the admission walk, after its tiers reserved and before the last gate (C8) — so route by
     * {@code ctx.candidateBeingAdmitted()}: {@code ctx.chosen} is set only when a candidate was admitted. An empty plan refuses this
     * candidate with NO_ROUTE (its reserves go back) and the next is tried.
     */
    protected abstract RoutePlan routing(C ctx);

    /**
     * (e) Reserve at this tier: the call model — the tier's rate ({@link #rateAtLevel}: package units before money, ONE unit), held on ITS
     * account through the one ledger port, under the session's and the tier's own reference. Override only to hold in one move of your
     * own — or answer {@link TierRate#held} from {@link #rateAtLevel} instead.
     */
    protected Verdict reserveBalance(C ctx, Tier tier, Partner partner) { return AdmissionChain.of(this).rateAndReserve(ctx, tier, partner); }

    /**
     * (e) The tier's final cost, in the final state: delivered → charged = rate × billed units ({@link #chargeAtSettle}), the rest back
     * (negative = an extra debit, the call's rule); not delivered → the refund rule ({@link #refundPolicy} on {@link #settleOutcome}).
     * Through the one ledger port, idempotent per (session, tier).
     */
    protected TierSettlement finalizeCost(C ctx, LevelAdmission tier, Outcome outcome) {
        BigDecimal charged = delivered(ctx, outcome) ? chargeAtSettle(ctx, tier) : chargeByTheRefundRule(ctx, tier);
        if (charged == null || charged.signum() < 0) charged = BigDecimal.ZERO;
        return kit.ledger().settle(tier, charged);
    }

    /**
     * (f) Failover by cause, after a signaling failure: RETRY_SAME → the same hop; REROUTE → the plan's next hop while the attempts are under
     * its cap; FAIL_TERMINAL → the end. The library sends the next {@code Connect}; nothing is re-admitted or re-reserved.
     */
    protected boolean handleRerouting(C ctx, SignalingFailed failed) { return rerouteByTable(ctx, failed.cause()); }

    /**
     * (g) The finish, from both finals, once: the teardown, the channel slot, the CDR per tier (one message, leaf first, on
     * {@code cdr_<root>} — the switch publishes, billing-core writes, R13), the task, the SDR, the application's own close. No step's
     * failure stops the next.
     */
    protected final void finishSession(C ctx, Outcome outcome) {
        guarded(ctx, "TEARDOWN", () -> tearDown(ctx, outcome));
        guarded(ctx, "RELEASE_SLOT", () -> kit.slots().release(ctx.sessionId()));
        guarded(ctx, "PUBLISH_CDR", () -> SessionCdr.of(this).publish(ctx, outcome));
        guarded(ctx, "CLOSE_TASK", () -> SessionTaskClose.of(this).close(ctx, outcome));
        guarded(ctx, "WRITE_SDR", () -> writeTheSdr(ctx, outcome));
        guarded(ctx, "ON_ENDED", () -> onEnded(ctx, outcome));
        kit.counters().ended.incrementAndGet();
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════════
    // The smaller hooks, in the order a session meets them — the call's defaults; ■ = every lane answers it
    // ═════════════════════════════════════════════════════════════════════════════════════════════

    /** INIT · Turn the request into the session's task on the context (the numbers, the source, the kind). The refusal, or null. */
    protected String buildTask(C ctx) { return null; }

    /** INIT · Who may serve this session, in failover order. The call: exactly one candidate, itself. */
    protected List<Candidate> selectCandidates(C ctx) { return List.of(Candidate.SELF); }

    /** INIT · Does this session renew its reserves while ACTIVE (the call, the WiFi session)? Default: when the settings set a period. */
    protected boolean renewsBudget() { return kit.settings().renews(); }

    /** ADMITTING · This candidate costs nothing (the house ad): every tier walked at zero, nothing reserved. */
    protected boolean isFree(C ctx, Candidate candidate) { return candidate != null && candidate.free(); }

    /**
     * ADMITTING, every tier, first · The partner must be active: a status that is set and is not {@code ACTIVE} refuses with
     * {@code PARTNER_DEACTIVATED} (the call switch's {@code DefaultAdmissionService.admitAtLevel}). No status passes.
     */
    protected Verdict checkPartner(C ctx, Tier tier, Partner partner) {
        boolean active = partner.getStatus() == null || "ACTIVE".equalsIgnoreCase(partner.getStatus());
        return active ? Verdict.ok() : Verdict.reject(SessionCause.PARTNER_DEACTIVATED);
    }

    /** ADMITTING, every tier, BEFORE the leaf's channel slot · Checks that refuse without taking a slot (a call: {@code INVALID_DID}). */
    protected Verdict authorizeBeforeSlot(C ctx, Tier tier, Partner partner) { return Verdict.ok(); }

    /** ADMITTING, every tier, AFTER the leaf's channel slot · The application's gates (a call: the SIP account's cap). A refusal frees the slot. */
    protected Verdict authorize(C ctx, Tier tier, Partner partner) { return Verdict.ok(); }

    /** ADMITTING, the root tier only · The root's rules on the task (a call: the digit filter). */
    protected Verdict applyRootRules(C ctx, Tier root, Partner rootPartner) { return Verdict.ok(); }

    /**
     * ADMITTING, every tier · What this tier's partner pays for this session on THIS tier's own rate plan, and how much admission reserves
     * (a call: one unit of talk time; an SMS: every part; an ad: the whole view). Null = no rate ({@code UNRATED}).
     */
    protected abstract TierRate rateAtLevel(C ctx, Tier tier, Partner partner);

    /** ADMITTING, last · What must hold before the session may start (an ad: claim one view of the quota). A refusal gives every reserve back. */
    protected Verdict confirmAdmission(C ctx, Candidate candidate) { return Verdict.ok(); }

    /** ADMITTING · The cause when a tier cannot pay (a call abroad: {@code NO_BALANCE_INT_OUT}). */
    protected String noBalanceCause(C ctx) { return SessionCause.INSUFFICIENT_BALANCE; }

    /** ADMITTING · The session's cause when no candidate was admitted. */
    protected String rejectCause(C ctx) {
        if (ctx.systemFault != null) return ctx.systemFault;
        if (ctx.budgetSpent) return SessionCause.ADMISSION_TIMEOUT;
        return ctx.lastRefusal != null ? ctx.lastRefusal : SessionCause.NO_CANDIDATE;
    }

    /**
     * CONNECTING ■ · Every lane decides its failover by cause, after a signaling failure: {@link RerouteAction#RETRY_SAME} = the same hop,
     * {@link RerouteAction#REROUTE} = the plan's next hop (under its cap), {@link RerouteAction#FAIL_TERMINAL} = the end. The call answers
     * the call switch's v1 table (C12: {@code USER_BUSY} → REROUTE, {@code SWITCH_CONGESTION} → RETRY_SAME, …); a lane with one hop and no
     * retry (the ad after its hand-over, the WiFi gate) answers FAIL_TERMINAL for every cause. Null = FAIL_TERMINAL.
     */
    protected abstract RerouteAction rerouteActionFor(String cause);

    /** CONNECTING · The signaling reported a phase (ringing, early media, the ad shown). The base stamped the first one (the PDD). */
    protected void onSignalingProgress(C ctx, SignalingProgress progress) { }

    /** CONNECTING → ACTIVE · The answer (a call: the 200 OK; an ad: viewed; an SMS: submitted). */
    protected void onAnswered(C ctx, Established answer) { }

    /** ACTIVE · The service runs (the budget window is open). */
    protected void onServiceStarted(C ctx) { }

    /** ACTIVE → SUCCEEDED · The service ended. */
    protected void onServiceEnded(C ctx, ServiceEnd end) { }

    /** CONNECTING → FAILED · The last signaling failure (no retry). */
    protected void onSignalingFailed(C ctx, SignalingFailed failed) { }

    /**
     * ACTIVE, every renewal · What this tier reserves for the next window of a long session. Null = this tier does not renew. Asked by
     * the default {@link #renewWindowSeconds}.
     */
    protected TierRate rateNextWindow(C ctx, LevelAdmission tier) { return null; }

    /**
     * ACTIVE, every renewal (C14) · Renew this tier's reserve for the next window and answer how many seconds it funds: the period = held;
     * less = only that much (the cut comes when it ends); 0 = nothing is left. The default rates the window with {@link #rateNextWindow}
     * and holds it through the ledger; a ledger FAULT answers the period (a fault never cuts a session: the settlement reconciles).
     * An override gives up the next-account rotation unless it calls {@code super} first.
     */
    protected double renewWindowSeconds(C ctx, LevelAdmission tier) { return AdmissionChain.of(this).renewThroughLedger(ctx, tier); }

    /** ACTIVE · A tier's account could not fund the next window: the NEXT account to carry the session on, or null (the money ended). */
    protected Long nextAccount(C ctx, LevelAdmission tier) { return null; }

    /**
     * The final state ■ · Every lane decides what "delivered" means: delivered, each tier pays rate × billed units; not delivered, the refund
     * rule decides. The call: it was answered ({@code ctx.answered()}); the SMS: its delivery report said delivered; the ad: the view was shown.
     */
    protected abstract boolean delivered(C ctx, Outcome outcome);

    /**
     * The final state · How many of the rate's units the session is billed for — the same for every tier, each at its own rate (the
     * ad: the seconds watched on a per-second rate; the call: its pulses). Null, the default = exactly the units it reserved (a pre-rated
     * event: the view, the message): the tier pays its reserve. §L.3: a session restored from the store ({@code ctx.restoredAtMs})
     * spans the downtime unless the lane says otherwise — the owner's business decision.
     */
    protected BigDecimal billedUnits(C ctx) { return null; }

    /**
     * The final state, delivered · What this tier is charged: its rate × {@link #billedUnits}, or its whole reserve when the units are
     * the reserved ones. A lane priced by its own rounding (the voice call's pulses, the WiFi's windows) overrides this whole.
     */
    protected BigDecimal chargeAtSettle(C ctx, LevelAdmission tier) {
        BigDecimal units = billedUnits(ctx);
        if (units == null) return TierSettlement.reservedOf(tier);
        BigDecimal rate = tier.getRate() == null ? BigDecimal.ZERO : tier.getRate();
        return rate.multiply(units).setScale(Math.max(2, rate.scale()), RoundingMode.HALF_UP);
    }

    /** The final state · What the session came to, for the refund rule: answered = consumed, else failed (the ad: offered, not shown). */
    protected SettleOutcome settleOutcome(C ctx) { return ctx.answered() ? SettleOutcome.CONSUMED : SettleOutcome.FAILED; }

    /**
     * The final state ■ · Every lane names its refund rule, as its config stands now: what a session NOT delivered pays, by
     * {@link #settleOutcome} (failed · offered and not consumed). The call: {@code RefundPolicy.callsRule()} (a failed session pays nothing);
     * the ad: the unshown rule by config; the SMS: the non-delivery rule by config. Null = the call's rule.
     */
    protected abstract RefundPolicy refundPolicy(C ctx);

    /**
     * The settlement, first · The billed duration in seconds, fixed once; the CDR carries the same number. Default: what the signaling
     * wrote ({@code durationSec}), else answer → end.
     */
    protected double billedDuration(C ctx, long nowMs) {
        if (ctx.durationSec > 0 || !ctx.answered()) return ctx.durationSec;
        long end = ctx.endedAtMs > 0 ? ctx.endedAtMs : nowMs;
        return Math.max(0, end - ctx.answeredAtMs()) / 1000.0;
    }

    /** The finish · Stop the service on the application's side, once (the wire's own teardown is the signaling machine's). */
    protected void onTeardown(C ctx, Outcome outcome) { }

    /** The finish · The session's CDR: one record per tier, leaf first (one per span of a tier that rotated). */
    protected List<CdrEvent> cdrPerTier(C ctx, Outcome outcome) { return SessionCdr.of(this).assemble(ctx, outcome); }

    /** The CDR · The application's own fields on one tier's record and its facts in {@code cdr.meta}. {@code tier} null = nobody admitted. */
    protected void fillCdr(C ctx, LevelAdmission tier, CdrEvent cdr) { }

    /** The CDR's hangup cause. */
    protected String cdrCause(C ctx, Outcome outcome) {
        if (ctx.endCause != null) return ctx.endCause;
        return outcome == Outcome.SUCCEEDED ? SessionCause.NORMAL_CLEARING : SessionCause.INTERNAL_ERROR;
    }

    /** The CDR of a session nobody was admitted for · The partner to write it on when none was identified. */
    protected Integer payerWhenUnknown(C ctx, Tenant tenant) { return null; }

    /** Does a tier with this unit pay in money (the CDR's cost) or in package units (the CDR's package amount)? */
    protected boolean paysInMoney(String uom) { return uom == null || "BDT".equalsIgnoreCase(uom); }

    /** The finish · Did the session's TASK complete? Default: the session succeeded (an ad: the view watched to its end). */
    protected boolean taskCompleted(C ctx, Outcome outcome) { return outcome == Outcome.SUCCEEDED; }

    /** The finish · The task record's end cause. Default: the CDR's cause word. */
    protected String taskCause(C ctx, Outcome outcome) { return cdrCause(ctx, outcome); }

    /** The finish · The application's session record (the SDR), or null for none. */
    protected Object buildSdr(C ctx, Outcome outcome) { return null; }

    /** The finish, last · The application's own closing. */
    protected void onEnded(C ctx, Outcome outcome) { }

    // ═════════════════════════════════════════════════════════════════════════════════════════════
    // Help for the steps
    // ═════════════════════════════════════════════════════════════════════════════════════════════

    /** The entry of a partner whose id is known (the ad's advertiser), INSIDE THE SESSION'S OWN TREE; null = this tree holds no such partner. */
    protected final Tier entryOfPartner(C ctx, int partnerId) {
        if (ctx.tree == null) return null;
        EntryPartner entry = ctx.tree.entryOf(partnerId);
        return entry == null ? null : Tier.entry(entry);
    }

    /** What is left of this session's admission budget, in milliseconds (a dry run: {@code Long.MAX_VALUE}). */
    protected final long admissionTimeLeftMs(C ctx) {
        long deadline = ctx.admissionDeadlineMs;
        return deadline <= 0 || deadline == Long.MAX_VALUE ? Long.MAX_VALUE : deadline - kit.clock().millis();
    }

    /** True when no time is left for a candidate that pays; asking marks the session ({@code ADMISSION_TIMEOUT} if nobody is admitted). */
    protected final boolean paidTimeIsOver(C ctx) {
        if (admissionTimeLeftMs(ctx) > 0) return false;
        if (!ctx.budgetSpent) ctx.history.note(name(), "the admission budget is spent: no candidate that pays is tried any more");
        ctx.budgetSpent = true;
        return true;
    }

    /**
     * The v1 ritual (C12): RETRY_SAME tries the same hop again; REROUTE moves the plan to its next hop while the attempts made are under
     * the plan's cap ({@link RoutePlan#maxAttempts}, v1's 3); FAIL_TERMINAL ends the session with the cause.
     */
    protected final boolean rerouteByTable(C ctx, String cause) {
        RerouteAction action = rerouteActionFor(cause);
        if (action == null) action = RerouteAction.FAIL_TERMINAL;
        RoutePlan plan = ctx.routePlan;
        return switch (action) {
            case RETRY_SAME -> true;
            case REROUTE -> plan != null && ctx.attempts < plan.maxAttempts() && plan.advance();
            case FAIL_TERMINAL -> false;
        };
    }

    /**
     * ACTIVE, every renewal tick (the budget child asks it): every tier renews its window; the narrowest answer, in seconds, is how long
     * the session may still run. The period = it goes on; 0 = a tier can pay nothing more; between = a partial window. Under the session's
     * money lock: a renewal never runs beside the settlement of the same session.
     */
    public final double renewReserves(C ctx) {
        double period = kit.settings().reservePeriodSec();
        ctx.money.lock();
        try {
            if (ctx.reservesClosed) return period;
            bringBackTheLiveTiers(ctx);
            double narrowest = narrowestWindow(ctx, period);
            ctx.keepTiers();
            return narrowest;
        } finally {
            ctx.money.unlock();
        }
    }

    private double narrowestWindow(C ctx, double period) {
        double narrowest = period;
        for (LevelAdmission level : ctx.levels) {
            narrowest = Math.min(narrowest, safely(ctx, "renewWindowSeconds", () -> renewWindowSeconds(ctx, level), period));
            if (narrowest <= 0) break;
        }
        return narrowest;
    }

    /**
     * ACTIVE · Hold {@code amount} of this tier's next window through the ledger under {@code reference}, recorded on the tier exactly as the
     * base's own renewal records it — for a lane that answers {@link #renewWindowSeconds} itself. Null = held; else the cause.
     */
    protected final String reserveWindow(C ctx, LevelAdmission tier, BigDecimal amount, String reference) {
        return AdmissionChain.of(this).reserveForRenewal(ctx, tier, amount, reference);
    }

    /**
     * The parent's partner that stands for the child tenant — the call switch's LIVE rule
     * ({@code CallAdmissionController.identifyPartnerAtParentLevel}): the partner whose id the child's database name ENDS with
     * ({@code res_233} → partner 233 of the root, {@code res_233_2} → partner 2 of {@code res_233}), else the partner NAMED as the child
     * tenant (its name or database name, without case). When both answer and disagree, the id wins (logged). No partner type is asked
     * (the call's data types a reseller 4, the ad's {@link PartnerType#RESELLER} = 100).
     */
    public static Partner resellerPartnerOf(Tenant child, Tenant parent) {
        if (child == null || parent == null || parent.getContext() == null || parent.getContext().getPartners() == null) return null;
        Partner byId = parent.getContext().getPartners().get(idAtTheEndOf(child.getDbName()));
        Partner byName = partnerNamedAs(child, parent);
        if (byId != null && byName != null && byId != byName) {
            log.warn("tier {}: partner {} ({}) is NAMED as the tenant below, but its database name ends with partner {} ({}): the id wins",
                parent.getDbName(), byName.getIdPartner(), byName.getPartnerName(), byId.getIdPartner(), byId.getPartnerName());
        }
        return byId != null ? byId : byName;
    }

    private static Partner partnerNamedAs(Tenant child, Tenant parent) {
        String childName = child.getName() == null || child.getName().isBlank() ? child.getDbName() : child.getName();
        for (Partner p : parent.getContext().getPartners().values()) {
            if (p != null && namesTenant(p.getPartnerName(), childName, child.getDbName())) return p;
        }
        return null;
    }

    private static boolean namesTenant(String partnerName, String tenantName, String tenantDbName) {
        if (partnerName == null) return false;
        return partnerName.equalsIgnoreCase(tenantName) || partnerName.equalsIgnoreCase(tenantDbName);
    }

    /** The number a schema name ends with after its last underscore; -1 when it ends with none. */
    private static int idAtTheEndOf(String schemaName) {
        if (schemaName == null) return -1;
        String tail = schemaName.substring(schemaName.lastIndexOf('_') + 1);
        if (tail.isEmpty() || tail.length() > 9 || !tail.chars().allMatch(Character::isDigit)) return -1;
        return Integer.parseInt(tail);
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════════
    // The dry run
    // ═════════════════════════════════════════════════════════════════════════════════════════════

    /**
     * What this session WOULD come to, with no side effect and no machine (a lane's routing admin, a price preview): the same start and
     * admission walk on the facts already on the context — nothing reserved, no slot, no CDR, every step traced. A lane's
     * {@code confirmAdmission} must claim nothing when {@code ctx.dryRun} is set.
     */
    public final DryRun simulate(C ctx) {
        ctx.traced = true;
        ctx.dryRun = true;
        onStart(ctx, new SessionStart(ctx.facts));
        if (ctx.startRefusal != null) return DryRun.of(false, ctx.startRefusal, ctx);
        ctx.candidates = candidatesOf(ctx);
        Verdict verdict = AdmissionChain.of(this).admitFirstCandidate(ctx);
        return DryRun.of(verdict.accepted(), verdict.cause(), ctx);
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════════
    // R1-6 · the sessions in the air (the journal): every reserve ends in a record or goes back, a process death included
    // ═════════════════════════════════════════════════════════════════════════════════════════════

    /**
     * The application hands the session to its user (an ad's start road hands the view to the phone): the session's line is written to the
     * journal FIRST, then {@code theFact} is asked (is the session still there to hand over?). False = do not hand it over.
     */
    public final boolean handOver(C ctx, java.util.function.BooleanSupplier theFact) {
        if (!journalTheHandOver(ctx)) return false;
        if (theFact.getAsBoolean()) return true;
        kit.journal().done(ctx.sessionId());
        return false;
    }

    private boolean journalTheHandOver(C ctx) {
        if (!kit.journal().keeps()) return true;
        try {
            kit.journal().handedOver(ctx.sessionId(), kit.clock().millis(), SessionCdr.of(this).lostRecordsOf(ctx));
            return true;
        } catch (RuntimeException e) {
            kit.counters().journalRefused.incrementAndGet();
            ctx.history.note(name(), "its line in the journal (" + kit.journal().where() + ") could not be written: " + e.getMessage()
                + " — not handed over, refused as a system fault; its reserves go back");
            return false;
        }
    }

    /** What the switch learned of a session in the air since its hand-over (its answer, its seconds so far). Never fails the session. */
    public final void noteInTheAir(String sessionId, long answeredAtMs, double billedSec) {
        try {
            kit.journal().noted(sessionId, kit.clock().millis(), answeredAtMs, billedSec);
        } catch (RuntimeException e) {
            log.warn("[{}] {} | the journal did not take what was learned of the session: {}", name(), sessionId, e.toString());
        }
    }

    /**
     * Before the first session of a start: the record of every session a stopped process left in the air is published (handed over: charged
     * as reserved, its reserves closed as such; never handed over: every reserve given back, its record at 0.00) on the root's road
     * ({@code cdr_<root>}). One WARN with the sums.
     */
    public final void publishWhatWasLeftInTheAir() {
        SessionCdr.LeftInTheAir left = SessionCdr.of(this).publishLeftovers();
        if (left.calls() == 0 && left.neverHandedOver() == 0) return;
        log.warn("[{}] the start published the records of {} session(s) a stopped process left in the air ({}): each ended {}, every tier charged what it"
            + " reserved and {} reserve(s) closed as such — {} in money, {} in units; and {} session(s) never handed over: {} reserve(s) given back ({}), {} OWED",
            name(), left.calls(), kit.journal().where(), SessionCause.LOST_AT_RESTART, left.closed(), left.money().toPlainString(), left.units().toPlainString(),
            left.neverHandedOver(), left.returned(), left.returnedMoney().toPlainString(), left.owed());
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════════
    // The base's own steps
    // ═════════════════════════════════════════════════════════════════════════════════════════════

    /** The plan step (d) made, made now if the session has none (a lane that routes after admission). */
    final RoutePlan planOf(C ctx) {
        if (ctx.routePlan == null) ctx.routePlan = step(ctx, "ROUTING", () -> routing(ctx));
        return ctx.routePlan;
    }

    /** (a) The lane's answer for the session's facts — facts that are not {@code RoutesphereFacts} name no door (said in the history). */
    private String rootOf(C ctx) {
        if (ctx.facts instanceof RoutesphereFacts facts) return safely(ctx, "identifyTenant", () -> identifyTenant(facts), null);
        noteFactsWithoutAnIngress(ctx);
        return null;
    }

    private void noteFactsWithoutAnIngress(C ctx) {
        String kind = ctx.facts == null ? "no facts" : ctx.facts.getClass().getName();
        ctx.history.note(name(), "the session's facts are " + kind + ", not RoutesphereFacts: they name no ingress, so no root — refused " + NO_TENANT);
    }

    private boolean takeTheRootsSnapshot(C ctx) {
        RootTree tree = kit.tenants().snapshot(ctx.root).orElse(null);
        if (tree == null) return false;
        ctx.tree = tree;
        ctx.treeVersion = tree.version();
        return true;
    }

    private void refuseAtStart(C ctx, String cause) {
        ctx.startRefusal = cause;
        ctx.history.note(name(), "refused before admission: " + cause + (ctx.root == null ? "" : " (root " + ctx.root + ")"));
    }

    private List<Candidate> candidatesOf(C ctx) {
        List<Candidate> chosen = safely(ctx, "selectCandidates", () -> selectCandidates(ctx), List.of());
        return chosen == null ? List.of() : List.copyOf(chosen);
    }

    private void recordFailedAttempt(C ctx, SignalingFailed failed) {
        if (ctx.routePlan != null) ctx.routePlan.record(failed.cause(), kit.clock().millis());
        ctx.history.note(name(), "attempt " + ctx.attempts + " failed: " + failed.cause() + " (" + failed.code() + ")");
    }

    private void tearDown(C ctx, Outcome outcome) {
        if (ctx.tornDown) return;
        ctx.tornDown = true;
        onTeardown(ctx, outcome);
    }

    private void writeTheSdr(C ctx, Outcome outcome) {
        Object sdr = buildSdr(ctx, outcome);
        if (sdr != null) kit.sdr().write(ctx.sessionId(), sdr);
    }

    /** What a tier not delivered pays: its whole reserve when the lane's refund rule charges the outcome, else nothing. */
    final BigDecimal chargeByTheRefundRule(C ctx, LevelAdmission tier) {
        RefundPolicy policy = refundPolicy(ctx);
        SettleOutcome outcome = settleOutcome(ctx);
        if (policy == null) policy = RefundPolicy.callsRule();
        if (outcome == null) outcome = ctx.answered() ? SettleOutcome.CONSUMED : SettleOutcome.FAILED;
        return policy.pays(outcome) ? TierSettlement.reservedOf(tier) : BigDecimal.ZERO;
    }

    /**
     * After a restore the live pictures are gone (they are never persisted): the root's snapshot is taken again by the root (§L.6 — the
     * version it started with is noted when it moved), and the live tiers and the closed spans (O4) are rebuilt from their persisted lines
     * on it.
     */
    final void bringBackTheLiveTiers(C ctx) {
        if (ctx.tree == null && ctx.root != null) retakeTheRootsSnapshot(ctx);
        if (ctx.levels.isEmpty() && !ctx.tiers.isEmpty()) {
            ctx.levels = ctx.tiers.stream().map(line -> line.toLevel(ctx.tree)).toList();
        }
        if (ctx.closedSpans.isEmpty() && !ctx.spans.isEmpty()) {
            ctx.closedSpans = ctx.spans.stream().map(line -> line.toSpan(ctx.tree)).toList();
        }
    }

    private void retakeTheRootsSnapshot(C ctx) {
        ctx.tree = kit.tenants().snapshot(ctx.root).orElse(null);
        if (ctx.restoredAtMs == 0) ctx.restoredAtMs = kit.clock().millis();
        long now = ctx.tree == null ? -1 : ctx.tree.version();
        if (now != ctx.treeVersion) {
            ctx.history.note(name(), "restored on the root's snapshot v" + now + " (it started on v" + ctx.treeVersion + ")");
        }
    }

    /** Run one step. With debug on the step is timed and written to the session's history and the log. */
    final <T> T step(C ctx, String stepName, Supplier<T> body) {
        boolean debug = kit.settings().debug();
        if (!ctx.traced && !debug) return body.get();
        long startedAt = System.nanoTime();
        T result = body.get();
        long micros = (System.nanoTime() - startedAt) / 1_000;
        Object shown = result == null ? "ok" : result;
        ctx.history.note(name(), stepName + " → " + shown + " (" + micros + " µs)");
        if (debug) log.info("[{}] {} | {} | {} | {} µs", name(), ctx.sessionId(), stepName, shown, micros);
        return result;
    }

    final String internalError(C ctx, String where, RuntimeException e) {
        ctx.history.note(name(), where + " threw: " + e);
        log.error("[{}] {} | {} threw — refused with INTERNAL_ERROR", name(), ctx.sessionId(), where, e);
        return SessionCause.INTERNAL_ERROR;
    }

    /** Run a step of the application that must never break the session: a throw is logged and {@code whenItThrows} is the answer. */
    final <T> T safely(C ctx, String stepName, Supplier<T> body, T whenItThrows) {
        try {
            return body.get();
        } catch (RuntimeException e) {
            ctx.history.note(name(), stepName + " threw: " + e);
            log.error("[{}] {} | {} threw", name(), ctx.sessionId(), stepName, e);
            return whenItThrows;
        }
    }

    final void guarded(C ctx, String stepName, Runnable body) {
        safely(ctx, stepName, () -> { body.run(); return null; }, null);
    }
}
