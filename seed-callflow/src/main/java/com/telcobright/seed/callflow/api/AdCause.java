package com.telcobright.seed.callflow.api;

import java.util.Locale;

/**
 * The cause codes of an ad call (design §2.7): the string on the CDR row's {@code hangupCause}, and — as {@link #wire()},
 * lower-kebab — the API's {@code X-Ad-Reason}. {@code BUSY} (pool full) never gets a machine, so it is a counter, not a CDR.
 */
public enum AdCause {
    NORMAL_CLEARING,
    PREPROCESS_TIMEOUT,
    TENANT_UNAVAILABLE,
    NO_CALLER,
    NO_RULE,
    NO_DIALPLAN_PREFIX,
    NO_ROUTE,
    NO_RUNNABLE_CAMPAIGN,
    NO_FUNDED_CAMPAIGN,
    FALLBACK_NOT_RUNNABLE,
    PARTNER_NOT_FOUND,
    PARTNER_DEACTIVATED,
    CHANNEL_LIMIT_REACHED,
    INSUFFICIENT_BALANCE,
    BILLING_SYSTEM_ERROR,
    ADMISSION_TIMEOUT,
    NOT_SHOWN,
    ABANDONED,
    MAX_DURATION_REACHED,
    SETTLE_TIMEOUT,
    HUNG_MACHINE,
    INTERNAL_ERROR;

    /** The API's word: lower-kebab ({@code no-funded-campaign}). */
    public String wire() { return name().toLowerCase(Locale.ROOT).replace('_', '-'); }

    /** The cause of a wire word or a cause name; {@link #INTERNAL_ERROR} for a word this enum does not know. */
    public static AdCause of(String text) {
        if (text == null || text.isBlank()) return INTERNAL_ERROR;
        String n = text.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        for (AdCause c : values()) if (c.name().equals(n)) return c;
        return INTERNAL_ERROR;
    }

    /** The wire word of any cause text: a known cause's own, else the text itself lower-kebab (a child's {@code abandoned:page-left}). */
    public static String wireOf(String cause) {
        if (cause == null || cause.isBlank()) return INTERNAL_ERROR.wire();
        String n = cause.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        for (AdCause c : values()) if (c.name().equals(n)) return c.wire();
        return cause.trim().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    /** A money-side fault the switch must never report as a customer cause (the {@code SYSTEM_FAULT} idea of ReserveBalanceStep). */
    public static boolean isSystemFault(String cause) {
        return cause != null && (BILLING_SYSTEM_ERROR.name().equals(cause) || TENANT_UNAVAILABLE.name().equals(cause));
    }
}
