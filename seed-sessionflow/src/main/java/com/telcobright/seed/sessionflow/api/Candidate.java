package com.telcobright.seed.sessionflow.api;

/**
 * One way a session may be served, tried in order at admission (a call: itself, one candidate; an ad: a campaign × a content, the
 * free house ad last). The application's {@code selectCandidates} names them in INIT; {@code admit} walks the tiers for each in turn
 * until one is admitted ({@code ctx.chosen}); a refused candidate gives every reserve back before the next is tried.
 *
 * <p>A candidate is part of the persisted context: an implementation is a Jackson-friendly record of ids and words.
 */
public interface Candidate {

    /** The call's one candidate: the session itself. */
    Candidate SELF = new Self();

    /** A candidate that costs nothing (the house ad): every tier is walked and recorded at zero, nothing is reserved. */
    default boolean free() { return false; }

    /** The session itself — the default candidate of a lane that has no choice to make. */
    record Self() implements Candidate {}
}
