package com.telcobright.seed.prepaid.admin.lab;

import com.telcobright.rtc.domainmodel.mysqlentity.PackageAccountReserve;
import com.telcobright.seed.switchledger.api.LedgerAccount;
import com.telcobright.seed.switchledger.api.LedgerPurchase;
import com.telcobright.seed.switchledger.internal.TierColumns;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.Statement;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * C4 on the lab PostgreSQL 16 (127.0.0.1:7544; database l2c_lab, schemas btcl, res_2, res_3; the ledger as the role l2c_ledger with the
 * purchase road's rights: no DELETE on an account nor on a purchase) · the ledger's start check reads its login's RIGHTS too: the cached
 * ledger may write every table it caches, so a right its login lacks would refuse a row at the write-behind and stop it — the start is
 * refused instead, in words naming the tier, the right and the table. With the rights given, it starts.
 */
class TierRightsLabTest {

    private final PostgresPurchaseLab lab = new PostgresPurchaseLab();

    @AfterEach
    void nothingIsLeft() throws Exception { lab.close(); }

    @Test
    void aRightTheLedgersLoginLacks_refusesItsStart_inWords_andGivenItStarts() throws Exception {
        assumeTrue(lab.absentBecause() == null, lab.absentBecause());
        lab.freshTiers();
        TierColumns check = TierColumns.of("btcl", lab.ledgerDataSource(), List.of(LedgerAccount.class, PackageAccountReserve.class, LedgerPurchase.class));

        assertThatThrownBy(() -> check.requireIn(List.of("btcl", "res_2")))
            .hasMessageContaining("btcl grants the ledger's login no DELETE on packageaccount")
            .hasMessageContaining("btcl grants the ledger's login no DELETE on packagepurchase")
            .hasMessageContaining("res_2 grants the ledger's login no DELETE on packagepurchase")
            .hasMessageNotContaining("on packageaccountreserve")
            .hasMessageContaining("primecontext.provision.ledger-*");

        try (Connection admin = lab.labDataSource().getConnection(); Statement s = admin.createStatement()) {
            for (String tier : List.of("btcl", "res_2")) s.execute("GRANT DELETE ON " + tier + ".packageaccount, " + tier + ".packagepurchase TO " + PostgresPurchaseLab.LEDGER_ROLE);
        }
        assertThatCode(() -> check.requireIn(List.of("btcl", "res_2"))).as("every right given").doesNotThrowAnyException();
    }
}
