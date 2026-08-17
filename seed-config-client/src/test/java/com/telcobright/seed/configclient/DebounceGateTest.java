package com.telcobright.seed.configclient;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class DebounceGateTest {

    @Test
    void burstOfRingsFiresOnce() throws Exception {
        AtomicInteger fired = new AtomicInteger();
        CountDownLatch latch = new CountDownLatch(1);
        try (DebounceGate gate = new DebounceGate(120, () -> { fired.incrementAndGet(); latch.countDown(); })) {
            for (int i = 0; i < 25; i++) { gate.ring("test"); Thread.sleep(3); }
            assertTrue(latch.await(2, TimeUnit.SECONDS), "action should fire after the quiet period");
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
            Thread.sleep(200);
            gate.ring("b");
            Thread.sleep(200);
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
            Thread.sleep(150);
            gate.ring("y");
            Thread.sleep(150);
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
            Thread.sleep(150);
            assertEquals(1, fired.get());
            assertTrue(bell.fireCount() >= 1);
        }
    }
}
