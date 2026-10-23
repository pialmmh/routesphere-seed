package com.telcobright.seed.sessionflow.spi;

import com.telcobright.seed.sessionflow.api.Deployment;
import com.telcobright.seed.sessionflow.api.RootResources;

import java.time.Clock;
import java.util.function.Function;

/**
 * Makes ONE root's ledger port from its bootstrap resources ({@code root.<root>.ledger.kind}): {@code switch} — seed-switch-ledger's
 * MemLedger port, the default (R12: prepaid is strictly package-account + mem-ledger) — or {@code orchestrix}, the REST roads of the
 * transition (built in). A maker is found on the classpath by its kind ({@code java.util.ServiceLoader}).
 */
public interface LedgerMaker {

    /** The kind this maker answers ({@code switch}, {@code orchestrix}). */
    String kind();

    /** The root's ledger; refused in words when a resource it needs is missing. */
    LedgerPort make(RootResources root, Context context);

    /**
     * What a maker may read besides the root's own resources.
     *
     * @param trees       already serving the root (the switch ledger loads every tier of its tree)
     * @param environment the process environment by variable NAME (a password by reference, a token)
     */
    record Context(Deployment deployment, TenantLookup trees, Clock clock, Function<String, String> environment) {}
}
