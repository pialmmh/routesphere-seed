package com.telcobright.seed.sessionflow.publishes;

import com.telcobright.statewalk.event.StatemachineEvent;

/**
 * The service runs (ACTIVE): the balance child may start its cadence — one renewal of every tier's reserve per reserve period.
 * Published by the supervisor on the call's bus and forwarded to its balance child.
 */
public record BudgetStart() implements StatemachineEvent {}
