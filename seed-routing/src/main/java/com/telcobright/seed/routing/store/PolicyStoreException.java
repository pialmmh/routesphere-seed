package com.telcobright.seed.routing.store;

/** The policy store could not be read or written — the cause is always kept (house rule: no silent catch). */
public class PolicyStoreException extends RuntimeException {
    public PolicyStoreException(String message, Throwable cause) { super(message, cause); }
}
