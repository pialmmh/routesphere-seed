package com.telcobright.seed.prepaid.parity;

import com.telcobright.rtc.domainmodel.LevelAdmission;
import com.telcobright.seed.prepaid.api.Prepaid;
import com.telcobright.seed.sessionflow.api.CdrEvent;
import com.telcobright.seed.sessionflow.api.RoutesphereFlow;
import com.telcobright.seed.sessionflow.api.SessionCause;
import com.telcobright.seed.sessionflow.api.TierSettlement;
import com.telcobright.seed.sessionflow.dependencies.RoutesphereKit;
import com.telcobright.seed.sessionflow.internal.CdrJson;
import com.telcobright.statewalk.session.Verdict;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static com.telcobright.seed.prepaid.parity.ParityScene.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * S5b (X1; routesphere {@code docs/architecture/seed-prepaid-s5-payable-accounts-hook.md} §7) · a tier the lane does NOT charge for this
 * request takes the road the lane's answers take for a tier rated free: walked and recorded at zero, nothing held, never refused for want
 * of an account, {@code payableAccounts} not asked for it; its renewals hold nothing and the end settles nothing. Unilever's call walks the
 * reseller's tier on btcl (21 {@code TF_min} 10 on purchase 5, 22 {@code BDT} 50 on purchase 6) and Unilever's own on res_2 (the tapped
 * account 13, as S5's rows). The WiFi's rule — only the tier that owns the purchase charges — is here "Unilever's tier charges, the
 * reseller's does not".
 */
class TierChargeTest {

    /** J4: purchase 1 the sooner, purchase 3 (account 13) valid and later; the reseller's purchases valid. */
    private static final Map<Long, String> J4 = Map.of(1L, "2027-06-01 00:00:00", 3L, "2030-01-01 00:00:00");
    /** J4 with the reseller's purchases 5 and 6 EXPIRED: its tier holds no account the rule lets pay. */
    private static final Map<Long, String> RESELLER_HOLDS_NONE = Map.of(1L, "2027-06-01 00:00:00", 3L, "2030-01-01 00:00:00",
        5L, "2026-01-01 00:00:00", 6L, "2026-01-01 00:00:00");

    private final List<ParityScene> scenes = new ArrayList<>();

    @AfterEach
    void close() { scenes.forEach(ParityScene::close); }

    @Test
    void x1_theResellersTierHoldsNoAccount_notChargedThere_theSessionIsHeldOnTheTappedAccount() {
        ParityScene scene = keep(openOnTheLedgersPurchases(Map.of(), RESELLER_HOLDS_NONE, onlyTheOwnersTierCharges()));

        CallContext call = scene.admitted("x1-a", UNILEVER, DOMESTIC);

        assertThat(scene.balance("res_2", 13)).as("held on the subscriber's tapped account").isEqualByComparingTo("99");
        LevelAdmission reseller = levelOf(call, RESELLER);
        assertThat(reseller.getRate()).as("the reseller's level is recorded, at zero").isEqualByComparingTo("0");
        assertThat(reseller.getDebitReference()).as("nothing held at the reseller's tier").isNull();
        assertThat(reseller.getPackageAccountId()).isNull();
        assertThat(scene.rows("btcl")).isZero();
    }

    @Test
    void x1_withoutTheAnswer_theWholeSessionIsRefused_todaysPrepaid() {
        ParityScene scene = keep(openOnTheLedgersPurchases(Map.of(), RESELLER_HOLDS_NONE, kit -> lane(kit, null)));

        Verdict verdict = scene.admit(scene.call("x1-b", UNILEVER, DOMESTIC));

        assertThat(verdict.cause()).as("every tier charges by default: the reseller's finds no account").isEqualTo(SessionCause.INSUFFICIENT_BALANCE);
        assertThat(scene.rows("res_2")).as("nothing held anywhere").isZero();
        assertThat(scene.balance("res_2", 13)).isEqualByComparingTo("100");
    }

    @Test
    void anExemptTierThatHoldsAccounts_noneOfThemMoves_atAdmissionRenewalOrTheEnd() {
        ParityScene scene = keep(openOnTheLedgersPurchases(Map.of(), J4, onlyTheOwnersTierCharges()));
        CallContext call = scene.admitted("x1-c", UNILEVER, DOMESTIC);
        assertThat(scene.balance("btcl", 21)).as("admission").isEqualByComparingTo("10");
        scene.answer(call);

        assertThat(scene.renew(call)).as("the window renews on Unilever's account alone").isEqualTo(60.0);
        assertThat(scene.balance("btcl", 21)).as("renewal").isEqualByComparingTo("10");
        scene.talk(90);
        scene.hangUp(call);

        assertThat(scene.balance("btcl", 21)).as("the end: the exempt tier's package").isEqualByComparingTo("10");
        assertThat(scene.balance("btcl", 22)).as("the end: the exempt tier's cash").isEqualByComparingTo("50");
        assertThat(scene.rows("btcl")).isZero();
        LevelAdmission reseller = levelOf(call, RESELLER), owner = levelOf(call, UNILEVER);
        assertThat(settlementOf(call, reseller.getLevelIndex())).as("the end settles nothing for it").isEqualTo(TierSettlement.nothing(reseller.getLevelIndex()));
        assertThat(scene.recorder.seen.stream().filter(line -> line.startsWith("settle ")).toList()).as("one settle: the owner's tier")
            .singleElement().asString().startsWith("settle " + owner.getDebitReference() + " ");
        assertThat(scene.balance("res_2", 13)).as("the owner's tier paid").isLessThan(new java.math.BigDecimal("100"));
    }

    @Test
    void theExemptTiersRecordLine_equalsTodaysFreeTiersLine() {
        ParityScene today = keep(openOnTheLedgersPurchases(Map.of(), J4,
            kit -> CallLane.onTheBase(kit, rates(), Map.of(DOMESTIC, PORTED), kit.clock(), partnerId -> partnerId == RESELLER)));
        ParityScene exempt = keep(openOnTheLedgersPurchases(Map.of(), J4, onlyTheOwnersTierCharges()));

        String todays = recordLineOfTheReseller(today, "x1-line"), exempts = recordLineOfTheReseller(exempt, "x1-line");

        assertThat(exempts).as("the CDR's shape does not move: the same fields, amount 0").isEqualTo(todays);
        assertThat(todays).contains("\"tenant\":\"btcl\"");
    }

    /** The reseller's (btcl) record of one answered minute and a half, as the CDR wire carries it. */
    private static String recordLineOfTheReseller(ParityScene scene, String id) {
        CallContext call = scene.admitted(id, UNILEVER, DOMESTIC);
        scene.answer(call);
        scene.talk(90);
        scene.hangUp(call);
        List<CdrEvent> tiers = scene.cdrs.of(id).get(0).tiers();
        CdrEvent reseller = tiers.stream().filter(t -> "btcl".equals(t.tenant)).findFirst().orElseThrow();
        return CdrJson.ofCall(List.of(reseller));
    }

    /** The WiFi's rule on the call's rows: only the tier that owns the purchase (Unilever's) charges; it pays on the tapped account 13. */
    private static Function<RoutesphereKit, RoutesphereFlow<CallContext>> onlyTheOwnersTierCharges() {
        return kit -> lane(kit, (call, tier) -> tier.partner().getIdPartner() == UNILEVER);
    }

    /** The call's lane on its Prepaid: Unilever's tier narrowed to the tapped account 13 (S5), {@code charges} as given (null = not given). */
    private static RoutesphereFlow<CallContext> lane(RoutesphereKit kit, Prepaid.TierCharge<CallContext> charges) {
        Prepaid.PayableAccounts<CallContext> tapped13 = (call, tier, fundable) -> tier.partner().getIdPartner() == UNILEVER
            ? fundable.stream().filter(a -> a.getId() == 13L).toList() : fundable;
        Prepaid<CallContext> prepaid = charges == null ? CallPrepaid.of(kit, rates(), tapped13) : CallPrepaid.of(kit, rates(), tapped13, charges);
        return CallLane.onPrepaid(kit, rates(), Map.of(DOMESTIC, PORTED), prepaid);
    }

    private static LevelAdmission levelOf(CallContext call, int partnerId) {
        return call.levels.stream().filter(level -> level.getPartnerId() == partnerId).findFirst().orElseThrow();
    }

    private static TierSettlement settlementOf(CallContext call, int levelIndex) {
        return call.settlements.stream().filter(s -> s.levelIndex() == levelIndex).findFirst().orElseThrow();
    }

    private ParityScene keep(ParityScene scene) {
        scenes.add(scene);
        return scene;
    }
}
