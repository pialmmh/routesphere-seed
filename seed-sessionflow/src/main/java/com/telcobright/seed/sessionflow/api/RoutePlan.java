package com.telcobright.seed.sessionflow.api;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.telcobright.statewalk.session.Route;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The hops a session may be sent over, in order, and the cursor on the one being tried — what the base's {@code nextHop} reads
 * ({@link #current()}) and {@code handleRerouting} advances (C12). Step (d) {@code routing} makes it once, at admission; it stays on the
 * context (persisted: every hop is the application's {@link Route}, a Jackson-friendly record). Every failed attempt is recorded here
 * before the policy decides.
 */
public final class RoutePlan {

    /** The call switch's own cap on attempts of a re-routed call (v1 {@code getMaxRerouteAttempts()}). */
    public static final int DEFAULT_MAX_ATTEMPTS = 3;

    /** One attempt that failed: which hop, with what cause, when. */
    public record Attempt(int hopIndex, String cause, long atMs) {}

    @JsonTypeInfo(use = JsonTypeInfo.Id.CLASS)
    private final List<Route> hops;
    private final int maxAttempts;
    private final List<Attempt> attempts;
    private volatile int cursor;

    @JsonCreator
    RoutePlan(@JsonProperty("hops") List<Route> hops, @JsonProperty("maxAttempts") int maxAttempts,
              @JsonProperty("attempts") List<Attempt> attempts, @JsonProperty("cursor") int cursor) {
        this.hops = List.copyOf(Objects.requireNonNull(hops, "hops"));
        if (maxAttempts < 1) throw new IllegalArgumentException("maxAttempts must be at least 1");
        this.maxAttempts = maxAttempts;
        this.attempts = new ArrayList<>(attempts == null ? List.of() : attempts);
        this.cursor = Math.max(0, Math.min(cursor, Math.max(0, this.hops.size() - 1)));
    }

    public static RoutePlan of(List<? extends Route> hops) { return new RoutePlan(new ArrayList<>(hops), DEFAULT_MAX_ATTEMPTS, List.of(), 0); }

    public static RoutePlan of(List<? extends Route> hops, int maxAttempts) { return new RoutePlan(new ArrayList<>(hops), maxAttempts, List.of(), 0); }

    /** One hop (an ad: the chosen creative; an SMS: the SMSC). */
    public static RoutePlan one(Route hop) { return new RoutePlan(List.of(hop), DEFAULT_MAX_ATTEMPTS, List.of(), 0); }

    /** No route: the session fails NO_ROUTE. */
    public static RoutePlan none() { return new RoutePlan(List.of(), DEFAULT_MAX_ATTEMPTS, List.of(), 0); }

    /** The hop being tried; null when the plan is empty. */
    public Route current() { return hops.isEmpty() ? null : hops.get(cursor); }

    @JsonProperty("cursor")
    public int currentIndex() { return cursor; }

    @JsonProperty("hops")
    public List<Route> hops() { return hops; }

    public int hopCount() { return hops.size(); }

    @JsonIgnore
    public boolean isEmpty() { return hops.isEmpty(); }

    public boolean hasMoreHops() { return cursor + 1 < hops.size(); }

    /** The most attempts a re-routed session may make in all (v1: {@value #DEFAULT_MAX_ATTEMPTS}). */
    @JsonProperty("maxAttempts")
    public int maxAttempts() { return maxAttempts; }

    /** Move the cursor to the next hop. False = there is none; the cursor stays. */
    public synchronized boolean advance() {
        if (!hasMoreHops()) return false;
        cursor++;
        return true;
    }

    /** The base records every failed attempt here before the policy decides. */
    public synchronized void record(String cause, long atMs) { attempts.add(new Attempt(cursor, cause, atMs)); }

    @JsonProperty("attempts")
    public synchronized List<Attempt> attempts() { return new ArrayList<>(attempts); }

    @Override
    public String toString() {
        return "RoutePlan{hop " + (cursor + 1) + "/" + hops.size() + ", attempts " + attempts().size() + "/" + maxAttempts + "}";
    }
}
