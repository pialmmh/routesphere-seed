package com.telcobright.seed.context;

import com.telcobright.seed.context.api.TenantContexts;
import com.telcobright.seed.context.dependencies.EnvSecrets;
import com.telcobright.seed.context.spi.TopicNaming;
import com.telcobright.seed.context.testkit.ScriptedLoader;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static com.telcobright.seed.context.testkit.TwoTenantsHarness.awaitUntil;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The per-tenant doorbell over a real broker (the local Kafka on 127.0.0.1:9092; run with -Dseed.it=true):
 * a record on a's topic reloads a and only a; b's topic is subscribed by exact name, never a pattern.
 */
@EnabledIfSystemProperty(named = "seed.it", matches = "true")
class KafkaDoorbellIT {

    static final String BOOTSTRAP = "127.0.0.1:9092";

    @Test
    void a_record_on_a_tenants_topic_reloads_that_tenant_only() throws Exception {
        String run = "it" + System.currentTimeMillis();
        TopicNaming naming = (base, tenant) -> base + "_" + tenant + "_" + run;
        Properties admin = new Properties();
        admin.put("bootstrap.servers", BOOTSTRAP);
        try (AdminClient ac = AdminClient.create(admin)) {
            ac.createTopics(List.of(new NewTopic(naming.topic("seedctx", "a"), 1, (short) 1), new NewTopic(naming.topic("seedctx", "b"), 1, (short) 1)))
              .all().get(20, TimeUnit.SECONDS);
        }
        ScriptedLoader loader = new ScriptedLoader();
        TenantContexts<Map<String, Object>> cache = TenantContexts.<Map<String, Object>>builder()
            .directory(() -> Set.of("a", "b"))
            .loader(loader)
            .secrets(EnvSecrets.of(Map.of()))
            .debounceMs(200).backstop(Duration.ZERO)
            .doorbell(BOOTSTRAP, "seedctx", naming, "seed-context-" + run)
            .build().start();
        try {
            assertEquals(1, cache.require("a").version());
            Properties pp = new Properties();
            pp.put("bootstrap.servers", BOOTSTRAP);
            try (KafkaProducer<String, String> producer = new KafkaProducer<>(pp, new StringSerializer(), new StringSerializer())) {
                // the consumer subscribes after a rebalance and starts at "latest": ring until it hears us
                boolean rang = awaitUntil(() -> {
                    producer.send(new ProducerRecord<>(naming.topic("seedctx", "a"), "k", "{\"event\":\"config_reload\"}"));
                    producer.flush();
                    sleep(500);
                    return cache.require("a").version() >= 2;
                }, 30_000);
                assertTrue(rang, "a reloaded on its doorbell");
            }
            Thread.sleep(1_000);
            assertEquals(1, cache.require("b").version(), "b never rang");
            assertEquals(1, loader.calls("b"));
        } finally {
            cache.close();
        }
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
