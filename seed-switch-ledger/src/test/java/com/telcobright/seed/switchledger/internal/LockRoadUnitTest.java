package com.telcobright.seed.switchledger.internal;

import com.telcobright.core.wal.WALEntryBatch;
import com.telcobright.memledger.api.MemLedger;
import com.telcobright.rtc.domainmodel.LevelAdmission;
import com.telcobright.rtc.domainmodel.mysqlentity.PackageAccount;
import com.telcobright.rtc.domainmodel.mysqlentity.Partner;
import com.telcobright.rtc.domainmodel.nonentity.Tenant;
import com.telcobright.seed.sessionflow.spi.LedgerPort.LedgerFault;
import com.telcobright.seed.sessionflow.spi.LedgerUnit;
import com.telcobright.seed.switchledger.LedgerLab;
import com.telcobright.seed.switchledger.dependencies.SwitchLedgerSettings;
import com.telcobright.seed.switchledger.spi.ExpectingLedger;
import com.telcobright.seed.switchledger.spi.ExpectingLedger.AsRead;
import com.telcobright.seed.switchledger.spi.LeasingLedger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * ARCH-0119 road 2 in the ENGINE: over a ledger that holds rows, a move names its rows (the account, its reserve or charge row), holds
 * them in ONE call at its first read, reads them from that hold and writes its one batch under it. Busy rows and a lost hold are a busy
 * fault of their own — nothing written, the same call works after. Off, the calls are exactly the calls the engine always made.
 *
 * <p>The ledger here is the lab's embedded one behind a recorder that also plays the lock road: what is judged is which calls the engine
 * makes, in which order, with which rows. The real server's side of the road is recall-db's proof.
 */
class LockRoadUnitTest {

    private final LedgerLab lab = LedgerLab.open().schema("btcl").account("btcl", 11, 1, "OTH_ea", "10").account("btcl", 12, 1, "OTH_ea", "10");
    private final List<String> calls = new CopyOnWriteArrayList<>();
    private boolean on = true;
    private int rowsOneHoldMayName = 16;
    private String holdAnswersBusy;
    private String writeAnswersLost;
    private boolean writeGetsNoAnswer;

    @AfterEach
    void close() { lab.close(); }

    @Test
    void aMove_holdsItsRowsInOneCall_readsThemFromTheHold_andWritesUnderIt() {
        MemLedgerPort port = portOver(recorded(lab.start()));

        assertThat(port.reserve(level(11), BigDecimal.ONE, "h-1#L0")).isPresent();

        assertThat(calls).as("ONE hold over the account and the move's own row, NO read of them by key, ONE write under the hold with nothing as read")
            .containsExactly("hold [PackageAccount 11, PackageAccountReserve h-1#L0]", "execCrud under the hold, as read []");
        assertThat(lab.cachedBalance("btcl", 11)).isEqualByComparingTo("9");
    }

    @Test
    void withTheLockRoadOff_theCallsAreExactlyTheCallsTheEngineAlwaysMade() {
        on = false;
        MemLedgerPort port = portOver(recorded(lab.start()));

        assertThat(port.reserve(level(11), BigDecimal.ONE, "h-1#L0")).isPresent();

        assertThat(calls).as("the account and its reserve row read by key, ONE write with the rows as read — no hold, no release")
            .containsExactly("getEntity", "getEntity", "execCrud, as read [PackageAccount 11, PackageAccountReserve h-1#L0]");
        assertThat(lab.cachedBalance("btcl", 11)).isEqualByComparingTo("9");
    }

    @Test
    void aUnitThatNamesNoRow_holdsNothing_evenWithTheLockRoadOn() {
        Books books = new Books(recorded(lab.start()), Clock.systemUTC());
        UnitOfWork unit = books.unit();

        PackageAccount live = books.liveAccount(unit, "btcl", 11);
        books.moveBalance(unit, "btcl", live, new BigDecimal("9"), BigDecimal.ONE.negate());
        unit.commit("move one");

        assertThat(calls).containsExactly("getEntity", "execCrud, as read [PackageAccount 11]");
    }

    @Test
    void busyRows_areABusyFaultOfTheirOwn_nothingIsWritten_andTheSameCallWorksAfter() {
        MemLedgerPort port = portOver(recorded(lab.start()));
        holdAnswersBusy = "QUEUE_FULL";

        Throwable refused = catchThrowable(() -> port.reserve(level(11), BigDecimal.ONE, "h-1#L0"));

        assertThat(refused).isInstanceOf(LedgerFault.class).hasMessageContaining("QUEUE_FULL");
        assertThat(((LedgerFault) refused).busy()).as("busy: nothing written, try again — not the broken-ledger fault").isTrue();
        assertThat(calls).as("the hold was asked once; no read, no write, nothing to give back").containsExactly("hold [PackageAccount 11, PackageAccountReserve h-1#L0]");
        assertThat(lab.cachedBalance("btcl", 11)).isEqualByComparingTo("10");

        holdAnswersBusy = null;
        assertThat(port.reserve(level(11), BigDecimal.ONE, "h-1#L0")).as("the same call, when the rows are free: the account's own lock was given back").isPresent();
        assertThat(lab.cachedBalance("btcl", 11)).isEqualByComparingTo("9");
    }

    @Test
    void aHoldLostBeforeTheWrite_isABusyFault_nothingIsWritten_nothingIsGivenBackTwice() {
        MemLedgerPort port = portOver(recorded(lab.start()));
        writeAnswersLost = "LOCK_LOST";

        Throwable refused = catchThrowable(() -> port.reserve(level(11), BigDecimal.ONE, "h-1#L0"));

        assertThat(refused).isInstanceOf(LedgerFault.class).hasMessageContaining("LOCK_LOST");
        assertThat(((LedgerFault) refused).busy()).isTrue();
        assertThat(calls).as("the ledger said the hold is gone: no release after it")
            .containsExactly("hold [PackageAccount 11, PackageAccountReserve h-1#L0]", "execCrud under the hold, as read []");
        assertThat(lab.cachedBalance("btcl", 11)).isEqualByComparingTo("10");
    }

    @Test
    void aWriteThatGotNoAnswer_givesTheRowsBack_andIsTheBrokenLedgerFault_notBusy() {
        MemLedgerPort port = portOver(recorded(lab.start()));
        writeGetsNoAnswer = true;

        Throwable fault = catchThrowable(() -> port.reserve(level(11), BigDecimal.ONE, "h-1#L0"));

        assertThat(fault).isInstanceOf(LedgerFault.class);
        assertThat(((LedgerFault) fault).busy()).as("no answer is not 'busy': the write may or may not have landed").isFalse();
        assertThat(calls).containsExactly("hold [PackageAccount 11, PackageAccountReserve h-1#L0]", "execCrud under the hold, as read []", "release");
    }

    @Test
    void aMoveThatWritesNothing_givesItsRowsBack_once() {
        MemLedgerPort port = portOver(recorded(lab.start()));

        assertThat(port.reserve(level(11), new BigDecimal("100"), "h-big#L0")).as("10 cannot fund 100: refused, nothing staged").isEmpty();

        assertThat(calls).containsExactly("hold [PackageAccount 11, PackageAccountReserve h-big#L0]", "release");
        assertThat(lab.cachedBalance("btcl", 11)).isEqualByComparingTo("10");
    }

    @Test
    void aUnitClosedWithoutItsCommit_givesItsRowsBack() {
        MemLedgerPort port = portOver(recorded(lab.start()));

        try (LedgerUnit unit = port.unit()) {
            unit.lock(List.of(level(11)));
            assertThat(unit.balanceOf(level(11))).hasValueSatisfying(balance -> assertThat(balance).isEqualByComparingTo("10"));
        }                                                                         // the caller stopped here: no commit

        assertThat(calls).as("the rows do not wait for the lease to run out").containsExactly("hold [PackageAccount 11]", "release");
    }

    @Test
    void aRowMetAfterTheHoldWasTaken_isReadByKey_andTravelsAsRead_underTheSameHold() {
        MemLedgerPort port = portOver(recorded(lab.start()));

        try (LedgerUnit unit = port.unit()) {
            unit.lock(List.of(level(11)));
            unit.reserve(level(11), BigDecimal.ONE, "a#L0", Long.MAX_VALUE);
            unit.reserve(level(12), BigDecimal.ONE, "b#L0", Long.MAX_VALUE);      // an account the unit did not name at its start
            unit.commit("two holds");
        }

        assertThat(calls).as("the hold is taken ONCE (no hold-and-wait): the second account and its row are read by key and checked as read at the write")
            .containsExactly("hold [PackageAccount 11, PackageAccountReserve a#L0]", "getEntity", "getEntity",
                "execCrud under the hold, as read [PackageAccount 12, PackageAccountReserve b#L0]");
        assertThat(lab.cachedBalance("btcl", 11)).isEqualByComparingTo("9");
        assertThat(lab.cachedBalance("btcl", 12)).isEqualByComparingTo("9");
    }

    @Test
    void accountsNamedAtTheUnitsStart_areAllInTheOneHold() {
        MemLedgerPort port = portOver(recorded(lab.start()));

        try (LedgerUnit unit = port.unit()) {
            unit.lock(List.of(level(11), level(12)));
            unit.reserve(level(11), BigDecimal.ONE, "a#L0", Long.MAX_VALUE);
            unit.reserve(level(12), BigDecimal.ONE, "b#L0", Long.MAX_VALUE);
            unit.commit("two holds");
        }

        assertThat(calls).as("both accounts and the first move's row in the one hold; only the second move's own row is read by key")
            .containsExactly("hold [PackageAccount 11, PackageAccount 12, PackageAccountReserve a#L0]", "getEntity",
                "execCrud under the hold, as read [PackageAccountReserve b#L0]");
    }

    @Test
    void moreRowsNamedThanOneHoldMayName_theFirstAreHeld_theRestAreReadAsAnyRow() {
        rowsOneHoldMayName = 1;
        MemLedgerPort port = portOver(recorded(lab.start()));

        assertThat(port.reserve(level(11), BigDecimal.ONE, "h-1#L0")).isPresent();

        assertThat(calls).containsExactly("hold [PackageAccount 11]", "getEntity", "execCrud under the hold, as read [PackageAccountReserve h-1#L0]");
    }

    // ── the lab ─────────────────────────────────────────────────────────────

    private MemLedgerPort portOver(MemLedger ledger) {
        return new MemLedgerPort(new Books(ledger, Clock.systemUTC()), AccountLocks.of(ledger, SwitchLedgerSettings.standard().lockTimeoutMs()));
    }

    private static LevelAdmission level(long accountId) {
        Partner partner = new Partner();
        partner.setIdPartner(3);
        PackageAccount account = new PackageAccount();
        account.setId(accountId);
        return new LevelAdmission(0, new Tenant("btcl"), partner, account);
    }

    /** The lab's ledger behind a recorder that plays the lock road: the reads by key, the hold, the write (under a hold or not) and the release are written down. */
    private MemLedger recorded(MemLedger real) {
        return (MemLedger) Proxy.newProxyInstance(MemLedger.class.getClassLoader(), new Class<?>[] {MemLedger.class, ExpectingLedger.class, LeasingLedger.class}, (proxy, method, args) -> {
            switch (method.getName()) {
                case "checksExpectations": return true;
                case "holdsRows": return on;
                case "rowsOneHoldMayName": return rowsOneHoldMayName;
                case "waitersOneRowMayHave": return 32;
                case "hold": {
                    @SuppressWarnings("unchecked") List<LeasingLedger.Row> rows = (List<LeasingLedger.Row>) args[0];
                    calls.add("hold " + rows.stream().map(row -> row.entity() + " " + row.id()).toList());
                    if (holdAnswersBusy != null) throw new LeasingLedger.RowsBusy(holdAnswersBusy, "the rows' queue is full");
                    Map<String, Object> held = new LinkedHashMap<>();
                    for (LeasingLedger.Row row : rows) held.put(row.tier() + "|" + row.entity() + "|" + row.id(), real.getEntity(row.tier(), row.entity(), row.id()));
                    return new LeasingLedger.Held() {
                        @Override public boolean holds(String tier, String entity, Object id) { return held.containsKey(tier + "|" + entity + "|" + id); }
                        @Override @SuppressWarnings("unchecked") public <T> T row(String tier, String entity, Object id) { return (T) held.get(tier + "|" + entity + "|" + id); }
                    };
                }
                case "release": calls.add("release"); return null;
                case "execCrud":
                    if (args.length == 3) {
                        calls.add("execCrud under the hold, as read " + keysOf(args[2]));
                        if (writeAnswersLost != null) throw new LeasingLedger.RowsBusy(writeAnswersLost, "the lease ran out");
                        if (writeGetsNoAnswer) throw new IllegalStateException("no answer within the deadline");
                        return real.execCrud((WALEntryBatch) args[1]);
                    }
                    if (args.length == 2) {
                        calls.add("execCrud, as read " + keysOf(args[1]));
                        return real.execCrud((WALEntryBatch) args[0]);
                    }
                    calls.add("execCrud");
                    break;
                case "getEntity": calls.add("getEntity"); break;
                default:
            }
            try {
                return method.invoke(real, args);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        });
    }

    @SuppressWarnings("unchecked")
    private static List<String> keysOf(Object asRead) { return ((List<AsRead>) asRead).stream().map(row -> row.entity() + " " + row.id()).toList(); }
}
