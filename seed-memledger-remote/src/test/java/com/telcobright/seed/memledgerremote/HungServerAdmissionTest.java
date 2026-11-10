package com.telcobright.seed.memledgerremote;

import com.telcobright.seed.memledgerremote.api.RemoteMemLedger;
import com.telcobright.seed.memledgerremote.dependencies.RemoteLedgerSettings;
import com.telcobright.seed.sessionflow.api.SessionCause;
import com.telcobright.seed.sessionflow.spi.LedgerPort;
import com.telcobright.seed.switchledger.internal.AccountLocks;
import com.telcobright.seed.switchledger.internal.Books;
import com.telcobright.seed.switchledger.internal.MemLedgerPort;
import com.telcobright.statewalk.session.Verdict;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.telcobright.seed.memledgerremote.AdShapedFlow.DHAKA;
import static com.telcobright.seed.memledgerremote.AdShapedFlow.HOUSE_AD;
import static com.telcobright.seed.memledgerremote.AdShapedFlow.THREE_PAID_THEN_THE_HOUSE_AD;
import static com.telcobright.seed.memledgerremote.AdShapedFlow.kitOver;
import static com.telcobright.seed.memledgerremote.AdShapedFlow.refusalsOf;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Item (a) of the remote-fixes round: recall-db HUNG (it accepts and never answers) behind a RemoteMemLedger with a 300 ms deadline, and
 * a view with three paid campaigns and the free house ad last. The first remote reads of a view are the PLAN step's (the account walk:
 * the payer's accounts from the ledger's cached purchases); a fault there must be the admission's SYSTEM FAULT — as a fault of the hold
 * already is (TierReserve) — so the ad's rule "after a ledger fault only the house ad is tried" applies after the FIRST paid campaign:
 * ONE deadline, then the house ad. Before: each paid campaign was refused INTERNAL_ERROR after its own deadline (> 3 deadlines), and no
 * system fault was marked. The view is decided after about ONE deadline-ms: inside the WiFi captive's 800 ms only while deadline-ms is
 * below about 760 ms — at the production deadline-ms 1000 a HUNG server's view is decided after about 1 s and the captive shows its own
 * timeout (the review's probe: 1041 / 1003 ms); a cleanly stopped server answers UNAVAILABLE within milliseconds (the house ad in time).
 */
class HungServerAdmissionTest {

    private static final long DEADLINE_MS = 300;

    private final HungServer hung = new HungServer();
    private final RemoteMemLedger remote =
        RemoteMemLedger.connect(new RemoteLedgerSettings(hung.address(), DEADLINE_MS, "LAB_LEDGER_TOKEN"), "lab-bearer", Map.of());

    @AfterEach
    void close() throws Exception {
        remote.shutdown();
        hung.close();
    }

    @Test
    void aHungServer_costsTheViewOneDeadline_marksTheSystemFault_andOnlyTheHouseAdIsTriedAfterIt() {
        AdShapedFlow flow = new AdShapedFlow(kitOver(portOverTheHungServer()), THREE_PAID_THEN_THE_HOUSE_AD);
        catchThrowable(remote::getRegisteredDatabases);                                   // the wire's classes loaded before the clock runs

        AdShapedFlow.View view = new AdShapedFlow.View();
        long startedAt = System.nanoTime();
        Verdict verdict = flow.admitAView(view, "v-hung-1");
        long tookMs = (System.nanoTime() - startedAt) / 1_000_000;

        List<String> refusals = refusalsOf(view);
        System.out.println("HungServerAdmissionTest: the view took " + tookMs + " ms (deadline " + DEADLINE_MS + " ms), systemFault "
            + view.systemFault + ", chosen " + view.chosen + ", refusals " + refusals);
        SoftAssertions soft = new SoftAssertions();
        soft.assertThat(verdict.accepted()).as("the house ad is served").isTrue();
        soft.assertThat(view.chosen).isEqualTo(HOUSE_AD);
        soft.assertThat(view.systemFault).as("a fault of the plan step is the admission's system fault").isEqualTo(SessionCause.BILLING_SYSTEM_ERROR);
        soft.assertThat(refusals).as("never INTERNAL_ERROR").noneMatch(r -> r.contains(SessionCause.INTERNAL_ERROR));
        soft.assertThat(refusals).as("the first paid campaign: the ledger's fault; the next two: not tried after it")
            .containsExactly("candidate 1 refused: " + SessionCause.BILLING_SYSTEM_ERROR, "candidate 2 refused: " + SessionCause.BILLING_SYSTEM_ERROR,
                "candidate 3 refused: " + SessionCause.BILLING_SYSTEM_ERROR);
        soft.assertThat(tookMs).as("ONE deadline for the whole view, never one per paid campaign").isLessThan(2 * DEADLINE_MS);
        soft.assertAll();
    }

    /** The switch ledger's port over the client, with the purchases cached: the walk's first read is the payer's accounts, on the wire. */
    private LedgerPort portOverTheHungServer() {
        Books books = new Books(remote, Clock.system(DHAKA), Set.of(Books.ACCOUNT, Books.RESERVE, Books.PURCHASE));
        return new MemLedgerPort(books, AccountLocks.of(remote, 5_000));
    }
}
