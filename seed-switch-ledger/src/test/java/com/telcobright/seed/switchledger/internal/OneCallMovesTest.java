package com.telcobright.seed.switchledger.internal;

import com.telcobright.memledger.api.MemLedger;
import com.telcobright.rtc.domainmodel.LevelAdmission;
import com.telcobright.rtc.domainmodel.mysqlentity.PackageAccount;
import com.telcobright.rtc.domainmodel.mysqlentity.Partner;
import com.telcobright.rtc.domainmodel.nonentity.Tenant;
import com.telcobright.seed.sessionflow.spi.LedgerPort.LedgerFault;
import com.telcobright.seed.switchledger.LedgerLab;
import com.telcobright.seed.switchledger.dependencies.SwitchLedgerSettings;
import com.telcobright.seed.switchledger.spi.PatchingLedger;
import com.telcobright.seed.switchledger.spi.PatchingLedger.Answer;
import com.telcobright.seed.switchledger.spi.PatchingLedger.Entry;
import com.telcobright.seed.switchledger.spi.PatchingLedger.Outcome;
import com.telcobright.seed.switchledger.spi.PatchingLedger.Patch;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * ARCH-0119 road 1 in the engine, at the seam: WHAT the engine asks of a ledger that moves in one call (the entries, in their order,
 * with their guards), and what it makes of each kind of answer. The ledger here answers what the case tells it to; the real server's
 * side — that such a batch is read, checked and written exactly — is recall-db's proof ({@code EngineOneCallTest}, {@code PatchTest}).
 */
class OneCallMovesTest {

    private final LedgerLab lab = LedgerLab.open().schema("btcl").account("btcl", 11, 77, "OTH_ea", "10");
    private final List<Patch> asked = new CopyOnWriteArrayList<>();
    private Answer answers = new Answer(Outcome.BUSY, "the queue of the row is full", 0, null, Map.of());

    @AfterEach
    void close() { lab.close(); }

    @Test
    void a_hold_asks_the_account_guarded_then_the_holds_row_grown_or_opened() {
        MemLedgerPort port = portOver(theLedger(lab.start()));

        catchThrowable(() -> port.reserve(level(), new BigDecimal("4"), "h-1#L0"));

        assertThat(asked).hasSize(1);
        List<Entry> entries = asked.get(0).entries();
        assertThat(entries).extracting(Entry::entity, entry -> String.valueOf(entry.id())).containsExactly(
            org.assertj.core.groups.Tuple.tuple(Books.ACCOUNT, "11"), org.assertj.core.groups.Tuple.tuple(Books.RESERVE, "h-1#L0"));
        assertThat(entries.get(0).guards()).as("the account must still be on the planned purchase, and must not go below 0")
            .extracting(guard -> guard.sign() + " " + guard.field() + " " + guard.value()).containsExactly("EQUAL idpackagePurchase 77", "AT_LEAST balanceAfter 0");
        assertThat(entries.get(0).changes()).extracting(change -> change.op() + " " + change.field())
            .containsExactly("COPY balanceBefore", "ADD balanceAfter", "SET lastAmount");
        assertThat(entries.get(1).insertIfAbsent()).as("the hold's row is opened when there is none").isNotNull();
        assertThat(asked.get(0).transactionId()).startsWith("SEED_");
    }

    @Test
    void busy_rows_are_the_busy_fault_and_any_other_refusal_is_not() {
        MemLedgerPort port = portOver(theLedger(lab.start()));

        answers = new Answer(Outcome.BUSY, "the queue of the row is full", 0, null, Map.of());
        Throwable busy = catchThrowable(() -> port.reserve(level(), BigDecimal.ONE, "b-1#L0"));
        assertThat(busy).isInstanceOf(LedgerFault.class).hasMessageContaining("the queue of the row is full");
        assertThat(((LedgerFault) busy).busy()).as("busy: nothing written, try again (mutant O8: busy is the plain fault)").isTrue();

        answers = new Answer(Outcome.OTHER, "REFUSED: the batch cannot be read", 0, null, Map.of());
        Throwable refused = catchThrowable(() -> port.reserve(level(), BigDecimal.ONE, "b-2#L0"));
        assertThat(refused).isInstanceOf(LedgerFault.class).hasMessageContaining("the batch cannot be read");
        assertThat(((LedgerFault) refused).busy()).isFalse();

        answers = new Answer(Outcome.APPLIED, "applied", 0, null, Map.of());
        Throwable noRow = catchThrowable(() -> port.reserve(level(), BigDecimal.ONE, "b-3#L0"));
        assertThat(noRow).as("applied with no account row in the answer: the result is not known, never invented").isInstanceOf(LedgerFault.class)
            .hasMessageContaining("answered no account row");
        assertThat(lab.cachedBalance("btcl", 11)).as("the fake wrote nothing; the engine made no write of its own beside the one call").isEqualByComparingTo("10");
    }

    // ── the lab ─────────────────────────────────────────────────────────────

    private MemLedgerPort portOver(MemLedger ledger) {
        return new MemLedgerPort(new Books(ledger, Clock.systemUTC()), AccountLocks.of(ledger, SwitchLedgerSettings.standard().lockTimeoutMs()));
    }

    private static LevelAdmission level() {
        Partner partner = new Partner();
        partner.setIdPartner(3);
        PackageAccount account = new PackageAccount();
        account.setId(11L);
        account.setUom("OTH_ea");
        account.setIdpackagePurchase(77L);
        return new LevelAdmission(0, new Tenant("btcl"), partner, account);
    }

    /** The lab's ledger, also a one-call ledger that writes down what it is asked and answers what the case set. */
    private MemLedger theLedger(MemLedger real) {
        return (MemLedger) Proxy.newProxyInstance(MemLedger.class.getClassLoader(), new Class<?>[] {MemLedger.class, PatchingLedger.class}, (proxy, method, args) -> {
            switch (method.getName()) {
                case "movesInOneCall": return true;
                case "patch":
                    asked.add((Patch) args[0]);
                    return answers;
                default:
            }
            try {
                return method.invoke(real, args);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        });
    }
}
