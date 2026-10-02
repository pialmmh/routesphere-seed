package com.telcobright.seed.callflow.dependencies;

import com.telcobright.seed.callflow.internal.FanOutCdrSink;
import com.telcobright.seed.callflow.internal.FileCdrJournal;
import com.telcobright.seed.callflow.internal.KafkaCdrSink;
import com.telcobright.seed.callflow.spi.CdrSink;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.ZoneId;
import java.util.List;

/**
 * The CDR sinks a host can choose from. A deployed switch uses the journal AND Kafka, the journal first — as the call
 * switch keeps its per-minute file beside Kafka.
 */
public final class CdrSinks {

    private CdrSinks() {}

    /** One message per call on {@code topic} (the deployed name: {@code cdr_<root tenant>}), acks=all, idempotent. */
    public static CdrSink kafka(String bootstrapServers, String topic, String clientId) {
        return new KafkaCdrSink(bootstrapServers, topic, clientId);
    }

    /** One file per minute under {@code directory}, one line per call. */
    public static CdrSink journal(Path directory, Clock clock, ZoneId zone) {
        return new FileCdrJournal(directory, clock, zone);
    }

    /** Several sinks as one, in the order given. */
    public static CdrSink all(CdrSink... sinks) { return new FanOutCdrSink(List.of(sinks)); }

    /**
     * Send every call of one journal file again (a minute Kafka never took).
     *
     * @return how many calls were sent
     */
    public static int replay(Path journalFile, CdrSink to) throws IOException {
        return FileCdrJournal.replay(journalFile, to);
    }
}
