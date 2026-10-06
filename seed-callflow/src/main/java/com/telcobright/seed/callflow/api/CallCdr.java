package com.telcobright.seed.callflow.api;

import com.telcobright.rtc.domainmodel.LevelAdmission;
import com.telcobright.rtc.domainmodel.nonentity.Tenant;
import com.telcobright.seed.callflow.internal.CdrAssembler;
import com.telcobright.seed.callflow.internal.CdrJson;
import com.telcobright.seed.callflow.spi.CallJournal;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * The CDR of an ended call, for every application: ONE message per call, one record per tier, the leaf first. A call
 * nobody was admitted for still gets one record, on the tenant it entered, with zero amounts and its cause.
 *
 * <p>The switch only publishes. billing-core writes the record and the summary input; summary-service sums them.
 * This is the work behind the CDR step of {@link CallFlow#end}; the application's fields are {@link CallFlowSteps#fillCdr}.
 *
 * <p>R1-6: a call that was handed over has a line in the journal of the calls in the air, written with its records made as the
 * next start would publish them ({@link #lostRecordsOf}); its own end publishes its real record and marks that line done
 * (publish FIRST, then done: a death in between publishes the call twice, and billing-core takes a call once per tier).
 */
final class CallCdr<C extends CallFlowContext> {

    private final CallFlow<C> flow;
    private final CdrAssembler assembler;

    CallCdr(CallFlow<C> flow) {
        this.flow = flow;
        this.assembler = new CdrAssembler(flow.kit().zone(), flow.kit().clock().millis() * 1000);
    }

    void publish(C ctx, String outcome) {
        if (ctx.cdrPublished) return;
        List<CdrEvent> tiers = assemble(ctx, outcome);
        if (tiers.isEmpty()) return;
        flow.kit().cdrSink().publish(ctx.sessionKey, tiers);
        ctx.cdrPublished = true;
        flow.counters().cdrPublished.incrementAndGet();
        markDone(ctx.sessionKey);
    }

    private List<CdrEvent> assemble(C ctx, String outcome) {
        int serviceGroup = flow.serviceGroup(ctx);
        String cause = flow.cdrCause(ctx, outcome);
        if (ctx.levels.isEmpty()) return unadmitted(ctx, serviceGroup, cause);
        List<CdrEvent> tiers = new ArrayList<>(ctx.levels.size());
        for (int i = 0; i < ctx.levels.size(); i++) tiers.add(tier(ctx, i, serviceGroup, cause));
        return tiers;
    }

    private CdrEvent tier(C ctx, int index, int serviceGroup, String cause) {
        LevelAdmission level = ctx.levels.get(index);
        TierSettlement settlement = index < ctx.settlements.size() ? ctx.settlements.get(index) : null;
        CdrEvent cdr = assembler.tierRecord(ctx, level, settlement, serviceGroup, cause, flow.paysInMoney(level.getUom()));
        return sealed(ctx, level, cdr);
    }

    /** Nobody was admitted (or the call was free and had no tier): one record on the tenant the call entered. */
    private List<CdrEvent> unadmitted(C ctx, int serviceGroup, String cause) {
        Tenant tenant = ctx.entryTenant != null ? ctx.entryTenant : flow.kit().tenants().root(ctx.tenantName).orElse(null);
        if (tenant == null) return lost(ctx, cause);
        Integer payer = ctx.partner != null ? ctx.partner.getIdPartner() : flow.payerWhenUnknown(ctx, tenant);
        return List.of(sealed(ctx, null, assembler.unadmittedRecord(ctx, tenant, payer, serviceGroup, cause)));
    }

    private List<CdrEvent> lost(C ctx, String cause) {
        flow.counters().cdrLost.incrementAndGet();
        flow.log.error("[{}] {} | the CDR has NO tenant to be written on (cause {}, tenant named: {}) — not published", flow.name(),
            ctx.sessionKey, cause, ctx.tenantName);
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
     * ended {@link CallCause#LOST_AT_RESTART}, every tier charged what it reserved (the owner's rule: an admitted call is charged).
     * Made at the hand-over, from the context as it is then; the time of the end, the answer and the seconds are the start's to set.
     */
    String lostRecordsOf(C ctx) {
        int serviceGroup = flow.serviceGroup(ctx);
        if (ctx.levels.isEmpty()) return CdrJson.ofCall(unadmitted(ctx, serviceGroup, CallCause.LOST_AT_RESTART));
        List<CdrEvent> tiers = new ArrayList<>(ctx.levels.size());
        for (LevelAdmission level : ctx.levels) {
            TierSettlement asReserved = TierSettlement.of(level, TierSettlement.reservedOf(level), level.getBalanceAfter());
            CdrEvent cdr = assembler.tierRecord(ctx, level, asReserved, serviceGroup, CallCause.LOST_AT_RESTART, flow.paysInMoney(level.getUom()));
            tiers.add(sealed(ctx, level, cdr));
        }
        return CdrJson.ofCall(tiers);
    }

    /** What the start published of the calls a stopped process left in the air. */
    record LeftInTheAir(int calls, BigDecimal money, BigDecimal units) {
        static final LeftInTheAir NOTHING = new LeftInTheAir(0, BigDecimal.ZERO, BigDecimal.ZERO);
    }

    /**
     * Before the first call: every call a stopped process left in the air is published as it was made at its hand-over, with the
     * last facts the switch had learned of it — the end = the last moment it knew of the call, the answer, the seconds — and its line
     * is done. A call whose record cannot be read stays in the journal for the next start (one ERROR).
     */
    LeftInTheAir publishLeftovers() {
        CallJournal journal = flow.kit().journal();
        int calls = 0;
        BigDecimal money = BigDecimal.ZERO, units = BigDecimal.ZERO;
        for (CallJournal.Leftover left : journal.leftovers()) {
            List<CdrEvent> tiers = recordsOfLeftover(left);
            if (tiers.isEmpty()) continue;
            flow.kit().cdrSink().publish(left.callId(), tiers);
            flow.counters().cdrPublished.incrementAndGet();
            journal.done(left.callId());
            calls++;
            for (CdrEvent cdr : tiers) {
                money = money.add(cdr.inPartnerCost == null ? BigDecimal.ZERO : cdr.inPartnerCost);
                units = units.add(cdr.packageAmount == null ? BigDecimal.ZERO : cdr.packageAmount);
            }
        }
        return calls == 0 ? LeftInTheAir.NOTHING : new LeftInTheAir(calls, money, units);
    }

    private List<CdrEvent> recordsOfLeftover(CallJournal.Leftover left) {
        try {
            List<CdrEvent> tiers = CdrJson.toCall(left.records());
            for (CdrEvent cdr : tiers) {
                cdr.endTime = assembler.wallClock(left.lastAtMs());
                cdr.answerTime = assembler.wallClock(left.answeredAtMs());
                cdr.durationSec = BigDecimal.valueOf(Math.max(0, left.billedSec()));
                cdr.hangupCause = CallCause.LOST_AT_RESTART;
            }
            return tiers;
        } catch (RuntimeException e) {
            flow.log.error("[{}] {} | the record a stopped process left in the air could not be read — it stays in {} for the next start: {}",
                flow.name(), left.callId(), flow.kit().journal().where(), e.toString());
            return List.of();
        }
    }

    private void markDone(String callId) {
        try {
            flow.kit().journal().done(callId);
        } catch (RuntimeException e) {
            flow.log.warn("[{}] {} | its record is published; its line in {} could not be marked done (a restart publishes it again, billing-core takes it once): {}",
                flow.name(), callId, flow.kit().journal().where(), e.toString());
        }
    }
}
