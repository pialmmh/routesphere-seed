package com.telcobright.seed.callflow.api;

import com.telcobright.rtc.domainmodel.LevelAdmission;
import com.telcobright.rtc.domainmodel.nonentity.Tenant;
import com.telcobright.seed.callflow.internal.CdrAssembler;
import com.telcobright.seed.callflow.internal.CdrJson;

import java.util.ArrayList;
import java.util.List;

/**
 * The CDR of an ended call, for every application: ONE message per call, one record per tier, the leaf first. A call
 * nobody was admitted for still gets one record, on the tenant it entered, with zero amounts and its cause.
 *
 * <p>The switch only publishes. billing-core writes the record and the summary input; summary-service sums them.
 * This is the work behind the CDR step of {@link CallFlow#end}; the application's fields are {@link CallFlowSteps#fillCdr}.
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
}
