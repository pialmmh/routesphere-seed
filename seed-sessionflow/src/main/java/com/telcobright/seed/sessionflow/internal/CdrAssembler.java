package com.telcobright.seed.sessionflow.internal;

import com.telcobright.rtc.domainmodel.LevelAdmission;
import com.telcobright.rtc.domainmodel.nonentity.Tenant;
import com.telcobright.seed.sessionflow.api.SessionFlowContext;
import com.telcobright.seed.sessionflow.api.CdrEvent;
import com.telcobright.seed.sessionflow.api.ClosedSpan;
import com.telcobright.seed.sessionflow.api.TierSettlement;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Builds the CDR records of an ended call from its context: what every application's record has in common. The
 * application then adds its own fields. Nothing here reads a database or a clock: the context is the only source.
 *
 * <p>The sequence is a counter seeded from the clock at start — the producer's own running number, no table behind it.
 */
public final class CdrAssembler {

    private static final DateTimeFormatter WALL_CLOCK = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final String HIERARCHY_SEPARATOR = " > ";

    private final ZoneId zone;
    private final AtomicLong sequence;

    public CdrAssembler(ZoneId zone, long firstSequence) {
        this.zone = zone;
        this.sequence = new AtomicLong(firstSequence);
    }

    /** One tier's record: the call's facts, this tier's payer and rate, and what the settle step charged here. */
    public CdrEvent tierRecord(SessionFlowContext ctx, LevelAdmission level, TierSettlement settlement, int serviceGroup,
                               String hangupCause, boolean paidInMoney) {
        CdrEvent cdr = callFacts(ctx, serviceGroup, hangupCause);
        cdr.tenant = level.getDbName();
        cdr.resellerHierarchy = hierarchyOf(level.getTenant());
        cdr.inPartnerId = level.getPartnerId();
        cdr.isPrepaid = level.getPackageAccountId() != null || level.getDebitReference() != null ? 1 : 0;
        cdr.matchPrefixCustomer = level.getRatePrefix();
        cdr.callRatePerMinBDT = level.getRate();
        cdr.inPartnerUom = level.getUom();
        cdr.idPackageAccount = level.getPackageAccountId();
        BigDecimal charged = settlement == null ? BigDecimal.ZERO : settlement.charged();
        cdr.inPartnerCost = paidInMoney ? charged : BigDecimal.ZERO;
        cdr.packageAmount = paidInMoney ? BigDecimal.ZERO : charged;
        noteTier(cdr, level, settlement);
        return cdr;
    }

    /**
     * O4 · The record of one CLOSED span of a tier that rotated its account: the same tier and partner, its own id {@code <sid>.<n>}
     * (billing keys a record on the id and the tenant: one per purchase), its own account, charge, wall-clock and seconds; the session's
     * cause, with the span's own end in the meta data. {@code callId} stays the session's: the spans correlate by it.
     */
    public CdrEvent spanRecord(SessionFlowContext ctx, ClosedSpan span, int serviceGroup, String hangupCause, boolean paidInMoney) {
        CdrEvent cdr = tierRecord(ctx, span.level(), span.settlement(), serviceGroup, hangupCause, paidInMoney);
        long answeredAt = span.spanNo() == 1 ? ctx.answeredAtMs : span.startedAtMs();
        asSpan(cdr, ctx, span.level(), span.spanNo(), span.startedAtMs(), answeredAt, span.endedAtMs(), span.seconds());
        cdr.meta.put("spanEnd", "NEXT_ACCOUNT");
        return cdr;
    }

    /** O4 · The record of the LIVE span of a tier that rotated: {@code <sid>.<n>}, from the last rotation to the end. */
    public CdrEvent lastSpanRecord(SessionFlowContext ctx, LevelAdmission level, TierSettlement settlement, int serviceGroup, String hangupCause,
                                   boolean paidInMoney) {
        CdrEvent cdr = tierRecord(ctx, level, settlement, serviceGroup, hangupCause, paidInMoney);
        asSpan(cdr, ctx, level, ctx.spanNo(), ctx.spanStartedAtMs, ctx.spanStartedAtMs, ctx.endedAtMs, ctx.spanSeconds());
        return cdr;
    }

    private void asSpan(CdrEvent cdr, SessionFlowContext ctx, LevelAdmission level, int spanNo, long startedAtMs, long answeredAtMs, long endedAtMs,
                        double seconds) {
        cdr.channelCallUuid = ctx.sessionKey + "." + spanNo;
        cdr.startTime = wallClock(startedAtMs);
        cdr.answerTime = wallClock(answeredAtMs);
        cdr.endTime = wallClock(endedAtMs);
        cdr.durationSec = BigDecimal.valueOf(seconds);
        cdr.idPackageAccount = level.getPackageAccountId() != null ? level.getPackageAccountId() : level.getChargeAccountId();
        cdr.meta.put("span", spanNo);
    }

    /** The one record of a call nobody was admitted for: on the tenant it entered, zero amounts, its cause. */
    public CdrEvent unadmittedRecord(SessionFlowContext ctx, Tenant tenant, Integer payerId, int serviceGroup, String hangupCause) {
        CdrEvent cdr = callFacts(ctx, serviceGroup, hangupCause);
        cdr.tenant = tenant.getDbName();
        cdr.resellerHierarchy = hierarchyOf(tenant);
        cdr.inPartnerId = payerId;
        cdr.isPrepaid = 0;
        cdr.inPartnerCost = BigDecimal.ZERO;
        cdr.packageAmount = BigDecimal.ZERO;
        return cdr;
    }

    /** {@code root > … > this tier}: the tenant's ancestor chain, turned round. */
    public static String hierarchyOf(Tenant tier) {
        List<Tenant> leafToRoot = tier.getAncestorChain();
        List<String> names = new ArrayList<>(leafToRoot.size());
        for (int i = leafToRoot.size() - 1; i >= 0; i--) names.add(leafToRoot.get(i).getDbName());
        return String.join(HIERARCHY_SEPARATOR, names);
    }

    /** The wall clock of the root tenant's zone, as the CSV and billing write it. Null for a time that never came. */
    public String wallClock(long epochMs) {
        return epochMs <= 0 ? null : WALL_CLOCK.format(Instant.ofEpochMilli(epochMs).atZone(zone));
    }

    private CdrEvent callFacts(SessionFlowContext ctx, int serviceGroup, String hangupCause) {
        CdrEvent cdr = new CdrEvent();
        cdr.sequenceNo = sequence.incrementAndGet();
        cdr.callId = ctx.sessionKey;
        cdr.channelCallUuid = ctx.sessionKey;
        cdr.serviceGroup = serviceGroup > 0 ? serviceGroup : null;
        cdr.startTime = wallClock(ctx.createdAtMs);
        cdr.answerTime = wallClock(ctx.answeredAtMs);
        cdr.endTime = wallClock(ctx.endedAtMs);
        cdr.durationSec = BigDecimal.valueOf(ctx.durationSec);
        cdr.originatingCallingNumber = ctx.originatingCallingNumber;
        cdr.originatingCalledNumber = ctx.originatingCalledNumber;
        cdr.terminatingCallingNumber = ctx.terminatingCallingNumber != null ? ctx.terminatingCallingNumber : ctx.originatingCallingNumber;
        cdr.terminatingCalledNumber = ctx.billingCalledNumber();
        cdr.callerIp = ctx.callerIp;
        cdr.receiverIp = ctx.receiverIp;
        cdr.hangupCause = hangupCause;
        cdr.channelReadCodecName = ctx.codec;
        cdr.pdd = postDialDelayOf(ctx);
        cdr.outPartnerId = ctx.outPartnerId;
        cdr.incomingRoute = ctx.incomingRoute;
        cdr.outgoingRoute = ctx.outgoingRoute;
        return cdr;
    }

    /** From the start to the first sign of the far end: the first progress, else the answer. Null when neither came. */
    private static Float postDialDelayOf(SessionFlowContext ctx) {
        long firstSign = ctx.progressAtMs > 0 ? ctx.progressAtMs : ctx.answeredAtMs;
        if (firstSign <= 0 || ctx.createdAtMs <= 0) return null;
        return Math.max(0, firstSign - ctx.createdAtMs) / 1000f;
    }

    private static void noteTier(CdrEvent cdr, LevelAdmission level, TierSettlement settlement) {
        cdr.meta.put("levelIndex", level.getLevelIndex());
        if (level.getPartnerName() != null) cdr.meta.put("partnerName", level.getPartnerName());
        if (level.getBalanceBefore() != null) cdr.meta.put("balanceBefore", level.getBalanceBefore());
        BigDecimal balanceAfter = settlement != null && settlement.balanceAfter() != null ? settlement.balanceAfter() : level.getBalanceAfter();
        if (balanceAfter != null) cdr.meta.put("balanceAfter", balanceAfter);
        if (level.getDebitReference() != null) cdr.meta.put("reserveRef", level.getDebitReference());
        if (settlement != null && !settlement.closed()) cdr.meta.put("settle", "OWED");
    }
}
