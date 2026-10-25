package com.telcobright.seed.sessionflow.samples.scene;

import com.telcobright.rtc.domainmodel.PartnerType;
import com.telcobright.rtc.domainmodel.nonentity.Tenant;
import com.telcobright.seed.campaign.api.CampaignTask;
import com.telcobright.seed.sessionflow.api.IdentificationRule;
import com.telcobright.seed.sessionflow.dependencies.RoutesphereKit;
import com.telcobright.seed.sessionflow.dependencies.RoutesphereSettings;
import com.telcobright.seed.sessionflow.spi.CdrSink;
import com.telcobright.seed.sessionflow.spi.LedgerPort;
import com.telcobright.seed.sessionflow.spi.TenantLookup;
import com.telcobright.seed.sessionflow.testkit.InMemoryLedger;
import com.telcobright.seed.sessionflow.testkit.RecordingCdrSink;
import com.telcobright.seed.sessionflow.testkit.TenantTreeBuilder;
import com.telcobright.statewalk.session.SessionTimings;

import java.math.BigDecimal;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The world every sample plays in (P1 §H): TWO operators served by one process, their partner ids overlapping, and reseller tiers one,
 * two and three deep.
 *
 * <pre>
 *   btcl          9 DirectCo · 44 the reseller ─► res_44
 *   └ res_44      701 Unilever · 702 Poorco (no money) · 703 OneLine (one at a time) · 704 Closed (deactivated) · 7 the sub-reseller ─► res_44_7
 *     └ res_44_7  801 Walton
 *   ccl           9 CCL Direct · 44 its reseller ─► ccl_44
 *   └ ccl_44      701 CCL Unilever                  (partner 9, 44 and 701 exist in BOTH trees: a lookup that crossed roots would show)
 * </pre>
 *
 * A session of 9 has one tier (btcl); of 701 two (res_44, btcl); of 801 three (res_44_7, res_44, btcl). One rate table for every lane
 * by {@code tier#partner}: the unit is the lane's own (a minute of talk, a view, a segment, a minute of WiFi).
 *
 * <p>P1-O · each partner is found THROUGH ITS ROUTE, as the switch serves them (the lanes keep no private map): the voice call's source
 * addresses ({@code route.field5} — 10.0.0.9 → 9, 10.0.0.7 → 701, 10.0.0.2/.3/.4 → 702/703/704, 10.0.0.8 → 801; 10.0.0.17:7023 → 701 only
 * at that port; 10.0.0.5 → 705 once NewCo exists; in ccl 10.0.0.9 → 9 and 10.0.0.7 → 701, the same addresses in another tree), Walton's
 * SIP account {@code sip-walton}, and the SMS's route credentials ({@code direct}, {@code unilever} hashed; {@code walton} a legacy
 * plaintext row) — {@link #SMS_PASSWORDS}.
 */
public final class World {

    public static final ZoneId DHAKA = ZoneId.of("Asia/Dhaka");
    public static final String BTCL = "btcl", CCL = "ccl";

    /** The ingress identities of the two roots, per lane — the identification rules below map each to its root. */
    public static final String AD_BTCL = "10.10.188.5:7443", AD_CCL = "10.10.177.5:7443";
    public static final String ESL_BTCL = "10.10.188.9:8021", ESL_CCL = "10.10.177.9:8021";
    public static final String GW_BTCL = "10.20.0.1", GW_CCL = "10.30.0.1";
    public static final int SVLAN_BTCL = 2104, SVLAN_CCL = 2200;
    public static final String SMS_HTTP_BTCL = "10.10.188.7:7480", SMS_TOPIC_CCL = "sms_in_ccl";
    /** A door no rule names. */
    public static final String NOBODYS = "10.10.199.99:9999";

    public static final Map<String, BigDecimal> RATES = Map.ofEntries(
        Map.entry("btcl#9", new BigDecimal("0.50")), Map.entry("btcl#44", new BigDecimal("0.40")),
        Map.entry("res_44#701", new BigDecimal("0.60")), Map.entry("res_44#702", new BigDecimal("0.60")),
        Map.entry("res_44#703", new BigDecimal("0.60")), Map.entry("res_44#704", new BigDecimal("0.60")),
        Map.entry("res_44#7", new BigDecimal("0.80")), Map.entry("res_44_7#801", new BigDecimal("1.00")),
        Map.entry("ccl#9", new BigDecimal("0.55")), Map.entry("ccl#44", new BigDecimal("0.45")),
        Map.entry("ccl_44#701", new BigDecimal("0.70")));

    /** The SMS route credentials of btcl's three users (test fixtures): the password each one's route keeps. */
    public static final Map<String, String> SMS_PASSWORDS = Map.of("direct", "direct-pw", "unilever", "unilever-pw", "walton", "walton-pw");

    public final Tenant btcl = btclTree(false);
    public final Tenant ccl = cclTree();

    public final InMemoryLedger ledger = new InMemoryLedger()
        .fund("btcl", 9, "100.00").fund("btcl", 44, "100.00")
        .fund("res_44", 701, "100.00").fund("res_44", 703, "100.00").fund("res_44", 704, "100.00").fund("res_44", 7, "100.00")
        .fund("res_44_7", 801, "100.00")
        .fund("ccl", 9, "100.00").fund("ccl", 44, "100.00").fund("ccl_44", 701, "100.00");
    public final RecordingCdrSink cdrs = new RecordingCdrSink();
    public final List<Object> sdrs = new CopyOnWriteArrayList<>();
    public final List<CampaignTask> closedTasks = new CopyOnWriteArrayList<>();

    /** btcl's tree as prime-context serves it; {@code withNewCo} = after a change: partner 705 "NewCo" added in res_44. */
    public static Tenant btclTree(boolean withNewCo) {
        TenantTreeBuilder b = new TenantTreeBuilder();
        TenantTreeBuilder.TenantSpec res44 = b.root("btcl").partner(9, "DirectCo", PartnerType.CUSTOMER)
                .route(9, "10.0.0.9").routeCredentials(9, "DirectCo-sms", "direct", SMS_PASSWORDS.get("direct"))
            .reseller(44, "Res44")
                .partner(701, "Unilever", PartnerType.CUSTOMER)
                .partner(702, "Poorco", PartnerType.CUSTOMER)
                .partner(703, "OneLine", PartnerType.CUSTOMER, 1)
                .partner(704, "Closed", PartnerType.CUSTOMER).deactivate(704)
                .route(701, "10.0.0.7", "10.0.0.17:7023").route(702, "10.0.0.2").route(703, "10.0.0.3").route(704, "10.0.0.4")
                .routeCredentials(701, "Unilever-sms", "unilever", SMS_PASSWORDS.get("unilever"));
        if (withNewCo) res44.partner(705, "NewCo", PartnerType.CUSTOMER).route(705, "10.0.0.5");
        res44.reseller(7, "Sub7").partner(801, "Walton", PartnerType.CUSTOMER)
            .route(801, "10.0.0.8").sipAccount(801, "sip-walton").legacyRouteCredentials(801, "Walton-sms", "walton", SMS_PASSWORDS.get("walton"));
        return b.build();
    }

    public static Tenant cclTree() {
        TenantTreeBuilder b = new TenantTreeBuilder();
        b.root("ccl").partner(9, "CCL Direct", PartnerType.CUSTOMER).partner(44, "CCL Res", PartnerType.RESELLER).route(9, "10.0.0.9")
            .and().tenant("ccl_44", "ccl").partner(701, "CCL Unilever", PartnerType.CUSTOMER).route(701, "10.0.0.7");
        return b.build();
    }

    /** The bootstrap's identification rules: every lane's door of each root (operator model §5). */
    public static List<IdentificationRule> rules() {
        return List.of(
            new IdentificationRule("listen", AD_BTCL, BTCL), new IdentificationRule("listen", AD_CCL, CCL),
            new IdentificationRule("esl", ESL_BTCL, BTCL), new IdentificationRule("esl", ESL_CCL, CCL),
            new IdentificationRule("vlan", GW_BTCL + ":" + SVLAN_BTCL, BTCL), new IdentificationRule("vlan", GW_CCL + ":" + SVLAN_CCL, CCL),
            new IdentificationRule("listen", SMS_HTTP_BTCL, BTCL), new IdentificationRule("topic", SMS_TOPIC_CCL, CCL));
    }

    /** The kit of a flow in this world: both roots served, the rules checked against them, the books and the sinks above. */
    public RoutesphereKit kit(RoutesphereSettings settings) { return kit(settings, TenantLookup.of(btcl, ccl)); }

    /** The same on another source of the trees (seed-tenant's TenantTrees: one snapshot per root, reloaded by its doorbell). */
    public RoutesphereKit kit(RoutesphereSettings settings, TenantLookup tenants) { return kit(settings, tenants, ledger, cdrs); }

    /** The kit of ONE node whose handles on the world die with it (P1 T10: a {@code CrashingLedger}, a {@code CrashingCdrSink}). */
    public RoutesphereKit kit(RoutesphereSettings settings, LedgerPort nodesLedger, CdrSink nodesCdrRoad) {
        return kit(settings, TenantLookup.of(btcl, ccl), nodesLedger, nodesCdrRoad);
    }

    private RoutesphereKit kit(RoutesphereSettings settings, TenantLookup tenants, LedgerPort books, CdrSink cdrRoad) {
        return RoutesphereKit.builder()
            .tenants(tenants)
            .identification(rules())
            .ledger(books)
            .cdr(cdrRoad)
            .sdr((sessionId, sdr) -> sdrs.add(sdr))
            .tasks((ctx, task) -> closedTasks.add(task))
            .zone(DHAKA)
            .settings(settings)
            .build();
    }

    /** Short timers for a test: INIT 2 s, ADMITTING 2 s, CONNECTING 10 s, ACTIVE {@code activeMaxSec}; no budget child; a pool of 8. */
    public static RoutesphereSettings settings(long activeMaxSec) {
        return new RoutesphereSettings(8, 2, 60 + activeMaxSec, new SessionTimings(2, 2, 10, activeMaxSec), 0, 0, 0, false, 500);
    }

    public static RoutesphereSettings settings() { return settings(20); }

    /** The same, with the budget child renewing every {@code periodSec}. */
    public static RoutesphereSettings renewing(long periodSec) { return settings().withReservePeriodSec(periodSec); }

    public BigDecimal balance(String tier, int partnerId) { return ledger.balanceOf(tier, partnerId); }
}
