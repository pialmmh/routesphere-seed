package com.telcobright.seed.callflow.api;

/**
 * Per-state timeouts of the routed session graph, in seconds — statewalk 3.2.0's {@code SessionTimings} plus the one state
 * in front (statewalk is frozen this round, so the record lives here; the promotion into statewalk 3.3.0 folds it back).
 * Every timeout targets FAILED — the base guarantees the SDR and the failed CDR are still written.
 *
 * @param preprocessingSec the preprocessing verdict must arrive within this (design §2.1: 3 s)
 * @param admittingSec     the admission verdict must arrive within this (5 s)
 * @param admittedSec      the whole signaling window (the ad: the view window)
 * @param activeMaxSec     dead-man backstop while ACTIVE
 * @param tearingDownSec   settlement must arrive within this
 */
public record RoutedSessionTimings(long preprocessingSec, long admittingSec, long admittedSec, long activeMaxSec, long tearingDownSec) {

    public RoutedSessionTimings {
        if (preprocessingSec <= 0 || admittingSec <= 0 || admittedSec <= 0 || activeMaxSec <= 0 || tearingDownSec <= 0) {
            throw new IllegalArgumentException("all RoutedSessionTimings must be positive");
        }
    }
}
