package com.telcobright.seed.memledgerremote;

import com.telcobright.seed.memledgerremote.api.Atomic;
import com.telcobright.seed.memledgerremote.api.AtomicAnswer;
import com.telcobright.seed.memledgerremote.api.AtomicAnswer.Outcome;
import com.telcobright.seed.memledgerremote.wire.ApplyReply;
import com.telcobright.seed.memledgerremote.wire.ApplyRequest;
import com.telcobright.seed.memledgerremote.wire.ChangeKind;
import com.telcobright.seed.memledgerremote.wire.GuardKind;
import com.telcobright.seed.memledgerremote.wire.Op;
import com.telcobright.seed.memledgerremote.wire.WalEntry;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * ARCH-0119 · The client's words for road 1 ({@link Atomic}) are the wire's, exactly; and the server's answer is TYPED
 * ({@link AtomicAnswer}): an application tells busy from a guard from a lost lock by an enum, never by reading a sentence.
 */
class AtomicTest {

    @Test
    void everyRefusalCodeHasItsOwnOutcome_andBusyIsNeverAMoneyRefusal() {
        assertThat(outcomeOf(1, "")).isEqualTo(Outcome.APPLIED);
        assertThat(outcomeOf(0, "GUARD")).as("a guard that did not hold is a DECISION (mutant K1)").isEqualTo(Outcome.GUARD);
        assertThat(outcomeOf(0, "QUEUE_FULL")).as("a full queue is BUSY: try again or give the house answer (mutant K3)").isEqualTo(Outcome.BUSY);
        assertThat(outcomeOf(0, "NOT_FREE_IN_TIME")).isEqualTo(Outcome.BUSY);
        assertThat(outcomeOf(0, "STALE")).isEqualTo(Outcome.STALE);
        assertThat(outcomeOf(0, "LOCK_LOST")).as("a lost lock means read again (mutant K2)").isEqualTo(Outcome.LOCK_LOST);
        assertThat(outcomeOf(0, "NOT_COVERED")).isEqualTo(Outcome.NOT_COVERED);
        assertThat(outcomeOf(0, "REFUSED")).isEqualTo(Outcome.REFUSED);
        assertThat(AtomicAnswer.of(ApplyReply.newBuilder().setRefusalCode("REFUSED").setMessage("Batch refused, nothing changed: the rows of batch T were not free within 5000 ms").build()).outcome())
            .as("the older server's words for rows not free in time are BUSY too").isEqualTo(Outcome.BUSY);
        AtomicAnswer repeat = AtomicAnswer.of(ApplyReply.newBuilder().setApplied(2).setAlreadyApplied(true).build());
        assertThat(repeat.isApplied()).isTrue();
        assertThat(repeat.alreadyApplied()).isTrue();
    }

    @Test
    void anAtomicBatchIsTheWiresWords_inTheOrderSaid_withEveryDecimalAsText() {
        BigDecimal amount = new BigDecimal("4.000000");
        ApplyRequest request = Atomic.batch("T-1")
            .change("res_2", "PackageAccount", 11L, row -> row.copy("balanceBefore", "balanceAfter").add("balanceAfter", amount.negate()).set("lastAmount", amount.negate())
                .min("balanceAfter", new BigDecimal("100")).atLeast("balanceAfter", BigDecimal.ZERO).atMost("balanceAfter", new BigDecimal("1000")).equalTo("uom", "OTH_ea")
                .notBefore("expireDate", "2026-11-10T00:00:00").set("note", null).set("count", 3L))
            .mustBeAbsent("res_2", "PackageAccountReserve", "c1#L0")
            .delete("res_2", "PackageAccountReserve", "c0#L0")
            .request();

        assertThat(request.getTransactionId()).isEqualTo("T-1");
        WalEntry account = request.getEntries(0);
        assertThat(account.getOp()).isEqualTo(Op.PATCH);
        assertThat(account.getEntityId()).isEqualTo("11");
        assertThat(account.getChangesList()).extracting(c -> c.getKind() + " " + c.getField() + " " + c.getValue() + c.getFromField()).containsExactly(
            "COPY balanceBefore balanceAfter", "ADD balanceAfter -4.000000", "SET lastAmount \"-4.000000\"", "MIN balanceAfter 100", "SET note null", "SET count 3");
        assertThat(account.getChanges(2).getValue()).as("a decimal that is SET travels as TEXT with its scale, never a JSON number (mutant K4)").isEqualTo("\"-4.000000\"");
        assertThat(account.getChanges(0).getKind()).isEqualTo(ChangeKind.COPY);
        assertThat(account.getGuardsList()).extracting(g -> g.getKind() + " " + g.getField() + " " + g.getValue()).containsExactly(
            "FIELD_GE balanceAfter 0", "FIELD_LE balanceAfter 1000", "FIELD_EQ uom OTH_ea", "FIELD_GE expireDate 2026-11-10T00:00:00");
        WalEntry absent = request.getEntries(1);
        assertThat(absent.getOp()).isEqualTo(Op.PATCH);
        assertThat(absent.getGuards(0).getKind()).isEqualTo(GuardKind.ROW_ABSENT);
        assertThat(absent.getChangesCount()).as("ROW_ABSENT stands alone").isZero();
        assertThat(request.getEntries(2).getOp()).isEqualTo(Op.DELETE);
        assertThat(catchThrowable(() -> Atomic.batch(" "))).as("a batch with no id cannot be repeated safely").isInstanceOf(IllegalArgumentException.class);
    }

    private static Outcome outcomeOf(int applied, String code) { return AtomicAnswer.of(ApplyReply.newBuilder().setApplied(applied).setRefusalCode(code).build()).outcome(); }
}
