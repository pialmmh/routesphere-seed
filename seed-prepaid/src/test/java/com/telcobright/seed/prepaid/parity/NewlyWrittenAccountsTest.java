package com.telcobright.seed.prepaid.parity;

import com.telcobright.rtc.domainmodel.mysqlentity.PackageAccount;
import com.telcobright.seed.sessionflow.api.RoutesphereFlow;
import com.telcobright.seed.sessionflow.api.SessionCause;
import com.telcobright.seed.sessionflow.dependencies.RoutesphereKit;
import com.telcobright.seed.switchledger.api.LedgerPurchase;
import com.telcobright.seed.switchledger.internal.Books;
import com.telcobright.seed.switchledger.internal.UnitOfWork;
import com.telcobright.statewalk.session.Verdict;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static com.telcobright.seed.prepaid.parity.ParityScene.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * I266 (routesphere {@code docs/architecture/seed-prepaid-s5-payable-accounts-hook.md} §8) · a purchase the BSS writes AFTER its partner's
 * first admission made the switch ledger's partner index. The lane names the account of the purchase the device tapped, by id (a lane's own
 * object, as S5's rows). An id the cache never saw costs ONE merge of the rows written since the ledger's last look, then the rule's list is
 * built again; a known id costs none; an id still unknown is remembered for 60 s; a merge that fails is the store's failure. The REAL switch
 * ledger over H2, on the roads that merge ({@link LedgerRoad}: the bootstrap road's gated port; the bare port over a ledger that takes out
 * its own ghosts — wifi-sphere's shared road). The BSS's write behind the cache = rows inserted into the store (purchase 16, account 56:
 * above every mark, as the BSS's ids are); its write THROUGH the shared ledger = rows in the cache itself, the port's index not told.
 */
class NewlyWrittenAccountsTest {

    /** J4: purchase 1 the sooner, purchase 3 (account 13) valid and later. */
    private static final Map<Long, String> J4 = Map.of(1L, "2027-06-01 00:00:00", 3L, "2030-01-01 00:00:00");

    /** The account of the purchase the device tapped: what the lane names for Unilever's tier. */
    private final AtomicLong tapped = new AtomicLong(13);
    private ParityScene scene;

    @AfterEach
    void close() { if (scene != null) scene.close(); }

    @ParameterizedTest
    @EnumSource(value = LedgerRoad.class, names = {"GATED", "BARE_GHOST_FREE"})
    void aPurchaseWrittenAfterTheFirstAdmission_aTapNamingIt_isHeldOnIt_oneMerge(LedgerRoad road) {
        scene = openOnTheLedgersPurchases(Map.of(), J4, road, this::tappedLane);
        scene.admitted("i266-first", UNILEVER, DOMESTIC);                         // the partner's first admission: its index is made
        bssSellsBehindTheCache(56);

        tapped.set(56);
        scene.admitted("i266-new", UNILEVER, DOMESTIC);

        assertThat(scene.balance("res_2", 56)).as("held on the new purchase's account").isEqualByComparingTo("99");
        assertThat(scene.recorder.merges).as("ONE merge").hasValue(1);
    }

    @ParameterizedTest
    @EnumSource(LedgerRoad.class)
    void aSaleThroughTheSharedLedger_theCacheHoldsIt_theIndexDoesNot_isHeldOnIt_zeroMerges(LedgerRoad road) {
        scene = openOnTheLedgersPurchases(Map.of(), J4, road, this::tappedLane);
        scene.admitted("i266-first", UNILEVER, DOMESTIC);                         // the index is made
        bssSellsThroughTheLedger(56);

        tapped.set(56);
        scene.admitted("i266-through", UNILEVER, DOMESTIC);

        assertThat(scene.balance("res_2", 56)).as("held on it: the index learnt it").isEqualByComparingTo("99");
        assertThat(scene.recorder.merges).as("nothing to merge: the cache held it").hasValue(0);
    }

    @ParameterizedTest
    @EnumSource(value = LedgerRoad.class, names = {"GATED", "BARE_GHOST_FREE"})
    void anIdUnknownEvenAfterTheMerge_isTheNoBalanceCause(LedgerRoad road) {
        scene = openOnTheLedgersPurchases(Map.of(), J4, road, this::tappedLane);
        tapped.set(999);

        Verdict verdict = scene.admit(scene.call("i266-none", UNILEVER, DOMESTIC));

        assertThat(verdict.cause()).isEqualTo(SessionCause.INSUFFICIENT_BALANCE);
        assertThat(scene.recorder.merges).as("it was merged for once").hasValue(1);
        assertThat(scene.rows("res_2")).isZero();
    }

    @ParameterizedTest
    @EnumSource(LedgerRoad.class)
    void aKnownButExpiredId_isDroppedAtOnce_zeroMerges(LedgerRoad road) {
        scene = openOnTheLedgersPurchases(Map.of(), Map.of(), road, this::tappedLane);   // purchase 3 (account 13) EXPIRED
        tapped.set(13);

        Verdict verdict = scene.admit(scene.call("i266-expired", UNILEVER, DOMESTIC));

        assertThat(verdict.cause()).isEqualTo(SessionCause.INSUFFICIENT_BALANCE);
        assertThat(scene.recorder.merges).as("a known id costs no merge").hasValue(0);
        assertThat(scene.balance("res_2", 13)).isEqualByComparingTo("100");
    }

    @ParameterizedTest
    @EnumSource(value = LedgerRoad.class, names = {"GATED", "BARE_GHOST_FREE"})
    void twoTapsNamingTheSameNewId_oneMerge(LedgerRoad road) {
        scene = openOnTheLedgersPurchases(Map.of(), J4, road, this::tappedLane);
        scene.admitted("i266-first", UNILEVER, DOMESTIC);
        bssSellsBehindTheCache(56);
        tapped.set(56);

        scene.admitted("i266-tap-1", UNILEVER, DOMESTIC);
        scene.admitted("i266-tap-2", UNILEVER, DOMESTIC);

        assertThat(scene.balance("res_2", 56)).as("both held on it").isEqualByComparingTo("98");
        assertThat(scene.recorder.merges).as("the second tap finds it known").hasValue(1);
    }

    @ParameterizedTest
    @EnumSource(value = LedgerRoad.class, names = {"GATED", "BARE_GHOST_FREE"})
    void tenTapsOnOneUnknownId_insideAMinute_oneMerge_allTheNoBalanceCause_andOneMoreAfterTheMinute(LedgerRoad road) {
        scene = openOnTheLedgersPurchases(Map.of(), J4, road, this::tappedLane);
        tapped.set(999);

        for (int tap = 1; tap <= 10; tap++) {
            assertThat(scene.admit(scene.call("i266-ten-" + tap, UNILEVER, DOMESTIC)).cause()).as("tap " + tap).isEqualTo(SessionCause.INSUFFICIENT_BALANCE);
            scene.talk(5);                                                         // ten taps inside 50 s
        }
        assertThat(scene.recorder.merges).as("ONE merge for ten taps").hasValue(1);

        scene.talk(11);                                                            // 61 s after the merge
        scene.admit(scene.call("i266-ten-11", UNILEVER, DOMESTIC));
        assertThat(scene.recorder.merges).as("remembered for 60 s, not longer").hasValue(2);
    }

    @Test
    void aMergeThatFails_isTheStoresFailure_neverNoBalance_theAccountUntouched() {
        scene = openOnTheLedgersPurchases(Map.of(), J4, LedgerRoad.GATED, this::tappedLane);
        scene.admitted("i266-first", UNILEVER, DOMESTIC);
        bssSellsBehindTheCache(56);
        int heldBefore = scene.rows("res_2");
        scene.recorder.mergeFails = new IllegalStateException("the store cannot be read");
        tapped.set(56);

        CallContext call = scene.call("i266-fail", UNILEVER, DOMESTIC);
        Verdict verdict = scene.admit(call);

        assertThat(verdict.cause()).as("the store's failure word, never 'no balance'").isEqualTo(SessionCause.BILLING_SYSTEM_ERROR);
        assertThat(call.systemFault).isEqualTo(SessionCause.BILLING_SYSTEM_ERROR);
        assertThat(scene.rows("res_2")).as("nothing more held").isEqualTo(heldBefore);
        assertThat(scene.lab.dbBalance("res_2", 56)).as("the new account untouched").isEqualByComparingTo("100");
    }

    @ParameterizedTest
    @EnumSource(value = LedgerRoad.class, names = {"GATED", "BARE_GHOST_FREE"})
    void aStoreThatCannotBeRead_theRealMergeIsTheStoresFailure_neverNoBalance(LedgerRoad road) {
        scene = openOnTheLedgersPurchases(Map.of(), J4, road, this::tappedLane);
        bssSellsBehindTheCache(56);
        scene.lab.execute("ALTER TABLE res_2.packagepurchase RENAME TO packagepurchase_gone");   // the purchases cannot be read
        tapped.set(56);

        CallContext call = scene.call("i266-store", UNILEVER, DOMESTIC);
        Verdict verdict = scene.admit(call);

        assertThat(verdict.cause()).as("the store's failure word, never 'no balance'").isEqualTo(SessionCause.BILLING_SYSTEM_ERROR);
        assertThat(scene.rows("res_2")).as("nothing held").isZero();
    }

    @Test
    void overTheEmbeddedStore_theBarePortMergesNothing_aRowBehindTheCacheStaysUnseen() {
        scene = openOnTheLedgersPurchases(Map.of(), J4, LedgerRoad.BARE, this::tappedLane);
        scene.admitted("i266-first", UNILEVER, DOMESTIC);
        bssSellsBehindTheCache(56);
        tapped.set(56);

        Verdict verdict = scene.admit(scene.call("i266-bare", UNILEVER, DOMESTIC));

        assertThat(verdict.cause()).as("its merge needs the gate the bootstrap road's port holds: nothing new here").isEqualTo(SessionCause.INSUFFICIENT_BALANCE);
        assertThat(scene.recorder.merges).hasValue(0);
    }

    /** The BSS sells Unilever a package BEHIND the cache: purchase 16 and its account ({@code TF_min} 100), written to the store only. */
    private void bssSellsBehindTheCache(long accountId) {
        scene.lab.purchase("res_2", 16, UNILEVER, "2030-01-01 00:00:00");
        scene.lab.account("res_2", accountId, 16, "TF_min", "100");
    }

    /**
     * The BSS sells THROUGH the shared ledger (recall-db applies a sale before it answers): purchase 16 and its account land in the cache
     * itself, in one unit — and the switch ledger's kept partner index is not told (another client wrote them).
     */
    private void bssSellsThroughTheLedger(long accountId) {
        UnitOfWork unit = new UnitOfWork(scene.lab.ledger());
        unit.insert("res_2", Books.PURCHASE, 16L, LedgerPurchase.made(16, 1, UNILEVER, LocalDateTime.of(2026, 1, 1, 0, 0), LATER, BigDecimal.ZERO));
        com.telcobright.seed.switchledger.api.LedgerAccount account = new com.telcobright.seed.switchledger.api.LedgerAccount();   // the cache's own row type
        account.setId(accountId);
        account.setIdpackagePurchase(16L);
        account.setName("TF_min-" + accountId);
        account.setUom("TF_min");
        account.setBalanceBefore(BigDecimal.ZERO);
        account.setBalanceAfter(new BigDecimal("100"));
        account.setLastAmount(new BigDecimal("100"));
        unit.insert("res_2", Books.ACCOUNT, accountId, account);
        unit.commit("the BSS's sale through the shared ledger");
    }

    /** The call's lane on its Prepaid, Unilever's tier narrowed to the tapped account by id; every other tier's list unchanged. */
    private RoutesphereFlow<CallContext> tappedLane(RoutesphereKit kit) {
        return CallLane.onPrepaid(kit, rates(), Map.of(DOMESTIC, PORTED), CallPrepaid.of(kit, rates(),
            (call, tier, fundable) -> tier.partner().getIdPartner() == UNILEVER ? List.of(account(tapped.get())) : fundable));
    }

    private static PackageAccount account(long id) {
        PackageAccount account = new PackageAccount();
        account.setId(id);
        return account;
    }
}
