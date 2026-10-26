package com.telcobright.seed.sessionflow.base;

import com.telcobright.seed.sessionflow.base.LabEvents.Dial;
import com.telcobright.seed.sessionflow.dependencies.RoutesphereSettings;
import com.telcobright.statewalk.channel.Delivery;
import com.telcobright.statewalk.session.SessionTimings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * P1-F item 4 · the pool's numbers counted where they happen. The machines built are the registry's supervisors' pool's own count
 * (statewalk builds the machines, the base builds none). A NEW session refused at the door because every machine is live is {@code busy}
 * — at the lane's channel's inbound and at the engine's {@code launch}; {@code deliver} counts nothing (a lane that counts its own
 * refusals, as ad-sphere does, is not counted twice).
 */
class PoolCountersTest {

    /** A pool of two: statewalk builds no machine ahead (it pre-builds a quarter of a pool), the third live session finds none. */
    static RoutesphereSettings poolOfTwo() {
        return new RoutesphereSettings(2, 2, 120, new SessionTimings(2, 2, 5, 60), 0, 0, 0, false, 200);
    }

    private Lab lab;

    @AfterEach
    void close() { if (lab != null) lab.close(); }

    private void labOfTwo() { lab = new Lab(poolOfTwo(), kit -> new LabFlow(kit, Map.of(), Lab.hops("carrier-a"), Set.of())); }

    @Test
    void theMachinesBuilt_areTheSupervisorsPoolsOwnCount_aMachineGivenBackIsUsedAgain() {
        labOfTwo();
        lab.dial("m-1", Lab.BTCL_DOOR, 1);
        lab.dial("m-2", Lab.BTCL_DOOR, 1);                                  // two live at once: the pool's two supervisors built
        lab.hangupWhenAnswered("m-1");
        lab.hangupWhenAnswered("m-2");
        awaitSessions(0);
        lab.dial("m-3", Lab.BTCL_DOOR, 1);                                  // a third, on a supervisor given back
        lab.hangupWhenAnswered("m-3");

        assertThat(lab.engine.stats().machinesBuilt()).isEqualTo(2)
            .isEqualTo(lab.engine.registry().poolStatistics(lab.flow.name()).totalCreated());
        assertThat(lab.engine.stats().launched()).isEqualTo(3);
    }

    @Test
    void aNewSessionRefusedAtTheChannelsDoor_becauseEveryMachineIsLive_isBusy() {
        labOfTwo();
        lab.dial("p-1", Lab.BTCL_DOOR, 1);
        lab.dial("p-2", Lab.BTCL_DOOR, 1);

        assertThatThrownBy(() -> lab.dial("p-3", Lab.BTCL_DOOR, 1)).hasMessageContaining("refused: CAPACITY_EXCEEDED");

        assertThat(lab.engine.stats().busy()).isEqualTo(1);
        assertThat(lab.engine.stats().launched()).as("the refused session never got a machine").isEqualTo(2);
        lab.hangupWhenAnswered("p-1");
        awaitSessions(1);
        lab.dial("p-4", Lab.BTCL_DOOR, 1);                                  // a machine given back: the next session takes it
        assertThat(lab.engine.stats().busy()).isEqualTo(1);
        assertThat(lab.engine.isLive("p-4")).isTrue();
    }

    @Test
    void aNewSessionRefusedAtTheEnginesLaunch_isBusyToo_andDeliverCountsNothing() {
        labOfTwo();
        lab.dial("p-1", Lab.BTCL_DOOR, 1);
        lab.dial("p-2", Lab.BTCL_DOOR, 1);

        assertThatThrownBy(() -> lab.engine.launch("p-3", firstFrame("p-3")).join()).hasMessageContaining("refused: CAPACITY_EXCEEDED");
        assertThat(lab.engine.stats().busy()).isEqualTo(1);

        assertThatThrownBy(() -> lab.engine.deliver("p-4", firstFrame("p-4")).join()).hasMessageContaining("refused: CAPACITY_EXCEEDED");
        assertThat(lab.engine.stats().busy()).as("deliver counts nothing: the lane counting its own refusals is not counted twice").isEqualTo(1);
    }

    @Test
    void aLaunchThatGetsItsMachine_isNotBusy() throws InterruptedException {
        labOfTwo();
        lab.engine.launch("l-1", firstFrame("l-1")).join();
        lab.engine.awaitSettled("l-1", 5, TimeUnit.SECONDS);                // the supervisor's INIT follows the signaling machine's up
        lab.engine.registry().awaitIdle(5, TimeUnit.SECONDS);
        assertThat(lab.engine.isLive("l-1")).isTrue();
        assertThat(lab.engine.stats().busy()).isZero();
        assertThat(lab.engine.stats().launched()).isEqualTo(1);
    }

    private static Delivery firstFrame(String id) {
        return Delivery.to(LabCall.TYPE, new Dial(new LabFacts(id, System.currentTimeMillis(), Lab.BTCL_DOOR, 1, "8801711000000")));
    }

    private void awaitSessions(int live) {
        long until = System.currentTimeMillis() + 5_000;
        while (lab.engine.registry().activeIdCount() > live && System.currentTimeMillis() < until) Thread.onSpinWait();
        assertThat(lab.engine.registry().activeIdCount()).isEqualTo(live);
    }
}
