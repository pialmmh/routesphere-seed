package com.telcobright.seed.memledgerremote;

import com.telcobright.rtc.domainmodel.PartnerType;
import com.telcobright.rtc.domainmodel.nonentity.Tenant;
import com.telcobright.seed.memledgerremote.AdShapedFlow.Campaign;
import com.telcobright.seed.memledgerremote.api.RemoteMemLedger;
import com.telcobright.seed.memledgerremote.dependencies.RemoteLedgerSettings;
import com.telcobright.seed.sessionflow.api.Candidate;
import com.telcobright.seed.sessionflow.api.IdentificationRule;
import com.telcobright.seed.sessionflow.api.SessionCause;
import com.telcobright.seed.sessionflow.dependencies.RoutesphereKit;
import com.telcobright.seed.sessionflow.dependencies.RoutesphereSettings;
import com.telcobright.seed.sessionflow.spi.LedgerPort;
import com.telcobright.seed.sessionflow.spi.TenantLookup;
import com.telcobright.seed.sessionflow.testkit.RecordingCdrSink;
import com.telcobright.seed.sessionflow.testkit.TenantTreeBuilder;
import com.telcobright.seed.switchledger.internal.AccountLocks;
import com.telcobright.seed.switchledger.internal.Books;
import com.telcobright.seed.switchledger.internal.MemLedgerPort;
import com.telcobright.statewalk.session.Verdict;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Item (a) of the remote-fixes round: recall-db HUNG (it accepts and never answers) behind a RemoteMemLedger with a 300 ms deadline, and
 * a view with three paid campaigns and the free house ad last. The first remote reads of a view are the PLAN step's (the account walk:
 * the payer's accounts from the ledger's cached purchases); a fault there must be the admission's SYSTEM FAULT — as a fault of the hold
 * already is (TierReserve) — so the ad's rule "after a ledger fault only the house ad is tried" applies after the FIRST paid campaign:
 * ONE deadline, then the house ad, inside the WiFi captive's 800 ms. Before: each paid campaign was refused INTERNAL_ERROR after its own
 * deadline (> 3 deadlines), and no system fault was marked.
 */
class HungServerAdmissionTest {

    private static final long DEADLINE_MS = 300;
    private static final ZoneId DHAKA = ZoneId.of("Asia/Dhaka");
    private static final Campaign HOUSE_AD = new Campaign(6, 1, true);
    private static final List<Candidate> THREE_PAID_THEN_THE_HOUSE_AD =
        List.of(new Campaign(1, 501, false), new Campaign(2, 502, false), new Campaign(3, 503, false), HOUSE_AD);

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

    /** The operator btcl (the house ad's payer 1, the reseller 5) and its reseller's three advertisers. */
    private static RoutesphereKit kitOver(LedgerPort port) {
        Tenant tree = new TenantTreeBuilder()
            .root("btcl").partner(1, "house", PartnerType.CUSTOMER).partner(5, "Res5", PartnerType.RESELLER).and()
            .tenant("btcl_res_5", "btcl").partner(501, "advertiser 1", PartnerType.CUSTOMER).partner(502, "advertiser 2", PartnerType.CUSTOMER)
                .partner(503, "advertiser 3", PartnerType.CUSTOMER).and()
            .build();
        return RoutesphereKit.builder().tenants(TenantLookup.of(tree)).identification(List.of(new IdentificationRule("vlan", "gw-1:310", "btcl")))
            .ledger(port).cdr(new RecordingCdrSink()).zone(DHAKA).clock(Clock.system(DHAKA)).settings(RoutesphereSettings.defaults()).build();
    }

    /** The chain's note of each refused candidate ({@code AdmissionChain.undo}): "candidate N refused: CAUSE". */
    private static List<String> refusalsOf(AdShapedFlow.View view) {
        return view.history.snapshot().stream().filter(r -> r.isNote() && r.cause().matches("(?s).*candidate \\d+ refused: .*"))
            .map(r -> r.cause().substring(r.cause().indexOf("candidate")).trim()).toList();
    }
}
