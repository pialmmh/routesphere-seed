package com.telcobright.seed.switchledger.api;

import com.telcobright.rtc.domainmodel.LevelAdmission;
import com.telcobright.rtc.domainmodel.PartnerType;
import com.telcobright.rtc.domainmodel.mysqlentity.Partner;
import com.telcobright.rtc.domainmodel.nonentity.Tenant;
import com.telcobright.seed.sessionflow.api.SessionCause;
import com.telcobright.seed.sessionflow.api.SessionFlowTimings;
import com.telcobright.seed.sessionflow.dependencies.SessionFlowKit;
import com.telcobright.seed.sessionflow.dependencies.SessionFlowSettings;
import com.telcobright.seed.sessionflow.spi.TenantLookup;
import com.telcobright.seed.sessionflow.testkit.RecordingCdrSink;
import com.telcobright.seed.sessionflow.testkit.TenantTreeBuilder;
import com.telcobright.seed.switchledger.LedgerLab;
import com.telcobright.seed.switchledger.dependencies.SwitchLedgerSettings;
import com.telcobright.seed.switchledger.dependencies.SwitchLedgers;
import com.telcobright.seed.switchledger.samples.LedgerFlow;
import com.telcobright.statewalk.pipeline.StepMode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ARCH-0077-A item 3b — the call's C14 remainder on the switch ledger ({@code BalanceBillingService.reserveNextWindowSeconds}): the whole
 * window first; refused → the live balance is peeked and what is left, if it buys at least {@code MIN_FINAL_WINDOW_SEC}, is held under the
 * same reference and answered in seconds; nothing left → 0; a zero rate → the period, nothing held. Every reservation is recorded on the
 * level by the base's own chain ({@code reserveWindow}), never by this module.
 */
class WindowRenewalTest {

    private static final ZoneId DHAKA = ZoneId.of("Asia/Dhaka");
    private static final LocalDateTime LATER = LocalDateTime.of(2030, 1, 1, 0, 0);

    private final LedgerLab lab = LedgerLab.open().schema("btcl").schema("res_2");
    private final Tenant root = new TenantTreeBuilder()
        .root("btcl").partner(2, "res_2", PartnerType.RESELLER).and()
        .tenant("res_2", "btcl").partner(701, "Unilever", PartnerType.CUSTOMER).account(701, 11, 1, "OTH_ea", "0", LATER).and()
        .build();

    @AfterEach
    void closeTheLab() { lab.close(); }

    /** Unilever's bucket holds {@code balance}; the leaf tier's window costs {@code rate}; the period is 60 s. */
    private LedgerFlow flow(String balance, String rate) {
        lab.account("res_2", 11, 1, "OTH_ea", balance).start();
        var ledger = SwitchLedgers.over(lab.ledger(), SwitchLedgerSettings.standard(), Clock.system(DHAKA));
        SessionFlowKit kit = SessionFlowKit.builder()
            .tenants(TenantLookup.of(root)).ledger(ledger.port()).cdrSink(new RecordingCdrSink()).zone(DHAKA).clock(Clock.system(DHAKA))
            .settings(new SessionFlowSettings(4, 2, 60, new SessionFlowTimings(2, 2, 2, 3, 20, 2), 60, 0, false))
            .build();
        return new LedgerFlow(kit, Map.of("AA:01", 701), Map.of("res_2#701", new BigDecimal(rate)), ledger.balances());
    }

    private static LedgerFlow.Session admitted(LedgerFlow flow, String id) {
        LedgerFlow.Session session = LedgerFlow.session(id, "AA:01");
        assertThat(flow.preprocess(session)).isNull();
        assertThat(flow.admission(session, StepMode.LIVE).accepted()).isTrue();
        return session;
    }

    @Test
    void twoAndAHalfWindowsLeft_renewsTwiceWhole_thenTheRemaindersSeconds_thenNothing_theRowAndTheTotalsMatch() {
        LedgerFlow flow = flow("35", "10");                                             // admission holds 10: 2.5 windows are left
        LedgerFlow.Session session = admitted(flow, "r-1");
        LevelAdmission leaf = session.levels.get(0);

        assertThat(flow.renewReserves(session)).as("the second window, whole").isEqualTo(60.0);
        assertThat(flow.renewReserves(session)).as("the third window, whole").isEqualTo(60.0);
        assertThat(flow.renewReserves(session)).as("5 left of a 10 window: 30 s").isEqualTo(30.0);
        assertThat(flow.renewReserves(session)).as("nothing left").isZero();

        assertThat(leaf.getReservationCount()).isEqualTo(4);
        assertThat(leaf.getTotalReserved()).isEqualByComparingTo("35");
        assertThat(leaf.getBalanceAfter()).isEqualByComparingTo("0");
        assertThat(lab.cachedBalance("res_2", 11)).isEqualByComparingTo("0");
        assertThat(lab.cachedReserveRows("res_2")).isEqualTo(1);
        assertThat(lab.cachedRow("res_2", "r-1#L0").getReserveUnit()).isEqualByComparingTo("35");
        LedgerLab.await("the write-behind", () -> lab.dbReserveUnit("res_2", "r-1#L0") != null && lab.dbReserveUnit("res_2", "r-1#L0").compareTo(new BigDecimal("35")) == 0);
    }

    @Test
    void belowTheMinimumFinalWindow_answersZero_andHoldsNothing() {
        LedgerFlow flow = flow("10.1", "10");                                           // 0.1 left after admission: 0.6 s < 1 s
        LedgerFlow.Session session = admitted(flow, "r-2");

        assertThat(flow.renewReserves(session)).isZero();

        assertThat(session.levels.get(0).getReservationCount()).isEqualTo(1);
        assertThat(session.levels.get(0).getTotalReserved()).isEqualByComparingTo("10");
        assertThat(lab.cachedBalance("res_2", 11)).as("the 0.1 stays").isEqualByComparingTo("0.1");
        assertThat(lab.cachedRow("res_2", "r-2#L0").getReserveUnit()).isEqualByComparingTo("10");
    }

    @Test
    void exactlyTheMinimum_isHeld() {
        LedgerFlow flow = flow("10.166667", "10");                                      // 0.166667 left → 1.000002 s ≥ 1 s
        LedgerFlow.Session session = admitted(flow, "r-3");

        assertThat(flow.renewReserves(session)).isBetween(1.0, 1.01);
        assertThat(lab.cachedBalance("res_2", 11)).isEqualByComparingTo("0");
    }

    // ── asked directly, with a scripted hold ────────────────────────────────

    private static LevelAdmission tier() {
        Partner partner = new Partner();
        partner.setIdPartner(701);
        return new LevelAdmission(0, new Tenant("res_2"), partner, null);
    }

    @Test
    void aZeroRate_answersThePeriod_andHoldsNothing() {
        List<String> held = new ArrayList<>();
        WindowRenewal renewal = new WindowRenewal(level -> Optional.of(new BigDecimal("100")), (level, amount, reference) -> { held.add(amount.toPlainString()); return null; });

        assertThat(renewal.renewSeconds(tier(), BigDecimal.ZERO, "z#L0#W2", BigDecimal.ZERO, 60)).isEqualTo(60.0);
        assertThat(renewal.renewSeconds(tier(), BigDecimal.ZERO, "z#L0#W3", null, 60)).isEqualTo(60.0);

        assertThat(held).isEmpty();
    }

    @Test
    void aFaultOnTheWholeWindow_answersThePeriod_neverACut_andPeeksNothing() {
        List<String> peeked = new ArrayList<>();
        WindowRenewal renewal = new WindowRenewal(level -> { peeked.add("peek"); return Optional.of(BigDecimal.TEN); },
            (level, amount, reference) -> SessionCause.BILLING_SYSTEM_ERROR);

        assertThat(renewal.renewSeconds(tier(), BigDecimal.TEN, "f#L0#W2", BigDecimal.TEN, 60)).isEqualTo(60.0);
        assertThat(peeked).isEmpty();
    }

    @Test
    void theRemainder_isHeldUnderTheSameReference_andAnsweredInSeconds() {
        List<String> asks = new ArrayList<>();
        WindowRenewal renewal = new WindowRenewal(level -> Optional.of(new BigDecimal("2.5")),
            (level, amount, reference) -> { asks.add(amount.toPlainString() + "@" + reference); return amount.compareTo(BigDecimal.TEN) < 0 ? null : SessionCause.BALANCE_EXHAUSTED; });

        assertThat(renewal.renewSeconds(tier(), BigDecimal.TEN, "s#L0#W2", BigDecimal.TEN, 60)).isEqualTo(15.0);
        assertThat(asks).containsExactly("10@s#L0#W2", "2.5@s#L0#W2");
        assertThat(WindowRenewal.MIN_FINAL_WINDOW_SEC).as("BalanceBillingService.MIN_FINAL_WINDOW_SEC").isEqualTo(1.0);
    }

    @Test
    void aRemainderTheLedgerRefusesAfterThePeek_isZero() {
        WindowRenewal renewal = new WindowRenewal(level -> Optional.of(new BigDecimal("2.5")), (level, amount, reference) -> SessionCause.BALANCE_EXHAUSTED);
        assertThat(renewal.renewSeconds(tier(), BigDecimal.TEN, "q#L0#W2", BigDecimal.TEN, 60)).isZero();
    }
}
