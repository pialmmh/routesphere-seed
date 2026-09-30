package com.telcobright.seed.callflow.publishes;

import com.telcobright.statewalk.event.StatemachineEvent;

/**
 * Published by the supervisor after its PREPROCESSING hook answered (or by an asynchronous preprocessor re-entering by id):
 * ok → ADMITTING, else FAILED with {@code cause} as the end cause.
 */
public record Preprocessed(boolean ok, String cause) implements StatemachineEvent {}
