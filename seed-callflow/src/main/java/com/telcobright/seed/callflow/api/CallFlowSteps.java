package com.telcobright.seed.callflow.api;

import com.telcobright.rtc.domainmodel.LevelAdmission;
import com.telcobright.rtc.domainmodel.PartnerType;
import com.telcobright.rtc.domainmodel.mysqlentity.Partner;
import com.telcobright.rtc.domainmodel.nonentity.Tenant;
import com.telcobright.seed.callflow.dependencies.CallFlowKit;
import com.telcobright.statewalk.pipeline.StepMode;
import com.telcobright.statewalk.registry.InternalEventResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.Optional;

/**
 * The steps of a call that an application may say in its own way — the override surface of {@link CallFlow}, in the
 * order a call meets them. Six steps have no default: every application must say them. Every other step has the
 * multi-tenant call switch's own behaviour as its default; override one only where the application differs.
 *
 * <p><b>The rules of a step.</b> A flow is ONE object shared by every call: a step keeps nothing in a field. What a step
 * learns goes onto the call's context. A step that refuses returns the cause (a {@link CallCause} word, or the
 * application's own); null means "passed". A step must not throw: a throw ends the call with {@code INTERNAL_ERROR}.
 *
 * @param <C> the application's context
 */
public abstract class CallFlowSteps<C extends CallFlowContext> {

    protected final Logger log = LoggerFactory.getLogger(getClass());
    protected final CallFlowKit kit;

    protected CallFlowSteps(CallFlowKit kit) { this.kit = Objects.requireNonNull(kit, "kit"); }

    /** What the host handed in: the tenant tree, the ledger, the CDR sink, the clock, the settings. */
    public final CallFlowKit kit() { return kit; }

    // ═════════════════════════════════════════════════════════════════════════════════════════════
    // What every application says
    // ═════════════════════════════════════════════════════════════════════════════════════════════

    /** The application's name: the machine type, the pool's name, the tag of its log lines ({@code call}, {@code sms}, {@code ad}). */
    public abstract String name();

    /** The CDR's service group. 0 = billing detects it (a voice call). 30 = an ad view, taken as given. */
    protected abstract int serviceGroup(C ctx);

    /**
     * PREPROCESSING · Turn the request into the call's task: the numbers, the source, the kind — everything the later
     * steps read from the context.
     */
    protected abstract String buildTask(C ctx);

    /**
     * ADMITTING · The partner this call belongs to, and the tenant that partner lives in (a call: by the source address
     * or the SIP account; an SMS: by the user name; an ad: the advertiser of the candidate). Null = nobody.
     */
    protected abstract EntryPartner identifyEntryPartner(C ctx);

    /**
     * ADMITTING, at every tier · What this tier's partner pays for this call on THIS tier's own rate plan, and how much
     * of it admission reserves. Null = this tier has no rate for the call ({@code UNRATED}).
     */
    protected abstract TierRate rateAtLevel(C ctx, Tenant tier, Partner partner, int levelIndex);

    /** ADMITTED · Start the signaling: spawn the children that carry the call on the wire. Runs again for a retry. */
    protected abstract void startSignaling(C ctx, CallMachine machine);

    // ═════════════════════════════════════════════════════════════════════════════════════════════
    // What it may say differently — in the order a call meets them
    // ═════════════════════════════════════════════════════════════════════════════════════════════

    /** The deadline of every state. Read once per machine, when the machine is first used. */
    public CallFlowTimings timings() { return kit.settings().timings(); }

    /**
     * Which of the application's events go to which child. Runs while a machine is BUILT: name event classes and child
     * types only, read no field. The base's own events are already routed.
     */
    public void defineRoutes(InternalEventResolver routes) { }

    /** PREPROCESSING · The tenant the request named must be one this process serves. A request that names none passes. */
    protected String resolveTenant(C ctx) {
        if (ctx.tenantName == null) return null;
        return kit.tenants().tenantByDbName(ctx.tenantName).isPresent() ? null : CallCause.TENANT_UNAVAILABLE;
    }

    /**
     * PREPROCESSING · Work out who may be tried for this call, in failover order, and keep them on the context (the ad:
     * the rule, the dialplan walk, the runnable campaigns). The default call has exactly one candidate: itself.
     */
    protected String selectCandidates(C ctx) { return null; }

    /** ADMITTING · How many candidates {@link #selectCandidates} kept. */
    protected int candidateCount(C ctx) { return 1; }

    /**
     * ADMITTING · Make candidate {@code index} the call's current task (the ad: invert the view for this campaign and
     * content). False = skip it (it is no longer eligible; {@code ctx.systemFault} tells a ledger fault happened).
     */
    protected boolean useCandidate(C ctx, int index) { return true; }

    /** ADMITTING · This call costs nothing (the house ad): every tier is walked and recorded at zero, nothing is reserved. */
    protected boolean isFree(C ctx) { return false; }

    /**
     * ADMITTING, above the leaf · The partner that pays at {@code tier} for traffic of the tenant below it. The default
     * is the call switch's rule: the tier's partner of type RESELLER whose name is the child tenant's.
     */
    protected Partner identifyPartner(C ctx, Tenant childTier, Tenant tier) { return resellerPartnerOf(childTier, tier); }

    /** ADMITTING, at every tier · The partner must be active. */
    protected String checkPartner(C ctx, Tenant tier, Partner partner, int levelIndex) {
        boolean active = partner.getStatus() == null || "ACTIVE".equalsIgnoreCase(partner.getStatus());
        return active ? null : CallCause.PARTNER_DEACTIVATED;
    }

    /**
     * ADMITTING, at every tier · The application's own authorization, after the partner's channel slot was taken at the
     * leaf (a call: the DID must be the partner's; the SIP account's own cap).
     */
    protected String authorize(C ctx, Tenant tier, Partner partner, int levelIndex) { return null; }

    /** ADMITTING, at the root tier only · The root's rules on the task (a call: the digit filter). */
    protected String applyRootRules(C ctx, Tenant root, Partner rootPartner) { return null; }

    /**
     * ADMITTING, after every tier reserved · Resolve the route at the root tenant and put it on the context
     * ({@code outgoingRoute}, {@code outPartnerId}). An application that routed in PREPROCESSING leaves the default.
     * {@code root} is null only for a free call with no partner.
     */
    protected String resolveRoute(C ctx, Tenant root) { return null; }

    /**
     * ADMITTING, last · Whatever must hold before the call may start (the ad: claim one view of the campaign's quota).
     * A refusal here gives every reserve back and the next candidate is tried. In {@code SIMULATE} claim nothing.
     */
    protected String confirmAdmission(C ctx, StepMode mode) { return null; }

    /** ADMITTING · The cause when a tier cannot pay (a call to abroad: {@code NO_BALANCE_INT_OUT}). */
    protected String noBalanceCause(C ctx) { return CallCause.INSUFFICIENT_BALANCE; }

    /** ADMITTING · The call's cause when no candidate was admitted. */
    protected String rejectCause(C ctx) {
        if (ctx.systemFault != null) return ctx.systemFault;
        return ctx.lastRefusal != null ? ctx.lastRefusal : CallCause.NO_CANDIDATE;
    }

    /** ADMITTED / RINGING · The signaling reported a phase (ringing, early media). */
    protected void onProgress(C ctx, String phase) { }

    /** The signaling answered: copy what it granted onto the context. */
    protected void onAnswered(C ctx, Object grant) { }

    /** ACTIVE · The service runs. */
    protected void onActive(C ctx, CallMachine machine) { }

    /** The signaling failed: try again (another route)? True = the children are retired and {@link #startSignaling} runs again. */
    protected boolean nextAttempt(C ctx, String failureCause) { return false; }

    /**
     * Stop the service and answer the wire — exactly once per call, on EVERY end path, whatever state the call was in
     * (a call: hang up both legs, or play the no-balance announcement).
     */
    protected void onTeardown(C ctx, CallMachine machine) { }

    /**
     * The settle rule · What this tier finally pays. The base applies it exactly once per tier, on every end path. The
     * default is the call's rule for a pre-rated event: an answered call pays what it reserved, an unanswered call pays
     * nothing. A call rated by duration returns its rate × {@code ctx.durationSec}.
     */
    protected BigDecimal chargeAtSettle(C ctx, LevelAdmission level) {
        return ctx.answered() ? TierSettlement.reservedOf(level) : BigDecimal.ZERO;
    }

    /**
     * The settlement, first · The billed duration of the call in seconds. It is asked once, when the tiers are settled, and
     * written on the context: the CDR carries the same number. The default: what the signaling wrote on the context
     * ({@code durationSec}), else the time since the answer. An application that measures it itself returns its own (the
     * ad: the seconds watched).
     */
    protected double billedDuration(C ctx, long nowMs) {
        if (ctx.durationSec > 0 || !ctx.answered()) return ctx.durationSec;
        return Math.max(0, nowMs - ctx.answeredAtMs) / 1000.0;
    }

    /**
     * ACTIVE, every reserve period · What this tier reserves for the next window of a long call. Null = this tier does
     * not renew. Only asked when the settings name a reserve period.
     */
    protected TierRate rateNextWindow(C ctx, LevelAdmission level) { return null; }

    /** After the settlement · Did the call succeed? The default: it was answered and the service ran. */
    protected boolean succeeded(C ctx) { return ctx.activatedAtMs > 0; }

    /** The cause of a state's deadline (the ad names the signaling window {@code NOT_SHOWN}). */
    protected String timeoutCause(String state) {
        return switch (state) {
            case CallState.PREPROCESSING -> CallCause.PREPROCESS_TIMEOUT;
            case CallState.ADMITTING -> CallCause.ADMISSION_TIMEOUT;
            case CallState.ADMITTED, CallState.RINGING -> CallCause.NO_ANSWER;
            case CallState.ACTIVE -> CallCause.MAX_DURATION_REACHED;
            case CallState.TEARING_DOWN -> CallCause.SETTLE_TIMEOUT;
            default -> CallCause.INTERNAL_ERROR;
        };
    }

    /** The CDR's hangup cause. */
    protected String cdrCause(C ctx, String outcome) {
        if (ctx.endCause != null) return ctx.endCause;
        return CallState.SUCCEEDED.equals(outcome) ? CallCause.NORMAL_CLEARING : CallCause.INTERNAL_ERROR;
    }

    /**
     * The CDR · Add the application's own fields to one tier's record, and its own facts to {@code cdr.meta}. The base
     * has filled everything the context and the tier know. {@code level} is null for a call nobody was admitted for.
     */
    protected void fillCdr(C ctx, LevelAdmission level, CdrEvent cdr) { }

    /** The CDR of a call nobody was admitted for · The partner to write it on when none was identified (the tenant's own). */
    protected Integer payerWhenUnknown(C ctx, Tenant tenant) { return null; }

    /** Does a tier with this unit pay in money (the CDR's cost) or in package units (the CDR's package amount)? */
    protected boolean paysInMoney(String uom) { return uom == null || "BDT".equalsIgnoreCase(uom); }

    /** The call is over, its CDR is published · The application's own closing (the ad: close the campaign task). */
    protected void onEnded(C ctx, String outcome) { }

    /** The application's part of the session record. */
    protected Object buildSdr(C ctx, String outcome) { return null; }

    // ═════════════════════════════════════════════════════════════════════════════════════════════
    // Help for the steps
    // ═════════════════════════════════════════════════════════════════════════════════════════════

    /** The entry of a partner whose id is known (the ad's advertiser): the tenant whose partners hold it. */
    protected final EntryPartner entryOfPartner(int partnerId) {
        Optional<Tenant> tenant = kit.tenants().tenantOfPartner(partnerId);
        if (tenant.isEmpty() || tenant.get().getContext() == null) return null;
        Partner partner = tenant.get().getContext().getPartners().get(partnerId);
        return partner == null ? null : new EntryPartner(tenant.get(), partner);
    }

    /**
     * The parent's partner of type RESELLER that stands for the child tenant: the one whose name is the child tenant's
     * name or schema name (the call switch's convention), else the one whose id the child's schema name ends with
     * ({@code res_44} → partner 44; {@code res_44_7} → partner 7 of {@code res_44}).
     *
     * <p>The call switch also matched a partner name that the schema name merely STARTS with. That rule is left out on
     * purpose: with schemas named {@code res_<id>}, a partner called {@code res_4} would stand for {@code res_44}.
     */
    protected static Partner resellerPartnerOf(Tenant child, Tenant parent) {
        if (child == null || parent == null || parent.getContext() == null || parent.getContext().getPartners() == null) return null;
        String childName = child.getName() == null || child.getName().isBlank() ? child.getDbName() : child.getName();
        for (Partner p : parent.getContext().getPartners().values()) {
            if (p == null || !PartnerType.isReseller(p.getPartnerType())) continue;
            if (namesTenant(p.getPartnerName(), childName, child.getDbName())) return p;
        }
        Partner byId = parent.getContext().getPartners().get(idAtTheEndOf(child.getDbName()));
        return byId != null && PartnerType.isReseller(byId.getPartnerType()) ? byId : null;
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
}
