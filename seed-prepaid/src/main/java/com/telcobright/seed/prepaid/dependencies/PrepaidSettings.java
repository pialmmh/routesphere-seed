package com.telcobright.seed.prepaid.dependencies;

import com.telcobright.seed.prepaid.api.AccountRule;
import com.telcobright.seed.prepaid.api.Admission;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Objects;

/**
 * The VALUES of a lane's Prepaid, as the application reads them from its own config (R0: the seed reads no file) — the builder's
 * {@code settings(...)} takes them in one move. Rules (the rate, the finalize, the hooks) are code, never here.
 *
 * @param admission     HOLD_WINDOW · HOLD · CHARGE_NOW
 * @param window        HOLD_WINDOW: one window's length (the call: 60 s); null otherwise
 * @param firstRenewal  HOLD_WINDOW: the first renewal after the answer (the call: 58 s); null = one window
 * @param accounts      which accounts may pay (default UNITS_THEN_CASH)
 * @param lowBalanceAt  under this balance after a hold or a charge, {@code onLowBalance} is told; null = never
 */
public record PrepaidSettings(Admission admission, Duration window, Duration firstRenewal, AccountRule accounts, BigDecimal lowBalanceAt) {

    public PrepaidSettings {
        Objects.requireNonNull(admission, "admission");
        if (accounts == null) accounts = AccountRule.UNITS_THEN_CASH;
    }

    /** A windowed lane (the call, the WiFi): HOLD_WINDOW, its window and first renewal, the default account rule. */
    public static PrepaidSettings windowed(Duration window, Duration firstRenewal) {
        return new PrepaidSettings(Admission.HOLD_WINDOW, window, firstRenewal, AccountRule.UNITS_THEN_CASH, null);
    }

    /** A lane priced per event: HOLD (the ad view) or CHARGE_NOW (the SMS). */
    public static PrepaidSettings perEvent(Admission admission) { return new PrepaidSettings(admission, null, null, AccountRule.UNITS_THEN_CASH, null); }
}
