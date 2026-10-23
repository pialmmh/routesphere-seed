package com.telcobright.seed.sessionflow.base;

import com.telcobright.rtc.domainmodel.LevelAdmission;
import com.telcobright.rtc.domainmodel.PartnerType;
import com.telcobright.rtc.domainmodel.mysqlentity.Partner;
import com.telcobright.rtc.domainmodel.nonentity.Tenant;
import com.telcobright.seed.sessionflow.api.Candidate;
import com.telcobright.seed.sessionflow.api.SessionCause;
import com.telcobright.seed.sessionflow.api.Tier;
import com.telcobright.seed.sessionflow.api.TierRate;
import com.telcobright.seed.sessionflow.testkit.TenantTreeBuilder;
import com.telcobright.statewalk.session.Outcome;
import com.telcobright.statewalk.session.Verdict;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * §B/§E · The admission walk, as the call switch runs it (DefaultAdmissionService.admitAtLevel) — kept from the 3.x base, re-proved on
 * the new one: the candidates in order (a refused one gives every reserve of its try back, the next reserves under its own reference),
 * the free candidate, no balance at a MIDDLE tier, the order of the gates (the checks before the leaf's channel slot take none; a refusal
 * after it frees it), the cap of channels, a tier the application held in one move (TierRate.held), and the pool's door.
 */
class AdmissionWalkTest {

    /** Two candidates: the first a payer that cannot pay at its middle tier, the second one that can. */
    record Payer(int partnerId, boolean free) implements Candidate {}

    private Lab lab;

    @AfterEach
    void close() { if (lab != null) lab.close(); }

    private Lab labWith(List<Candidate> candidates, Map<String, String> rates) {
        return new Lab(Lab.settings(0), kit -> new LabFlow(kit, rates, Lab.hops("carrier-a"), Set.of()) {
            @Override protected List<Candidate> selectCandidates(LabContext ctx) { return candidates; }
            @Override protected Tier identifyPartner(LabContext ctx, Candidate candidate) {
                Payer p = (Payer) candidate;
                return p.partnerId() == 0 ? null : byPartnerId(ctx, p.partnerId());
            }
            @Override protected boolean isFree(LabContext ctx, Candidate candidate) { return ((Payer) candidate).free(); }
        });
    }

    @Test
    void noBalanceAtAMiddleTier_everyReserveOfThatTryBack_theNextCandidateReservesUnderItsOwnReference() {
        lab = labWith(List.of(new Payer(3, false), new Payer(5, false)), Map.of("res_44", "500.00"));   // res_44's partner 7 holds 100
        lab.ledger.fund("res_44", 5, "1000.00");
        lab.ledger.fund("btcl", 44, "100.00");
        lab.dial("c-1", Lab.BTCL_DOOR, 0);

        LabContext ctx = lab.ctx("c-1");
        assertThat(ctx.candidatesTried).isEqualTo(2);
        assertThat(ctx.chosen).isEqualTo(new Payer(5, false));
        assertThat(ctx.candidateIndex).isEqualTo(1);
        assertThat(lab.ledger.journal()).extracting(e -> e.verb() + " " + e.reference())
            .containsExactly("reserve c-1#L0", "refused c-1#L1", "release c-1#L0", "reserve c-1#2#L0", "reserve c-1#2#L1");
        assertThat(lab.ledger.balanceOf("res_44_7", 3)).as("the first try's leaf reserve went back").isEqualByComparingTo("100.00");
        assertThat(ctx.tiers).extracting(t -> t.debitReference).containsExactly("c-1#2#L0", "c-1#2#L1");
    }

    @Test
    void noCandidateAdmitted_theLastRefusalIsTheCause_nothingHeld() {
        lab = labWith(List.of(new Payer(3, false)), Map.of("res_44", "500.00"));
        lab.dial("c-2", Lab.BTCL_DOOR, 0);

        assertThat(lab.ctx("c-2").outcome).isEqualTo(Outcome.FAILED);
        assertThat(lab.ctx("c-2").endCause).isEqualTo(SessionCause.INSUFFICIENT_BALANCE);
        assertThat(lab.ledger.openReserves()).isZero();
    }

    @Test
    void aFreeCandidate_runsWithNoPartner_walksNoTier_reservesNothing() {
        lab = labWith(List.of(new Payer(999, false), new Payer(0, true)), Map.of());
        lab.dial("c-3", Lab.BTCL_DOOR, 0);
        lab.answer("c-3");
        lab.hangup("c-3");

        LabContext ctx = lab.ctx("c-3");
        assertThat(ctx.outcome).isEqualTo(Outcome.SUCCEEDED);
        assertThat(ctx.chosen).isEqualTo(new Payer(0, true));
        assertThat(ctx.tiers).isEmpty();
        assertThat(lab.ledger.journal()).isEmpty();
        assertThat(lab.cdr.of("c-3").get(0).tiers()).extracting(r -> r.tenant).as("one record on the root").containsExactly("btcl");
    }

    @Test
    void theGatesOrder_aCheckBeforeTheSlotTakesNone_aRefusalAfterItFreesIt_theCapRefuses() {
        List<String> gates = new CopyOnWriteArrayList<>();
        lab = new Lab(Lab.settings(0), kit -> new LabFlow(kit, Map.of(), Lab.hops("carrier-a"), Set.of()) {
            @Override protected Verdict checkPartner(LabContext ctx, Tier tier, Partner partner) { gates.add("check:" + tier.dbName()); return super.checkPartner(ctx, tier, partner); }
            @Override protected Verdict authorizeBeforeSlot(LabContext ctx, Tier tier, Partner partner) {
                gates.add("beforeSlot:" + tier.dbName());
                return ctx.sessionId().equals("did") ? Verdict.reject("INVALID_DID") : Verdict.ok();
            }
            @Override protected Verdict authorize(LabContext ctx, Tier tier, Partner partner) {
                gates.add("authorize:" + tier.dbName() + ":slots=" + kit.slots().activeOf("tele2", 1));
                return ctx.sessionId().equals("sip") ? Verdict.reject("RETAIL_CHANNEL_LIMIT_REACHED") : Verdict.ok();
            }
            @Override protected Verdict applyRootRules(LabContext ctx, Tier root, Partner partner) { gates.add("root:" + root.dbName()); return Verdict.ok(); }
        }, new VersionedTrees().serve(Lab.btclTree()).serve(cappedTele2()));
        lab.dial("ok", Lab.TELE2_DOOR, 1);
        assertThat(gates).containsExactly("check:tele2", "beforeSlot:tele2", "authorize:tele2:slots=1", "root:tele2");
        lab.dial("cap", Lab.TELE2_DOOR, 1);
        lab.dial("did", Lab.TELE2_DOOR, 1);
        lab.hangupWhenAnswered("ok");
        lab.dial("sip", Lab.TELE2_DOOR, 1);

        assertThat(lab.ctx("cap").endCause).as("the partner's one channel is taken").isEqualTo(SessionCause.CHANNEL_LIMIT_REACHED);
        assertThat(lab.ctx("did").endCause).isEqualTo("INVALID_DID");
        assertThat(lab.ctx("sip").endCause).isEqualTo("RETAIL_CHANNEL_LIMIT_REACHED");
        assertThat(lab.kit.slots().activeOf("tele2", 1)).as("every slot given back").isZero();
        assertThat(lab.ledger.openReserves()).isZero();
    }

    @Test
    void aTierTheApplicationHeldInOneMove_isTakenAsItIs_namedByTheBase_settledOnce() {
        lab = new Lab(Lab.settings(0), kit -> new LabFlow(kit, Map.of(), Lab.hops("carrier-a"), Set.of()) {
            @Override protected TierRate rateAtLevel(LabContext ctx, Tier tier, Partner partner) {
                LevelAdmission held = new LevelAdmission(tier.levelIndex(), tier.tenant(), partner, null);
                held.setRate(new BigDecimal("2.00"));
                held.setUom("TF_min");
                held.setReservedAmount(BigDecimal.ONE);
                held.incrementReservationCount();
                return TierRate.held(held);                                            // the call switch's ReserveBalanceStep: held already
            }
        });
        lab.dial("h-1", Lab.BTCL_DOOR, 1);
        lab.answer("h-1");
        lab.hangup("h-1");

        LabContext ctx = lab.ctx("h-1");
        assertThat(ctx.tiers).extracting(t -> t.debitReference + "/" + t.uom).containsExactly("h-1#L0/TF_min");
        assertThat(lab.ledger.count("reserve")).as("the base asked its ledger nothing for a held tier").isZero();
        assertThat(lab.ledger.timesAsked("settle")).isEqualTo(1);
    }

    @Test
    void aFullPool_refusesTheNextSessionAtTheDoor() throws Exception {
        lab = new Lab(Lab.settings(0).withPool(1));
        lab.dial("pool-1", Lab.BTCL_DOOR, 1);

        var door = lab.wire.inject("pool-2", new LabEvents.Dial(new LabFacts("pool-2", 1L, Lab.BTCL_DOOR, 1, "8801"))).toCompletableFuture();
        assertThatThrownBy(() -> door.get(5, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class).hasCauseInstanceOf(RejectedExecutionException.class);
        assertThat(lab.engine.isLive("pool-2")).isFalse();
        assertThat(lab.engine.isLive("pool-1")).isTrue();
    }

    private static Tenant cappedTele2() {
        TenantTreeBuilder b = new TenantTreeBuilder();
        b.root("tele2").partner(1, "tele2-client", PartnerType.CUSTOMER, 1).partner(9, "res_9", PartnerType.RESELLER);
        b.tenant("res_9", "tele2").partner(3, "res9-client", PartnerType.CUSTOMER);
        return b.build();
    }
}
