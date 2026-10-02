package com.telcobright.seed.callflow.spi;

import com.telcobright.seed.callflow.api.CdrEvent;

import java.util.List;

/**
 * Where the CDR of an ended call goes. The switch only publishes: it never writes a CDR table. One call = one
 * {@link #publish}: every tier's record together, the leaf first — billing-core writes them, summary-service sums them.
 *
 * <p>An implementation must not block the call's thread for long. A failure is the sink's own to log, with the call id.
 */
public interface CdrSink extends AutoCloseable {

    void publish(String callId, List<CdrEvent> tiers);

    @Override
    default void close() { }
}
