package com.telcobright.seed.switchledger;

import com.telcobright.memledger.api.MemLedger;
import com.telcobright.rtc.domainmodel.LevelAdmission;
import com.telcobright.rtc.domainmodel.mysqlentity.PackageAccount;
import com.telcobright.rtc.domainmodel.mysqlentity.Partner;
import com.telcobright.rtc.domainmodel.nonentity.Tenant;
import com.telcobright.seed.sessionflow.spi.LedgerPort;
import com.telcobright.seed.switchledger.dependencies.SwitchLedgerSettings;
import com.telcobright.seed.switchledger.dependencies.SwitchLedgers;
import com.telcobright.seed.switchledger.internal.MemLedgerPort;
import com.telcobright.seed.switchledger.internal.OwnedMemLedgerPort;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L2b item 2 · the root's port a kit from the bootstrap is handed ({@code SwitchLedgerMaker} → {@link OwnedMemLedgerPort}) takes EVERY verb
 * of the switch ledger: L2a's charge, refund, credit and reparent were not passed through it (the port's defaults refused them in words:
 * a CHARGE_NOW lane or a credit road on the bootstrap kit could not move money); and its unit is the switch ledger's (one record).
 */
class OwnedPortTakesEveryVerbTest {

    private final LedgerLab lab = LedgerLab.open().schema("btcl").account("btcl", 1, 10, "BDT", "10");

    @AfterEach
    void close() { lab.close(); }

    @Test
    void chargeRefundCreditReparent_andAUnit_reachTheSwitchLedger() {
        MemLedger memLedger = lab.start();
        MemLedgerPort port = (MemLedgerPort) SwitchLedgers.memLedger(memLedger, SwitchLedgerSettings.standard(), Clock.systemUTC());
        OwnedMemLedgerPort owned = new OwnedMemLedgerPort(port, memLedger, Set.of("btcl"), tier -> true);
        LevelAdmission level = level();

        assertThat(owned.charge(level, new BigDecimal("2"), "c1#L0")).isPresent();
        level.setDebitReference("c1#L0");
        level.setReservedAmount(new BigDecimal("2"));
        owned.refund(level, new BigDecimal("0.5"), "partly back");
        assertThat(owned.credit(level, new BigDecimal("1"), "topup").after()).isEqualByComparingTo("9.5");
        assertThat(owned.reparent(level, 77).purchaseId()).isEqualTo(77);
        long before = lab.walRecords();
        try (var unit = owned.unit()) {
            unit.lock(java.util.List.of(level));
            unit.charge(level, BigDecimal.ONE, "c2#L0");
            unit.commit("one more charge");
        }
        assertThat(lab.walRecords()).isEqualTo(before + 1);
        assertThat(lab.cachedBalance("btcl", 1)).isEqualByComparingTo("8.5");
        owned.close();
    }

    private static LevelAdmission level() {
        Partner partner = new Partner();
        partner.setIdPartner(3);
        PackageAccount account = new PackageAccount();
        account.setId(1L);
        return new LevelAdmission(0, new Tenant("btcl"), partner, account);
    }
}
