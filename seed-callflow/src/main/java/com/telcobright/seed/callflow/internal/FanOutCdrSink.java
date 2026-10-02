package com.telcobright.seed.callflow.internal;

import com.telcobright.seed.callflow.api.CdrEvent;
import com.telcobright.seed.callflow.spi.CdrSink;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/** Several sinks as one, in the order given (the journal first, then Kafka). One sink's failure never stops the next. */
public final class FanOutCdrSink implements CdrSink {

    private static final Logger log = LoggerFactory.getLogger(FanOutCdrSink.class);

    private final List<CdrSink> sinks;

    public FanOutCdrSink(List<CdrSink> sinks) { this.sinks = List.copyOf(sinks); }

    @Override
    public void publish(String callId, List<CdrEvent> tiers) {
        for (CdrSink sink : sinks) {
            try {
                sink.publish(callId, tiers);
            } catch (RuntimeException e) {
                log.error("the CDR of call {} was NOT taken by {}: {}", callId, sink.getClass().getSimpleName(), e.toString());
            }
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
