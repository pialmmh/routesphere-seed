package com.telcobright.seed.configclient;

import org.apache.kafka.clients.consumer.Consumer;
import org.slf4j.Logger;

import java.time.Duration;

/**
 * How a doorbell's poll thread is stopped: WAKE its consumer (the one thread-safe call a Kafka consumer has), let the poll
 * thread close the consumer itself, and interrupt only as the last resort. An interrupt during poll() leaves the thread's
 * interrupt flag set while the client closes, so the client's own close fails and logs two ERROR lines ("Failed to close
 * coordinator/fetcher with a timeout(ms)=30000") at every stop — seen on the bed at W-ad-service, 2026-10-29.
 */
final class PollStopper {

    /** How long the poll thread gives its consumer to leave the group and close. */
    static final Duration CLOSE_TIMEOUT = Duration.ofSeconds(5);
    /** How long a stop waits for the poll thread: the close above, plus a poll turn. */
    static final Duration JOIN_WAIT = Duration.ofSeconds(8);

    private volatile Consumer<?, ?> polling;

    /** The poll thread names the consumer it polls with, so a stop can wake it. */
    void polling(Consumer<?, ?> consumer) { polling = consumer; }

    /** The poll thread is done with its consumer. */
    void released() { polling = null; }

    /** Wakes the consumer, waits for the poll thread to end, and interrupts it only if it does not end in time. */
    void stop(Thread pollThread, Logger log) {
        Consumer<?, ?> consumer = polling;
        if (consumer != null) consumer.wakeup();
        if (Thread.currentThread() == pollThread) return;   // a stop from the poll thread itself: the loop ends on its own
        joinFor(pollThread, JOIN_WAIT);
        if (!pollThread.isAlive()) return;
        log.warn("doorbell poll thread {} did not end within {} — interrupting it", pollThread.getName(), JOIN_WAIT);
        pollThread.interrupt();
    }

    /** Closes a consumer with a bounded wait; a failing close is said once, never thrown. */
    static void closeQuietly(Consumer<?, ?> consumer, Logger log) {
        try { consumer.close(CLOSE_TIMEOUT); }
        catch (RuntimeException e) { log.warn("doorbell consumer close: {}", e.toString()); }
    }

    private static void joinFor(Thread thread, Duration wait) {
        try { thread.join(wait.toMillis()); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
