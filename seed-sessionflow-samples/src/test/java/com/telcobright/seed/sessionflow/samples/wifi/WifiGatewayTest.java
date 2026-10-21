package com.telcobright.seed.sessionflow.samples.wifi;

import com.telcobright.seed.sessionflow.samples.bed.Bed;
import com.telcobright.seed.sessionflow.samples.bed.Script;
import com.telcobright.seed.sessionflow.samples.wifi.WifiGateway.WifiTimings;
import com.telcobright.statewalk.session.Verdict;
import com.telcobright.statewalk.session.events.BudgetExhausted;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/** The WifiGateway machine ALONE under the minimal handler (P1 §H item 2): its dictionary table through a fake gateway adapter. */
class WifiGatewayTest {

    static final WifiTimings LONG = new WifiTimings(5_000, 5_000, 3_600_000, 5_000);
    static final WifiTimings SHORT = new WifiTimings(5_000, 300, 300, 300);
    static final WifiGate GATE = new WifiGate("kamalapur-gate", "10.20.0.1", "p-5m");
    static final String MAC = "AA:BB:CC:00:11:22";

    private final List<Bed<?, ?>> open = new ArrayList<>();
    private final List<WifiGatewayContext> machines = new CopyOnWriteArrayList<>();

    @AfterEach
    void tearDown() { open.forEach(Bed::close); }

    private Bed<WifiOut, WifiFrame> bed(String name, WifiTimings t, Script script) {
        Bed<WifiOut, WifiFrame> b = new Bed<>(name, script, WifiGateway.spec(t, this::newMachineContext), new WifiChannel(), WifiFrames::decode);
        open.add(b);
        return b;
    }

    private WifiGatewayContext newMachineContext() { WifiGatewayContext c = new WifiGatewayContext(); machines.add(c); return c; }

    private static WifiFrame login() { return WifiFrame.login("10.20.0.1", 2104, MAC, "8801711000001", "dhaka-zone", "kamalapur"); }

    @Test
    void a_session_login_open_online_usage_logout_close_then_the_pooled_machines_are_clean() throws Exception {
        Bed<WifiOut, WifiFrame> b = bed("wifi-1", LONG, new Script().plan(GATE));
        b.frame("w-1", login());
        assertEquals("CONNECTING/AUTHORIZING", b.states("w-1"));
        assertEquals(List.of("gateway:open " + MAC + " policy p-5m on 10.20.0.1"), b.wire("w-1"));
        WifiFacts facts = (WifiFacts) b.script.last().facts;
        assertEquals("10.20.0.1:2104", facts.vlan(), "the ingress identity: the gateway and its S-VLAN");
        assertEquals("8801711000001", facts.msisdn());

        b.frame("w-1", WifiFrame.opened("p-5m"));
        assertEquals("ACTIVE/ONLINE", b.states("w-1"));
        assertEquals(MAC, b.script.last().answer.remoteParty());
        assertEquals("p-5m", b.script.last().answer.codec());
        b.frame("w-1", WifiFrame.usage(1_000_000, 60));
        b.frame("w-1", WifiFrame.usage(2_500_000, 120));
        assertEquals("ACTIVE/ONLINE", b.states("w-1"), "usage is a stay");
        assertEquals(2, machines.get(0).usageReports);
        Thread.sleep(20);

        b.frame("w-1", WifiFrame.left("LOGOUT"));
        assertEquals("ACTIVE/CLOSING", b.states("w-1"));
        assertEquals("gateway:close " + MAC + " LOGOUT", b.wire("w-1").get(1));
        b.frame("w-1", WifiFrame.closed(3_000_000, 150));
        assertEquals("-/-", b.states("w-1"));
        List<String> tail = b.script.from("onEnd:");
        assertEquals("onEnd:LOGOUT:SUBSCRIBER", tail.get(0));
        assertTrue(tail.get(1).startsWith("settle:SUCCEEDED:"), tail.toString());
        assertEquals("record:SUCCEEDED:LOGOUT", tail.get(2));
        assertEquals(3_000_000, machines.get(0).bytes, "the final usage from the accounting stop");
        assertEquals(List.of("IDLE", "INIT", "AUTHORIZING", "ONLINE", "CLOSING", "TERMINATED"), machines.get(0).road);
        b.assertBackInThePools("w-1");

        b.frame("w-2", login());
        assertEquals(List.of("IDLE", "INIT", "AUTHORIZING"), machines.get(1).road, "a fresh machine context");
        assertEquals(0, machines.get(1).usageReports);
    }

    @Test
    void a_refused_admission_denies_the_device_with_the_cause() throws Exception {
        Script s = new Script().plan(GATE);
        s.verdict = Verdict.reject("NO_TENANT");
        Bed<WifiOut, WifiFrame> b = bed("wifi-2", LONG, s);
        b.frame("w-x", login());
        assertEquals("-/-", b.states("w-x"));
        assertEquals(List.of("portal:deny " + MAC + " NO_TENANT"), b.wire("w-x"));
        assertEquals(List.of("onStart:w-x", "admit", "release", "record:FAILED:NO_TENANT"), b.script.calls);
        b.assertBackInThePools("w-x");
    }

    @Test
    void a_refused_gate_ends_the_machine_and_the_session_fails_without_a_re_route() throws Exception {
        Bed<WifiOut, WifiFrame> b = bed("wifi-3", LONG, new Script().plan(GATE, GATE).on("POLICY_UNKNOWN", Script.OnFailure.REROUTE));
        b.frame("w-r", login());
        b.frame("w-r", WifiFrame.refused("POLICY_UNKNOWN"));
        assertEquals("-/-", b.states("w-r"));
        assertFalse(b.script.calls.stream().anyMatch(c -> c.startsWith("retry:")), b.script.calls.toString());
        assertEquals(List.of("onFailed:POLICY_UNKNOWN:0", "release", "record:FAILED:POLICY_UNKNOWN"), b.script.from("onFailed:"));
        assertEquals("portal:deny " + MAC + " POLICY_UNKNOWN", b.wire("w-r").get(1));
    }

    @Test
    void the_window_running_out_closes_the_gate_and_the_session_is_settled() throws Exception {
        Bed<WifiOut, WifiFrame> b = bed("wifi-4", LONG, new Script().plan(GATE));
        b.frame("w-b", login());
        b.frame("w-b", WifiFrame.opened("p-5m"));
        b.reg.onInboundEvent("w-b", new BudgetExhausted(System.currentTimeMillis(), "minutes"));
        b.quiet("w-b");
        assertEquals("ACTIVE/CLOSING", b.states("w-b"));
        assertEquals("gateway:close " + MAC + " BALANCE_EXHAUSTED", b.wire("w-b").get(1));
        b.frame("w-b", WifiFrame.closed(10, 1));
        assertEquals("record:SUCCEEDED:BALANCE_EXHAUSTED", last(b));
        assertTrue(b.script.calls.contains("onEnd:BALANCE_EXHAUSTED:SUPERVISOR"), b.script.calls.toString());
    }

    @Test
    void the_gateways_silences_and_the_session_maximum_each_end_by_their_table_row() throws Exception {
        Bed<WifiOut, WifiFrame> b = bed("wifi-5", SHORT, new Script().plan(GATE));
        b.frame("w-g", login());                                                  // the gateway never answers the open
        b.awaitGone("w-g", 3_000);
        assertEquals("record:FAILED:GATEWAY_TIMEOUT", last(b));
        assertEquals("portal:deny " + MAC + " GATEWAY_TIMEOUT", b.wire("w-g").get(1));

        b.frame("w-m", login());                                                  // online past the maximum, and no accounting stop
        b.frame("w-m", WifiFrame.opened("p-5m"));
        b.awaitGone("w-m", 3_000);
        assertEquals("gateway:close " + MAC + " SESSION_TIMEOUT", b.wire("w-m").get(1));
        assertTrue(b.script.calls.contains("onEnd:SESSION_TIMEOUT:TIMER"), b.script.calls.toString());
        assertEquals("record:SUCCEEDED:SESSION_TIMEOUT", last(b));
        assertEquals(List.of("IDLE", "INIT", "AUTHORIZING", "ONLINE", "CLOSING", "TERMINATED"), machines.get(1).road);
    }

    private static String last(Bed<?, ?> b) { return b.script.calls.get(b.script.calls.size() - 1); }
}
