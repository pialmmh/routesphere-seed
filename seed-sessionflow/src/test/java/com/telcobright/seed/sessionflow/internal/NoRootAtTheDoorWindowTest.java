package com.telcobright.seed.sessionflow.internal;

import com.telcobright.seed.sessionflow.api.Ingress;
import com.telcobright.seed.sessionflow.api.RoutesphereFacts;
import com.telcobright.seed.sessionflow.testkit.ManualClock;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P1-K item 5 · the door's refusals for no root are flood-safe, per door: the first in each 60 s window is said in full; the rest are
 * counted and said as ONE summary line at the door's next refusal after the window — no timer thread (the clock here is a manual one).
 * The count is exact; memory is bounded (at most 1024 doors held apart, then one shared window).
 */
class NoRootAtTheDoorWindowTest {

    private static final Ingress NOBODYS = Ingress.esl("10.10.197.9", 8021), STRANGERS = Ingress.listen("10.10.188.2", 7070);
    private final ManualClock clock = new ManualClock(Instant.parse("2026-10-24T11:20:00Z"), ZoneOffset.UTC);
    private final FlowCounters counters = new FlowCounters();
    private final NoRootAtTheDoor door = new NoRootAtTheDoor("lab", clock, counters, () -> false);

    private record DoorFacts(String sessionId, long atMs, Ingress ingress) implements RoutesphereFacts {}

    private void refuse(String sessionId, Ingress at) { door.refused(new DoorFacts(sessionId, clock.millis(), at), null); }

    @Test
    void aBurstOf1000_saysOneLine_thenAfterTheWindow_ONESummaryWithTheExactCount_andTheNextInFull() {
        for (int i = 0; i < 1000; i++) refuse("s-" + i, NOBODYS);
        assertThat(door.said()).singleElement().asString().contains("no root at esl 10.10.197.9:8021 (session s-0)");
        assertThat(counters.refusedNoRoot).hasValue(1000);

        clock.advance(NoRootAtTheDoor.WINDOW_MS);
        refuse("s-1000", NOBODYS);

        assertThat(door.said()).hasSize(3);
        assertThat(door.said().get(1)).contains("… and 999 more refused at esl 10.10.197.9:8021 in the 60 s window from 2026-10-24T11:20:00Z");
        assertThat(door.said().get(2)).contains("(session s-1000)").contains("no rule names this door — the request is refused");
        assertThat(counters.refusedNoRoot).hasValue(1001);

        for (int i = 1001; i < 1011; i++) refuse("s-" + i, NOBODYS);           // the second window: its own count, from 0
        clock.advance(NoRootAtTheDoor.WINDOW_MS);
        refuse("s-1011", NOBODYS);

        assertThat(door.said()).hasSize(5);
        assertThat(door.said().get(3)).contains("… and 10 more refused at esl 10.10.197.9:8021 in the 60 s window from 2026-10-24T11:21:00Z");
        assertThat(counters.refusedNoRoot).hasValue(1012);
    }

    @Test
    void aMillisecondBeforeTheWindowEnds_isCounted_notSaid_theFirstAfterIt_closesTheWindow() {
        refuse("s-1", NOBODYS);
        clock.advance(NoRootAtTheDoor.WINDOW_MS - 1);
        refuse("s-2", NOBODYS);
        assertThat(door.said()).hasSize(1);

        clock.advance(1);
        refuse("s-3", NOBODYS);
        assertThat(door.said()).hasSize(3);
        assertThat(door.said().get(1)).contains("… and 1 more refused at esl 10.10.197.9:8021");
    }

    @Test
    void eachDoorHasItsOwnWindow() {
        for (int i = 0; i < 500; i++) { refuse("a-" + i, NOBODYS); refuse("b-" + i, STRANGERS); }
        assertThat(door.said()).hasSize(2);

        clock.advance(NoRootAtTheDoor.WINDOW_MS);
        refuse("a-500", NOBODYS);
        refuse("b-500", STRANGERS);

        assertThat(door.said()).hasSize(6).anySatisfy(l -> assertThat(l).contains("… and 499 more refused at esl 10.10.197.9:8021"))
            .anySatisfy(l -> assertThat(l).contains("… and 499 more refused at listen 10.10.188.2:7070"));
        assertThat(counters.refusedNoRoot).hasValue(1002);
    }

    @Test
    void aWindowWithNoMoreRefusals_saysNoSummary() {
        refuse("s-1", NOBODYS);
        clock.advance(NoRootAtTheDoor.WINDOW_MS);
        refuse("s-2", NOBODYS);

        assertThat(door.said()).hasSize(2).noneSatisfy(l -> assertThat(l).contains("more refused"));
    }

    @Test
    void factsWithoutAnIngress_areSaidSo() {
        door.refused(new DoorFacts("s-1", 0, null), null);
        assertThat(door.said()).singleElement().asString().contains("no root at no ingress (session s-1)").contains("the facts carry no ingress");
    }

    @Test
    void beyondTheMostDoors_theFurtherDoorsShareOneWindow_memoryBounded() {
        int further = 100;
        for (int i = 0; i < NoRootAtTheDoor.MOST_DOORS + further; i++) refuse("s-" + i, Ingress.vlan("10.10.1.1", i));
        assertThat(door.doorsHeld()).isEqualTo(NoRootAtTheDoor.MOST_DOORS + 1);
        assertThat(door.said().get(door.said().size() - 1)).as("the first further door opens the shared window")
            .contains("vlan 10.10.1.1:" + NoRootAtTheDoor.MOST_DOORS);

        clock.advance(NoRootAtTheDoor.WINDOW_MS);
        refuse("s-last", Ingress.vlan("10.10.1.1", 5000));
        assertThat(door.said()).anySatisfy(l -> assertThat(l).contains("… and " + (further - 1) + " more refused at " + NoRootAtTheDoor.OTHER_DOORS));
        assertThat(door.doorsHeld()).isEqualTo(NoRootAtTheDoor.MOST_DOORS + 1);
        assertThat(counters.refusedNoRoot).hasValue(NoRootAtTheDoor.MOST_DOORS + further + 1);
    }
}
