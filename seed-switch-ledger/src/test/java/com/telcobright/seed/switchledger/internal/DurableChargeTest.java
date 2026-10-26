package com.telcobright.seed.switchledger.internal;

import com.telcobright.rtc.domainmodel.LevelAdmission;
import com.telcobright.rtc.domainmodel.mysqlentity.PackageAccount;
import com.telcobright.rtc.domainmodel.mysqlentity.Partner;
import com.telcobright.rtc.domainmodel.nonentity.Tenant;
import com.telcobright.seed.sessionflow.api.TierSettlement;
import com.telcobright.seed.sessionflow.spi.LedgerPort;
import com.telcobright.seed.sessionflow.spi.LedgerPort.Reservation;
import com.telcobright.seed.switchledger.LedgerLab;
import com.telcobright.seed.switchledger.dependencies.SwitchLedgerSettings;
import com.telcobright.seed.switchledger.dependencies.SwitchLedgers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L2b item 2b · a final charge (CHARGE_NOW) carries its DURABLE reference row in the SAME unit as its debit (one WAL record), and its
 * refund marks it in the same unit as its credit: a process that dies between the charge and its answer, asked again after its restart,
 * charges nothing more; a refund after a restart finds its charge, and a second refund after another restart moves nothing. The REAL
 * MemLedger over H2: a restart = a new MemLedger over the same database and WAL, a new switch ledger (its memory empty).
 */
class DurableChargeTest {

    private final LedgerLab lab = LedgerLab.open().schema("btcl").account("btcl", 1, 10, "BDT", "10").chargeReferences();
    private LedgerPort port;

    @AfterEach
    void close() { lab.close(); }

    @Test
    void theChargeAndItsReference_areOneRecord_andARepeatAfterARestartChargesNothing() {
        lab.start();
        port = portOverTheLedger();
        long before = lab.walRecords();

        Optional<Reservation> first = port.charge(level(), new BigDecimal("2"), "sms-1#L0");
        assertThat(first).isPresent();
        assertThat(lab.walRecords()).as("the debit and its reference: ONE record").isEqualTo(before + 1);
        assertThat(lab.entriesOfTheLastRecord()).isEqualTo(2);

        restart();                                                                          // died before the answer reached the lane
        Optional<Reservation> again = port.charge(level(), new BigDecimal("2"), "sms-1#L0");

        assertThat(again).isPresent();
        assertThat(again.get().repeated()).as("the first answer, repeated").isTrue();
        assertThat(again.get().balanceAfter()).isEqualByComparingTo("8");
        assertThat(lab.cachedBalance("btcl", 1)).as("charged once").isEqualByComparingTo("8");
    }

    @Test
    void aRefundAfterARestart_findsItsCharge_andASecondRefundAfterAnotherRestartMovesNothing() {
        lab.start();
        port = portOverTheLedger();
        port.charge(level(), new BigDecimal("2"), "sms-2#L0");

        restart();
        TierSettlement refunded = port.refund(charged("sms-2#L0", "2"), new BigDecimal("2"), "SUBMIT_FAILED");
        assertThat(refunded.returned()).isEqualByComparingTo("2");
        assertThat(lab.cachedBalance("btcl", 1)).isEqualByComparingTo("10");

        restart();
        TierSettlement again = port.refund(charged("sms-2#L0", "2"), new BigDecimal("2"), "SUBMIT_FAILED");
        assertThat(again.returned()).as("the first refund's answer").isEqualByComparingTo("2");
        assertThat(lab.cachedBalance("btcl", 1)).as("given back once").isEqualByComparingTo("10");
    }

    private void restart() {
        lab.restart();
        port = portOverTheLedger();
    }

    private LedgerPort portOverTheLedger() { return SwitchLedgers.over(lab.ledger(), SwitchLedgerSettings.standard(), Clock.systemUTC()).port(); }

    private static LevelAdmission level() {
        Partner partner = new Partner();
        partner.setIdPartner(4);
        PackageAccount account = new PackageAccount();
        account.setId(1L);
        return new LevelAdmission(0, new Tenant("btcl"), partner, account);
    }

    /** The tier as the lane recorded its charge: its reference and what was taken. */
    private static LevelAdmission charged(String reference, String amount) {
        LevelAdmission level = level();
        level.setDebitReference(reference);
        level.setReservedAmount(new BigDecimal(amount));
        return level;
    }
}
