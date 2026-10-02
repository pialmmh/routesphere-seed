package com.telcobright.seed.callflow.samples;

import com.telcobright.rtc.domainmodel.PartnerType;
import com.telcobright.rtc.domainmodel.nonentity.Tenant;
import com.telcobright.seed.callflow.api.CallFlowTimings;
import com.telcobright.seed.callflow.api.CallMachine;
import com.telcobright.seed.callflow.dependencies.CallFlowKit;
import com.telcobright.seed.callflow.dependencies.CallFlowSettings;
import com.telcobright.seed.callflow.spi.TenantLookup;
import com.telcobright.seed.callflow.testkit.InMemoryLedger;
import com.telcobright.seed.callflow.testkit.RecordingCdrSink;
import com.telcobright.seed.callflow.testkit.TenantTreeBuilder;
import com.telcobright.statewalk.event.StatemachineEvent;
import com.telcobright.statewalk.session.SdrRecord;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

/**
 * The story every test of the base plays in: the operator {@code btcl} and its reseller {@code res_44}.
 *
 * <pre>
 *   btcl      partner 44 "res_44" (the reseller) · partner 9 "DirectCo" · partner 5 "BTCL Network" (the operator's own)
 *   └ res_44  partner 701 "Unilever" · 702 "Poorco" (no money) · 703 "OneLine" (one call at a time) · 704 "Closed" (deactivated)
 * </pre>
 *
 * A call of 701 has two tiers: {@code res_44} (701 pays the reseller) and {@code btcl} (the reseller, partner 44, pays the operator).
 */
public final class Scene {

    public static final ZoneId DHAKA = ZoneId.of("Asia/Dhaka");

    public final Tenant root = new TenantTreeBuilder()
        .root("btcl")
            .partner(44, "res_44", PartnerType.RESELLER)
            .partner(9, "DirectCo", PartnerType.CUSTOMER)
            .partner(5, "BTCL Network", PartnerType.CUSTOMER)
            .and()
        .tenant("res_44", "btcl")
            .partner(701, "Unilever", PartnerType.CUSTOMER)
            .partner(702, "Poorco", PartnerType.CUSTOMER)
            .partner(703, "OneLine", PartnerType.CUSTOMER, 1)
            .partner(704, "Closed", PartnerType.CUSTOMER)
            .deactivate(704)
            .and()
        .build();

    public final InMemoryLedger ledger = new InMemoryLedger()
        .fund("res_44", 701, "100.00")
        .fund("res_44", 703, "100.00")
        .fund("btcl", 44, "100.00")
        .fund("btcl", 9, "100.00");

    public final RecordingCdrSink cdrs = new RecordingCdrSink();
    public final List<SdrRecord> sessionRecords = new CopyOnWriteArrayList<>();
    private Clock clock = Clock.system(DHAKA);

    /** The clock of every flow built from now on: a test of a deadline hands in a clock it moves by hand. */
    public Scene withClock(Clock clock) {
        this.clock = clock;
        return this;
    }

    public CallFlowKit kit(CallFlowSettings settings) {
        return CallFlowKit.builder()
            .tenants(TenantLookup.of(root))
            .ledger(ledger)
            .cdrSink(cdrs)
            .sdrSink(sessionRecords::add)
            .clock(clock)
            .zone(DHAKA)
            .settings(settings)
            .build();
    }

    /** Short deadlines for a test: 2 s per state, the answered call at most {@code activeMaxSec}, the killer at {@code globalSec}. */
    public static CallFlowSettings settings(int pool, long activeMaxSec, long globalSec) {
        return new CallFlowSettings(pool, 2, globalSec, new CallFlowTimings(2, 2, 2, 3, activeMaxSec, 2), 0, 0, false);
    }

    public static CallFlowSettings settings(int pool) { return settings(pool, 20, 60); }

    // ── the three sample applications, wired on this story ──────────────────

    public VoiceFlow voice(CallFlowSettings settings) {
        return new VoiceFlow(kit(settings),
            Map.of("10.0.0.7", 701, "10.0.0.3", 703, "10.0.0.9", 9, "10.0.0.2", 702, "10.0.0.4", 704),
            Map.of("res_44#701", new BigDecimal("0.60"), "res_44#702", new BigDecimal("0.60"), "res_44#703", new BigDecimal("0.60"),
                "res_44#704", new BigDecimal("0.60"), "btcl#44", new BigDecimal("0.40"), "btcl#9", new BigDecimal("0.50")),
            List.of(new VoiceFlow.Route("017", "GP-trunk", 5), new VoiceFlow.Route("018", "Robi-trunk", 5)));
    }

    public SmsFlow sms(CallFlowSettings settings) {
        return new SmsFlow(kit(settings),
            Map.of("unilever", 701, "poorco", 702),
            Map.of("res_44#701", new BigDecimal("0.30"), "res_44#702", new BigDecimal("0.30"), "btcl#44", new BigDecimal("0.20")),
            Map.of("res_44#701", "SMS_ea"),
            List.of("smsc-1", "smsc-2"));
    }

    public AdFlow ad(CallFlowSettings settings, boolean unshownViewIsCharged) {
        return new AdFlow(kit(settings),
            Map.of("dhaka-zone", "R100", "house-zone", "R900", "poor-zone", "R200", "paying-zone", "R300"),
            Map.of(
                "R300", AdFlow.campaigns(new AdFlow.Campaign(10, "camp-10", 702, false), new AdFlow.Campaign(11, "camp-11", 701, false)),
                "R100", AdFlow.campaigns(new AdFlow.Campaign(10, "camp-10", 702, false), new AdFlow.Campaign(11, "camp-11", 701, false),
                    new AdFlow.Campaign(99, "house", 701, true)),
                "R200", AdFlow.campaigns(new AdFlow.Campaign(10, "camp-10", 702, false)),
                "R900", AdFlow.campaigns(new AdFlow.Campaign(98, "house-bare", null, true))),
            Map.of("res_44#701", new BigDecimal("0.50"), "res_44#702", new BigDecimal("0.50"), "btcl#44", new BigDecimal("0.40")),
            unshownViewIsCharged, 5);
    }

    // ── contexts ────────────────────────────────────────────────────────────

    public static VoiceFlow.Call call(String id, String sourceIp, String dialed) {
        VoiceFlow.Call call = new VoiceFlow.Call();
        call.sessionKey = id;
        call.tenantName = "btcl";
        call.sourceIp = sourceIp;
        call.caller = "01711000001";
        call.dialed = dialed;
        return call;
    }

    public static SmsFlow.Message message(String id, String user, String text) {
        SmsFlow.Message sms = new SmsFlow.Message();
        sms.sessionKey = id;
        sms.tenantName = "btcl";
        sms.user = user;
        sms.sender = "BRAND";
        sms.receiver = "8801711000002";
        sms.text = text;
        return sms;
    }

    public static AdFlow.View view(String id, String zone) {
        AdFlow.View view = new AdFlow.View();
        view.sessionKey = id;
        view.tenantName = "res_44";
        view.zone = zone;
        view.gateway = "10.20.0.1";
        view.mac = "AA:BB:CC:00:11:22";
        view.app = "captive-portal";
        return view;
    }

    // ── small helpers ───────────────────────────────────────────────────────

    /** A machine handle for the tests that run the flow's steps with no machine: nothing may be asked of it. */
    public static final CallMachine NO_MACHINE = new CallMachine() {
        @Override public String callId() { return "no-machine"; }
        @Override public void publish(StatemachineEvent event) { }
        @Override public void spawnChild(String childType, Object childContext) { }
        @Override public void retireChildren() { }
    };

    public static void await(String what, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 15_000;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) throw new AssertionError("timed out waiting for: " + what);
            Thread.sleep(20);
        }
    }
}
