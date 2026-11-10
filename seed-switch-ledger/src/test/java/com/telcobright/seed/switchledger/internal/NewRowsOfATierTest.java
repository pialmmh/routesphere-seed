package com.telcobright.seed.switchledger.internal;

import com.telcobright.core.wal.WALEntry;
import com.telcobright.core.wal.WALEntryBatch;
import com.telcobright.memledger.api.MemLedger;
import com.telcobright.rtc.domainmodel.mysqlentity.PackageAccountReserve;
import com.telcobright.seed.switchledger.LedgerLab;
import com.telcobright.seed.switchledger.spi.GhostFreeReloads;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ARCH-0118 item 3 — S1 in the client. Two applications write through one ledger: a reserve the OTHER one inserts between this merge's
 * two looks (its keys before the reload, its keys after) is that application's held money, never a ghost. Over a ledger that takes out
 * its own ghosts (a recall-db whose Health says "ghosts") the merge takes none out itself, and keeps it; over one that does not (the
 * embedded MemLedger, an older server) today's client-side rule stays — right only while ONE application writes the root's reserves.
 * Deterministic: the ledger here lets the second client in at the door of its reload.
 */
class NewRowsOfATierTest {

    private static final String THE_OTHER_APPS_HOLD = "other-app#L0";

    private final LedgerLab lab = LedgerLab.open().schema("btcl").account("btcl", 11, 1, "OTH_ea", "100");

    @AfterEach
    void close() { lab.close(); }

    @Test
    void overALedgerThatTakesOutItsOwnGhosts_aReserveAnotherClientInsertsDuringTheReload_keepsItsRow() {
        MemLedger ledger = aSecondClientWritesAtTheReload(lab.start(), true);

        NewRowsOfATier.merge(ledger);

        assertThat(lab.cachedRow("btcl", THE_OTHER_APPS_HOLD)).as("the other application's held money keeps its row").isNotNull();
    }

    @Test
    void overALedgerThatDoesNot_todaysRuleStays_andTakesTheNewReserveForAGhost() {
        MemLedger ledger = aSecondClientWritesAtTheReload(lab.start(), false);

        NewRowsOfATier.merge(ledger);

        assertThat(lab.cachedRow("btcl", THE_OTHER_APPS_HOLD)).as("one writer per instance: a key that was not there before is a ghost").isNull();
    }

    /** The ledger, its reload letting a second client insert a reserve first; it says whether it takes out its own ghosts. */
    private static MemLedger aSecondClientWritesAtTheReload(MemLedger shared, boolean takesOutGhosts) {
        return (MemLedger) Proxy.newProxyInstance(MemLedger.class.getClassLoader(), new Class<?>[] {MemLedger.class, GhostFreeReloads.class}, (proxy, method, args) -> {
            if (method.getName().equals("takesOutGhosts")) return takesOutGhosts;
            if (method.getName().equals("reloadNewRows")) theOtherApplicationHolds(shared);
            try {
                return method.invoke(shared, args);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        });
    }

    private static void theOtherApplicationHolds(MemLedger shared) {
        PackageAccountReserve hold = new PackageAccountReserve();
        hold.setChannelCallUuid(THE_OTHER_APPS_HOLD);
        hold.setIdPackageAccount(11L);
        hold.setIdPackagePurchase(1L);
        hold.setName("SESSION-Reserve-" + THE_OTHER_APPS_HOLD);
        hold.setReserveUnit(new BigDecimal("10"));
        hold.setUom("OTH_ea");
        hold.setTime("2026-11-10T00:00:00");
        WALEntry insert = new WALEntry();
        insert.setDbName("btcl");
        insert.setEntityType(Books.RESERVE);
        insert.setOperationType(WALEntry.OperationType.INSERT);
        insert.setEntityId(THE_OTHER_APPS_HOLD);
        insert.setEntityData(hold);
        assertThat(shared.execCrud(new WALEntryBatch("OTHER_APP_1", List.of(insert))).isSuccess()).as("the other application's hold").isTrue();
    }
}
