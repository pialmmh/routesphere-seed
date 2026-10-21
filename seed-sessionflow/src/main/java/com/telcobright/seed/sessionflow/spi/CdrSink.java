package com.telcobright.seed.sessionflow.spi;

import com.telcobright.seed.sessionflow.api.CdrEvent;

import java.util.List;

/**
 * Where the CDR of an ended call goes. The switch only publishes: it never writes a CDR table. One call = one
 * {@link #publish}: every tier's record together, the leaf first — billing-core writes them, summary-service sums them.
 *
 * <p>An implementation must not block the call's thread for long. A failure is the sink's own to log, with the call id.
 */
public interface CdrSink extends AutoCloseable {

    void publish(String callId, List<CdrEvent> tiers);

    /**
     * The session's records on its ROOT's road ({@code cdr_<root>}, §C: every lookup of a session is scoped to its root, its CDR topic too).
     * The default ignores the root (a sink bound to one topic); a per-root sink ({@code CdrSinks.kafkaPerRoot}) names the topic by it.
     */
    default void publish(String root, String callId, List<CdrEvent> tiers) { publish(callId, tiers); }

    @Override
    default void close() { }
}
