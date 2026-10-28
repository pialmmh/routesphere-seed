package com.telcobright.seed.prepaid.dependencies;

import com.telcobright.seed.prepaid.api.AccountRule;
import com.telcobright.seed.prepaid.api.Admission;

import java.time.Duration;
import java.util.Objects;

/**
 * The VALUES of a lane's Prepaid, as the application reads them from its own config (R0: the seed reads no file) — the builder's
 * {@code settings(...)} takes them in one move. Rules (the units, the rate, the end) are code, never here.
 *
 * @param admission     HOLD_WINDOW · CHARGE_NOW
 * @param window        HOLD_WINDOW: one window's length (the call: 60 s); null otherwise
 * @param firstRenewal  HOLD_WINDOW: the first renewal after the answer (the call: 58 s); null = one window
 * @param accounts      which accounts may pay (default UNITS_THEN_CASH)
 */
public record PrepaidSettings(Admission admission, Duration window, Duration firstRenewal, AccountRule accounts) {

    public PrepaidSettings {
        Objects.requireNonNull(admission, "admission");
        if (accounts == null) accounts = AccountRule.UNITS_THEN_CASH;
    }

    /** A windowed lane (the call, the WiFi): HOLD_WINDOW, its window and first renewal, the default account rule. */
    public static PrepaidSettings windowed(Duration window, Duration firstRenewal) {
        return new PrepaidSettings(Admission.HOLD_WINDOW, window, firstRenewal, AccountRule.UNITS_THEN_CASH);
    }

    /** A lane priced per event, charged now (the ad view, the SMS). */
    public static PrepaidSettings chargedNow() { return new PrepaidSettings(Admission.CHARGE_NOW, null, null, AccountRule.UNITS_THEN_CASH); }
}
