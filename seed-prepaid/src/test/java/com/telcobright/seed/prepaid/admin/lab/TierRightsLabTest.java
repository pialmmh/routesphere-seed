package com.telcobright.seed.prepaid.admin.lab;

import com.telcobright.rtc.domainmodel.mysqlentity.PackageAccountReserve;
import com.telcobright.seed.switchledger.api.LedgerAccount;
import com.telcobright.seed.switchledger.api.LedgerPurchase;
import com.telcobright.seed.switchledger.api.LedgerServiceGroup;
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

    /**
     * §15.3 (found by the product-items FULLSTACK, 2026-10-30) · A READ-ONLY entity (the service groups: loaded at the start, never written)
     * needs SELECT alone on its table — prime-context's 009 grants exactly that to the ledger's login, and asking for INSERT, UPDATE and DELETE
     * there refused the root's start. Without SELECT it is still refused, in the same words.
     */
    @Test
    void aReadOnlyEntitysTable_needsSelectAlone() throws Exception {
        assumeTrue(lab.absentBecause() == null, lab.absentBecause());
        lab.freshTiers();
        try (Connection admin = lab.labDataSource().getConnection(); Statement s = admin.createStatement()) {
            for (String tier : List.of("btcl", "res_2")) {
                s.execute("CREATE TABLE IF NOT EXISTS " + tier + ".enumservicegroup (id INT NOT NULL PRIMARY KEY, name VARCHAR(100))");
                s.execute("REVOKE ALL ON " + tier + ".enumservicegroup FROM " + PostgresPurchaseLab.LEDGER_ROLE);
                // a role with NO right on a table does not see it in information_schema (the check would say "has no table"): REFERENCES keeps
                // it visible while SELECT is still missing
                s.execute("GRANT REFERENCES ON " + tier + ".enumservicegroup TO " + PostgresPurchaseLab.LEDGER_ROLE);
            }
        }
        TierColumns check = TierColumns.of("btcl", lab.ledgerDataSource(), List.of(LedgerServiceGroup.class));

        assertThatThrownBy(() -> check.requireIn(List.of("btcl", "res_2")))
            .hasMessageContaining("btcl grants the ledger's login no SELECT on enumservicegroup")
            .hasMessageContaining("res_2 grants the ledger's login no SELECT on enumservicegroup")
            .hasMessageNotContaining("no INSERT on enumservicegroup")
            .hasMessageNotContaining("no DELETE on enumservicegroup");

        try (Connection admin = lab.labDataSource().getConnection(); Statement s = admin.createStatement()) {
            for (String tier : List.of("btcl", "res_2")) s.execute("GRANT SELECT ON " + tier + ".enumservicegroup TO " + PostgresPurchaseLab.LEDGER_ROLE);
        }
        assertThatCode(() -> check.requireIn(List.of("btcl", "res_2"))).as("SELECT alone: the ledger never writes the service groups").doesNotThrowAnyException();
    }
}
