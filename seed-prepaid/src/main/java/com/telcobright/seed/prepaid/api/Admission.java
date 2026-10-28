package com.telcobright.seed.prepaid.api;

/**
 * How a lane's money is taken at admission (design {@code prepaid-engine-design.md} §10, §14.1: "charge now or windowed").
 *
 * <ul>
 *   <li>{@link #HOLD_WINDOW} — the call, the WiFi session: ONE unit held per tier at admission (one window), the next window held at
 *       every renewal (the call: 58 s after the answer, then every 60 s), the whole unit else the remainder; settled at the end;</li>
 *   <li>{@link #CHARGE_NOW} — the ad view, the SMS: charged at once on every tier, no hold row; the end leaves it ({@code Final.nothing})
 *       or gives it back ({@code Final.refundAll}).</li>
 * </ul>
 * C3 (2026-10-28): {@code HOLD} (a whole price held at admission, settled at the end) is gone — the ad view is charged now (the owner,
 * 2026-10-27), and no lane holds a whole price.
 */
public enum Admission { HOLD_WINDOW, CHARGE_NOW }
