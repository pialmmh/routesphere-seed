package com.telcobright.seed.prepaid.api;

import com.telcobright.rtc.domainmodel.LevelAdmission;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.function.Function;

/**
 * What the end of a session does with the money of every tier — the lane's {@code finalize(ctx -> Final)} answers it once per session
 * (design {@code prepaid-engine-design.md} §10). The base applies it per tier, through the kit's one ledger port, idempotent per
 * (session, tier):
 *
 * <pre>
 *                      HOLD · HOLD_WINDOW (a hold row per tier)              CHARGE_NOW (charged at admission, no row)
 *   charge(tier → c)   settled at c: the rest back, an overrun taken (P16)   the charge less c given back; c above the charge = OWED
 *   releaseAll         every hold back (settled at 0)                        every charge back
 *   refundAll          every hold back (settled at 0)                        every charge back
 *   nothing            the holds stand as the charge (settled at the held)   the charge stands
 * </pre>
 */
public sealed interface Final {

    /** Each tier costs {@code cost(tier)} — the call's re-rated talk time ({@code BalanceBillingService.finalizeBalance} 127–148). */
    record Charge(Function<LevelAdmission, BigDecimal> cost) implements Final {
        public Charge { Objects.requireNonNull(cost, "a charge needs its cost per tier"); }
    }

    /** Every tier's money back (the call never answered: {@code BalanceBillingService.refundBalance} 203–219). */
    record ReleaseAll() implements Final {}

    /** Every tier's money back — the SMS that failed, when its refund rule gives it back. */
    record RefundAll() implements Final {}

    /** Nothing more moves: what was taken stays taken (the SMS delivered; a hold settled at exactly what it held). */
    record Nothing() implements Final {}

    static Final charge(Function<LevelAdmission, BigDecimal> cost) { return new Charge(cost); }

    static Final releaseAll() { return RELEASE_ALL; }

    static Final refundAll() { return REFUND_ALL; }

    static Final nothing() { return NOTHING; }

    Final RELEASE_ALL = new ReleaseAll();
    Final REFUND_ALL = new RefundAll();
    Final NOTHING = new Nothing();
}
