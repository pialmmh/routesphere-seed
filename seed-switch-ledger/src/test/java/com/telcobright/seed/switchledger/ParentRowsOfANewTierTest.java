package com.telcobright.seed.switchledger;

import com.telcobright.memledger.api.MemLedger;
import com.telcobright.seed.switchledger.dependencies.SwitchLedgerSettings;
import com.telcobright.seed.switchledger.dependencies.SwitchLedgers;
import com.telcobright.seed.switchledger.internal.MemLedgerPort;
import com.telcobright.seed.switchledger.internal.OwnedMemLedgerPort;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P1-G item 3 · a reseller tier created live brings a row OUTSIDE its own schema: its own account at its PARENT (the packageaccount row
 * the provisioning makes in the parent's schema with the tier). After the tier's schema, the parent's new row is in the cache too —
 * mem-ledger's {@code reloadNewEntities()} (P2 gap 8: ACCOUNT_NOT_FOUND at the parent before) — and a settled reserve whose row the
 * write-behind has not deleted yet is NOT brought back. MemLedger on H2 in memory, as the lab's; the tier's schema by a stand-in (the
 * pinned mem-ledger has no {@code addDatabase}, item 13).
 */
class ParentRowsOfANewTierTest {

    private final LedgerLab lab = LedgerLab.open().schema("btcl").schema("btcl_res_9").account("btcl", 1, 10, "BDT", "100.00");

    @AfterEach
    void close() throws Exception { lab.close(); }

    /** The root's port, built over btcl alone; the tier's schema "loaded" by a stand-in of addDatabase. */
    private OwnedMemLedgerPort portOver(MemLedger memLedger) {
        MemLedgerPort port = (MemLedgerPort) SwitchLedgers.memLedger(memLedger, SwitchLedgerSettings.standard(), Clock.systemUTC());
        return new OwnedMemLedgerPort(port, memLedger, Set.of("btcl"), tier -> true);
    }

    @Test
    void aNewTiersOwnAccountAtItsParent_isInTheCache_onceTheTierIsAdded() {
        OwnedMemLedgerPort owned = portOver(lab.start());
        lab.account("btcl", 9, 90, "BDT", "50.00");                                   // the provisioning: res_9's own account in btcl
        assertThat(lab.cachedBalance("btcl", 9)).as("before: not in the parent's cache — ACCOUNT_NOT_FOUND").isNull();

        owned.tierAdded("btcl_res_9");

        assertThat(lab.cachedBalance("btcl", 9)).as("the parent's new row, with the tier").isEqualByComparingTo("50.00");
        assertThat(lab.cachedBalance("btcl", 1)).as("an old row as it was").isEqualByComparingTo("100.00");
    }

    @Test
    void aSettledReserveTheWriteBehindHasNotDeletedYet_isNotBroughtBack_aLiveOneStays() {
        lab.reserveRow("btcl", "s-1#L0", 1, 10, "BDT", "2.00", "2026-10-24 00:00:00");    // a reserve the ledger holds (loaded at its start)
        OwnedMemLedgerPort owned = portOver(lab.start());
        lab.reserveRow("btcl", "s-7#L0", 1, 10, "BDT", "5.00", "2026-10-24 00:00:00");    // gone from the cache, still in the table

        owned.tierAdded("btcl_res_9");

        assertThat(lab.cachedRow("btcl", "s-7#L0")).as("no ghost reserve: the reaper would give its money back twice").isNull();
        assertThat(lab.cachedRow("btcl", "s-1#L0")).as("the reserve the ledger holds stays").isNotNull();
    }
}
