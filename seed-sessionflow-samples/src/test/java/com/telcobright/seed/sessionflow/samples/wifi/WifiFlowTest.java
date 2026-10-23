package com.telcobright.seed.sessionflow.samples.wifi;

import com.telcobright.seed.sessionflow.dependencies.RoutesphereSettings;
import com.telcobright.seed.sessionflow.samples.bed.CrashingStore;
import com.telcobright.statewalk.persistence.InMemoryPersistenceProvider;
import com.telcobright.statewalk.persistence.PersistenceProvider;
import com.telcobright.seed.sessionflow.samples.scene.FlowBed;
import com.telcobright.seed.sessionflow.samples.scene.World;
import com.telcobright.seed.sessionflow.samples.wifi.WifiGateway.WifiTimings;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** The WiFi sample on the base (P1 §H): the subscriber as a partner [assumes D5], the windows, the SDR. */
class WifiFlowTest {

    static final WifiTimings GATEWAY = new WifiTimings(5_000, 5_000, 3_600_000, 5_000);
    static final String MAC = "AA:BB:CC:00:11:22";

    static final Map<String, Integer> SUBSCRIBERS = Map.of(
        "btcl|8801700000009", 9, "btcl|8801700000701", 701, "btcl|8801700000801", 801, "ccl|8801700000701", 701);
    static final Map<String, WifiGate> GATES = Map.of(
        "btcl|kamalapur", new WifiGate("kamalapur-gate", World.GW_BTCL, "p-5m"),
        "ccl|airport", new WifiGate("airport-gate", World.GW_CCL, "p-10m"));

    private final List<AutoCloseable> open = new ArrayList<>();
    private final World w = new World();

    @AfterEach
    void tearDown() throws Exception { for (int i = open.size() - 1; i >= 0; i--) open.get(i).close(); }

    private FlowBed<WifiContext, WifiOut, WifiFrame> bed(RoutesphereSettings s) { return bed(s, null); }

    private FlowBed<WifiContext, WifiOut, WifiFrame> bed(RoutesphereSettings s, PersistenceProvider store) {
        WifiFlow flow = new WifiFlow(w.kit(s), SUBSCRIBERS, World.RATES, GATES);
        FlowBed<WifiContext, WifiOut, WifiFrame> b = new FlowBed<>(c -> new WifiSupervisor(flow, c), WifiContext::new, WifiGateway.spec(GATEWAY, WifiGatewayContext::new),
            new WifiChannel(), WifiFrames::decode, store);
        open.add(b);
        return b;
    }

    static WifiFrame btclLogin(String msisdn) { return WifiFrame.login(World.GW_BTCL, World.SVLAN_BTCL, MAC, msisdn, "dhaka", "kamalapur"); }

    static void online(FlowBed<WifiContext, WifiOut, WifiFrame> b, String id, WifiFrame login) throws Exception {
        b.frame(id, login);
        assertEquals("CONNECTING/AUTHORIZING", b.states(id), b.wire(id).toString());
        b.frame(id, WifiFrame.opened("p-5m"));
        b.frame(id, WifiFrame.usage(1_000, 10));
        b.frame(id, WifiFrame.left("LOGOUT"));
        b.frame(id, WifiFrame.closed(2_000, 20));
        assertEquals("-/-", b.states(id));
    }

    private List<String> tiersOf(String id) {
        return w.cdrs.of(id).get(0).tiers().stream().map(t -> t.tenant + "/" + t.inPartnerId).toList();
    }

    @Test
    void a_session_on_one_two_and_three_tiers_pays_its_started_minute_at_each_tier_and_writes_its_sdr() throws Exception {
        FlowBed<WifiContext, WifiOut, WifiFrame> b = bed(World.settings());
        online(b, "w-1t", btclLogin("8801700000009"));
        online(b, "w-2t", btclLogin("8801700000701"));
        online(b, "w-3t", btclLogin("8801700000801"));
        assertEquals(List.of("btcl/9"), tiersOf("w-1t"));
        assertEquals(List.of("res_44/701", "btcl/44"), tiersOf("w-2t"));
        assertEquals(List.of("res_44_7/801", "res_44/7", "btcl/44"), tiersOf("w-3t"));
        assertEquals(new BigDecimal("99.40"), w.balance("res_44", 701));
        assertEquals(new BigDecimal("99.00"), w.balance("res_44_7", 801));
        WifiSdr sdr = (WifiSdr) w.sdrs.get(2);
        assertEquals("w-3t", sdr.sessionId());
        assertEquals("8801700000801", sdr.msisdn());
        assertEquals("LOGOUT", sdr.endCause());
        assertEquals("SUCCEEDED", sdr.outcome());
        assertEquals(0, w.ledger.openReserves());
        b.assertBackInThePools("w-3t");
    }

    @Test
    void two_roots_each_gateway_and_s_vlan_names_its_own_root() throws Exception {
        FlowBed<WifiContext, WifiOut, WifiFrame> b = bed(World.settings());
        online(b, "w-btcl", btclLogin("8801700000701"));
        online(b, "w-ccl", WifiFrame.login(World.GW_CCL, World.SVLAN_CCL, MAC, "8801700000701", "dhaka", "airport"));
        assertEquals(List.of("ccl_44/701", "ccl/44"), tiersOf("w-ccl"));
        assertEquals("gateway:open " + MAC + " policy p-10m on " + World.GW_CCL, b.wire("w-ccl").get(0), "ccl's own gate");
        assertEquals(new BigDecimal("99.30"), w.balance("ccl_44", 701));
        assertEquals(new BigDecimal("99.40"), w.balance("res_44", 701));
    }

    @Test
    void the_budget_child_renews_a_window_per_period_and_when_the_subscriber_can_pay_no_more_the_gate_is_closed_and_settled() throws Exception {
        w.ledger.fund("res_44", 701, "1.80");                                      // three windows at 0.60
        FlowBed<WifiContext, WifiOut, WifiFrame> b = bed(World.renewing(1));
        String id = "w-bud";
        b.frame(id, btclLogin("8801700000701"));
        b.frame(id, WifiFrame.opened("p-5m"));
        long until = System.currentTimeMillis() + 8_000;
        while (!b.wire(id).contains("gateway:close " + MAC + " BALANCE_EXHAUSTED") && System.currentTimeMillis() < until) Thread.sleep(20);
        assertEquals("ACTIVE/CLOSING", b.states(id), b.wire(id).toString());
        b.frame(id, WifiFrame.closed(5_000, 3));
        assertEquals("BALANCE_EXHAUSTED", b.session(id).endCause);
        assertEquals(new BigDecimal("1.20"), w.balance("res_44", 701), "one started minute charged, the renewed windows back");
        assertEquals(0, w.ledger.openReserves());
        b.assertBackInThePools(id);
    }

    @Test
    void no_balance_at_the_middle_tier_of_three_gives_the_subscribers_window_back() throws Exception {
        w.ledger.fund("res_44", 7, "0.00");
        FlowBed<WifiContext, WifiOut, WifiFrame> b = bed(World.settings());
        b.frame("w-mid", btclLogin("8801700000801"));
        assertEquals(List.of("portal:deny " + MAC + " INSUFFICIENT_BALANCE"), b.wire("w-mid"));
        assertEquals(new BigDecimal("100.00"), w.balance("res_44_7", 801), "the leaf's window came back");
        assertEquals(1, w.ledger.journal().stream().filter(e -> e.verb().equals("release") && e.reference().startsWith("w-mid")).count());
        assertEquals(0, w.ledger.openReserves());
    }

    @Test
    void a_session_restored_online_on_the_next_node_closes_there_and_each_tier_pays_once() throws Exception {
        InMemoryPersistenceProvider store = new InMemoryPersistenceProvider();
        CrashingStore nodeA = new CrashingStore(store);
        FlowBed<WifiContext, WifiOut, WifiFrame> a = bed(World.settings(), nodeA);
        a.frame("w-r", btclLogin("8801700000701"));
        a.frame("w-r", WifiFrame.opened("p-5m"));
        assertEquals("ACTIVE/ONLINE", a.states("w-r"));
        nodeA.crash();

        FlowBed<WifiContext, WifiOut, WifiFrame> b = bed(World.settings(), store);
        assertEquals("ACTIVE/ONLINE", b.states("w-r"));
        assertEquals("8801700000701", b.registry().supervisorContextOf("w-r").msisdn);
        b.frame("w-r", WifiFrame.left("LOGOUT"));
        b.frame("w-r", WifiFrame.closed(10, 1));
        assertEquals("-/-", b.states("w-r"));
        assertEquals(new BigDecimal("99.40"), w.balance("res_44", 701));
        assertEquals(2, w.ledger.journal().stream().filter(e -> e.verb().equals("settle") && e.reference().startsWith("w-r")).count());
        assertEquals("w-r", ((WifiSdr) w.sdrs.get(0)).sessionId(), "the SDR written on node B");
    }

    @Test
    void an_unknown_subscriber_and_a_subscriber_without_money_are_denied_at_the_portal() throws Exception {
        w.ledger.fund("res_44", 701, "0.00");
        FlowBed<WifiContext, WifiOut, WifiFrame> b = bed(World.settings());
        b.frame("w-nf", btclLogin("8801799999999"));
        assertEquals(List.of("portal:deny " + MAC + " PARTNER_NOT_FOUND"), b.wire("w-nf"));
        b.frame("w-nb", btclLogin("8801700000701"));
        assertEquals(List.of("portal:deny " + MAC + " INSUFFICIENT_BALANCE"), b.wire("w-nb"));
        assertEquals(0, w.ledger.openReserves());
    }
}
