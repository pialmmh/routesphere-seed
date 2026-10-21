package com.telcobright.seed.sessionflow.dependencies;

import com.telcobright.seed.sessionflow.internal.FanOutCdrSink;
import com.telcobright.seed.sessionflow.internal.FileCdrJournal;
import com.telcobright.seed.sessionflow.internal.KafkaCdrSink;
import com.telcobright.seed.sessionflow.internal.OncePerSessionCdrSink;
import com.telcobright.seed.sessionflow.spi.CdrSink;

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

    /** One message per session on ONE {@code topic}, acks=all, idempotent. */
    public static CdrSink kafka(String bootstrapServers, String topic, String clientId) {
        return new KafkaCdrSink(bootstrapServers, topic, clientId);
    }

    /** §C · One message per session on its ROOT's topic, {@code prefix + root} — the deployed {@code cdr_<root>} (prefix {@code "cdr_"}). */
    public static CdrSink kafkaPerRoot(String bootstrapServers, String prefix, String clientId) {
        return KafkaCdrSink.perRoot(bootstrapServers, prefix, clientId);
    }

    /** One file per minute under {@code directory}, one line per call. */
    public static CdrSink journal(Path directory, Clock clock, ZoneId zone) {
        return new FileCdrJournal(directory, clock, zone);
    }

    /**
     * §L.1 · One message per session id: a session ended twice (restored from an older row after a death between its record and its final
     * row) publishes once. {@code kept} = how many recent session ids are remembered.
     */
    public static CdrSink oncePerSession(CdrSink next, int kept) { return new OncePerSessionCdrSink(next, kept); }

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
