package com.telcobright.seed.context.spi;

/**
 * The product's side: build ONE tenant's context, whole, from that tenant's sources (its database, its
 * Odoo, its switch tables). Called on a virtual thread of the cache, never on a caller's thread; may be
 * called for several tenants at once, never twice at once for one tenant.
 *
 * <p>Return an IMMUTABLE object: the cache hands the same instance to every reader until the next swap.
 * Throw (anything) when the tenant cannot be built — the cache keeps the last good snapshot, records the
 * cause, and the siblings never notice. A pointer to a secret is resolved through {@code secrets}
 * ({@code env:<VAR>}); the value must not leave the built context.
 */
@FunctionalInterface
public interface ContextLoader<T> {
    T load(String tenantId, SecretResolver secrets);
}
