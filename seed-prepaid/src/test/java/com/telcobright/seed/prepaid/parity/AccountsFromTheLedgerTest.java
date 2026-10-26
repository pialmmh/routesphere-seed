package com.telcobright.seed.prepaid.parity;

import com.telcobright.seed.sessionflow.api.SessionCause;
import com.telcobright.statewalk.session.Verdict;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static com.telcobright.seed.prepaid.parity.ParityScene.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * L2b item 4 (S4) · the call on its Prepaid, its ledger caching the PURCHASES: a partner's accounts and their expiry come from the ledger,
 * not from prime-context's tree (guide G5: "a reparent or an expiry is seen late"). P2's scene — Unilever's package has half a minute left —
 * pays from the cash (12) while the ledger's purchase of 12 is valid; once the LEDGER says that purchase expired (the tree still says it
 * is valid), the cash is skipped by the order and the call is refused, nothing held.
 */
class AccountsFromTheLedgerTest {

    private ParityScene scene;

    @AfterEach
    void close() { if (scene != null) scene.close(); }

    @Test
    void theLedgersPurchaseValid_theCashPays() {
        scene = ParityScene.openOnTheLedgersPurchases(Map.of("res_2#11", "0.5"), Map.of(), Lane.ON_PREPAID);

        scene.admitted("s4-a", UNILEVER, DOMESTIC);

        assertThat(scene.balance("res_2", 12)).isEqualByComparingTo("99.40");
        assertThat(scene.balance("res_2", 13)).as("its purchase expired (the ledger's and the tree's)").isEqualByComparingTo("100");
    }

    @Test
    void theLedgersPurchaseExpired_theCashIsSkipped_eventhoughTheTreeStillSaysValid() {
        scene = ParityScene.openOnTheLedgersPurchases(Map.of("res_2#11", "0.5"), Map.of(2L, "2026-10-01 00:00:00"), Lane.ON_PREPAID);

        Verdict verdict = scene.admit(scene.call("s4-b", UNILEVER, DOMESTIC));

        assertThat(verdict.cause()).isEqualTo(SessionCause.INSUFFICIENT_BALANCE);
        assertThat(scene.balance("res_2", 12)).as("the cash of an expired purchase pays nothing").isEqualByComparingTo("100");
        assertThat(scene.rows("res_2")).isZero();
    }
}
