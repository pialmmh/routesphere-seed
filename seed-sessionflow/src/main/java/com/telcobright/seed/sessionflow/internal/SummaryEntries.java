package com.telcobright.seed.sessionflow.internal;

import com.telcobright.seed.sessionflow.api.CdrEvent;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The base's default summary entry (P1-R §A): one record in billing-core's blob shape (blob v2, {@code SummaryOutboxWriter}'s {@code Entry}) —
 * {@code {"Cdr": the cdr row as billing-core writes it, "Chargeables": its legs}} — by billing-core's own field names, which summary-service
 * reads without case. A stated group is pre-rated, and billing-core's family for it ({@code SfPreRated}) makes ONE customer leg of what the
 * switch settled; a record of any other group carries no leg (billing rates a call; the switch cannot).
 */
public final class SummaryEntries {

    /** {@code AssignmentDirection.Customer}. */
    static final int CUSTOMER = 1;
    /** {@code SfPreRated.FamilyId}. */
    static final int PRE_RATED_FAMILY = 30;
    /** {@code SfPreRated.MoneyUom}, {@code SfPreRated.SecondsUom}. */
    static final String MONEY = "BDT", SECONDS = "TF_s";

    private SummaryEntries() {}

    /** The record as billing-core's entry: its cdr and, for a stated group, the pre-rated customer leg. */
    public static Map<String, Object> of(CdrEvent record, int switchId) {
        Map<String, Object> cdr = CdrColumns.asSummaryCdr(record, switchId, CdrTableSql.idCallOf(record));
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("Cdr", cdr);
        entry.put("Chargeables", CdrColumns.stated(record) ? List.of(preRatedLegOf(record, cdr)) : List.of());
        return entry;
    }

    /**
     * {@code SfPreRated.ChargeableOfWhatWasSettled}, field by field: the units when the tier paid in units, else the money; the unit as sent
     * (none: money, unless it paid in units); the seconds watched in {@code TF_s}; the rate charged; the prefix; the cdr's bill id, IdCall and
     * start. billing-core's primitives are written as it writes them (0); its empty fields are left out.
     */
    private static Map<String, Object> preRatedLegOf(CdrEvent e, Map<String, Object> cdr) {
        boolean paidInUnits = e.packageAmount != null && e.packageAmount.signum() > 0;
        Map<String, Object> leg = new LinkedHashMap<>();
        leg.put("id", 0L);
        putIfSet(leg, "uniqueBillId", e.callId);
        leg.put("idEvent", cdr.get("IdCall"));
        putIfSet(leg, "transactionTime", cdr.get("StartTime"));
        leg.put("assignedDirection", CUSTOMER);
        leg.put("description", "nc");
        leg.put("glAccountId", 0L);
        leg.put("servicegroup", CdrColumns.serviceGroupOf(e));
        leg.put("servicefamily", PRE_RATED_FAMILY);
        leg.put("ProductId", 0L);
        putIfSet(leg, "idBilledUom", billedUomOf(e, paidInUnits));
        leg.put("idQuantityUom", SECONDS);
        leg.put("BilledAmount", paidInUnits ? e.packageAmount : orZero(e.inPartnerCost));
        putIfSet(leg, "Quantity", e.durationSec);
        leg.put("unitPriceOrCharge", orZero(e.callRatePerMinBDT));
        putIfSet(leg, "Prefix", e.matchPrefixCustomer);
        leg.put("RateId", 0L);
        leg.put("idBillingrule", 0);
        return leg;
    }

    private static String billedUomOf(CdrEvent e, boolean paidInUnits) {
        if (e.inPartnerUom != null && !e.inPartnerUom.isBlank()) return e.inPartnerUom;
        return paidInUnits ? null : MONEY;
    }

    private static BigDecimal orZero(BigDecimal value) { return value != null ? value : BigDecimal.ZERO; }

    private static void putIfSet(Map<String, Object> m, String key, Object value) { if (value != null) m.put(key, value); }
}
