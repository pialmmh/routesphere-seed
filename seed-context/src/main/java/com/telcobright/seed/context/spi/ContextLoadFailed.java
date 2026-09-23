package com.telcobright.seed.context.spi;

/** A loader's explicit refusal, with the tenant in the message. Any other exception a loader throws counts the same. */
public class ContextLoadFailed extends RuntimeException {
    public ContextLoadFailed(String tenantId, String message) {
        super("tenant '" + tenantId + "': " + message);
    }

    public ContextLoadFailed(String tenantId, String message, Throwable cause) {
        super("tenant '" + tenantId + "': " + message, cause);
    }
}
