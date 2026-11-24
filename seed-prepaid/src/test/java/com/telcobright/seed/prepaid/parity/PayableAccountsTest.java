package com.telcobright.seed.prepaid.parity;

import com.telcobright.rtc.domainmodel.mysqlentity.PackageAccount;
import com.telcobright.seed.prepaid.api.Prepaid;
import com.telcobright.seed.sessionflow.api.RoutesphereFlow;
import com.telcobright.seed.sessionflow.api.SessionCause;
import com.telcobright.seed.sessionflow.dependencies.RoutesphereKit;
import com.telcobright.statewalk.session.Verdict;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static com.telcobright.seed.prepaid.parity.ParityScene.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * S5 (A-0230 R1; routesphere {@code docs/architecture/seed-prepaid-s5-payable-accounts-hook.md}) · a lane NARROWS the accounts the rule
 * lets pay THIS request — the WiFi's "the account of the purchase the device tapped" — shown on the call's rows. Unilever (701) holds
 * 11 {@code TF_min} 2.5 on purchase 1, 12 {@code BDT} 100 on purchase 2 and 13 {@code TF_min} 100 on purchase 3. J4 makes purchase 1 the
 * SOONER and purchase 3 valid and LATER, so the rule lets 11, 13 and the cash 12 pay, and the catalog's soonest (11) pays by default.
 */
class PayableAccountsTest {

    /** J4: purchase 1 (account 11) the sooner, purchase 3 (account 13) valid and later — two packages of one unit. */
    private static final Map<Long, String> J4 = Map.of(1L, "2027-06-01 00:00:00", 3L, "2030-01-01 00:00:00");

    private ParityScene scene;

    @AfterEach
    void close() { if (scene != null) scene.close(); }

    @Test
    void j4_noHook_theCatalogsSoonestPays() {
        scene = openOnTheLedgersPurchases(Map.of(), J4, Lane.ON_PREPAID);

        scene.admitted("s5-a", UNILEVER, DOMESTIC);

        assertThat(scene.balance("res_2", 11)).as("the soonest package holds the minute").isEqualByComparingTo("1.5");
        assertThat(scene.balance("res_2", 13)).isEqualByComparingTo("100");
    }

    @Test
    void j4_theHookNamesTheTappedPurchase_itAlonePays() {
        scene = openOnTheLedgersPurchases(Map.of(), J4, narrowedTo(13L));

        scene.admitted("s5-b", UNILEVER, DOMESTIC);

        assertThat(scene.balance("res_2", 13)).as("the tapped purchase holds the minute").isEqualByComparingTo("99");
        assertThat(scene.balance("res_2", 11)).as("the catalog's soonest is untouched").isEqualByComparingTo("2.5");
    }

    @Test
    void edge_theWholeList_isTheDefault() {
        scene = openOnTheLedgersPurchases(Map.of(), J4, kit -> lane(kit, (call, tier, fundable) -> fundable));

        scene.admitted("s5-c", UNILEVER, DOMESTIC);

        assertThat(scene.balance("res_2", 11)).isEqualByComparingTo("1.5");
        assertThat(scene.balance("res_2", 13)).isEqualByComparingTo("100");
    }

    @Test
    void edge_theLanesOrderDecides_notTheCatalogs() {
        scene = openOnTheLedgersPurchases(Map.of(), J4, kit -> lane(kit, (call, tier, fundable) -> List.of(byId(fundable, 13L), byId(fundable, 11L))));

        scene.admitted("s5-o", UNILEVER, DOMESTIC);

        assertThat(scene.balance("res_2", 13)).as("the lane named it first").isEqualByComparingTo("99");
        assertThat(scene.balance("res_2", 11)).isEqualByComparingTo("2.5");
    }

    @Test
    void edge_anEmptyAnswer_noneAndNoOtherPays() {
        scene = openOnTheLedgersPurchases(Map.of(), J4, kit -> lane(kit, (call, tier, fundable) -> List.of()));

        Verdict verdict = scene.admit(scene.call("s5-d", UNILEVER, DOMESTIC));

        assertThat(verdict.cause()).isEqualTo(SessionCause.INSUFFICIENT_BALANCE);
        assertThat(scene.rows("res_2")).as("nothing held").isZero();
        assertThat(scene.balance("res_2", 11)).isEqualByComparingTo("2.5");
        assertThat(scene.balance("res_2", 12)).as("not even the cash").isEqualByComparingTo("100");
        assertThat(scene.balance("res_2", 13)).isEqualByComparingTo("100");
    }

    @Test
    void edge_aNullAnswer_isAnEmptyOne() {
        scene = openOnTheLedgersPurchases(Map.of(), J4, kit -> lane(kit, (call, tier, fundable) -> null));

        Verdict verdict = scene.admit(scene.call("s5-n", UNILEVER, DOMESTIC));

        assertThat(verdict.cause()).isEqualTo(SessionCause.INSUFFICIENT_BALANCE);
        assertThat(scene.rows("res_2")).isZero();
    }

    @Test
    void edge_anExpiredPurchase_isDropped_nothingPays() {
        scene = openOnTheLedgersPurchases(Map.of(), Map.of(), kit -> lane(kit, (call, tier, fundable) -> List.of(account(13L))));   // purchase 3 EXPIRED

        Verdict verdict = scene.admit(scene.call("s5-e", UNILEVER, DOMESTIC));

        assertThat(verdict.cause()).as("13 is not the rule's: dropped, and nothing is left").isEqualTo(SessionCause.INSUFFICIENT_BALANCE);
        assertThat(scene.rows("res_2")).isZero();
        assertThat(scene.balance("res_2", 13)).isEqualByComparingTo("100");
    }

    @Test
    void edge_anotherPartnersAccount_isDropped_theListedRestPays() {
        scene = openOnTheLedgersPurchases(Map.of(), J4, kit -> lane(kit, (call, tier, fundable) -> List.of(account(41L), byId(fundable, 12L))));

        scene.admitted("s5-g", UNILEVER, DOMESTIC);

        assertThat(scene.balance("res_2", 12)).as("the listed cash pays").isEqualByComparingTo("99.40");
        assertThat(scene.balance("res_2", 41)).as("PackagesOnly's package is never touched").isEqualByComparingTo("100");
        assertThat(scene.balance("res_2", 11)).isEqualByComparingTo("2.5");
    }

    @Test
    void renewal_staysOnTheNarrowedAccount() {
        scene = openWith(handlerSettings(), Map.of(), kit -> lane(kit, (call, tier, fundable) -> List.of(byId(fundable, 12L))));
        CallContext call = scene.admitted("s5-r", UNILEVER, DOMESTIC);                        // the cash holds 0.60
        scene.answer(call);

        assertThat(scene.renew(call)).as("a whole minute, on the cash again").isEqualTo(60.0);
        assertThat(scene.balance("res_2", 12)).isEqualByComparingTo("98.80");
        assertThat(scene.balance("res_2", 11)).as("the package never pays").isEqualByComparingTo("2.5");
    }

    private static Function<RoutesphereKit, RoutesphereFlow<CallContext>> narrowedTo(long accountId) {
        return kit -> lane(kit, (call, tier, fundable) -> List.of(byId(fundable, accountId)));
    }

    /** The call's lane on its Prepaid with the test's narrowing — of Unilever's tier only (see {@link #onUnilever}). */
    private static RoutesphereFlow<CallContext> lane(RoutesphereKit kit, Prepaid.PayableAccounts<CallContext> narrowing) {
        return CallLane.onPrepaid(kit, rates(), Map.of(DOMESTIC, PORTED), CallPrepaid.of(kit, rates(), onUnilever(narrowing)));
    }

    /**
     * The hook is asked once per PAYING TIER: Unilever's call walks the reseller's tier on btcl (accounts 21, 22) and Unilever's on res_2.
     * A lane narrows the tier that owns the purchase and leaves the others as the rule made them — as the WiFi will (the subscriber's tier).
     */
    private static Prepaid.PayableAccounts<CallContext> onUnilever(Prepaid.PayableAccounts<CallContext> narrowing) {
        return (call, tier, fundable) -> tier.partner().getIdPartner() == UNILEVER ? narrowing.narrow(call, tier, fundable) : fundable;
    }

    /** An account the rule did not list: a lane's own object with only an id. */
    private static PackageAccount account(long id) {
        PackageAccount account = new PackageAccount();
        account.setId(id);
        return account;
    }

    private static PackageAccount byId(List<PackageAccount> fundable, long id) {
        return fundable.stream().filter(a -> a.getId() == id).findFirst().orElseThrow();
    }
}
