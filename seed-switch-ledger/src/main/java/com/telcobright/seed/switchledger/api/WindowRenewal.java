package com.telcobright.seed.switchledger.api;

import com.telcobright.rtc.domainmodel.LevelAdmission;
import com.telcobright.seed.sessionflow.api.SessionCause;

import java.math.BigDecimal;

/**
 * The call's C14 remainder on the switch ledger (ARCH-0077-A item 3b) — routesphere-core {@code BalanceBillingService.reserveNextWindowSeconds},
 * lines 86–120: "a whole unit is tried first. If the balance cannot cover one, the REMAINDER is reserved rather than failing: whole-unit-only
 * renewal cuts the caller while up to a full unit of paid credit still sits in the package (0.64 min stranded in the sbc1 2026-08-04 test).
 * The partial window is converted to seconds so the supervisor can cut exactly when the money runs out instead of at the next fixed tick."
 *
 * <ol>
 *   <li>the whole window is held through the base's own reserve under {@code reference} — held → {@code periodSec}; a fault → {@code periodSec}
 *       too (a fault never cuts; the settlement reconciles);</li>
 *   <li>refused → the account's live balance is PEEKED ({@link LiveBalance}, a read); nothing left → 0;</li>
 *   <li>{@code seconds = remaining / ratePerPeriod × periodSec}; below {@link #MIN_FINAL_WINDOW_SEC} → 0, nothing held ("not worth a timer");
 *       else exactly {@code remaining} is held under the SAME reference and those seconds are answered — the tracker then enters WINDING_DOWN
 *       and arms the final cut.</li>
 * </ol>
 *
 * <p>A zero rate answers {@code periodSec} and holds nothing (line 89: "zero-rated: nothing to fund, keep the cadence"). Every reservation
 * is RECORDED on the level by the base's own chain — the {@link Hold} is the flow's {@code reserveWindow(ctx, level, amount, reference)}
 * bound as a lambda — never by this class: one piece of code records reservations.
 */
public final class WindowRenewal {

    /** Below this the leftover cannot buy meaningful service; cut instead of arming a timer ({@code BalanceBillingService}, line 37: {@code 1.0}). */
    public static final double MIN_FINAL_WINDOW_SEC = 1.0;

    /** The base's reserve of a window, recorded on the level: null = held, else the cause ({@code SessionFlowSteps.reserveWindow}). */
    @FunctionalInterface
    public interface Hold {
        String reserve(LevelAdmission level, BigDecimal amount, String reference);
    }

    private final LiveBalance balances;
    private final Hold hold;

    public WindowRenewal(LiveBalance balances, Hold hold) {
        this.balances = balances;
        this.hold = hold;
    }

    /**
     * @param wholeAmount   what one whole window costs at this tier
     * @param reference     the window's reference ({@code <tier>#W<n>}): the whole and the remainder are held under it
     * @param ratePerPeriod the tier's rate per period — what {@code periodSec} of service costs
     * @param periodSec     the reserve period
     * @return the seconds of service funded: {@code periodSec} = a whole window; less = the remainder, to be cut when it ends; 0 = nothing left
     */
    public double renewSeconds(LevelAdmission level, BigDecimal wholeAmount, String reference, BigDecimal ratePerPeriod, double periodSec) {
        if (ratePerPeriod == null || ratePerPeriod.signum() <= 0) return periodSec;
        String refusal = hold.reserve(level, wholeAmount, reference);
        if (refusal == null || SessionCause.BILLING_SYSTEM_ERROR.equals(refusal)) return periodSec;
        return remainderSeconds(level, reference, ratePerPeriod, periodSec);
    }

    /** The whole window was refused: spend what is left, if it is worth spending. */
    private double remainderSeconds(LevelAdmission level, String reference, BigDecimal ratePerPeriod, double periodSec) {
        BigDecimal remaining = balances.balanceOf(level).orElse(BigDecimal.ZERO);
        if (remaining.signum() <= 0) return 0;
        double seconds = remaining.doubleValue() / ratePerPeriod.doubleValue() * periodSec;
        if (seconds < MIN_FINAL_WINDOW_SEC) return 0;
        return hold.reserve(level, remaining, reference) == null ? seconds : 0;
    }
}
