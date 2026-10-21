package com.telcobright.seed.sessionflow.samples.ad;

import com.telcobright.seed.sessionflow.dependencies.RoutesphereSettings;
import com.telcobright.seed.sessionflow.samples.ad.AdFlow.Campaign;
import com.telcobright.seed.sessionflow.samples.ad.AdPlayback.AdTimings;
import com.telcobright.seed.sessionflow.samples.bed.CrashingStore;
import com.telcobright.seed.sessionflow.samples.scene.FlowBed;
import com.telcobright.seed.sessionflow.samples.scene.World;
import com.telcobright.statewalk.persistence.InMemoryPersistenceProvider;
import com.telcobright.statewalk.persistence.PersistenceProvider;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** The ad sample on the base (P1 §H): the scenarios every sample runs, in the ad's words — the whole view, the unshown rule, the candidates. */
class AdFlowTest {

    static final AdTimings PLAY = new AdTimings(5_000, 10_000, 10_000, 10_000);
    static final AdTimings NEVER_SHOWN_300_MS = new AdTimings(5_000, 300, 10_000, 10_000);

    static Campaign campaign(int id, String creative, Integer advertiser) { return new Campaign(id, creative, "video", 15, advertiser, false); }
    static final Campaign HOUSE = new Campaign(99, "house-1", "image", 5, null, true);

    static final Map<String, String> RULES = Map.of(
        "btcl|zone-9", "R9", "btcl|zone-701", "R701", "btcl|zone-801", "R801", "btcl|zone-poor", "RP", "btcl|zone-ghost", "RG",
        "ccl|zone-701", "R701");
    static final Map<String, List<Campaign>> CAMPAIGNS = Map.of(
        "btcl|R9", List.of(campaign(9, "c-9", 9)),
        "btcl|R701", List.of(campaign(71, "c-71", 701)),
        "btcl|R801", List.of(campaign(81, "c-81", 801), campaign(71, "c-71", 701)),
        "btcl|RP", List.of(campaign(72, "c-72", 702), campaign(71, "c-71", 701), HOUSE),
        "btcl|RG", List.of(campaign(77, "c-77", 777), HOUSE),
        "ccl|R701", List.of(campaign(171, "c-171", 701)));

    private final List<AutoCloseable> open = new ArrayList<>();
    private final World w = new World();
    private final AdQuota quota = new AdQuota();

    @AfterEach
    void tearDown() throws Exception { for (int i = open.size() - 1; i >= 0; i--) open.get(i).close(); }

    private FlowBed<AdContext, AdOut, AdFrame> bed(boolean unshownCharged, AdTimings t) { return bed(unshownCharged, t, World.settings(), null); }

    private FlowBed<AdContext, AdOut, AdFrame> bed(boolean unshownCharged, AdTimings t, RoutesphereSettings s, PersistenceProvider store) {
        AdFlow flow = new AdFlow(w.kit(s), RULES, CAMPAIGNS, World.RATES, unshownCharged, quota);
        FlowBed<AdContext, AdOut, AdFrame> b = new FlowBed<>(flow, AdContext::new, AdPlayback.spec(t, AdPlaybackContext::new), new AdChannel(),
            AdFrames::decode, store);
        open.add(b);
        return b;
    }

    static AdFrame start(String listen, String zone) {
        return AdFrame.start(listen, "AA:BB:CC:00:11:22", "8801711000001", zone, "kamalapur", "captive-portal", "10.20.0.1");
    }

    /** A whole view: start, shown, viewed, credited. */
    static void watch(FlowBed<AdContext, AdOut, AdFrame> b, String id, String listen, String zone) throws Exception {
        b.frame(id, start(listen, zone));
        assertEquals("CONNECTING/OFFERED", b.states(id), "admitted, the creative handed over: " + b.wire(id));
        b.frame(id, AdFrame.shown());
        b.frame(id, AdFrame.viewed(15));
        b.frame(id, AdFrame.credited());
        assertEquals("-/-", b.states(id));
    }

    private List<String> tiersOf(String id) {
        return w.cdrs.of(id).get(0).tiers().stream().map(t -> t.tenant + "/" + t.inPartnerId).toList();
    }

    private long verbs(String verb, String reference) {
        return w.ledger.journal().stream().filter(e -> e.verb().equals(verb) && e.reference().startsWith(reference)).count();
    }

    @Test
    void a_whole_view_on_one_two_and_three_tiers_each_tier_pays_its_view_one_record_per_tier_group_30_and_the_sdr() throws Exception {
        FlowBed<AdContext, AdOut, AdFrame> b = bed(true, PLAY);
        watch(b, "v-1t", World.AD_BTCL, "zone-9");
        watch(b, "v-2t", World.AD_BTCL, "zone-701");
        watch(b, "v-3t", World.AD_BTCL, "zone-801");

        assertEquals(List.of("btcl/9"), tiersOf("v-1t"));
        assertEquals(List.of("res_44/701", "btcl/44"), tiersOf("v-2t"));
        assertEquals(List.of("res_44_7/801", "res_44/7", "btcl/44"), tiersOf("v-3t"));
        assertEquals(30, w.cdrs.of("v-3t").get(0).tiers().get(0).serviceGroup);
        assertEquals(new BigDecimal("99.50"), w.balance("btcl", 9), "the whole view at the tier's rate");
        assertEquals(new BigDecimal("99.00"), w.balance("res_44_7", 801));
        assertEquals(new BigDecimal("99.20"), w.balance("res_44", 7));
        assertEquals(new BigDecimal("99.20"), w.balance("btcl", 44));
        assertEquals(0, w.ledger.openReserves());
        AdSdr sdr = (AdSdr) w.sdrs.get(2);
        assertEquals("v-3t", sdr.sessionId());
        assertEquals(81, sdr.campaignId());
        assertTrue(sdr.shown());
        assertEquals(15, sdr.watchedSeconds());
        assertEquals("SUCCEEDED", sdr.outcome());
        b.assertBackInThePools("v-3t");
    }

    @Test
    void two_roots_the_same_advertiser_id_each_view_pays_inside_the_tree_its_listener_named() throws Exception {
        FlowBed<AdContext, AdOut, AdFrame> b = bed(true, PLAY);
        watch(b, "v-btcl", World.AD_BTCL, "zone-701");
        watch(b, "v-ccl", World.AD_CCL, "zone-701");
        assertEquals(List.of("res_44/701", "btcl/44"), tiersOf("v-btcl"));
        assertEquals(List.of("ccl_44/701", "ccl/44"), tiersOf("v-ccl"));
        assertEquals(List.of("200 AdSessionView c-171", "200 shown", "200 AdVerdict watched=15", "200 credited"), b.wire("v-ccl"),
            "ccl's own campaign");
        assertEquals("cdr_ccl", w.cdrs.of("v-ccl").get(0).topic());
        assertEquals(new BigDecimal("99.30"), w.balance("ccl_44", 701));
    }

    @Test
    void the_unshown_rule_a_view_handed_over_and_never_shown_is_charged_by_default_and_refunded_when_the_lane_says_so() throws Exception {
        FlowBed<AdContext, AdOut, AdFrame> charging = bed(true, NEVER_SHOWN_300_MS);
        charging.frame("v-uc", start(World.AD_BTCL, "zone-701"));
        charging.awaitGone("v-uc", 3_000);
        assertEquals("NOT_SHOWN", charging.session("v-uc").endCause);
        assertEquals(new BigDecimal("99.40"), w.balance("res_44", 701), "unshown: charge — the reserve is kept");
        assertEquals(2, verbs("settle", "v-uc"));

        FlowBed<AdContext, AdOut, AdFrame> refunding = bed(false, NEVER_SHOWN_300_MS);
        refunding.frame("v-ur", start(World.AD_BTCL, "zone-701"));
        refunding.awaitGone("v-ur", 3_000);
        assertEquals(new BigDecimal("99.40"), w.balance("res_44", 701), "unshown: refund — nothing more charged");
        assertEquals(2, verbs("release", "v-ur"));
        assertEquals(0, w.ledger.openReserves());
    }

    @Test
    void a_view_shown_then_abandoned_pays_its_reserve_and_a_view_never_handed_over_pays_nothing() throws Exception {
        FlowBed<AdContext, AdOut, AdFrame> b = bed(false, PLAY);
        b.frame("v-ab", start(World.AD_BTCL, "zone-701"));
        b.frame("v-ab", AdFrame.shown());
        b.frame("v-ab", AdFrame.abandoned("back-button"));
        assertEquals("ABANDONED", b.session("v-ab").endCause);
        assertEquals(new BigDecimal("99.40"), w.balance("res_44", 701), "shown = consumed: the view is paid even though it failed");

        w.ledger.fund("res_44", 701, "0.10");                                      // too little for a view: never admitted
        b.frame("v-no", start(World.AD_BTCL, "zone-701"));
        assertEquals("INSUFFICIENT_BALANCE", b.session("v-no").endCause);
        assertEquals(List.of("204 X-Ad-Reason INSUFFICIENT_BALANCE"), b.wire("v-no"));
        assertEquals(new BigDecimal("0.10"), w.balance("res_44", 701));
    }

    @Test
    void the_candidates_in_order_a_poor_advertiser_then_an_unknown_one_then_the_quota_each_refused_candidate_gives_back_then_the_next_or_the_house_ad() throws Exception {
        quota.quota(71, 1);
        FlowBed<AdContext, AdOut, AdFrame> b = bed(true, PLAY);

        watch(b, "v-poor", World.AD_BTCL, "zone-poor");                            // 702 has no money → 701 pays
        assertEquals(List.of("res_44/701", "btcl/44"), tiersOf("v-poor"));
        assertEquals("c-71", ((AdCreative) b.session("v-poor").routePlan.current()).creativeId(), "the second candidate's creative");
        assertEquals(0, quota.left(71), "the quota claimed once, after the tiers reserved");

        watch(b, "v-house", World.AD_BTCL, "zone-poor");                           // 702 poor, 71's quota gone → the house ad, free
        assertEquals(new AdCreative("house-1", 99, "image", 5, "media/house-1"), b.session("v-house").routePlan.current());
        assertEquals(new BigDecimal("99.40"), w.balance("res_44", 701), "the refused candidate's reserves came back: 701 paid one view only");

        watch(b, "v-ghost", World.AD_BTCL, "zone-ghost");                         // advertiser 777 is in no tier of btcl → the house ad
        assertEquals("house-1", ((AdCandidate) b.session("v-ghost").chosen).creativeId());
        assertEquals(0, w.ledger.openReserves());
    }

    @Test
    void no_balance_at_a_middle_tier_of_a_three_tier_candidate_gives_its_reserves_back_and_the_next_candidate_is_admitted() throws Exception {
        w.ledger.fund("res_44", 7, "0.00");
        FlowBed<AdContext, AdOut, AdFrame> b = bed(true, PLAY);
        watch(b, "v-mid", World.AD_BTCL, "zone-801");
        assertEquals(List.of("res_44/701", "btcl/44"), tiersOf("v-mid"), "801's chain refused at res_44: 701's campaign served the view");
        assertEquals(new BigDecimal("100.00"), w.balance("res_44_7", 801), "the leaf's reserve came back");
        assertEquals(1, verbs("release", "v-mid#L0"), "the first candidate's leaf released");
        assertEquals(0, w.ledger.openReserves());
    }

    @Test
    void a_zone_no_rule_serves_and_a_door_no_rule_names_are_refused_before_any_tier() throws Exception {
        FlowBed<AdContext, AdOut, AdFrame> b = bed(true, PLAY);
        b.frame("v-nr", start(World.AD_BTCL, "zone-unknown"));
        assertEquals("NO_RULE", b.session("v-nr").endCause);
        b.frame("v-nt", start(World.NOBODYS, "zone-701"));
        assertEquals("NO_TENANT", b.session("v-nt").endCause);
        assertEquals(List.of("204 X-Ad-Reason NO_TENANT"), b.wire("v-nt"));
        assertTrue(w.ledger.journal().isEmpty());
    }

    @Test
    void a_view_restored_in_its_credit_window_is_credited_on_the_next_node_and_each_tier_pays_once() throws Exception {
        InMemoryPersistenceProvider store = new InMemoryPersistenceProvider();
        CrashingStore nodeA = new CrashingStore(store);
        FlowBed<AdContext, AdOut, AdFrame> a = bed(true, PLAY, World.settings(), nodeA);
        a.frame("v-r", start(World.AD_BTCL, "zone-701"));
        a.frame("v-r", AdFrame.shown());
        a.frame("v-r", AdFrame.viewed(15));
        assertEquals("ACTIVE/VIEWED", a.states("v-r"));
        nodeA.crash();

        FlowBed<AdContext, AdOut, AdFrame> b = bed(true, PLAY, World.settings(), store);
        assertEquals("ACTIVE/VIEWED", b.states("v-r"));
        assertTrue(b.registry().supervisorContextOf("v-r").shown, "the view's facts came back with the row");
        b.frame("v-r", AdFrame.credited());
        assertEquals("-/-", b.states("v-r"));
        assertEquals(new BigDecimal("99.40"), w.balance("res_44", 701));
        assertEquals(2, verbs("settle", "v-r"));
        assertEquals(1, w.cdrs.of("v-r").size());
    }
}
