package com.telcobright.seed.prepaid.api;

/**
 * How a lane's money is taken at admission (design {@code prepaid-engine-design.md} §10).
 *
 * <ul>
 *   <li>{@link #HOLD_WINDOW} — the call, the WiFi session: ONE unit held per tier at admission (one window), the next window held at
 *       every renewal (the call: 58 s after the answer, then every 60 s), the whole unit else the remainder; settled at the end;</li>
 *   <li>{@link #HOLD} — the ad view: its whole price held per tier at admission, settled at the end; no renewal;</li>
 *   <li>{@link #CHARGE_NOW} — the SMS: charged at once on every tier, no hold row; the end leaves it ({@code Final.nothing}) or gives
 *       it back ({@code Final.refundAll}).</li>
 * </ul>
 */
public enum Admission { HOLD_WINDOW, HOLD, CHARGE_NOW }
