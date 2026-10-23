package com.telcobright.seed.sessionflow.internal;

import com.telcobright.seed.sessionflow.api.CdrEvent;
import com.telcobright.seed.sessionflow.spi.CdrSink;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Several sinks as one, in the order given (the journal first, then Kafka). One sink's failure never stops the next. P1-F: the session's
 * ROOT is handed to every sink — a per-root sink behind the fan-out names its road by it ({@code cdr_<root>}); a root-less call stays
 * root-less.
 */
public final class FanOutCdrSink implements CdrSink {

    private static final Logger log = LoggerFactory.getLogger(FanOutCdrSink.class);

    private final List<CdrSink> sinks;

    public FanOutCdrSink(List<CdrSink> sinks) { this.sinks = List.copyOf(sinks); }

    @Override
    public void publish(String callId, List<CdrEvent> tiers) {
        for (CdrSink sink : sinks) hand(sink, callId, () -> sink.publish(callId, tiers));
    }

    /** The session's root reaches every sink (P1-F): a per-root sink behind the fan-out publishes on its root's road. */
    @Override
    public void publish(String root, String callId, List<CdrEvent> tiers) {
        for (CdrSink sink : sinks) hand(sink, callId, () -> sink.publish(root, callId, tiers));
    }

    private static void hand(CdrSink sink, String callId, Runnable publish) {
        try {
            publish.run();
        } catch (RuntimeException e) {
            log.error("the CDR of call {} was NOT taken by {}: {}", callId, sink.getClass().getSimpleName(), e.toString());
        }
    }

    @Override
    public void close() {
        for (CdrSink sink : sinks) {
            try {
                sink.close();
            } catch (Exception e) {
                log.warn("{} did not close cleanly: {}", sink.getClass().getSimpleName(), e.toString());
            }
        }
    }
}
