package com.telcobright.seed.switchledger.internal;

import com.telcobright.core.wal.WALEntryBatch;
import com.telcobright.memledger.api.MemLedger;
import com.telcobright.rtc.domainmodel.mysqlentity.PackageAccount;
import com.telcobright.seed.switchledger.LedgerLab;
import com.telcobright.seed.switchledger.spi.ExpectingLedger;
import com.telcobright.seed.switchledger.spi.ExpectingLedger.AsRead;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ARCH-0118 item 5 — S2 in the unit: it remembers, per row, the FIRST row it read FROM THE LEDGER (never one read back from its own
 * staged rows), and a row it saw none of. A ledger that checks them gets them with the batch; any other ledger — the embedded one —
 * gets exactly the calls it always got: the reads, then ONE {@code execCrud(batch)}.
 */
class RowsAsReadTest {

    private final LedgerLab lab = LedgerLab.open().schema("btcl").account("btcl", 11, 1, "OTH_ea", "10");
    private final List<String> calls = new CopyOnWriteArrayList<>();
    private final List<List<AsRead>> sentAsRead = new CopyOnWriteArrayList<>();

    @AfterEach
    void close() { lab.close(); }

    @Test
    void overALedgerThatDoesNotCheck_theUnitMakesTheCallsItAlwaysMade_theReadsThenOneExecCrud() {
        Books books = new Books(recorded(lab.start(), false), Clock.systemUTC());

        holdOneUnderTwoReads(books);

        assertThat(calls).as("the account and a row not there read from the ledger, ONE write — nothing else").containsExactly("getEntity", "getEntity", "execCrud");
        assertThat(sentAsRead).isEmpty();
    }

    @Test
    void overALedgerThatChecks_theFirstRowReadFromTheLedgerTravels_andARowSeenAbsent_neverOneReadBackFromTheUnit() {
        Books books = new Books(recorded(lab.start(), true), Clock.systemUTC());

        holdOneUnderTwoReads(books);

        assertThat(calls).as("the same reads, then the write WITH the rows as read").containsExactly("getEntity", "getEntity", "execCrud+asRead");
        List<AsRead> asRead = sentAsRead.get(0);
        assertThat(asRead).as("the rows read FROM THE LEDGER only — not h-1#L0 nor the account's second read, both read back from the unit")
            .extracting(AsRead::entity, a -> String.valueOf(a.id()))
            .containsExactly(org.assertj.core.groups.Tuple.tuple(Books.ACCOUNT, "11"), org.assertj.core.groups.Tuple.tuple(Books.RESERVE, "h-2#L0"));
        assertThat(((PackageAccount) asRead.get(0).row()).getBalanceAfter()).as("as FIRST read: 10, not the unit's own 9").isEqualByComparingTo("10");
        assertThat(asRead.get(1).row()).as("h-2#L0: seen absent").isNull();
        assertThat(lab.cachedBalance("btcl", 11)).isEqualByComparingTo("9");
    }

    /** One unit: the account read twice from the ledger around a read of the reserve row it opens, moved once, committed. */
    private static void holdOneUnderTwoReads(Books books) {
        UnitOfWork unit = books.unit();
        PackageAccount live = books.liveAccount(unit, "btcl", 11);
        books.openReserveRow(unit, "btcl", "h-1#L0", live, BigDecimal.ONE);
        books.reserveRow(unit, "btcl", "h-1#L0");                                         // read back from the unit: not remembered
        unit.read("btcl", Books.RESERVE, "h-2#L0");                                       // a row the unit does not write, seen absent
        books.moveBalance(unit, "btcl", live, Books.balanceOf(live).subtract(BigDecimal.ONE), BigDecimal.ONE.negate());
        books.liveAccount(unit, "btcl", 11);                                               // read back from the unit: not remembered
        unit.commit("hold one");
    }

    /** The lab's ledger, every call it is asked written down; {@code checks} = it says it checks the rows a batch read. */
    private MemLedger recorded(MemLedger real, boolean checks) {
        return (MemLedger) Proxy.newProxyInstance(MemLedger.class.getClassLoader(), new Class<?>[] {MemLedger.class, ExpectingLedger.class}, (proxy, method, args) -> {
            switch (method.getName()) {
                case "checksExpectations": return checks;
                case "execCrud":
                    if (args.length == 2) {
                        calls.add("execCrud+asRead");
                        @SuppressWarnings("unchecked") List<AsRead> rows = (List<AsRead>) args[1];
                        sentAsRead.add(rows);
                        return real.execCrud((WALEntryBatch) args[0]);
                    }
                    calls.add("execCrud");
                    break;
                default: calls.add(method.getName());
            }
            try {
                return method.invoke(real, args);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        });
    }
}
