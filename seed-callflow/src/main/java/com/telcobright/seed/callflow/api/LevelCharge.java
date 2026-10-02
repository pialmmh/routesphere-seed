package com.telcobright.seed.callflow.api;

import java.math.BigDecimal;

/**
 * What ONE tier's debit came to, as orchestrix's road 16 answers it (the S14 v1.2b keys, verbatim):
 * {@code chargeAccount, chargeUom, chargeUnits, chargeBdt, balanceBefore, balanceAfter} (+ {@code repeated} on a replay).
 * The reference is {@code <sessionId>#L<levelIndex>}; a credit (compensation) names the same account and a fresh reference.
 *
 * @param partnerId the tier's partner (the payer at that tier)
 * @param tenant    the tier's tenant database name
 
 *
 * @deprecated The ad-only shape of 2026-09-29. Since the base call pipeline (2026-10-03): a reserve answers {@link com.telcobright.seed.callflow.spi.LedgerPort.Reservation}.
 *     Removed when ad-sphere has moved onto {@code CallFlow}.
 */
@Deprecated(since = "2026-10-03", forRemoval = true)
public record LevelCharge(int levelIndex, String tenant, int partnerId, Long chargeAccount, String chargeUom, BigDecimal chargeUnits,
                          BigDecimal chargeBdt, BigDecimal balanceBefore, BigDecimal balanceAfter, String reference, boolean repeated) {

    /** The amount the debit took: the units of a bucket, else the cash. */
    public BigDecimal amount() {
        if (chargeUnits != null && chargeUnits.signum() > 0) return chargeUnits;
        return chargeBdt == null ? BigDecimal.ZERO : chargeBdt;
    }
}
