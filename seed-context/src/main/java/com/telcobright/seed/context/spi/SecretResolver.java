package com.telcobright.seed.context.spi;

import java.util.Optional;

/**
 * How a loader turns a POINTER into a value. The pointer is what an entity row carries
 * ({@code env:TENANT_BTCL_ODOO_PASSWORD}); the value exists only in the process — the secreteer
 * contract: an app reads its secrets from its environment, never from a store, and a missing one is a
 * failure that names the VARIABLE, never the value.
 */
public interface SecretResolver {
    /** The value the pointer names, or {@link SecretUnavailable} naming the variable. */
    String require(String pointer);

    /** The value, or empty when the variable is absent or blank; a malformed pointer still throws. */
    Optional<String> find(String pointer);
}
