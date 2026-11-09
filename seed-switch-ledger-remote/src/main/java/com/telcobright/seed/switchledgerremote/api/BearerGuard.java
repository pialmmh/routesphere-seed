package com.telcobright.seed.switchledgerremote.api;

import com.telcobright.seed.switchledgerremote.internal.BearerHeader;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * The bearer checked on EVERY call (design §3.3): {@code authorization: Bearer <token>} compared in constant time; anything else is
 * UNAUTHENTICATED before the service sees the call. The token's value comes from the environment variable the service's config names
 * and is held here only; it is never logged, and the refusal never echoes what was sent.
 */
public final class BearerGuard implements ServerInterceptor {

    private static final String PREFIX = "Bearer ";

    private final byte[] expected;

    public BearerGuard(String token) {
        if (token == null || token.isBlank()) throw new IllegalArgumentException("the bearer guard needs a token (the environment variable named by the config is empty)");
        this.expected = token.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public <Q, A> ServerCall.Listener<Q> interceptCall(ServerCall<Q, A> call, Metadata headers, ServerCallHandler<Q, A> next) {
        if (!accepts(headers.get(BearerHeader.AUTHORIZATION))) {
            call.close(Status.UNAUTHENTICATED.withDescription("a bearer token is required on every call of the switch-ledger"), new Metadata());
            return new ServerCall.Listener<>() {};
        }
        return next.startCall(call, headers);
    }

    private boolean accepts(String header) {
        if (header == null || !header.startsWith(PREFIX)) return false;
        return MessageDigest.isEqual(expected, header.substring(PREFIX.length()).getBytes(StandardCharsets.UTF_8));
    }
}
