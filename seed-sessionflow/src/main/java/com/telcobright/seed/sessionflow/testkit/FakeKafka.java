package com.telcobright.seed.sessionflow.testkit;

import com.telcobright.seed.sessionflow.spi.KafkaClients;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.CreateTopicsResult;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.clients.consumer.OffsetResetStrategy;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.common.KafkaFuture;
import org.apache.kafka.common.errors.TopicExistsException;
import org.apache.kafka.common.internals.KafkaFutureImpl;
import org.apache.kafka.common.serialization.StringSerializer;

import java.lang.reflect.Proxy;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A test's Kafka — no broker: one {@code MockProducer} per bootstrap address (auto-completing), one {@code MockConsumer} per bootstrap
 * (latest), and an admin that keeps the topics made per bootstrap ({@code createTopics}: made once, then "exists"; a cluster scripted to
 * refuse refuses every topic).
 */
public final class FakeKafka implements KafkaClients {

    public final Map<String, MockProducer<String, String>> producers = new ConcurrentHashMap<>();
    /** One per bootstrap (a {@code MockConsumer}: a test rebalances it and adds the records it wants heard). */
    public final Map<String, MockConsumer<String, String>> consumers = new ConcurrentHashMap<>();
    /** Every topic made, per bootstrap, in the order made. */
    public final Map<String, List<String>> topics = new ConcurrentHashMap<>();
    /** Every createTopics call, per bootstrap (a test of idempotence counts them). */
    public final Map<String, List<List<String>>> asks = new ConcurrentHashMap<>();
    private final Set<String> refusing = ConcurrentHashMap.newKeySet();

    /** The cluster at {@code bootstrap} refuses to make topics (no right, a broker down). */
    public FakeKafka refusing(String bootstrap) { refusing.add(bootstrap); return this; }

    @Override
    public Producer<String, String> producer(String bootstrap, String clientId) {
        return producers.computeIfAbsent(bootstrap, b -> new MockProducer<>(true, new StringSerializer(), new StringSerializer()));
    }

    @Override
    public Consumer<String, String> consumer(String bootstrap, String groupId) {
        return consumers.computeIfAbsent(bootstrap, b -> new PacedConsumer());
    }

    @Override
    public Admin admin(String bootstrap) {
        return (Admin) Proxy.newProxyInstance(Admin.class.getClassLoader(), new Class<?>[] {Admin.class}, (proxy, method, args) -> switch (method.getName()) {
            case "createTopics" -> create(bootstrap, (Collection<?>) args[0]);
            case "close" -> null;
            case "toString" -> "FakeKafka admin of " + bootstrap;
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            default -> throw new UnsupportedOperationException("the fake admin does only createTopics: " + method.getName());
        });
    }

    /** Kafka's MockConsumer answers a poll at once; a listener's loop would spin — this one waits (the monitor let go) while nothing came. */
    private static final class PacedConsumer extends MockConsumer<String, String> {
        PacedConsumer() { super(OffsetResetStrategy.EARLIEST); }

        @Override
        public synchronized org.apache.kafka.clients.consumer.ConsumerRecords<String, String> poll(java.time.Duration timeout) {
            org.apache.kafka.clients.consumer.ConsumerRecords<String, String> records = super.poll(timeout);
            if (records.isEmpty()) {
                try { wait(Math.min(20, Math.max(1, timeout.toMillis()))); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
            return records;
        }
    }

    private CreateTopicsResult create(String bootstrap, Collection<?> wanted) {
        List<String> made = topics.computeIfAbsent(bootstrap, b -> new CopyOnWriteArrayList<>());
        Map<String, KafkaFuture<CreateTopicsResult.TopicMetadataAndConfig>> futures = new LinkedHashMap<>();
        List<String> names = wanted.stream().map(t -> ((NewTopic) t).name()).toList();
        asks.computeIfAbsent(bootstrap, b -> new CopyOnWriteArrayList<>()).add(names);
        for (String name : names) futures.put(name, outcome(bootstrap, name, made));
        return new CreateTopicsResult(futures) { };
    }

    private KafkaFuture<CreateTopicsResult.TopicMetadataAndConfig> outcome(String bootstrap, String name, List<String> made) {
        KafkaFutureImpl<CreateTopicsResult.TopicMetadataAndConfig> f = new KafkaFutureImpl<>();
        if (refusing.contains(bootstrap)) f.completeExceptionally(new org.apache.kafka.common.errors.TopicAuthorizationException("not authorized to create " + name));
        else if (made.contains(name)) f.completeExceptionally(new TopicExistsException("Topic '" + name + "' already exists."));
        else { made.add(name); f.complete(null); }
        return f;
    }
}
