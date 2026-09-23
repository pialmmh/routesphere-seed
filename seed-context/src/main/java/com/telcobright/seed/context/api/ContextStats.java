package com.telcobright.seed.context.api;

import java.time.Instant;

/**
 * What one tenant's cell has seen — for an admin road and for the alarms. {@code serving} is false for a
 * tenant that never loaded; {@code lastFailure} keeps the cause of the latest failed load even while an
 * older snapshot still serves.
 */
public record ContextStats(String tenantId, boolean serving, long version, Instant loadedAt,
                           long loads, long failures, String lastFailure, long lastLoadMs, long rings) {}
