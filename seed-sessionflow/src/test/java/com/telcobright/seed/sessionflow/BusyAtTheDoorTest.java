package com.telcobright.seed.sessionflow;

import com.telcobright.seed.sessionflow.internal.BusyAtTheDoor;
import com.telcobright.seed.sessionflow.internal.DoorChannel;
import com.telcobright.seed.sessionflow.internal.FlowCounters;
import com.telcobright.statewalk.channel.TestChannel;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.RejectedExecutionException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P1-F item 4 · the engine's door counter: only statewalk's refusal of a NEW session because every machine is live is {@code busy}; the
 * lane's channel behind the door is otherwise the lane's own (its sends, cancels, stop, state and name).
 */
class BusyAtTheDoorTest {

    private static final String FULL = "[ad] new session id=v-1 refused: CAPACITY_EXCEEDED";

    @Test
    void onlyTheRefusalBecauseEveryMachineIsLive_isBusy() {
        assertThat(BusyAtTheDoor.isBusy(new RejectedExecutionException(FULL))).isTrue();
        assertThat(BusyAtTheDoor.isBusy(new CompletionException(new RejectedExecutionException(FULL)))).as("as join() throws it").isTrue();
        assertThat(BusyAtTheDoor.isBusy(new RejectedExecutionException("[ad] new session id=v-1 refused: SHUTTING_DOWN"))).isFalse();
        assertThat(BusyAtTheDoor.isBusy(new RejectedExecutionException("[ad] new session id=v-1 refused: DUPLICATE_ID"))).isFalse();
        assertThat(BusyAtTheDoor.isBusy(new RejectedExecutionException("[ad] inbound backlog over bound — event Dial for id=v-1 shed"))).isFalse();
        assertThat(BusyAtTheDoor.isBusy(new IllegalStateException(FULL))).as("not statewalk's refusal").isFalse();
        assertThat(BusyAtTheDoor.isBusy(new RejectedExecutionException())).isFalse();
        assertThat(BusyAtTheDoor.isBusy(null)).as("a frame taken").isFalse();
    }

    @Test
    void theDoorCountsTheRegistrysBusyRefusals_andHandsEveryFrameOn() {
        TestChannel<String, String> lane = new TestChannel<>("ad-http");
        FlowCounters counters = new FlowCounters();
        DoorChannel<String, String> door = new DoorChannel<>(lane, counters);
        List<String> offered = new CopyOnWriteArrayList<>();
        door.start((id, frame) -> {
            offered.add(id + ":" + frame);
            return id.equals("full") ? CompletableFuture.failedFuture(new RejectedExecutionException(FULL)) : CompletableFuture.completedFuture(null);
        });

        assertThat(lane.inject("v-1", "start")).isCompleted();
        assertThat(lane.inject("full", "start").toCompletableFuture()).isCompletedExceptionally();

        assertThat(offered).containsExactly("v-1:start", "full:start");
        assertThat(counters.busy.get()).isEqualTo(1);
    }

    @Test
    void behindTheDoor_theLanesChannelIsItsOwn() {
        TestChannel<String, String> lane = new TestChannel<>("ad-http");
        DoorChannel<String, String> door = new DoorChannel<>(lane, new FlowCounters());
        door.start((id, frame) -> CompletableFuture.completedFuture(null));

        door.send("v-1", "offer");
        door.cancel("v-2");
        lane.setConnected(false);

        assertThat(lane.sends).extracting(s -> s.requestId() + ":" + s.command()).containsExactly("v-1:offer");
        assertThat(lane.cancels).containsExactly("v-2");
        assertThat(door.isConnected()).isFalse();
        assertThat(door.getName()).isEqualTo("ad-http");
        assertThat(door.lane()).isSameAs(lane);
        assertThat(lane.isStarted()).isTrue();
        door.stop();
        assertThat(lane.isStarted()).isFalse();
    }
}
