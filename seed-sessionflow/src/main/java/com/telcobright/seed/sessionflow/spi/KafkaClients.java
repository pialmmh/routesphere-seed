package com.telcobright.seed.sessionflow.spi;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.producer.Producer;

/**
 * The Kafka clients a kit made from the bootstrap opens, one bootstrap address at a time: the CDR road's producer, the admin that creates a
 * root's topics, the consumer of {@code tenant_created}. The real ones by default ({@code KitMakers}); a test's are Kafka's
 * {@code MockProducer} / {@code MockConsumer} and a fake admin — no broker on the build box.
 */
public interface KafkaClients {

    /** A producer of strings (acks=all, idempotent) on {@code bootstrap}. */
    Producer<String, String> producer(String bootstrap, String clientId);

    /** The admin client of {@code bootstrap} (topic creation). */
    Admin admin(String bootstrap);

    /** A consumer of strings of {@code groupId} on {@code bootstrap}. */
    Consumer<String, String> consumer(String bootstrap, String groupId);
}
