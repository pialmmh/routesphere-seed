package com.telcobright.seed.prepaid.parity;

import com.telcobright.rtc.domainmodel.mysqlentity.PackageAccount;
import com.telcobright.seed.prepaid.api.PrepaidAdmin;
import com.telcobright.seed.sessionflow.spi.LedgerPort;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static com.telcobright.seed.prepaid.parity.ParityScene.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * L2a item 5 · {@code prepaid.admin()} — the money roads of the lane's Prepaid, on the REAL switch ledger (P20's roads through the
 * Prepaid): a credit is a DELTA through the kit's ledger port under the account's lock, over the switch ledger's existing {@code Credit}
 * (routesphere-core {@code PrepaidServiceWithCompensation.recharge} 294–383) — the next hold sees it; a reparent moves the account onto a new
 * purchase, its balance kept (347–351); a partner's accounts as the TREE names them today (S4: the catalog is the tree's until L2b) with the
 * ledger's live balance; L2b: a purchase without the application's id source is refused in words.
 */
class PrepaidAdminTest {

    private ParityScene scene;

    @AfterEach
    void close() { if (scene != null) scene.close(); }

    @Test
    void aCredit_isADeltaThroughTheLedger_andTheNextHoldSeesIt() {
        scene = ParityScene.open(java.util.Map.of(), ParityScene.Lane.ON_PREPAID);
        PrepaidAdmin admin = CallPrepaid.of(scene.kit, ParityScene.rates()).admin();

        LedgerPort.Credited credited = admin.credit("btcl", "res_2", 11, new BigDecimal("10"), "topup-1");

        assertThat(credited.before()).isEqualByComparingTo("2.5");
        assertThat(credited.after()).isEqualByComparingTo("12.5");
        assertThat(scene.recorder.seen).anyMatch(s -> s.startsWith("credit topup-1 "));
        CallContext call = scene.admitted("a-1", UNILEVER, DOMESTIC);
        assertThat(call.levels.get(0).getBalanceBefore()).as("the next hold sees the credit").isEqualByComparingTo("12.5");
        assertThat(scene.balance("res_2", 11)).isEqualByComparingTo("11.5");
    }

    @Test
    void aReparent_movesTheAccountOntoANewPurchase_itsBalanceKept() {
        scene = ParityScene.open(java.util.Map.of(), ParityScene.Lane.ON_PREPAID);
        PrepaidAdmin admin = CallPrepaid.of(scene.kit, ParityScene.rates()).admin();

        LedgerPort.Credited moved = admin.reparent("btcl", "res_2", 11, 99);

        assertThat(moved.purchaseId()).isEqualTo(99L);
        PackageAccount onTheLedger = scene.lab.ledger().getEntity("res_2", "PackageAccount", 11L);
        assertThat(onTheLedger.getIdpackagePurchase()).isEqualTo(99L);
        assertThat(scene.balance("res_2", 11)).isEqualByComparingTo("2.5");
    }

    @Test
    void aPartnersAccounts_asTheTreeNamesThem_withTheirLiveBalance_expiredOnesIncluded() {
        scene = ParityScene.open(java.util.Map.of(), ParityScene.Lane.ON_PREPAID);
        PrepaidAdmin admin = CallPrepaid.of(scene.kit, ParityScene.rates()).admin();
        scene.admitted("a-3", UNILEVER, DOMESTIC);                                         // a minute held on 11: its live balance moves

        assertThat(admin.accounts("btcl", "res_2", UNILEVER)).extracting(PrepaidAdmin.Account::id).containsExactly(11L, 12L, 13L);
        assertThat(admin.accounts("btcl", "res_2", UNILEVER)).extracting(a -> a.balance().stripTrailingZeros().toPlainString()).containsExactly("1.5", "100", "100");
        assertThat(admin.accounts("btcl", "res_2", UNILEVER).get(2).expiresAt()).isEqualTo(EXPIRED);
        assertThat(admin.accounts("btcl", "res_2", POORCO)).isEmpty();
    }

    @Test
    void aCreditOfNothing_andAnUnknownTier_areRefusedInWords() {
        scene = ParityScene.open(java.util.Map.of(), ParityScene.Lane.ON_PREPAID);
        PrepaidAdmin admin = CallPrepaid.of(scene.kit, ParityScene.rates()).admin();

        assertThatThrownBy(() -> admin.credit("btcl", "res_2", 11, BigDecimal.ZERO, "zero")).hasMessageContaining("a credit is positive");
        assertThatThrownBy(() -> admin.credit("btcl", "res_9", 11, BigDecimal.ONE, "x")).hasMessageContaining("the tier 'res_9' is not in the tree of the root 'btcl'");
        assertThat(scene.balance("res_2", 11)).isEqualByComparingTo("2.5");
    }

    /** L2b item 4: the purchase road is made (PurchaseThroughTheLedgerTest); without the application's id source it is refused in words. */
    @Test
    void aPurchaseWithoutTheApplicationsIdSource_isRefusedInWords() {
        scene = ParityScene.open(java.util.Map.of(), ParityScene.Lane.ON_PREPAID);
        PrepaidAdmin admin = CallPrepaid.of(scene.kit, ParityScene.rates()).admin();
        LedgerPort.Offer offer = new LedgerPort.Offer(1, "BDT-100", new BigDecimal("100"), scene.now(), scene.now().plusDays(30),
            java.util.List.of(new LedgerPort.Bucket("BDT", new BigDecimal("100"))));

        assertThatThrownBy(() -> admin.purchase("btcl", "res_2", UNILEVER, offer, "p-1"))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("seed-idgen IdSource");
    }
}
