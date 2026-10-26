package com.telcobright.seed.prepaid.parity;

import com.telcobright.statewalk.session.Outcome;
import com.telcobright.statewalk.session.Verdict;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static com.telcobright.seed.prepaid.parity.ParityScene.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * L2b item 2 · every business operation of a session is ONE record of the ledger's REAL WAL, over every tier it touches (the call on its
 * Prepaid: res_2's Unilever and btcl's reseller): the hold at admission, each renewal, the settlement — and the SMS's charge at once.
 * A tier that cannot pay leaves every other tier untouched: no balance moved, no hold row, no record.
 */
class OneRecordPerOperationTest {

    private ParityScene scene;

    @AfterEach
    void close() { if (scene != null) scene.close(); }

    @Test
    void theHold_eachRenewal_andTheSettlement_areOneRecordEach_overBothTiers() {
        scene = ParityScene.open(Map.of(), Lane.ON_PREPAID);
        long start = scene.lab.walRecords();

        CallContext call = scene.admitted("r1", UNILEVER, DOMESTIC);
        assertThat(scene.lab.walRecords()).as("the hold of both tiers: one record").isEqualTo(start + 1);
        assertThat(scene.lab.entriesOfTheLastRecord()).as("two accounts, two hold rows").isEqualTo(4);

        scene.answer(call);
        scene.talk(58);
        assertThat(scene.renew(call)).isEqualTo(60.0);
        assertThat(scene.lab.walRecords()).as("the renewal of both tiers: one record").isEqualTo(start + 2);
        assertThat(scene.lab.entriesOfTheLastRecord()).isEqualTo(4);

        scene.talk(30);
        scene.hangUp(call);
        assertThat(scene.lab.walRecords()).as("the settlement of both tiers: one record").isEqualTo(start + 3);
        assertThat(scene.lab.entriesOfTheLastRecord()).as("two accounts, two rows deleted").isEqualTo(4);
        assertThat(scene.rows("res_2")).isZero();
        assertThat(scene.rows("btcl")).isZero();
    }

    @Test
    void theRootCannotPay_theLeafIsUntouched_noRow_noRecord() {
        scene = ParityScene.open(Map.of("btcl#21", "0.5", "btcl#22", "0"), Lane.ON_PREPAID);   // the reseller's minutes and cash, short
        long start = scene.lab.walRecords();

        Verdict verdict = scene.admit(scene.call("r2", UNILEVER, DOMESTIC));

        assertThat(verdict.accepted()).isFalse();
        assertThat(scene.balance("res_2", 11)).as("the leaf, rated and authorized, never held").isEqualByComparingTo("2.5");
        assertThat(scene.balance("btcl", 21)).isEqualByComparingTo("0.5");
        assertThat(scene.rows("res_2")).isZero();
        assertThat(scene.rows("btcl")).isZero();
        assertThat(scene.lab.walRecords()).as("no record").isEqualTo(start);
        assertThat(scene.recorder.seen).as("the leaf's hold was asked in the unit, never written").anyMatch(s -> s.startsWith("reserve r2#L0 "));
    }

    @Test
    void theSmsChargeOfEveryTier_isOneRecord_andItsRefundOneMore() {
        scene = ParityScene.openWith(ParityScene.perEventSettings(), Map.of(), kit -> SmsLane.onPrepaid(kit, true));
        long start = scene.lab.walRecords();

        CallContext sms = scene.admitted("r3", UNILEVER, DOMESTIC);
        assertThat(scene.lab.walRecords()).as("the charge of both tiers: one record").isEqualTo(start + 1);

        sms.endCause = "SMSC_REJECTED";
        scene.flow.release(sms);
        scene.flow.record(sms, Outcome.FAILED);
        assertThat(scene.lab.walRecords()).as("the refund of both tiers: one record").isEqualTo(start + 2);
        assertThat(scene.balance("res_2", 12)).isEqualByComparingTo("100");
        assertThat(scene.balance("btcl", 22)).isEqualByComparingTo("50");
    }
}
