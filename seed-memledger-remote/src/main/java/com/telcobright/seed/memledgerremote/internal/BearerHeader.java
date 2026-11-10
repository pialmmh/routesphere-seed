package com.telcobright.seed.memledgerremote.internal;

import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ForwardingClientCall;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;

/** The client's bearer on every call: {@code authorization: Bearer <token>}. The token's value is held here and never logged. */
public final class BearerHeader implements ClientInterceptor {

    public static final Metadata.Key<String> AUTHORIZATION = Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);

    private final String header;

    public BearerHeader(String token) { this.header = "Bearer " + token; }

    @Override
    public <Q, A> ClientCall<Q, A> interceptCall(MethodDescriptor<Q, A> method, CallOptions options, Channel next) {
        return new ForwardingClientCall.SimpleForwardingClientCall<>(next.newCall(method, options)) {
            @Override
            public void start(Listener<A> listener, Metadata headers) {
                headers.put(AUTHORIZATION, header);
                super.start(listener, headers);
            }
        };
    }
}
