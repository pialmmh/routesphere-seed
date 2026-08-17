package com.telcobright.seed.configclient;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * The config doorbell — the routesphere ConfigEventConsumer pattern as a plain library.
 *
 * A product subscribes to change notifications (Kafka and/or Redis), and after a quiet period
 * (debounce) its callback runs ONCE to re-fetch config from the source of truth. Events never
 * carry config; a missed event costs nothing beyond waiting for the next one (products should
 * keep their own slow poll / fetch-on-boot as the reliable floor).
 *
 * <pre>
 *   ConfigDoorbell bell = ConfigDoorbell.builder()
 *       .kafka("10.10.199.20:9092,10.10.197.20:9092,10.10.198.20:9092", "config_event_loader_prod")
 *       .redis("127.0.0.1", 6379, null, "config_event_loader_prod")   // optional fast leg
 *       .debounceMs(3000)
 *       .onRing(contextCache::refetch)
 *       .build()
 *       .start();
 * </pre>
 */
public final class ConfigDoorbell implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ConfigDoorbell.class);

    private final DebounceGate gate;
    private final List<AutoCloseable> sources;

    private ConfigDoorbell(DebounceGate gate, List<AutoCloseable> sources) {
        this.gate = gate;
        this.sources = sources;
    }

    public static Builder builder() { return new Builder(); }

    public ConfigDoorbell start() {
        for (AutoCloseable s : sources) {
            if (s instanceof KafkaDoorbellSource k) k.start();
            if (s instanceof RedisDoorbellSource r) r.start();
        }
        log.info("config doorbell started ({} source(s))", sources.size());
        return this;
    }

    /** Manual ring — for tests and for a product's own "I know something changed" paths. */
    public void ring() { gate.ring("manual"); }

    public long ringCount() { return gate.ringCount(); }

    public long fireCount() { return gate.fireCount(); }

    @Override
    public void close() {
        for (AutoCloseable s : sources) {
            try { s.close(); } catch (Exception ignored) { }
        }
        gate.close();
    }

    public static final class Builder {
        private String kafkaBootstrap, kafkaTopic, kafkaGroupId;
        private boolean kafkaFailFast;
        private String redisHost, redisPassword, redisChannel;
        private int redisPort = 6379;
        private long debounceMs = 3_000;
        private Runnable onRing;

        public Builder kafka(String bootstrap, String topic) {
            this.kafkaBootstrap = bootstrap; this.kafkaTopic = topic; return this;
        }

        /** Fixed group id — default is a fresh uuid group so every instance hears every event. */
        public Builder kafkaGroupId(String groupId) { this.kafkaGroupId = groupId; return this; }

        /** Abort instead of retrying when Kafka is unreachable (routesphere-core semantics). */
        public Builder kafkaFailFast(boolean failFast) { this.kafkaFailFast = failFast; return this; }

        public Builder redis(String host, int port, String password, String channel) {
            this.redisHost = host; this.redisPort = port;
            this.redisPassword = password; this.redisChannel = channel; return this;
        }

        public Builder debounceMs(long ms) { this.debounceMs = ms; return this; }

        /** The re-fetch. Runs on the gate thread, once per quiet period. */
        public Builder onRing(Runnable action) { this.onRing = action; return this; }

        public ConfigDoorbell build() {
            if (onRing == null) throw new IllegalStateException("onRing(action) is required");
            if (kafkaBootstrap == null && redisHost == null)
                throw new IllegalStateException("at least one source (kafka or redis) is required");
            DebounceGate gate = new DebounceGate(debounceMs, onRing);
            List<AutoCloseable> sources = new ArrayList<>();
            if (kafkaBootstrap != null)
                sources.add(new KafkaDoorbellSource(kafkaBootstrap, kafkaTopic, kafkaGroupId, kafkaFailFast, gate));
            if (redisHost != null)
                sources.add(new RedisDoorbellSource(redisHost, redisPort, redisPassword, redisChannel, gate));
            return new ConfigDoorbell(gate, sources);
        }
    }
}
