package com.telcobright.seed.sessionflow.bootstrap;

import com.telcobright.seed.naming.api.Topics;
import com.telcobright.seed.sessionflow.api.TenantCreated;
import com.telcobright.seed.sessionflow.internal.TenantCreatedListener;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.clients.consumer.OffsetResetStrategy;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/** P1-E item 8 · the listener of {@code tenant_created}: it subscribes the platform topic and hands every §C.2 message to its road; a bad one is said, the next is read. */
class TenantCreatedListenerTest {

    @Test
    void everyMessageIsHandedOver_aBadOneSaid_andTheNextRead() {
        MockConsumer<String, String> consumer = new MockConsumer<>(OffsetResetStrategy.EARLIEST);
        List<TenantCreated> heard = new CopyOnWriteArrayList<>();
        TenantCreatedListener listener = new TenantCreatedListener(consumer, heard::add, "ad-sphere");
        assertThat(consumer.subscription()).containsExactly(Topics.TENANT_CREATED);

        TopicPartition tp = new TopicPartition(Topics.TENANT_CREATED, 0);
        consumer.rebalance(List.of(tp));
        consumer.updateBeginningOffsets(Map.of(tp, 0L));
        consumer.addRecord(new ConsumerRecord<>(Topics.TENANT_CREATED, 0, 0, "x", "not json"));
        consumer.addRecord(new ConsumerRecord<>(Topics.TENANT_CREATED, 0, 1, "link3", "{\"root\": \"link3\", \"kind\": \"root\", \"apps\": [\"ad-sphere\"]}"));

        assertThat(listener.pollOnce(Duration.ofMillis(10))).isEqualTo(2);
        assertThat(heard).extracting(TenantCreated::root).containsExactly("link3");
        listener.close();
        assertThat(consumer.closed()).isTrue();
    }
}
