package com.telcobright.seed.sessionflow.samples.voice;

import com.telcobright.seed.sessionflow.samples.bed.Bed;
import com.telcobright.seed.sessionflow.samples.bed.Script;
import com.telcobright.seed.sessionflow.samples.voice.EslCall.EslTimings;
import com.telcobright.statewalk.session.Verdict;
import com.telcobright.statewalk.session.events.BudgetExhausted;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/** The EslCall machine ALONE under the minimal handler (P1 §H item 2): its dictionary table driven through a fake ESL connection. */
class EslCallTest {

    static final EslTimings LONG = new EslTimings(5_000, 8_000, 60_000, 3_600_000, 5_000);
    static final EslTimings SHORT = new EslTimings(5_000, 8_000, 300, 300, 300);
    static final EslTarget GP = new EslTarget("GP-trunk", 5, "10.10.199.20", "external", "8801711000002", "rs-out");
    static final EslTarget GP_BACKUP = new EslTarget("GP-backup", 6, "10.10.199.21", "external", "8801711000002", "rs-out");
    static final String A = "a-leg-1", B = "b-leg-1";

    private final List<Bed<?, ?>> open = new ArrayList<>();
    private final List<EslCallContext> machines = new CopyOnWriteArrayList<>();

    @AfterEach
    void tearDown() { open.forEach(Bed::close); }

    private Bed<EslOut, EslFrame> bed(String name, EslTimings t, Script script) {
        Bed<EslOut, EslFrame> b = new Bed<>(name, script, EslCall.spec(t, this::newMachineContext), new EslChannel(), EslFrames::decode);
        open.add(b);
        return b;
    }

    private EslCallContext newMachineContext() { EslCallContext c = new EslCallContext(); machines.add(c); return c; }

    private static EslFrame park(String uuid) {
        return EslFrame.park(uuid, "10.10.188.9:8021", "01711000001", "01711000002", "10.0.0.7", "1001");
    }

    private static List<String> transferTo(String uuid, EslTarget t) {
        return List.of("uuid_setvar " + uuid + " origination_caller_id_number 01711000001",
            "uuid_setvar " + uuid + " origination_caller_id_name 01711000001",
            "uuid_setvar " + uuid + " effective_caller_id_number 01711000001",
            "uuid_setvar " + uuid + " effective_caller_id_name 01711000001",
            "uuid_setvar " + uuid + " target_ip " + t.targetIp(),
            "uuid_setvar " + uuid + " sofia_profile " + t.sofiaProfile(),
            "uuid_transfer " + uuid + " " + t.destination() + " XML " + t.context());
    }

    @Test
    void an_answered_call_park_transfer_ring_bridge_answer_hangup_then_the_pooled_machines_are_clean() throws Exception {
        Bed<EslOut, EslFrame> b = bed("esl-1", LONG, new Script().plan(GP));

        b.frame(A, park(A));
        assertEquals("CONNECTING/TRYING", b.states(A));
        assertEquals(transferTo(A, GP), b.wire(A), "C10: the caller id ×4, target_ip, sofia_profile, uuid_transfer");
        EslFacts facts = (EslFacts) b.script.last().facts;
        assertEquals("10.10.188.9:8021", facts.esl(), "the ingress identity: the FreeSWITCH connection");
        assertEquals("10.0.0.7", facts.sourceIp());

        b.frame(A, EslFrame.progress(A));
        assertEquals("CONNECTING/RINGING", b.states(A));
        b.frame(A, EslFrame.bridge(A, B));
        assertTrue(b.script.calls.contains("onProgress:BRIDGED"), "C22: the other leg recorded " + b.script.calls);
        assertEquals(B, machines.get(0).otherUuid);
        b.frame(A, EslFrame.answer(A, B, "PCMA"));
        assertEquals("ACTIVE/ANSWERED", b.states(A));
        assertEquals("PCMA", b.script.last().answer.codec());
        Thread.sleep(30);

        b.frame(A, EslFrame.hangup(A, "NORMAL_CLEARING"));                       // the caller hangs up
        assertEquals("ACTIVE/DISCONNECTING", b.states(A));
        assertEquals("uuid_kill " + B + " NORMAL_CLEARING", b.wire(A).get(b.wire(A).size() - 1), "C15: the other leg is killed");
        b.frame(A, EslFrame.hangupComplete(A, "NORMAL_CLEARING"));
        assertEquals("-/-", b.states(A));
        List<String> tail = b.script.from("onEnd:");
        assertEquals("onEnd:NORMAL_CLEARING:CALLER", tail.get(0));
        assertTrue(Long.parseLong(tail.get(1).substring("settle:SUCCEEDED:".length())) >= 20, "the real talk time: " + tail);
        assertEquals("record:SUCCEEDED:NORMAL_CLEARING", tail.get(2));
        assertEquals(List.of("IDLE", "INIT", "TRYING", "RINGING", "ANSWERED", "DISCONNECTING", "TERMINATED"), machines.get(0).road);
        b.assertBackInThePools(A);

        b.frame("a-leg-2", park("a-leg-2"));                                      // the next call: pooled machines, fresh contexts
        assertEquals(List.of("IDLE", "INIT", "TRYING"), machines.get(1).road);
        assertNull(machines.get(1).otherUuid);
        b.frame("a-leg-2", EslFrame.answer("a-leg-2", "b-leg-2", "OPUS"));
        b.frame("a-leg-2", EslFrame.hangup("b-leg-2", "NORMAL_CLEARING"));       // the callee hangs up this time
        assertEquals("uuid_kill a-leg-2 NORMAL_CLEARING", b.wire("a-leg-2").get(b.wire("a-leg-2").size() - 1));
        b.frame("a-leg-2", EslFrame.hangupComplete("a-leg-2", "NORMAL_CLEARING"));
        assertTrue(b.script.calls.contains("onEnd:NORMAL_CLEARING:CALLEE"), b.script.calls.toString());
        b.assertBackInThePools("a-leg-2");
    }

    @Test
    void a_busy_hop_re_routes_with_a_second_transfer_on_the_same_machine_then_the_last_busy_rejects_the_caller_busy() throws Exception {
        Bed<EslOut, EslFrame> b = bed("esl-2", LONG, new Script().plan(GP, GP_BACKUP).on("USER_BUSY", Script.OnFailure.REROUTE));
        b.frame(A, park(A));
        b.frame(A, EslFrame.bridgeFailed(A, "USER_BUSY"));
        assertEquals("CONNECTING/TRYING", b.states(A), "SignalingFailed → retry → ROUTING → Connect(the next hop) → TRYING again");
        List<String> expected = new ArrayList<>(transferTo(A, GP));
        expected.addAll(transferTo(A, GP_BACKUP));
        assertEquals(expected, b.wire(A), "the second uuid_transfer went to the backup; nothing killed");
        assertEquals(2, b.script.last().attempts);

        b.frame(A, EslFrame.bridgeFailed(A, "USER_BUSY"));                        // the backup is busy too: no hop left
        assertEquals("-/-", b.states(A));
        assertEquals("uuid_kill " + A + " USER_BUSY", b.wire(A).get(b.wire(A).size() - 1), "the Reject reached the parked leg with the busy");
        assertEquals(2, b.script.count("retry:USER_BUSY"), "asked once per failed hop");
        assertEquals(List.of("onFailed:USER_BUSY:17", "release", "record:FAILED:USER_BUSY"), b.script.from("onFailed:"));
        assertEquals(List.of("IDLE", "INIT", "TRYING", "INIT", "TRYING", "INIT", "TERMINATED"), machines.get(0).road);
    }

    @Test
    void a_temporary_failure_retries_the_same_hop_then_the_callee_answers() throws Exception {
        Bed<EslOut, EslFrame> b = bed("esl-3", LONG, new Script().plan(GP, GP_BACKUP).on("NORMAL_TEMPORARY_FAILURE", Script.OnFailure.RETRY_SAME));
        b.frame(A, park(A));
        b.frame(A, EslFrame.bridgeFailed(A, "NORMAL_TEMPORARY_FAILURE"));
        List<String> expected = new ArrayList<>(transferTo(A, GP));
        expected.addAll(transferTo(A, GP));
        assertEquals(expected, b.wire(A), "RETRY_SAME: the same hop again");
        b.frame(A, EslFrame.answer(A, B, "PCMA"));
        assertEquals("ACTIVE/ANSWERED", b.states(A));
        assertEquals(2, machines.get(0).transfers);
    }

    @Test
    void a_refused_call_is_killed_with_the_causes_hangup_word_and_released() throws Exception {
        Script s = new Script().plan(GP);
        s.verdict = Verdict.reject("INSUFFICIENT_BALANCE");
        Bed<EslOut, EslFrame> b = bed("esl-4", LONG, s);
        b.frame(A, park(A));
        assertEquals("-/-", b.states(A));
        assertEquals(List.of("uuid_kill " + A + " CALL_REJECTED"), b.wire(A));
        assertEquals(List.of("onStart:" + A, "admit", "release", "record:FAILED:INSUFFICIENT_BALANCE"), b.script.calls);
        b.assertBackInThePools(A);
    }

    @Test
    void the_no_answer_deadline_ends_the_machine_so_no_re_route_is_asked() throws Exception {
        Bed<EslOut, EslFrame> b = bed("esl-5", SHORT, new Script().plan(GP, GP_BACKUP).on(EslCall.NO_ANSWER, Script.OnFailure.REROUTE));
        b.frame(A, park(A));
        b.frame(A, EslFrame.progress(A));
        b.awaitGone(A, 3_000);
        assertEquals("uuid_kill " + A + " NO_ANSWER", b.wire(A).get(b.wire(A).size() - 1));
        assertFalse(b.script.calls.stream().anyMatch(c -> c.startsWith("retry:")), "CN-2: the machine ended " + b.script.calls);
        assertEquals("record:FAILED:NO_ANSWER", last(b));
    }

    @Test
    void the_caller_hanging_up_while_it_rings_kills_the_other_leg_and_fails_the_call() throws Exception {
        Bed<EslOut, EslFrame> b = bed("esl-6", LONG, new Script().plan(GP));
        b.frame(A, park(A));
        b.frame(A, EslFrame.progress(A));
        b.frame(A, EslFrame.bridge(A, B));
        b.frame(A, EslFrame.hangup(A, "ORIGINATOR_CANCEL"));
        assertEquals("-/-", b.states(A));
        assertTrue(b.wire(A).contains("uuid_kill " + B + " ORIGINATOR_CANCEL"), b.wire(A).toString());
        assertEquals("record:FAILED:ORIGINATOR_CANCEL", last(b));
        assertTrue(b.script.calls.contains("release"));
    }

    @Test
    void the_budget_cut_and_the_maximum_duration_both_kill_the_legs_and_the_call_is_settled() throws Exception {
        Bed<EslOut, EslFrame> b = bed("esl-7", SHORT, new Script().plan(GP));
        b.frame(A, park(A));
        b.frame(A, EslFrame.answer(A, B, "PCMA"));
        b.reg.onInboundEvent(A, new BudgetExhausted(System.currentTimeMillis(), "talk time"));
        b.quiet(A);
        assertEquals("ACTIVE/DISCONNECTING", b.states(A), "C14: Stop(BALANCE_EXHAUSTED) → uuid_kill");
        assertEquals("uuid_kill " + A + " CALL_REJECTED", b.wire(A).get(b.wire(A).size() - 1));
        b.frame(A, EslFrame.hangupComplete(A, "CALL_REJECTED"));
        assertEquals("record:SUCCEEDED:BALANCE_EXHAUSTED", last(b));

        b.frame("a-max", park("a-max"));                                          // C23 on the wire: the machine's own maximum (300 ms)
        b.frame("a-max", EslFrame.answer("a-max", "b-max", "PCMA"));
        b.awaitGone("a-max", 3_000);
        assertTrue(b.wire("a-max").contains("uuid_kill a-max ALLOTTED_TIMEOUT"), b.wire("a-max").toString());
        assertTrue(b.script.calls.contains("onEnd:MAX_DURATION:TIMER"), b.script.calls.toString());
        assertEquals("record:SUCCEEDED:MAX_DURATION", last(b));
    }

    private static String last(Bed<?, ?> b) { return b.script.calls.get(b.script.calls.size() - 1); }
}
