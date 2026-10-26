package com.telcobright.seed.sessionflow.base;

import com.telcobright.seed.sessionflow.api.PoolStats;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P1-F item 5 · the sessions live now, beside the registry's live CELLS ({@code live}, kept as it was): two cells per session here — its
 * supervisor and its signaling machine. A record made with the ten numbers of 2.0 still builds; its sessions are not counted.
 */
class PoolSessionsTest {

    private Lab lab;

    @AfterEach
    void close() { if (lab != null) lab.close(); }

    @Test
    void theSessionsLiveNow_standBesideTheCells() {
        lab = new Lab(PoolCountersTest.poolOfTwo(), kit -> new LabFlow(kit, Map.of(), Lab.hops("carrier-a"), Set.of()));
        lab.dial("s-1", Lab.BTCL_DOOR, 1);
        lab.dial("s-2", Lab.BTCL_DOOR, 1);

        PoolStats stats = lab.engine.stats();
        assertThat(stats.sessions()).isEqualTo(2);
        assertThat(stats.live()).as("the registry's cells: a supervisor and a signaling machine per session").isEqualTo(4);

        lab.hangupWhenAnswered("s-1");
        long until = System.currentTimeMillis() + 5_000;
        while (lab.engine.stats().sessions() > 1 && System.currentTimeMillis() < until) Thread.onSpinWait();
        assertThat(lab.engine.stats().sessions()).isEqualTo(1);
    }

    @Test
    void theTenNumbersOf2_0_stillMakeARecord_itsSessionsNotCounted() {
        PoolStats made = new PoolStats(100, 3, 100, 1000, 1, 997, 997, 0, 0, 0);
        assertThat(made.sessions()).isEqualTo(PoolStats.NOT_COUNTED).isEqualTo(-1);
        assertThat(made.live()).isEqualTo(3);
        assertThat(made.busy()).isEqualTo(1);
    }
}
