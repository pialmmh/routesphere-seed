package com.telcobright.seed.callflow.publishes;

import com.telcobright.statewalk.event.StatemachineEvent;

/**
 * One reserve period of an answered call has passed: the call renews its reserve at every tier. Published by the call's
 * own reserve clock, on the call's bus, so a renewal never runs beside the settlement of the same call.
 */
public record ReserveTick() implements StatemachineEvent {}
