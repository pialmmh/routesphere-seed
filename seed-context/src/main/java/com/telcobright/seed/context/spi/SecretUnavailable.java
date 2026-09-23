package com.telcobright.seed.context.spi;

/** A pointer that names no value. The message carries the pointer, never a value. */
public final class SecretUnavailable extends RuntimeException {
    public SecretUnavailable(String message) { super(message); }
}
