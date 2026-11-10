package com.telcobright.seed.memledgerremote.internal;

import com.telcobright.seed.memledgerremote.wire.Nothing;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;

/**
 * ARCH-0118 · What the server says it does ({@code HealthReply.capabilities}), asked ONCE when the client connects. A server that says
 * none is an older one, said once in the log: the client then keeps the client-side ghost rule — one writer per instance.
 */
public final class ServerCapabilities {

    private static final Logger log = LoggerFactory.getLogger(ServerCapabilities.class);

    private ServerCapabilities() {}

    public static Set<String> askedOf(Calls calls, String address) {
        Set<String> said = Set.copyOf(calls.call("Health", s -> s.health(Nothing.getDefaultInstance())).getCapabilitiesList());
        if (said.isEmpty()) {
            log.warn("mem-ledger client: the server at {} says no capabilities — an older server: this client takes out the ghost reserves of a reload itself (one writer per instance)", address);
        } else {
            log.info("mem-ledger client: the server at {} says {}", address, said);
        }
        return said;
    }
}
