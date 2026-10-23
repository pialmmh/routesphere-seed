package com.telcobright.seed.sessionflow.bootstrap;

import com.telcobright.seed.sessionflow.dependencies.TopicProvisioner;
import com.telcobright.seed.sessionflow.testkit.FakeKafka;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * P1-E item 7 · a root's topics made at the start (R9) through Kafka's {@code Admin.createTopics}: idempotent (a topic already there is
 * fine), a cluster that refuses is said once and the start goes on, a name Kafka would refuse is refused before the cluster is asked.
 */
class TopicProvisionerTest {

    private final FakeKafka kafka = new FakeKafka();
    private static final String BROKERS = "10.10.188.2:9092";

    @Test
    void theTopicsAreMadeOnce_aSecondEnsureFindsThemThere() {
        TopicProvisioner.Ensured first = TopicProvisioner.ensure(kafka.admin(BROKERS), List.of("cdr_btcl", "cdr_link3", "cdr_btcl"), 3, (short) 1, BROKERS);
        TopicProvisioner.Ensured second = TopicProvisioner.ensure(kafka.admin(BROKERS), List.of("cdr_btcl", "cdr_link3"), 3, (short) 1, BROKERS);
        assertThat(first.created()).containsExactlyInAnyOrder("cdr_btcl", "cdr_link3");
        assertThat(second.created()).isEmpty();
        assertThat(second.existed()).containsExactlyInAnyOrder("cdr_btcl", "cdr_link3");
        assertThat(kafka.topics.get(BROKERS)).as("each made once").containsExactly("cdr_btcl", "cdr_link3");
        assertThat(kafka.asks.get(BROKERS).get(0)).as("a name named twice is asked once").containsExactly("cdr_btcl", "cdr_link3");
    }

    @Test
    void aClusterThatRefuses_isSaid_andTheStartGoesOn() {
        kafka.refusing(BROKERS);
        TopicProvisioner.Ensured ensured = TopicProvisioner.ensure(kafka.admin(BROKERS), List.of("cdr_btcl"), 3, (short) 1, BROKERS);
        assertThat(ensured.created()).isEmpty();
        assertThat(ensured.refused()).containsOnlyKeys("cdr_btcl");
        assertThat(ensured.refused().get("cdr_btcl")).contains("not authorized");
    }

    @Test
    void aNameKafkaWouldRefuse_isRefusedBeforeTheClusterIsAsked() {
        assertThatThrownBy(() -> TopicProvisioner.ensure(kafka.admin(BROKERS), List.of("cdr.btcl"), 3, (short) 1, BROKERS)).hasMessageContaining("'.'");
        assertThat(kafka.asks).isEmpty();
    }
}
