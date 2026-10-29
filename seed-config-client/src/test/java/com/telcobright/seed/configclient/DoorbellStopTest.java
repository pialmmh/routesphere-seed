package com.telcobright.seed.configclient;

import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.clients.consumer.OffsetResetStrategy;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A doorbell stops by WAKING its consumer, and its poll thread closes the consumer itself on a thread that is not interrupted.
 * An interrupt in poll() made the real client's close fail with two ERROR lines at every stop (the bed, W-ad-service,
 * 2026-10-29). Both sources, each with a consumer that records how it was stopped and closed.
 */
class DoorbellStopTest {

    static final String TOPIC = "config_event_loader_btcl";

    @Test
    void the_tenant_doorbell_stops_by_waking_its_consumer_and_closes_it_on_a_thread_that_is_not_interrupted() {
        RecordingConsumer consumer = new RecordingConsumer();
        KafkaTopicsDoorbellSource source = new KafkaTopicsDoorbellSource("unused:9092", "g", false, topic -> {}, null, () -> consumer);
        source.subscribe(List.of(TOPIC));
        source.start();
        awaitTrue(() -> !consumer.subscription().isEmpty(), "the doorbell subscribed");

        long took = timed(source::close);

        assertStoppedCleanly(consumer, source.polling(), took);
    }

    @Test
    void the_single_topic_doorbell_stops_the_same_way() {
        RecordingConsumer consumer = new RecordingConsumer();
        try (DebounceGate gate = new DebounceGate(50, () -> {})) {
            KafkaDoorbellSource source = new KafkaDoorbellSource("unused:9092", TOPIC, "g", false, gate, () -> consumer);
            source.start();
            awaitTrue(() -> !consumer.subscription().isEmpty(), "the doorbell subscribed");

            long took = timed(source::close);

            assertStoppedCleanly(consumer, source.polling(), took);
        }
    }

    @Test
    void a_stop_while_nothing_is_subscribed_still_ends_the_thread() {
        KafkaTopicsDoorbellSource source = new KafkaTopicsDoorbellSource("unused:9092", "g", false, topic -> {}, null, RecordingConsumer::new);
        source.start();   // nothing subscribed: the loop sleeps between looks at the list, its consumer never polls

        long took = timed(source::close);

        assertFalse(source.polling(), "the poll thread ended");
        assertTrue(took < PollStopper.JOIN_WAIT.toMillis(), "it ended on its own, without waiting for the last-resort interrupt: " + took + " ms");
    }

    static void assertStoppedCleanly(RecordingConsumer consumer, boolean stillPolling, long tookMs) {
        assertTrue(consumer.wokenUp, "the stop woke the consumer");
        assertEquals(PollStopper.CLOSE_TIMEOUT, consumer.closedWith, "closed once, with the bounded timeout");
        assertFalse(consumer.interruptedAtClose, "the consumer was closed on an interrupted thread: the real client's close fails then");
        assertFalse(stillPolling, "the poll thread ended");
        assertTrue(tookMs < PollStopper.JOIN_WAIT.toMillis(), "the stop returned without the last-resort interrupt: " + tookMs + " ms");
    }

    static long timed(Runnable r) {
        long t0 = System.nanoTime();
        r.run();
        return (System.nanoTime() - t0) / 1_000_000;
    }

    static void awaitTrue(BooleanSupplier condition, String what) {
        long until = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > until) fail("timed out waiting: " + what);
            Thread.onSpinWait();
        }
    }

    /** A mock consumer that records the wake-up, the close timeout, and whether the closing thread was interrupted. */
    static final class RecordingConsumer extends MockConsumer<String, String> {
        volatile boolean wokenUp;
        volatile Duration closedWith;
        volatile boolean interruptedAtClose;

        RecordingConsumer() { super(OffsetResetStrategy.LATEST); }

        @Override
        public synchronized void wakeup() {
            wokenUp = true;
            super.wakeup();
        }

        @Override
        public synchronized void close(Duration timeout) {
            closedWith = timeout;
            interruptedAtClose = Thread.currentThread().isInterrupted();
            super.close(timeout);
        }
    }
}
