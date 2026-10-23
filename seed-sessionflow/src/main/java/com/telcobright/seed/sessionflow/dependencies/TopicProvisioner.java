package com.telcobright.seed.sessionflow.dependencies;

import com.telcobright.seed.naming.api.Topics;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.CreateTopicsOptions;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.KafkaFuture;
import org.apache.kafka.common.errors.TopicExistsException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The topics of a root made at the start (R9: "the seed creates the topics at service start for every served root, and again when a root is
 * added live"), through Kafka's {@code Admin.createTopics}. Idempotent: a topic that exists is fine ({@code TopicExistsException}). A
 * cluster that refuses (no right to create, a broker down) is said ONCE, with every topic it refused, and the start goes on — the brokers
 * may create a topic at its first message.
 */
public final class TopicProvisioner {

    private static final Logger log = LoggerFactory.getLogger(TopicProvisioner.class);
    static final long WAIT_MS = 10_000;

    private TopicProvisioner() {}

    /** What one ensure came to: the topics made now, the ones already there, the ones the cluster refused (with its words). */
    public record Ensured(List<String> created, List<String> existed, Map<String, String> refused) {}

    /** Every topic named (each checked by Kafka's rules first), {@code partitions} × {@code replication}; {@code where} names the cluster in the log. */
    public static Ensured ensure(Admin admin, Collection<String> topics, int partitions, short replication, String where) {
        List<NewTopic> wanted = new ArrayList<>();
        for (String topic : new LinkedHashSet<>(topics)) wanted.add(new NewTopic(Topics.checked(topic), partitions, replication));
        if (wanted.isEmpty()) return new Ensured(List.of(), List.of(), Map.of());
        Ensured ensured = outcomeOf(admin.createTopics(wanted, new CreateTopicsOptions().timeoutMs((int) WAIT_MS)).values());
        sayIt(ensured, where);
        return ensured;
    }

    private static Ensured outcomeOf(Map<String, KafkaFuture<Void>> results) {
        List<String> created = new ArrayList<>(), existed = new ArrayList<>();
        Map<String, String> refused = new LinkedHashMap<>();
        results.forEach((topic, future) -> {
            String failure = failureOf(future);
            if (failure == null) created.add(topic);
            else if (failure.isEmpty()) existed.add(topic);
            else refused.put(topic, failure);
        });
        return new Ensured(List.copyOf(created), List.copyOf(existed), Map.copyOf(refused));
    }

    /** null = made; "" = it was there; else the cluster's words. */
    private static String failureOf(KafkaFuture<Void> future) {
        try {
            future.get(WAIT_MS, TimeUnit.MILLISECONDS);
            return null;
        } catch (ExecutionException e) {
            return e.getCause() instanceof TopicExistsException ? "" : String.valueOf(e.getCause());
        } catch (TimeoutException e) {
            return "no answer within " + WAIT_MS + " ms";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "interrupted";
        }
    }

    private static void sayIt(Ensured ensured, String where) {
        if (!ensured.created().isEmpty()) log.info("Kafka {}: topics made {}", where, ensured.created());
        if (!ensured.refused().isEmpty()) {
            log.warn("Kafka {} refused to make the topics {} — the start goes on (the brokers may make a topic at its first message): {}", where,
                ensured.refused().keySet(), ensured.refused());
        }
    }
}
