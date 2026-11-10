package com.telcobright.seed.memledgerremote;

import com.telcobright.memledger.api.MemLedger;
import com.telcobright.seed.sessionflow.api.SessionCause;
import com.telcobright.seed.sessionflow.spi.LedgerPort;
import com.telcobright.seed.switchledger.internal.AccountLocks;
import com.telcobright.seed.switchledger.internal.Books;
import com.telcobright.seed.switchledger.internal.MemLedgerPort;
import com.telcobright.statewalk.session.Verdict;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.Clock;
import java.util.Set;

import static com.telcobright.seed.memledgerremote.AdShapedFlow.DHAKA;
import static com.telcobright.seed.memledgerremote.AdShapedFlow.HOUSE_AD;
import static com.telcobright.seed.memledgerremote.AdShapedFlow.THREE_PAID_THEN_THE_HOUSE_AD;
import static com.telcobright.seed.memledgerremote.AdShapedFlow.kitOver;
import static com.telcobright.seed.memledgerremote.AdShapedFlow.refusalsOf;

/**
 * The remote-fixes round's closing (the review of item (a)): the EMBEDDED path behaves exactly as before the round. A plan-step read of a
 * ledger IN THE PROCESS that throws — mem-ledger f739824 throws there only for a read after its shutdown (or before its start) and for a
 * row lock held past its timeout — refuses THAT candidate INTERNAL_ERROR, and the next candidates are still tried, as at 82704c0: it is not
 * the admission's system fault. Only a ledger on the wire makes a plan-step fault the system fault ({@link HungServerAdmissionTest}).
 */
class InProcessPlanFaultTest {

    private static final String SHUT_DOWN = "MemLedger not initialized. Database loading and WAL replay must complete first.";

    @Test
    void aPlanReadThatThrowsInTheProcess_refusesEachPaidCampaignInternalError_asBeforeTheRound_andMarksNoSystemFault() {
        AdShapedFlow flow = new AdShapedFlow(kitOver(portOver(aLedgerInTheProcessThatIsShutDown())), THREE_PAID_THEN_THE_HOUSE_AD);

        AdShapedFlow.View view = new AdShapedFlow.View();
        Verdict verdict = flow.admitAView(view, "v-in-process-1");

        System.out.println("InProcessPlanFaultTest: systemFault " + view.systemFault + ", chosen " + view.chosen + ", refusals " + refusalsOf(view));
        SoftAssertions soft = new SoftAssertions();
        soft.assertThat(verdict.accepted()).as("the house ad is served").isTrue();
        soft.assertThat(view.chosen).isEqualTo(HOUSE_AD);
        soft.assertThat(view.systemFault).as("a fault of a ledger in the process at the plan step is not the system fault (as before the round)").isNull();
        soft.assertThat(refusalsOf(view)).as("each paid campaign refused INTERNAL_ERROR and the next one tried, as at 82704c0")
            .containsExactly("candidate 1 refused: " + SessionCause.INTERNAL_ERROR, "candidate 2 refused: " + SessionCause.INTERNAL_ERROR,
                "candidate 3 refused: " + SessionCause.INTERNAL_ERROR);
        soft.assertAll();
    }

    /** The switch ledger's port over a ledger in the process, the purchases cached: the walk's first read is the payer's accounts. */
    private static LedgerPort portOver(MemLedger inTheProcess) {
        Books books = new Books(inTheProcess, Clock.system(DHAKA), Set.of(Books.ACCOUNT, Books.RESERVE, Books.PURCHASE));
        return new MemLedgerPort(books, AccountLocks.of(inTheProcess, 5_000));
    }

    /** An in-process MemLedger (not a client of a server) after its shutdown: every call refused in mem-ledger's own words. */
    private static MemLedger aLedgerInTheProcessThatIsShutDown() {
        return (MemLedger) Proxy.newProxyInstance(MemLedger.class.getClassLoader(), new Class<?>[] {MemLedger.class}, (proxy, method, args) -> switch (method.getName()) {
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            case "toString" -> "an in-process ledger, shut down";
            default -> throw new IllegalStateException(SHUT_DOWN);
        });
    }
}
