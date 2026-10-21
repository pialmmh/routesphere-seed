package com.telcobright.seed.sessionflow.base;

import com.telcobright.rtc.domainmodel.PartnerType;
import com.telcobright.rtc.domainmodel.nonentity.Tenant;
import com.telcobright.seed.sessionflow.api.IdentificationRule;
import com.telcobright.seed.sessionflow.api.Ingress;
import com.telcobright.seed.sessionflow.api.RoutesphereEngine;
import com.telcobright.seed.sessionflow.base.LabEvents.Answer;
import com.telcobright.seed.sessionflow.base.LabEvents.Dial;
import com.telcobright.seed.sessionflow.base.LabEvents.Fail;
import com.telcobright.seed.sessionflow.base.LabEvents.Hangup;
import com.telcobright.seed.sessionflow.dependencies.RoutesphereKit;
import com.telcobright.seed.sessionflow.dependencies.RoutesphereSettings;
import com.telcobright.seed.sessionflow.spi.TenantLookup;
import com.telcobright.seed.sessionflow.testkit.InMemoryLedger;
import com.telcobright.seed.sessionflow.testkit.RecordingCdrSink;
import com.telcobright.seed.sessionflow.testkit.TenantTreeBuilder;
import com.telcobright.statewalk.channel.Delivery;
import com.telcobright.statewalk.channel.TestChannel;
import com.telcobright.statewalk.event.StatemachineEvent;
import com.telcobright.statewalk.session.SessionTimings;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * The base's lab: TWO roots whose partner ids overlap (btcl: btcl > res_44 > res_44_7; tele2: tele2 > res_9), the in-memory ledger, the
 * recording CDR sink, the lab flow on statewalk 4.0's registry with the lab's signaling machine, and the wire. One per test.
 *
 * <pre>
 *   btcl      partners 1 (client), 44 (reseller → res_44)            tele2    partners 1 (client), 9 (reseller → res_9)
 *   res_44    partners 5 (client), 7 (reseller → res_44_7)           res_9    partners 3 (client)
 *   res_44_7  partners 3 (client)
 * </pre>
 */
public final class Lab implements AutoCloseable {

    public static final Ingress BTCL_DOOR = Ingress.esl("10.10.199.9", 8021);
    public static final Ingress TELE2_DOOR = Ingress.esl("10.10.198.9", 8021);
    public static final Ingress NOBODYS_DOOR = Ingress.esl("10.10.197.9", 8021);
    public static final List<IdentificationRule> RULES = List.of(
        new IdentificationRule("esl", "10.10.199.9:8021", "btcl"), new IdentificationRule("esl", "10.10.198.9:8021", "tele2"));

    public final Tenant btcl = btclTree();
    public final Tenant tele2 = tele2Tree();
    public final InMemoryLedger ledger = new InMemoryLedger();
    public final RecordingCdrSink cdr = new RecordingCdrSink();
    public final List<LabContext> contexts = new CopyOnWriteArrayList<>();
    public final List<Object> sdrs = new CopyOnWriteArrayList<>();
    public final TestChannel<String, StatemachineEvent> wire = new TestChannel<>("lab");
    public final RoutesphereKit kit;
    public final LabFlow flow;
    public final RoutesphereEngine<LabContext> engine;

    public Lab() { this(settings(0)); }

    public Lab(RoutesphereSettings settings) { this(settings, kit -> new LabFlow(kit, Map.of(), hops("carrier-a", "carrier-b", "carrier-c"), Set.of())); }

    public Lab(RoutesphereSettings settings, Function<RoutesphereKit, LabFlow> flows) {
        this(settings, flows, null);
    }

    public Lab(RoutesphereSettings settings, Function<RoutesphereKit, LabFlow> flows, TenantLookup trees) {
        fundEveryone();
        this.kit = RoutesphereKit.builder()
            .tenants(trees != null ? trees : TenantLookup.of(btcl, tele2))
            .identification(RULES)
            .ledger(ledger).cdr(cdr).sdr((id, sdr) -> sdrs.add(sdr))
            .zone(ZoneId.of("Asia/Dhaka")).settings(settings)
            .build();
        this.flow = flows.apply(kit);
        this.engine = RoutesphereEngine.of(flow, this::newContext).signaling(LabCall.spec()).channel(wire, Lab::decode).start();
    }

    /** The lab's settings: a pool of 8, INIT 2 s, ADMITTING 2 s, CONNECTING 5 s, ACTIVE 60 s, the cap 120 s, the given renewal period. */
    public static RoutesphereSettings settings(long reservePeriodSec) {
        return new RoutesphereSettings(8, 2, 120, new SessionTimings(2, 2, 5, 60), reservePeriodSec, 0, 0, false, 200);
    }

    public static List<LabHop> hops(String... carriers) {
        List<LabHop> hops = new ArrayList<>();
        for (String c : carriers) hops.add(new LabHop(c));
        return hops;
    }

    private LabContext newContext() {
        LabContext c = new LabContext();
        contexts.add(c);
        return c;
    }

    static Delivery decode(String sessionId, StatemachineEvent frame) { return Delivery.to(LabCall.TYPE, frame); }

    // ── the wire ────────────────────────────────────────────────────────────

    public void dial(String id, Ingress door, int partnerId) { frame(id, new Dial(new LabFacts(id, System.currentTimeMillis(), door, partnerId, "8801711000000"))); }

    public void answer(String id) { frame(id, new Answer(System.currentTimeMillis())); }

    public void fail(String id, String cause, int code) { frame(id, new Fail(cause, code)); }

    public void hangup(String id) { frame(id, new Hangup(System.currentTimeMillis())); }

    public void frame(String id, StatemachineEvent e) {
        try {
            wire.inject(id, e).toCompletableFuture().get(5, TimeUnit.SECONDS);
            engine.awaitSettled(id, 5, TimeUnit.SECONDS);
            engine.registry().awaitIdle(5, TimeUnit.SECONDS);
        } catch (Exception ex) {
            throw new IllegalStateException("the frame " + e + " for " + id + " was not taken: " + ex, ex);
        }
    }

    /** The context of the session with this id (the last one created for it). */
    public LabContext ctx(String id) {
        for (int i = contexts.size() - 1; i >= 0; i--) if (id.equals(contexts.get(i).sessionId())) return contexts.get(i);
        throw new AssertionError("no session " + id);
    }

    /** What the wire was told for a session, in order. */
    public List<String> sent(String id) {
        return wire.sends.stream().filter(s -> s.requestId().equals(id)).map(s -> String.valueOf(s.command())).toList();
    }

    @Override public void close() { engine.close(); }

    // ── the scene ───────────────────────────────────────────────────────────

    private void fundEveryone() {
        Map<String, int[]> partners = new HashMap<>();
        partners.put("btcl", new int[] {1, 8, 44});
        partners.put("res_44", new int[] {5, 7});
        partners.put("res_44_7", new int[] {3});
        partners.put("tele2", new int[] {1, 9});
        partners.put("res_9", new int[] {3});
        partners.forEach((tenant, ids) -> { for (int id : ids) ledger.fund(tenant, id, "100.00"); });
    }

    static Tenant btclTree() {
        TenantTreeBuilder b = new TenantTreeBuilder();
        b.root("btcl").partner(1, "btcl-client", PartnerType.CUSTOMER).partner(44, "res_44", PartnerType.RESELLER);
        b.tenant("res_44", "btcl").partner(5, "res44-client", PartnerType.CUSTOMER).partner(7, "res_44_7", PartnerType.RESELLER);
        b.tenant("res_44_7", "res_44").partner(3, "res447-client", PartnerType.CUSTOMER);
        return b.build();
    }

    static Tenant tele2Tree() {
        TenantTreeBuilder b = new TenantTreeBuilder();
        b.root("tele2").partner(1, "tele2-client", PartnerType.CUSTOMER).partner(9, "res_9", PartnerType.RESELLER);
        b.tenant("res_9", "tele2").partner(3, "res9-client", PartnerType.CUSTOMER);
        return b.build();
    }
}
