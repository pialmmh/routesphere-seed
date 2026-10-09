package com.telcobright.seed.sessionflow.api;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The hops a call may be sent over, in order, and the cursor on the one being tried — what the re-route ritual (C12) advances.
 * Routing puts it on the context ({@code ctx.routePlan}); the signaling reads {@link #current()}. Every failed attempt is
 * recorded here before the policy decides.
 *
 * <p>A call with no plan on its context has one implicit hop: a failed attempt can be retried on it, never re-routed.
 *
 * @param <H> what a hop is to the application (the call switch: a dialplan context and a destination; an SMS: an SMSC)
 */
public final class RoutePlan<H> {

    /** The call switch's own cap on attempts of a re-routed call (v1 {@code getMaxRerouteAttempts()}). */
    public static final int DEFAULT_MAX_ATTEMPTS = 3;

    /** One attempt that failed: which hop, with what cause, when. */
    public record Attempt(int hopIndex, String cause, long atMs) {}

    private final List<H> hops;
    private final int maxAttempts;
    private final List<Attempt> attempts = new CopyOnWriteArrayList<>();
    private volatile int cursor;

    private RoutePlan(List<H> hops, int maxAttempts) {
        this.hops = List.copyOf(Objects.requireNonNull(hops, "hops"));
        if (maxAttempts < 1) throw new IllegalArgumentException("maxAttempts must be at least 1");
        this.maxAttempts = maxAttempts;
    }

    public static <H> RoutePlan<H> of(List<H> hops) { return new RoutePlan<>(hops, DEFAULT_MAX_ATTEMPTS); }

    public static <H> RoutePlan<H> of(List<H> hops, int maxAttempts) { return new RoutePlan<>(hops, maxAttempts); }

    public static <H> RoutePlan<H> single(H hop) { return new RoutePlan<>(List.of(hop), DEFAULT_MAX_ATTEMPTS); }

    /** The hop being tried; null when the plan is empty. */
    public H current() { return hops.isEmpty() ? null : hops.get(cursor); }

    public int currentIndex() { return cursor; }

    public List<H> hops() { return hops; }

    public int hopCount() { return hops.size(); }

    public boolean hasMoreHops() { return cursor + 1 < hops.size(); }

    /** The most attempts a re-routed call may make in all (v1: {@value #DEFAULT_MAX_ATTEMPTS}). */
    public int maxAttempts() { return maxAttempts; }

    /** Move the cursor to the next hop. False = there is none; the cursor stays. */
    public boolean advance() {
        if (!hasMoreHops()) return false;
        cursor++;
        return true;
    }

    /** The base records every failed attempt here before the policy decides. */
    public void record(String cause, long atMs) { attempts.add(new Attempt(cursor, cause, atMs)); }

    public List<Attempt> attempts() { return new ArrayList<>(attempts); }

    @Override
    public String toString() {
        return "RoutePlan{hop " + (cursor + 1) + "/" + hops.size() + ", attempts " + attempts.size() + "/" + maxAttempts + "}";
    }
}
