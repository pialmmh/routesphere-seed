package com.telcobright.seed.sessionflow.samples.voice;

import com.telcobright.seed.sessionflow.samples.bed.Bed;
import com.telcobright.seed.sessionflow.samples.bed.CrashingStore;
import com.telcobright.seed.sessionflow.samples.bed.Script;
import com.telcobright.seed.sessionflow.samples.voice.EslCall.EslTimings;
import com.telcobright.statewalk.persistence.InMemoryPersistenceProvider;
import com.telcobright.statewalk.persistence.PersistenceProvider;
import com.telcobright.statewalk.persistence.SessionBundle;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The crash harness of the scenarios (P1 §H, §L.1), proven on the EslCall machine alone: ONE row per session holds both machines;
 * a node that dies mid-call leaves the call to the next node, which finishes it settled once; a node that dies between the final
 * state's settle and its final row leaves an older row, and the next node ends the session AGAIN — the second settle the base's money
 * calls must turn into a no-op (the flows' scenarios prove that on the ledger).
 */
class EslCallRestoreTest {

    static final String NAME = "esl-restore", A = "a-leg-r", B = "b-leg-r";
    static final EslTimings T = new EslTimings(5_000, 8_000, 60_000, 3_600_000, 300);
    static final EslTarget GP = new EslTarget("GP-trunk", 5, "10.10.199.20", "external", "8801711000002", "rs-out");

    private final Script script = new Script().plan(GP);                       // one script for both nodes: the calls accumulate
    private final List<Bed<?, ?>> open = new ArrayList<>();

    @AfterEach
    void tearDown() { for (int i = open.size() - 1; i >= 0; i--) open.get(i).close(); }

    private Bed<EslOut, EslFrame> node(PersistenceProvider store) {
        Bed<EslOut, EslFrame> b = new Bed<>(NAME, script, EslCall.spec(T, EslCallContext::new), new EslChannel(), EslFrames::decode, store);
        open.add(b);
        return b;
    }

    private static EslFrame park() { return EslFrame.park(A, "10.10.188.9:8021", "01711000001", "01711000002", "10.0.0.7", "1001"); }

    @Test
    void a_node_that_dies_mid_call_leaves_the_call_to_the_next_node_which_finishes_it_settled_once() throws Exception {
        InMemoryPersistenceProvider store = new InMemoryPersistenceProvider();
        CrashingStore nodeA = new CrashingStore(store);
        Bed<EslOut, EslFrame> a = node(nodeA);
        a.frame(A, park());
        a.frame(A, EslFrame.bridge(A, B));
        a.frame(A, EslFrame.answer(A, B, "PCMA"));
        assertEquals("ACTIVE/ANSWERED", a.states(A));
        assertEquals(1, store.loadAllForRegistry(NAME).size(), "ONE row for the session: both machines in it");
        nodeA.crash();

        Bed<EslOut, EslFrame> b = node(store);                                   // RESUME: restored before the channel opens
        assertTrue(b.reg.awaitIdle(5, SECONDS));
        assertEquals("ACTIVE/ANSWERED", b.states(A), "both machines restored in their states");
        EslCallContext restored = (EslCallContext) b.reg.childContextOf(A, EslCall.TYPE);
        assertEquals(B, restored.otherUuid, "the machine's context came back with it");
        assertEquals("PCMA", b.reg.supervisorContextOf(A).answer.codec());

        b.frame(A, EslFrame.hangup(A, "NORMAL_CLEARING"));
        assertEquals(List.of("uuid_kill " + B + " NORMAL_CLEARING"), b.wire(A), "the restored machine answers on node B's wire");
        b.frame(A, EslFrame.hangupComplete(A, "NORMAL_CLEARING"));
        assertEquals("-/-", b.states(A));
        assertEquals(1, script.count("settle:SUCCEEDED"), "settled once across both nodes: " + script.calls);
        assertEquals(1, script.count("record:"));
        assertEquals("SUCCEEDED", store.load(A, NAME).orElseThrow().currentState(), "the final row is kept");
    }

    /**
     * The row the store kept is the session's last landed picture: the supervisor ACTIVE, its signaling machine either still
     * DISCONNECTING (its TERMINATED and the supervisor's SUCCEEDED went out as one write, the one that never landed) or already
     * TERMINATED (written on its own just before). Either way the next node ends the session a second time: by the restored machine's
     * timer (SUCCEEDED, the same talk time), or — the machine being final, the supervisor is seated alone — by ACTIVE's own deadline
     * (FAILED, MAX_DURATION_REACHED, the downtime billed as talk time: §L.3). The base's money calls must make that second end a
     * no-op (§L.1); the scenarios of the flows prove it on the ledger and the CDR sink.
     */
    @Test
    void a_node_that_dies_between_the_settle_and_the_final_row_leaves_an_older_row_and_the_next_node_ends_the_session_again() throws Exception {
        script.timings = new com.telcobright.statewalk.session.SessionTimings(5, 2, 30, 1);    // ACTIVE's own deadline: 1 s
        InMemoryPersistenceProvider store = new InMemoryPersistenceProvider();
        CrashingStore nodeA = new CrashingStore(store).dieBeforeTheFinalRowOf(A);
        Bed<EslOut, EslFrame> a = node(nodeA);
        a.frame(A, park());
        a.frame(A, EslFrame.answer(A, B, "PCMA"));
        a.frame(A, EslFrame.hangup(A, "NORMAL_CLEARING"));
        assertEquals("ACTIVE/DISCONNECTING", a.states(A));
        a.frame(A, EslFrame.hangupComplete(A, "NORMAL_CLEARING"));             // SUCCEEDED: settle + record ran, then the final row …
        assertTrue(nodeA.isDead(), "… never landed: the node died writing it");
        assertEquals(1, script.count("settle:SUCCEEDED"));
        assertEquals("ACTIVE", store.load(A, NAME).orElseThrow().currentState(), "the store kept the older row");
        String machineInTheRow = SessionBundle.fromRow(store.load(A, NAME).orElseThrow()).children().get(0).currentState();

        Bed<EslOut, EslFrame> b = node(store);                                   // RESUME: the session is back in ACTIVE
        b.awaitGone(A, 4_000);
        assertEquals(2, script.count("settle:"), "ended AGAIN on node B (the machine in the row: " + machineInTheRow + "): " + script.calls);
        assertEquals(2, script.count("record:"));
        String secondEnd = script.calls.get(script.calls.size() - 1);
        if (EslCall.DISCONNECTING.equals(machineInTheRow)) {
            assertEquals("record:SUCCEEDED:NORMAL_CLEARING", secondEnd, "the restored machine's timer ended it with the end it had built");
        } else {
            assertEquals(EslCall.TERMINATED, machineInTheRow);
            assertEquals("record:FAILED:MAX_DURATION_REACHED", secondEnd, "the supervisor alone: only ACTIVE's own deadline ends it");
        }
        assertEquals(List.of("FAILED", "SUCCEEDED").contains(store.load(A, NAME).orElseThrow().currentState()), true, "node B's final row landed");
    }
}
