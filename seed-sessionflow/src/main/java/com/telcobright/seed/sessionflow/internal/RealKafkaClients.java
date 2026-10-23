package com.telcobright.seed.sessionflow.internal;

import com.telcobright.seed.sessionflow.spi.KafkaClients;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;

import java.util.Properties;

/** The real Kafka clients of a kit made from the bootstrap: the CDR's producer as {@link KafkaCdrSink} sets it, an admin, a consumer. */
public final class RealKafkaClients implements KafkaClients {

    @Override
    public Producer<String, String> producer(String bootstrap, String clientId) {
        return new KafkaProducer<>(KafkaCdrSink.producerSettings(bootstrap, clientId), new StringSerializer(), new StringSerializer());
    }

    @Override
    public Admin admin(String bootstrap) {
        Properties p = new Properties();
        p.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        p.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, 10_000);
        p.put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, 15_000);
        return Admin.create(p);
    }

    @Override
    public Consumer<String, String> consumer(String bootstrap, String groupId) {
        Properties p = new Properties();
        p.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        p.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        p.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");
        p.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "true");
        return new KafkaConsumer<>(p, new StringDeserializer(), new StringDeserializer());
    }
}
