package com.telcobright.seed.sessionflow.api;

import com.telcobright.rtc.domainmodel.LevelAdmission;
import com.telcobright.rtc.domainmodel.nonentity.Tenant;
import com.telcobright.seed.sessionflow.internal.CdrAssembler;
import com.telcobright.seed.sessionflow.internal.CdrJson;
import com.telcobright.seed.sessionflow.spi.SessionJournal;
import com.telcobright.statewalk.session.Outcome;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * The CDR of an ended call, for every application: ONE message per call, one record per tier, the leaf first. A call
 * nobody was admitted for still gets one record, on the tenant it entered, with zero amounts and its cause.
 *
 * <p>The switch only publishes (R13: every switch publishes its CDR to Kafka). billing-core writes the record and the summary input;
 * summary-service sums them. The work behind (g)'s CDR step of {@link RoutesphereFlow#finishSession}; the application's fields are
 * {@link RoutesphereFlow#fillCdr}.
 *
 * <p>R1-6: a call that was handed over has a line in the journal of the calls in the air, written with its records made as the
 * next start would publish them ({@link #lostRecordsOf}); its own end publishes its real record and marks that line done
 * (publish FIRST, then done: a death in between publishes the call twice, and billing-core takes a call once per tier).
 */
final class SessionCdr<C extends RoutesphereContext> {

    private final RoutesphereFlow<C> flow;
    private final CdrAssembler assembler;

    private SessionCdr(RoutesphereFlow<C> flow) {
        this.flow = flow;
        this.assembler = flow.kit().cdrAssembler();
    }

    static <C extends RoutesphereContext> SessionCdr<C> of(RoutesphereFlow<C> flow) { return new SessionCdr<>(flow); }

    /** (g) The session's ONE message: the application's {@code cdrPerTier}, on the root's road ({@code cdr_<root>}); once per session. */
    void publish(C ctx, Outcome outcome) {
        if (ctx.cdrPublished) return;
        List<CdrEvent> tiers = flow.cdrPerTier(ctx, outcome);
        if (tiers == null || tiers.isEmpty()) return;
        flow.kit().cdr().publish(AdmissionChain.rootDbOf(ctx), ctx.sessionId(), tiers);
        ctx.cdrPublished = true;
        flow.kit().counters().cdrPublished.incrementAndGet();
        markDone(ctx.sessionId());
    }

    /** One record per tier, the leaf first (a tier that rotated: one per span); a session nobody was admitted for: one, on its entry. */
    List<CdrEvent> assemble(C ctx, Outcome outcome) {
        int serviceGroup = flow.serviceGroup(ctx);
        String cause = flow.cdrCause(ctx, outcome);
        if (ctx.levels.isEmpty()) return unadmitted(ctx, serviceGroup, cause);
        List<CdrEvent> tiers = new ArrayList<>(ctx.levels.size());
        for (int i = 0; i < ctx.levels.size(); i++) tiers.addAll(tier(ctx, i, serviceGroup, cause));
        return tiers;
    }

    /** One record per tier — and, for a tier that rotated its account (O4), one record per span: the closed ones, then the live one. */
    private List<CdrEvent> tier(C ctx, int index, int laneGroup, String cause) {
        LevelAdmission level = ctx.levels.get(index);
        int serviceGroup = groupOf(ctx, level, laneGroup);
        TierSettlement settlement = index < ctx.settlements.size() ? ctx.settlements.get(index) : null;
        boolean money = flow.paysInMoney(level.getUom());
        if (!rotated(ctx, level)) return List.of(sealed(ctx, level, assembler.tierRecord(ctx, level, settlement, serviceGroup, cause, money)));
        List<CdrEvent> spans = new ArrayList<>(ctx.closedSpans.size() + 1);
        for (ClosedSpan span : ctx.closedSpans) spans.add(sealed(ctx, span.level(), assembler.spanRecord(ctx, span, serviceGroup, cause, money)));
        spans.add(sealed(ctx, level, assembler.lastSpanRecord(ctx, level, settlement, serviceGroup, cause, money)));
        return spans;
    }

    /** §15.3 · The group of the product that paid this tier where the walk matched one, else the lane's (the revenue stream of the charge record). */
    private static int groupOf(RoutesphereContext ctx, LevelAdmission level, int laneGroup) {
        Integer matched = ctx.tierServiceGroups == null ? null : ctx.tierServiceGroups.get(level.getLevelIndex());
        return matched != null && matched > 0 ? matched : laneGroup;
    }

    private static boolean rotated(RoutesphereContext ctx, LevelAdmission level) {
        return !ctx.closedSpans.isEmpty() && ctx.closedSpans.get(0).level().getLevelIndex() == level.getLevelIndex();
    }

    /** Nobody was admitted (or the call was free and had no tier): one record on the tenant the call entered. */
    private List<CdrEvent> unadmitted(C ctx, int serviceGroup, String cause) {
        Tenant tenant = ctx.entryTenant != null ? ctx.entryTenant : ctx.tree != null ? ctx.tree.tree() : null;
        if (tenant == null) return lost(ctx, cause);
        Integer payer = ctx.partner != null ? ctx.partner.getIdPartner() : flow.payerWhenUnknown(ctx, tenant);
        return List.of(sealed(ctx, null, assembler.unadmittedRecord(ctx, tenant, payer, serviceGroup, cause)));
    }

    private List<CdrEvent> lost(C ctx, String cause) {
        flow.kit().counters().cdrLost.incrementAndGet();
        RoutesphereFlow.log.warn("[{}] {} | the CDR has NO tenant to be written on (cause {}, root: {}) — not published", flow.name(),
            LogText.clean(ctx.sessionId()), LogText.clean(cause), ctx.root);
        return List.of();
    }

    /** The application adds its own fields; its facts become the one JSON object of the meta data. */
    private CdrEvent sealed(C ctx, LevelAdmission level, CdrEvent cdr) {
        flow.fillCdr(ctx, level, cdr);
        cdr.additionalMetaData = CdrJson.ofMeta(cdr.meta);
        return cdr;
    }

    // ── R1-6 · the calls in the air ─────────────────────────────────────────

    /**
     * The call's records as the next start publishes them if this process dies before the call's end: a call that was handed over,
     * ended {@link SessionCause#LOST_AT_RESTART}, every tier charged what it reserved (the owner's rule: an admitted call is charged).
     * Made at the hand-over, from the context as it is then; the time of the end, the answer and the seconds are the start's to set.
     */
    String lostRecordsOf(C ctx) {
        int serviceGroup = flow.serviceGroup(ctx);
        if (ctx.levels.isEmpty()) return CdrJson.ofCall(unadmitted(ctx, serviceGroup, SessionCause.LOST_AT_RESTART));
        List<CdrEvent> tiers = new ArrayList<>(ctx.levels.size());
        for (LevelAdmission level : ctx.levels) {
            TierSettlement asReserved = TierSettlement.of(level, TierSettlement.reservedOf(level), level.getBalanceAfter());
            CdrEvent cdr = assembler.tierRecord(ctx, level, asReserved, serviceGroup, SessionCause.LOST_AT_RESTART, flow.paysInMoney(level.getUom()));
            tiers.add(sealed(ctx, level, cdr));
        }
        return CdrJson.ofCall(tiers);
    }

    /**
     * What the start published of the calls a stopped process left in the air: {@code calls} handed over (charged as reserved);
     * {@code neverHandedOver} with reserves and no hand-over (F9) — {@code returned} of their reserves given back ({@code returnedMoney}),
     * {@code owed} not given back (the ledger refused or did not answer: one ERROR each, the reference in it).
     */
    record LeftInTheAir(int calls, BigDecimal money, BigDecimal units, int neverHandedOver, int returned, BigDecimal returnedMoney, int owed, int closed) {
        static final LeftInTheAir NOTHING = new LeftInTheAir(0, BigDecimal.ZERO, BigDecimal.ZERO, 0, 0, BigDecimal.ZERO, 0, 0);
    }

    /**
     * Before the first call: every call a stopped process left in the air is published — one HANDED OVER as it was made at its
     * hand-over, with the last facts the switch had learned of it (the end = the last moment it knew of the call, the answer, the
     * seconds); one with reserves and NO hand-over (F9) with every reserve given back first (the ledger's release by its reference —
     * the ad's return road, else its owed journal; a release the ledger refuses is OWED: one ERROR with the reference) and its record
     * the entry tier at 0.00 — and its line is done. A call whose record cannot be read stays in the journal for the next start (one ERROR).
     */
    LeftInTheAir publishLeftovers() {
        SessionJournal journal = flow.kit().journal();
        int calls = 0, neverHandedOver = 0, returned = 0, owed = 0, closed = 0;
        BigDecimal money = BigDecimal.ZERO, units = BigDecimal.ZERO, returnedMoney = BigDecimal.ZERO;
        for (SessionJournal.Leftover left : journal.leftovers()) {
            List<CdrEvent> tiers = recordsOfLeftover(left);
            if (tiers.isEmpty()) continue;
            if (!left.handedOver()) {
                neverHandedOver++;
                for (SessionJournal.Held held : left.reserves()) {
                    if (flow.money().giveBackLeftover(left.callId(), held)) { returned++; returnedMoney = returnedMoney.add(held.amount() == null ? BigDecimal.ZERO : held.amount()); }
                    else owed++;
                }
                for (CdrEvent cdr : tiers) { cdr.inPartnerCost = BigDecimal.ZERO; cdr.packageAmount = BigDecimal.ZERO; cdr.answerTime = null; cdr.durationSec = BigDecimal.ZERO; }
            } else {
                calls++;
                int[] done = flow.money().closeAsReserved(left);   // A2: the rows of a handed-over call die here, nothing returned
                closed += done[0];
                owed += done[1];
            }
            publishALeftover(left.callId(), tiers);
            for (CdrEvent cdr : tiers) {
                money = money.add(cdr.inPartnerCost == null ? BigDecimal.ZERO : cdr.inPartnerCost);
                units = units.add(cdr.packageAmount == null ? BigDecimal.ZERO : cdr.packageAmount);
            }
        }
        return calls == 0 && neverHandedOver == 0 ? LeftInTheAir.NOTHING : new LeftInTheAir(calls, money, units, neverHandedOver, returned, returnedMoney, owed, closed);
    }

    /** A leftover's records on its ROOT's road ({@code cdr_<root>}, the root from the records' hierarchy — as a live session's), then done. */
    private void publishALeftover(String callId, List<CdrEvent> tiers) {
        flow.kit().cdr().publish(CdrAssembler.rootOf(tiers.get(0)), callId, tiers);
        flow.kit().counters().cdrPublished.incrementAndGet();
        flow.kit().journal().done(callId);
    }

    private List<CdrEvent> recordsOfLeftover(SessionJournal.Leftover left) {
        try {
            List<CdrEvent> tiers = CdrJson.toCall(left.records());
            for (CdrEvent cdr : tiers) {
                cdr.endTime = assembler.wallClock(left.lastAtMs());
                cdr.answerTime = assembler.wallClock(left.answeredAtMs());
                cdr.durationSec = BigDecimal.valueOf(Math.max(0, left.billedSec()));
                cdr.hangupCause = SessionCause.LOST_AT_RESTART;
            }
            return tiers;
        } catch (RuntimeException e) {
            RoutesphereFlow.log.error("[{}] {} | the record a stopped process left in the air could not be read — it stays in {} for the next start: {}",
                flow.name(), LogText.clean(left.callId()), flow.kit().journal().where(), LogText.failureText(e));
            return List.of();
        }
    }

    private void markDone(String callId) {
        try {
            flow.kit().journal().done(callId);
        } catch (RuntimeException e) {
            RoutesphereFlow.log.warn("[{}] {} | its record is published; its line in {} could not be marked done (a restart publishes it again, billing-core takes it once): {}",
                flow.name(), LogText.clean(callId), flow.kit().journal().where(), LogText.failureText(e));
        }
    }
}
