package com.telcobright.seed.configclient;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPubSub;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The fast doorbell leg: Redis pub/sub on the notification channel. Purely latency sugar on top
 * of the Kafka leg — pub/sub delivery is best-effort (a subscriber that is down misses the
 * message), which is fine because Kafka carries the same doorbell reliably.
 */
final class RedisDoorbellSource implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(RedisDoorbellSource.class);
    private static final long RETRY_BACKOFF_MS = 5_000;

    private final String host;
    private final int port;
    private final String password;   // null = no auth
    private final String channel;
    private final DebounceGate gate;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final Thread thread;
    private volatile JedisPubSub active;

    RedisDoorbellSource(String host, int port, String password, String channel, DebounceGate gate) {
        this.host = host;
        this.port = port;
        this.password = password;
        this.channel = channel;
        this.gate = gate;
        this.thread = new Thread(this::run, "config-doorbell-redis");
        this.thread.setDaemon(true);
    }

    void start() { thread.start(); }

    private void run() {
        while (running.get()) {
            try (Jedis jedis = new Jedis(host, port)) {
                if (password != null && !password.isBlank()) jedis.auth(password);
                JedisPubSub sub = new JedisPubSub() {
                    @Override public void onMessage(String ch, String msg) { gate.ring("redis"); }
                };
                active = sub;
                log.info("doorbell listening on redis {}:{} channel={}", host, port, channel);
                jedis.subscribe(sub, channel);   // blocks until unsubscribed
            } catch (Exception e) {
                if (!running.get()) return;
                log.warn("redis doorbell lost ({}) — retrying in {}ms", e.getMessage(), RETRY_BACKOFF_MS);
                sleep(RETRY_BACKOFF_MS);
            }
        }
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
    }

    @Override
    public void close() {
        running.set(false);
        JedisPubSub sub = active;
        if (sub != null) {
            try { sub.unsubscribe(); } catch (Exception ignored) { }
        }
        thread.interrupt();
    }
}
