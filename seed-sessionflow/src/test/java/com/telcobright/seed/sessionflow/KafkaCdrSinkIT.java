package com.telcobright.seed.sessionflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.telcobright.seed.sessionflow.api.SessionState;
import com.telcobright.seed.sessionflow.api.CdrEvent;
import com.telcobright.seed.sessionflow.dependencies.CdrSinks;
import com.telcobright.seed.sessionflow.samples.AdFlow;
import com.telcobright.seed.sessionflow.samples.Scene;
import com.telcobright.seed.sessionflow.spi.CdrSink;
import com.telcobright.statewalk.pipeline.StepMode;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The CDR road over a real broker (the local Kafka on 127.0.0.1:9092; run with -Dseed.it=true): a call is ONE message,
 * its key is the call id, its value is the array of its tier records — what billing-core's consumer reads. A new
 * consumer group that starts at the beginning of the topic sees every call published before it started.
 */
@EnabledIfSystemProperty(named = "seed.it", matches = "true")
class KafkaCdrSinkIT {

    static final String BOOTSTRAP = "127.0.0.1:9092";

    @Test
    void aCallIsOneMessage_keyedByItsId_carryingEveryTier() throws Exception {
        String topic = "cdr_it" + System.currentTimeMillis();
        createTopic(topic);
        Scene scene = new Scene();
        AdFlow ad = scene.ad(Scene.settings(2), true);
        AdFlow.View view = Scene.view("ad-kafka-1", "dhaka-zone");
        assertThat(ad.preprocess(view)).isNull();
        assertThat(ad.admission(view, StepMode.LIVE).accepted()).isTrue();
        ad.close(view, SessionState.FAILED, Scene.NO_MACHINE);
        List<CdrEvent> tiers = scene.cdrs.of("ad-kafka-1").get(0).tiers();

        try (CdrSink kafka = CdrSinks.kafka(BOOTSTRAP, topic, "seed-callflow-it")) {
            kafka.publish("ad-kafka-1", tiers);
            kafka.publish("ad-kafka-1", tiers);              // sent twice: the road is at-least-once, billing takes it once
        }

        List<ConsumerRecord<String, String>> records = readFromTheBeginning(topic, 2);
        assertThat(records).hasSize(2);
        assertThat(records).allSatisfy(record -> assertThat(record.key()).isEqualTo("ad-kafka-1"));
        JsonNode call = new ObjectMapper().readTree(records.get(0).value());
        assertThat(call.isArray()).isTrue();
        assertThat(call).hasSize(2);
        assertThat(call.get(0).get("tenant").asText()).isEqualTo("res_44");
        assertThat(call.get(1).get("tenant").asText()).isEqualTo("btcl");
        assertThat(call.get(0).get("serviceGroup").asInt()).isEqualTo(30);
        assertThat(records.get(1).value()).isEqualTo(records.get(0).value());
    }

    private static void createTopic(String topic) throws Exception {
        Properties admin = new Properties();
        admin.put("bootstrap.servers", BOOTSTRAP);
        try (AdminClient client = AdminClient.create(admin)) {
            client.createTopics(List.of(new NewTopic(topic, 1, (short) 1))).all().get(20, TimeUnit.SECONDS);
        }
    }

    private static List<ConsumerRecord<String, String>> readFromTheBeginning(String topic, int expected) {
        Properties p = new Properties();
        p.put("bootstrap.servers", BOOTSTRAP);
        p.put("group.id", "billing-core-like-" + topic);
        p.put("auto.offset.reset", "earliest");
        p.put("enable.auto.commit", "false");
        List<ConsumerRecord<String, String>> out = new ArrayList<>();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(p, new StringDeserializer(), new StringDeserializer())) {
            consumer.subscribe(List.of(topic));
            long deadline = System.currentTimeMillis() + 30_000;
            while (out.size() < expected && System.currentTimeMillis() < deadline) {
                consumer.poll(Duration.ofMillis(500)).forEach(out::add);
            }
        }
        return out;
    }
}
