package com.telcobright.seed.configclient;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class DebounceGateTest {

    /** P1-H 3 · A fire waited for with a generous deadline: a loaded box fires late, never early. */
    static void awaitFired(AtomicInteger fired, int atLeast) throws InterruptedException {
        long until = System.currentTimeMillis() + 10_000;
        while (fired.get() < atLeast && System.currentTimeMillis() < until) Thread.sleep(10);
    }

    @Test
    void burstOfRingsFiresOnce() throws Exception {
        AtomicInteger fired = new AtomicInteger();
        CountDownLatch latch = new CountDownLatch(1);
        try (DebounceGate gate = new DebounceGate(120, () -> { fired.incrementAndGet(); latch.countDown(); })) {
            for (int i = 0; i < 25; i++) { gate.ring("test"); Thread.sleep(3); }
            assertTrue(latch.await(10, TimeUnit.SECONDS), "action should fire after the quiet period");
            Thread.sleep(250);   // no late second fire
            assertEquals(1, fired.get(), "a burst must collapse to exactly one fire");
            assertEquals(25, gate.ringCount());
        }
    }

    @Test
    void separatedRingsFireSeparately() throws Exception {
        AtomicInteger fired = new AtomicInteger();
        try (DebounceGate gate = new DebounceGate(60, fired::incrementAndGet)) {
            gate.ring("a");
            awaitFired(fired, 1);                                                  // P1-H 3: the first fire waited for, never a fixed sleep
            gate.ring("b");
            awaitFired(fired, 2);
            assertEquals(2, fired.get(), "rings separated by more than the quiet period each fire");
        }
    }

    @Test
    void throwingActionDoesNotKillTheGate() throws Exception {
        AtomicInteger fired = new AtomicInteger();
        try (DebounceGate gate = new DebounceGate(40, () -> {
            if (fired.incrementAndGet() == 1) throw new RuntimeException("boom");
        })) {
            gate.ring("x");
            awaitFired(fired, 1);
            gate.ring("y");
            awaitFired(fired, 2);
            assertEquals(2, fired.get(), "the gate must survive a throwing action");
        }
    }

    @Test
    void manualRingViaDoorbellFacade() throws Exception {
        AtomicInteger fired = new AtomicInteger();
        ConfigDoorbell bell = ConfigDoorbell.builder()
                .redis("localhost", 6399, null, "never-connects")   // source never used in this test
                .debounceMs(40)
                .onRing(fired::incrementAndGet)
                .build();
        try (bell) {
            bell.ring();
            awaitFired(fired, 1);
            assertEquals(1, fired.get());
            assertTrue(bell.fireCount() >= 1);
        }
    }
}
