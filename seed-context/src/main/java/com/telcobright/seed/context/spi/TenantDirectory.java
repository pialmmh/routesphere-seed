package com.telcobright.seed.context.spi;

import java.util.Set;

/**
 * Who the tenants are. The product decides what it trusts — the BSS registry, seed-config's
 * {@code tenants[]}, a table — and a sphere never invents one. Read at start and on every
 * {@code refreshDirectory()}; must be cheap and must not throw for a transient fault (return the last
 * known set instead, or throw and the refresh is skipped with a log line).
 */
@FunctionalInterface
public interface TenantDirectory {
    Set<String> active();
}
