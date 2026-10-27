package com.telcobright.seed.sessionflow.base;

import com.telcobright.seed.sessionflow.api.Candidate;
import com.telcobright.seed.sessionflow.api.SessionCause;
import com.telcobright.seed.sessionflow.api.Tier;
import com.telcobright.seed.sessionflow.dependencies.RoutesphereKit;
import com.telcobright.seed.sessionflow.spi.TenantLookup;
import com.telcobright.seed.sessionflow.testkit.InMemoryLedger;
import com.telcobright.seed.sessionflow.testkit.ManualClock;
import com.telcobright.seed.sessionflow.testkit.RecordingCdrSink;
import com.telcobright.statewalk.session.Verdict;
import com.telcobright.statewalk.session.events.SessionStart;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The admission's ONE budget (kept from the 3.x base): ADMITTING's timer minus the reserve kept for a free candidate (the lab: 2 s − 200 ms);
 * every ledger call bounded by what is left; a tier with no time left is not asked (ADMISSION_TIMEOUT); a ledger that does not answer in
 * time is a system fault (never a balance cause); once the budget is spent, a paid candidate is not tried and a free one still is. On a
 * manual clock: the slow ledger takes its time by moving it.
 */
class AdmissionBudgetTest {

    record Payer(int partnerId, boolean free) implements Candidate {}

    private final ManualClock clock = new ManualClock(Instant.parse("2026-10-22T00:00:00Z"), ZoneId.of("Asia/Dhaka"));
    private final InMemoryLedger ledger = new InMemoryLedger().timePassesBy(clock::advance)
        .fund("res_44_7", 3, "100.00").fund("res_44", 7, "100.00").fund("btcl", 44, "100.00").fund("res_44", 5, "100.00");

    private LabFlow flow(List<Candidate> candidates) {
        RoutesphereKit kit = RoutesphereKit.builder().tenants(TenantLookup.of(Lab.btclTree())).identification(List.of(Lab.RULES.get(0)))
            .ledger(ledger).cdr(new RecordingCdrSink()).zone(ZoneId.of("Asia/Dhaka")).clock(clock).settings(Lab.settings(0)).build();
        return new LabFlow(kit, Map.of(), Lab.hops("carrier-a"), Set.of()) {
            @Override protected List<Candidate> selectCandidates(LabContext ctx) { return candidates; }
            @Override protected Tier identifyPartner(LabContext ctx, Candidate candidate) {
                Payer p = (Payer) candidate;
                return p.partnerId() == 0 ? null : byPartnerId(ctx, p.partnerId());
            }
            @Override protected boolean isFree(LabContext ctx, Candidate candidate) { return ((Payer) candidate).free(); }
        };
    }

    /** INIT + ADMITTING through the handler (prepare's selectCandidates is the flow's: the candidates are the test's). */
    private Verdict admit(List<Candidate> candidates, LabContext ctx) {
        LabFlow flow = flow(candidates);
        ctx.facts = new LabFacts("b-" + System.nanoTime(), clock.millis(), Lab.BTCL_DOOR, 0, "8801");
        flow.onStart(ctx, new SessionStart(ctx.facts));
        ctx.candidates = candidates;
        return flow.admit(ctx);
    }

    @Test
    void aTierWithNoTimeLeft_isNotAsked_ADMISSION_TIMEOUT() {
        ledger.slowOn("res_44_7", 3, 1800);                                              // the leaf's answer takes the whole budget
        LabContext ctx = new LabContext();

        Verdict verdict = admit(List.of(new Payer(3, false)), ctx);

        assertThat(verdict.accepted()).isFalse();
        assertThat(verdict.cause()).isEqualTo(SessionCause.ADMISSION_TIMEOUT);
        assertThat(ledger.timesAsked("reserve")).as("the tier with no time left was not asked").isEqualTo(1);
        assertThat(ledger.openReserves()).as("the leaf's reserve went back").isZero();
    }

    @Test
    void aLedgerThatDoesNotAnswerInTime_isASystemFault_theBudgetSpent_onlyTheFreeCandidateIsTried() {
        ledger.slowOn("res_44_7", 3, 5000);                                              // longer than the whole budget
        LabContext ctx = new LabContext();

        Verdict verdict = admit(List.of(new Payer(3, false), new Payer(5, false), new Payer(0, true)), ctx);

        assertThat(verdict.accepted()).isTrue();
        assertThat(ctx.chosen).isEqualTo(new Payer(0, true));
        assertThat(ctx.budgetSpent).isTrue();
        assertThat(ctx.systemFault).isEqualTo(SessionCause.BILLING_SYSTEM_ERROR);
        assertThat(ctx.candidatesTried).as("the second paid candidate was never started").isEqualTo(2);
        assertThat(ledger.timeGivenFor(ctx.sessionId() + "#L0")).isEqualTo(1800L);
    }

    /**
     * L2b gate (ad-sphere item6d / item6g): a FREE candidate that walks its tiers (the house ad: a partner, a tier, nothing to pay) is still
     * admitted once the paid candidates spent the budget — every tier's hold is planned and asked at once (reserveBalance), and a tier with
     * nothing to hold is never "not asked for lack of time".
     */
    @Test
    void aFreeCandidateWithItsTiers_isStillAdmitted_afterAHangingLedgerSpentTheBudget() {
        ledger.slowOn("res_44_7", 3, 5000);                                              // the paid candidate's road hangs past the budget
        LabContext ctx = new LabContext();

        Verdict verdict = admit(List.of(new Payer(3, false), new Payer(5, true)), ctx);

        assertThat(verdict.accepted()).as("the free candidate with its tiers: " + verdict.cause()).isTrue();
        assertThat(ctx.chosen).isEqualTo(new Payer(5, true));
        assertThat(ctx.systemFault).isEqualTo(SessionCause.BILLING_SYSTEM_ERROR);
        assertThat(ctx.levels).as("its tiers walked, nothing held").isNotEmpty();
        assertThat(ledger.openReserves()).isZero();
    }

    @Test
    void withoutAFreeCandidate_theSystemFaultIsTheCause_neverABalanceCause() {
        ledger.slowOn("res_44_7", 3, 5000);
        Verdict verdict = admit(List.of(new Payer(3, false)), new LabContext());

        assertThat(verdict.cause()).isEqualTo(SessionCause.BILLING_SYSTEM_ERROR);
    }
}
