package com.telcobright.seed.configclient;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ARCH-0077-A item 7 — the CAP of the debounce (routesphere-core {@code ConfigEventConsumer.scheduleReload}, 560–601): a pure trailing-edge
 * debounce is starved by a dense stream — every ring re-arms, the reload never comes. The rule: when the last FIRE is older than
 * 2 × debounceMs, a ring fires at once (delay 0); otherwise it re-arms. A ring every second for 30 s, with 3 s of debounce, must fire about
 * every 6 s — at least 4 times — and once more after the silence.
 */
class DebounceGateCapTest {

    @Test
    void aDenseStream_firesAboutEveryTwiceTheDebounce_thenOnceAfterTheSilence() throws Exception {
        List<Long> fires = new CopyOnWriteArrayList<>();
        long start = System.currentTimeMillis();
        try (DebounceGate gate = new DebounceGate(3000, () -> fires.add(System.currentTimeMillis() - start))) {
            for (int i = 0; i < 30; i++) {
                gate.ring("kafka");
                Thread.sleep(1000);
            }
            int duringTheStream = fires.size();
            assertTrue(duringTheStream >= 4, "a dense stream must not starve the gate: fires during 30 s of rings every 1 s = " + duringTheStream + " at " + fires);

            Thread.sleep(3600);                                                      // the silence: the last ring's trailing fire
            assertEquals(duringTheStream + 1, fires.size(), "exactly one trailing fire after the silence: " + fires);

            List<Long> gaps = new ArrayList<>();
            for (int i = 1; i < duringTheStream; i++) gaps.add(fires.get(i) - fires.get(i - 1));
            assertTrue(gaps.stream().allMatch(g -> g >= 5500 && g <= 8500), "the fires during the stream come about every 6–7 s (2 × debounce, plus the ring's second): " + gaps);
            assertEquals(30, gate.ringCount());
            assertEquals(fires.size(), gate.fireCount());
        }
    }
}
