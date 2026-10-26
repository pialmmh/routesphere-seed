package com.telcobright.seed.switchledger;

import com.telcobright.rtc.domainmodel.LevelAdmission;
import com.telcobright.rtc.domainmodel.PartnerType;
import com.telcobright.rtc.domainmodel.nonentity.Tenant;
import com.telcobright.seed.sessionflow.api.SessionCause;
import com.telcobright.seed.sessionflow.dependencies.RoutesphereKit;
import com.telcobright.seed.sessionflow.dependencies.RoutesphereSettings;
import com.telcobright.seed.sessionflow.api.IdentificationRule;
import com.telcobright.statewalk.session.SessionTimings;
import com.telcobright.statewalk.session.Outcome;
import com.telcobright.seed.sessionflow.spi.TenantLookup;
import com.telcobright.seed.sessionflow.testkit.RecordingCdrSink;
import com.telcobright.seed.sessionflow.testkit.TenantTreeBuilder;
import com.telcobright.seed.switchledger.dependencies.SwitchLedgerSettings;
import com.telcobright.seed.switchledger.dependencies.SwitchLedgers;
import com.telcobright.seed.switchledger.samples.LedgerFlow;
import com.telcobright.statewalk.session.Verdict;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ARCH-0077-A item 3, on P1's base (RoutesphereFlow) — the base's AdmissionChain walked through the MemLedgerPort over a REAL MemLedger (H2 in MySQL mode as its MySQL,
 * Chronicle in a temp dir, standalone). The story: the operator {@code btcl} (partner 2 = the reseller {@code res_2}, partner 9 = a direct
 * customer) and its reseller {@code res_2} (partner 701 Unilever with an SMS bucket, 702 Poorco with no package). Each tier is a SCHEMA of
 * the one database: Unilever's account 11 lives in {@code res_2.packageaccount}, the reseller's account 21 in {@code btcl.packageaccount}.
 */
class ChainOnTheLedgerTest {

    private static final ZoneId DHAKA = ZoneId.of("Asia/Dhaka");
    private static final LocalDateTime LATER = LocalDateTime.of(2030, 1, 1, 0, 0);

    private final LedgerLab lab = LedgerLab.open()
        .schema("btcl").account("btcl", 21, 5, "OTH_ea", "50")
        .schema("res_2").account("res_2", 11, 1, "OTH_ea", "100");
    private final Tenant root = new TenantTreeBuilder()
        .root("btcl")
            .partner(2, "res_2", PartnerType.RESELLER)
            .partner(9, "DirectCo", PartnerType.CUSTOMER)
            .account(2, 21, 5, "OTH_ea", "50", LATER)
            .and()
        .tenant("res_2", "btcl")
            .partner(701, "Unilever", PartnerType.CUSTOMER)
            .partner(702, "Poorco", PartnerType.CUSTOMER)
            .account(701, 11, 1, "OTH_ea", "100", LATER)
            .and()
        .build();
    private final RecordingCdrSink cdrs = new RecordingCdrSink();

    @AfterEach
    void closeTheLab() { lab.close(); }

    private LedgerFlow flow(Map<String, BigDecimal> ratePerWindow) {
        lab.start();
        return flowOn(SwitchLedgers.memLedger(lab.ledger(), SwitchLedgerSettings.standard(), Clock.system(DHAKA)), ratePerWindow);
    }

    /** The base's kit on this ledger port: the gateway's vlan rule names btcl; the budget renews every 60 s. */
    private LedgerFlow flowOn(com.telcobright.seed.sessionflow.spi.LedgerPort port, Map<String, BigDecimal> ratePerWindow) {
        RoutesphereKit kit = RoutesphereKit.builder()
            .tenants(TenantLookup.of(root))
            .identification(java.util.List.of(new IdentificationRule("vlan", "gw-1:310", "btcl")))
            .ledger(port)
            .cdr(cdrs)
            .zone(DHAKA)
            .clock(Clock.system(DHAKA))
            .settings(new RoutesphereSettings(4, 2, 60, new SessionTimings(2, 2, 2, 20), 60, 0, 0, false, 200))
            .build();
        return new LedgerFlow(kit, Map.of("AA:01", 701, "AA:02", 702, "AA:09", 9), ratePerWindow);
    }

    private static final Map<String, BigDecimal> LEAF_PAYS = Map.of("res_2#701", new BigDecimal("10"));
    private static final Map<String, BigDecimal> BOTH_PAY = Map.of("res_2#701", new BigDecimal("10"), "btcl#2", new BigDecimal("5"));

    private static Verdict admit(LedgerFlow flow, LedgerFlow.Session session) { return flow.admit(session, true); }

    // ── the walk ────────────────────────────────────────────────────────────

    @Test
    void theLeafReservesOnItsBucket_theTierAboveIsFree_theRowLandsInTheLeafsSchema_neverTheRoots() {
        LedgerFlow flow = flow(LEAF_PAYS);
        LedgerFlow.Session session = LedgerFlow.session("w-1", "AA:01");

        assertThat(admit(flow, session).accepted()).isTrue();

        assertThat(session.levels).hasSize(2);
        LevelAdmission leaf = session.levels.get(0), top = session.levels.get(1);
        assertThat(leaf.getDbName()).isEqualTo("res_2");
        assertThat(leaf.getDebitReference()).isEqualTo("w-1#L0");
        assertThat(leaf.getPackageAccountId()).isEqualTo(11L);
        assertThat(leaf.getUom()).isEqualTo("OTH_ea");
        assertThat(leaf.getReservedAmount()).isEqualByComparingTo("10");
        assertThat(leaf.getBalanceBefore()).isEqualByComparingTo("100");
        assertThat(leaf.getBalanceAfter()).isEqualByComparingTo("90");
        assertThat(top.getDebitReference()).as("a free tier asks the ledger nothing").isNull();
        assertThat(lab.cachedBalance("res_2", 11)).isEqualByComparingTo("90");
        assertThat(lab.cachedRow("res_2", "w-1#L0").getReserveUnit()).isEqualByComparingTo("10");
        assertThat(lab.cachedRow("res_2", "w-1#L0").getIdPackagePurchase()).isEqualTo(1L);
        assertThat(lab.cachedReserveRows("btcl")).as("the root's schema holds no row of the leaf's tier").isZero();
        assertThat(lab.cachedBalance("btcl", 21)).isEqualByComparingTo("50");
        LedgerLab.await("the write-behind", () -> lab.dbReserveRows("res_2") == 1 && lab.dbBalance("res_2", 11).compareTo(new BigDecimal("90")) == 0);
        assertThat(lab.dbReserveUnit("res_2", "w-1#L0")).isEqualByComparingTo("10");
        assertThat(lab.dbReserveRows("btcl")).isZero();
    }

    @Test
    void bothTiersPay_eachRowInItsOwnTiersSchema() {
        LedgerFlow flow = flow(BOTH_PAY);
        LedgerFlow.Session session = LedgerFlow.session("w-2", "AA:01");

        assertThat(admit(flow, session).accepted()).isTrue();

        assertThat(session.levels.get(1).getDebitReference()).isEqualTo("w-2#L1");
        assertThat(session.levels.get(1).getPackageAccountId()).isEqualTo(21L);
        assertThat(lab.cachedRow("res_2", "w-2#L0").getIdPackageAccount()).isEqualTo(11L);
        assertThat(lab.cachedRow("btcl", "w-2#L1").getIdPackageAccount()).isEqualTo(21L);
        assertThat(lab.cachedRow("btcl", "w-2#L0")).isNull();
        assertThat(lab.cachedRow("res_2", "w-2#L1")).isNull();
        assertThat(lab.cachedBalance("btcl", 21)).isEqualByComparingTo("45");
        assertThat(lab.cachedBalance("res_2", 11)).isEqualByComparingTo("90");
    }

    @Test
    void renewals_growTheOneRow_underW2andW3_theTotalsFollow() {
        LedgerFlow flow = flow(LEAF_PAYS);
        LedgerFlow.Session session = LedgerFlow.session("w-3", "AA:01");
        assertThat(admit(flow, session).accepted()).isTrue();

        assertThat(flow.renewReserves(session)).isEqualTo(60.0);
        assertThat(flow.renewReserves(session)).isEqualTo(60.0);

        LevelAdmission leaf = session.levels.get(0);
        assertThat(leaf.getReservationCount()).isEqualTo(3);
        assertThat(leaf.getTotalReserved()).isEqualByComparingTo("30");
        assertThat(leaf.getBalanceAfter()).isEqualByComparingTo("70");
        assertThat(lab.cachedReserveRows("res_2")).as("one row per tier, grown per window").isEqualTo(1);
        assertThat(lab.cachedRow("res_2", "w-3#L0").getReserveUnit()).isEqualByComparingTo("30");
        assertThat(lab.cachedBalance("res_2", 11)).isEqualByComparingTo("70");
        LedgerLab.await("the write-behind", () -> lab.dbReserveUnit("res_2", "w-3#L0") != null && lab.dbReserveUnit("res_2", "w-3#L0").compareTo(new BigDecimal("30")) == 0);
    }

    @Test
    void aRenewalTheBucketCannotFund_isRefused_notAFault() {
        LedgerFlow flow = flow(Map.of("res_2#701", new BigDecimal("40")));
        LedgerFlow.Session session = LedgerFlow.session("w-4", "AA:01");
        assertThat(admit(flow, session).accepted()).isTrue();                           // 60 left

        assertThat(flow.renewReserves(session)).isEqualTo(60.0);                        // 20 left
        assertThat(flow.renewReserves(session)).as("the third window: 20 < 40").isZero();

        assertThat(lab.cachedBalance("res_2", 11)).isEqualByComparingTo("20");
        assertThat(session.systemFault).isNull();
        assertThat(lab.cachedRow("res_2", "w-4#L0").getReserveUnit()).isEqualByComparingTo("80");
    }

    // ── the settlement ──────────────────────────────────────────────────────

    @Test
    void settle_returnsReservedLessCharged_theRowDies_inTheCacheAndInMySql() {
        LedgerFlow flow = flow(LEAF_PAYS);
        LedgerFlow.Session session = LedgerFlow.session("w-5", "AA:01");
        assertThat(admit(flow, session).accepted()).isTrue();
        flow.renewReserves(session);
        flow.renewReserves(session);                                                    // 30 held
        flow.charge = level -> new BigDecimal("25");

        flow.settle(session, Outcome.SUCCEEDED);

        assertThat(session.settlements).hasSize(2);
        assertThat(session.settlements.get(0).closed()).isTrue();
        assertThat(session.settlements.get(0).reserved()).isEqualByComparingTo("30");
        assertThat(session.settlements.get(0).charged()).isEqualByComparingTo("25");
        assertThat(session.settlements.get(0).returned()).isEqualByComparingTo("5");
        assertThat(session.settlements.get(0).balanceAfter()).isEqualByComparingTo("75");
        assertThat(session.settlements.get(1).reserved()).as("the free tier settles nothing").isEqualByComparingTo("0");
        assertThat(lab.cachedBalance("res_2", 11)).isEqualByComparingTo("75");
        assertThat(lab.cachedReserveRows("res_2")).as("packageaccountreserve after the settle").isZero();
        LedgerLab.await("the write-behind", () -> lab.dbReserveRows("res_2") == 0 && lab.dbBalance("res_2", 11).compareTo(new BigDecimal("75")) == 0);
    }

    @Test
    void settle_debitsAnOverrun_theRowDies() {
        LedgerFlow flow = flow(LEAF_PAYS);
        LedgerFlow.Session session = LedgerFlow.session("w-6", "AA:01");
        assertThat(admit(flow, session).accepted()).isTrue();                           // 10 held
        flow.charge = level -> new BigDecimal("14");

        flow.settle(session, Outcome.SUCCEEDED);

        assertThat(session.settlements.get(0).returned()).isEqualByComparingTo("-4");
        assertThat(session.settlements.get(0).balanceAfter()).isEqualByComparingTo("86");
        assertThat(lab.cachedBalance("res_2", 11)).isEqualByComparingTo("86");
        assertThat(lab.cachedReserveRows("res_2")).isZero();
        LedgerLab.await("the write-behind", () -> lab.dbReserveRows("res_2") == 0 && lab.dbBalance("res_2", 11).compareTo(new BigDecimal("86")) == 0);
    }

    // ── §L.1 · the second end of a restored session moves nothing ──────────

    @Test
    void aSessionEndedAgainAfterARestart_fromItsOlderRow_movesNothingOnTheSwitchLedger() {
        LedgerFlow flow = flow(BOTH_PAY);
        LedgerFlow.Session session = LedgerFlow.session("w-10", "AA:01");
        assertThat(admit(flow, session).accepted()).isTrue();                           // 10 held on account 11, 5 on account 21
        String olderRow = com.telcobright.statewalk.persistence.SnapshotSerializer.contextToBase64Json(session);
        flow.charge = level -> new BigDecimal("4");
        flow.settle(session, Outcome.SUCCEEDED);                                      // settled at 4 each: 96 and 46; the rows die
        assertThat(lab.cachedBalance("res_2", 11)).isEqualByComparingTo("96");
        assertThat(lab.cachedBalance("btcl", 21)).isEqualByComparingTo("46");

        LedgerFlow afterTheRestart = flowOn(SwitchLedgers.memLedger(lab.ledger(), SwitchLedgerSettings.standard(), Clock.system(DHAKA)), BOTH_PAY);
        afterTheRestart.charge = level -> new BigDecimal("9");                          // a longer talk time the second time (E §9)
        LedgerFlow.Session restored = (LedgerFlow.Session) com.telcobright.statewalk.persistence.SnapshotSerializer
            .contextFromBase64Json(olderRow, LedgerFlow.Session.class.getName());
        afterTheRestart.settle(restored, Outcome.SUCCEEDED);

        assertThat(lab.cachedBalance("res_2", 11)).as("nothing moved the second time").isEqualByComparingTo("96");
        assertThat(lab.cachedBalance("btcl", 21)).isEqualByComparingTo("46");
        assertThat(restored.settlements).as("the switch ledger finds no row open: its words are OWED, its money is none")
            .allMatch(s -> !s.closed() && s.note().contains("nothing moved"));
        assertThat(restored.tiers).extracting(t -> t.debitReference).containsExactly("w-10#L0", "w-10#L1");
    }

    // ── the refusals ────────────────────────────────────────────────────────

    @Test
    void aLaterTiersRefusal_releasesTheLeaf_everyRowGone() {
        LedgerFlow flow = flow(Map.of("res_2#701", new BigDecimal("10"), "btcl#2", new BigDecimal("500")));   // the reseller holds 50
        LedgerFlow.Session session = LedgerFlow.session("w-7", "AA:01");

        Verdict verdict = admit(flow, session);

        assertThat(verdict.accepted()).isFalse();
        assertThat(verdict.cause()).isEqualTo(SessionCause.INSUFFICIENT_BALANCE);
        assertThat(session.levels).isEmpty();
        assertThat(lab.cachedBalance("res_2", 11)).isEqualByComparingTo("100");
        assertThat(lab.cachedBalance("btcl", 21)).isEqualByComparingTo("50");
        assertThat(lab.cachedReserveRows("res_2")).isZero();
        assertThat(lab.cachedReserveRows("btcl")).isZero();
        LedgerLab.await("the write-behind", () -> lab.dbReserveRows("res_2") == 0 && lab.dbBalance("res_2", 11).compareTo(new BigDecimal("100")) == 0);
    }

    @Test
    void noPackage_noWifi_thePartnerWithNoBucketIsUnrated() {
        LedgerFlow flow = flow(Map.of("res_2#702", new BigDecimal("10")));
        LedgerFlow.Session session = LedgerFlow.session("w-8", "AA:02");

        Verdict verdict = admit(flow, session);

        assertThat(verdict.accepted()).isFalse();
        assertThat(verdict.cause()).isEqualTo(SessionCause.UNRATED);
        assertThat(lab.cachedReserveRows("res_2")).isZero();
    }

    @Test
    void aLedgerThatDoesNotAnswer_isBILLING_SYSTEM_ERROR_neverABalanceCause() {
        LedgerFlow flow = flow(LEAF_PAYS);
        lab.ledger().shutdown();
        LedgerFlow.Session session = LedgerFlow.session("w-9", "AA:01");

        Verdict verdict = admit(flow, session);

        assertThat(verdict.accepted()).isFalse();
        assertThat(verdict.cause()).isEqualTo(SessionCause.BILLING_SYSTEM_ERROR);
        assertThat(session.systemFault).isEqualTo(SessionCause.BILLING_SYSTEM_ERROR);
    }
}
