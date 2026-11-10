package com.telcobright.seed.memledgerremote;

import com.telcobright.rtc.domainmodel.LevelAdmission;
import com.telcobright.rtc.domainmodel.PartnerType;
import com.telcobright.rtc.domainmodel.mysqlentity.PackageAccount;
import com.telcobright.rtc.domainmodel.mysqlentity.Partner;
import com.telcobright.rtc.domainmodel.nonentity.Tenant;
import com.telcobright.seed.sessionflow.api.Deployment;
import com.telcobright.seed.sessionflow.api.RootResources;
import com.telcobright.seed.sessionflow.api.TierSettlement;
import com.telcobright.seed.sessionflow.dependencies.RoutesphereSettings;
import com.telcobright.seed.sessionflow.spi.LedgerMaker;
import com.telcobright.seed.sessionflow.spi.LedgerPort;
import com.telcobright.seed.sessionflow.spi.LedgerPort.LedgerFault;
import com.telcobright.seed.sessionflow.spi.LedgerPort.LedgerRefusal;
import com.telcobright.seed.sessionflow.spi.LedgerPort.Reservation;
import com.telcobright.seed.sessionflow.spi.TenantLookup;
import com.telcobright.seed.sessionflow.testkit.TenantTreeBuilder;
import com.telcobright.seed.switchledger.LedgerLab;
import com.telcobright.seed.switchledger.dependencies.SwitchLedgerMaker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * ARCH-0117 item 3's reds, through the switch ledger's own maker: a root whose ledger says {@code store: remote} gets the engine over
 * its mem-ledger server (the kind stays {@code switch}; nothing is opened in the root's directory), and without an {@code address} or
 * its bearer's variable refuses to start in words; a fault on commit is a {@link LedgerFault} (the pipeline's BILLING_SYSTEM_ERROR) and
 * no row changed; the port's {@code slowestAnswerMs()} is the deadline (0 stays the embedded ledger's); a write whose deadline passes is
 * sent once more under the same transaction id, and a second deadline is a fault.
 */
class RemoteStoreTest {

    @TempDir Path dir;
    private final LedgerLab lab = LedgerLab.open().schema("btcl").schema("btcl_res_5").account("btcl_res_5", 11, 1, "OTH_ea", "100");
    private final TenantLookup trees = TenantLookup.of(new TenantTreeBuilder().root("btcl").partner(1, "client", PartnerType.CUSTOMER)
        .partner(5, "Res5", PartnerType.RESELLER).and().tenant("btcl_res_5", "btcl").partner(501, "Res5 client", PartnerType.CUSTOMER).and().build());
    private StubServer server;
    private LedgerPort port;

    @BeforeEach
    void serve() {
        lab.start();
        server = new StubServer(lab.ledger());
    }

    @AfterEach
    void close() throws Exception {
        if (port instanceof AutoCloseable owned) owned.close();
        server.close();
        lab.close();
    }

    // ── the words ───────────────────────────────────────────────────────────

    @Test
    void aRootWhoseLedgerSaysStoreRemote_withoutItsAddress_orItsBearer_refusesToStart_inWords() {
        assertThatThrownBy(() -> made(Map.of("store", "remote", "token-var", "LAB_LEDGER_TOKEN")))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("the root 'btcl' has a remote ledger store with no 'address'");
        assertThatThrownBy(() -> made(Map.of("store", "remote", "address", server.address())))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("the root 'btcl' has a remote ledger store with no 'token-var'");
        assertThatThrownBy(() -> new SwitchLedgerMaker().make(btcl(Map.of("store", "remote", "address", server.address(), "token-var", "LAB_LEDGER_TOKEN")), context(Map.of())))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("LAB_LEDGER_TOKEN is not in the process environment").hasMessageNotContaining(server.token);
        assertThatThrownBy(() -> made(Map.of("store", "elsewhere")))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("ledger.store: elsewhere").hasMessageContaining("embedded").hasMessageContaining("remote");
    }

    @Test
    void aRootWhoseLedgerSaysStoreRemote_getsTheEngineOverItsServer_theMoneyMovesThere_nothingOpensHere() throws Exception {
        port = made(remoteWords(1_500));
        LevelAdmission level = tier(11L);

        Reservation held = port.reserve(level, new BigDecimal("10"), "r-1#L0").orElseThrow();
        record(level, held, "r-1#L0");
        TierSettlement settled = port.settle(level, new BigDecimal("4"));

        assertThat(held.balanceBefore()).isEqualByComparingTo("100");
        assertThat(held.balanceAfter()).isEqualByComparingTo("90");
        assertThat(settled.balanceAfter()).isEqualByComparingTo("96");
        assertThat(lab.cachedBalance("btcl_res_5", 11)).as("the server's cache holds it").isEqualByComparingTo("96");
        assertThat(server.writesSeen).as("the hold and the settlement, one batch each").hasSize(2);
        assertThat(Files.exists(dir.resolve("ledger"))).as("no WAL, no queue in the application's directory").isFalse();
    }

    @Test
    void thePortsSlowestAnswer_isTheRemoteStoresDeadline_andZeroForTheEmbeddedLedger() throws Exception {
        port = made(remoteWords(1_500));
        assertThat(port.slowestAnswerMs()).as("the deadline of one call of the server").isEqualTo(1_500);

        LedgerLab own = LedgerLab.open().schema("btcl").schema("btcl_res_5");
        try (AutoCloseable embedded = (AutoCloseable) new SwitchLedgerMaker().make(
            new RootResources("btcl", new RootResources.SwitchDb(own.url(), "btcl", "sa", null), new RootResources.KafkaRoad("10.10.188.2:9092", 1, (short) 1), null,
                new RootResources.Ledger(RootResources.Ledger.SWITCH, Map.of()), dir), context(Map.of()))) {
            assertThat(((LedgerPort) embedded).slowestAnswerMs()).as("in the process").isZero();
        } finally {
            own.close();
        }
    }

    // ── the faults ──────────────────────────────────────────────────────────

    @Test
    void aFaultOnCommit_isALedgerFault_neverARefusal_andNoRowChanged() throws Exception {
        port = made(remoteWords(1_500));
        server.faultEveryWrite();

        Throwable t = catchThrowable(() -> port.reserve(tier(11L), new BigDecimal("10"), "f-1#L0"));

        assertThat(t).as("BILLING_SYSTEM_ERROR in the pipeline: a fault of the billing system, never a balance cause").isInstanceOf(LedgerFault.class).isNotInstanceOf(LedgerRefusal.class);
        assertThat(t.getMessage()).contains(server.address()).contains("nothing changed");
        assertThat(lab.cachedBalance("btcl_res_5", 11)).as("no row changed").isEqualByComparingTo("100");
        assertThat(lab.cachedReserveRows("btcl_res_5")).isZero();
    }

    @Test
    void aWriteWhoseDeadlinePasses_isSentOnceMoreUnderTheSameId_andASecondDeadlineIsAFault() throws Exception {
        port = made(remoteWords(400));
        server.stallTheFirstTryOfEachBatch(900);

        Reservation held = port.reserve(tier(11L), new BigDecimal("10"), "d-1#L0").orElseThrow();

        assertThat(held.balanceAfter()).isEqualByComparingTo("90");
        assertThat(server.writesSeen).as("the same batch twice").hasSize(2);
        assertThat(server.writesSeen.get(1)).as("under the SAME transaction id").isEqualTo(server.writesSeen.get(0));
        assertThat(lab.cachedBalance("btcl_res_5", 11)).as("debited once").isEqualByComparingTo("90");

        server.stallTheFirstTryOfEachBatch(0);
        server.stallEveryWrite(900);
        int before = server.writesSeen.size();
        Throwable t = catchThrowable(() -> port.reserve(tier(11L), new BigDecimal("10"), "d-2#L0"));

        assertThat(t).isInstanceOf(LedgerFault.class);
        assertThat(t.getMessage()).contains("sent twice").contains("400 ms");
        assertThat(server.writesSeen.size() - before).as("twice, never a third time").isEqualTo(2);
    }

    /**
     * Remote-fixes item (e): after the second deadline the batch's fate is UNKNOWN — the server may still apply it (a stall inside its
     * append) — so the fault says so, greppably, with the transaction id to reconcile by; the behaviour stays (no third try).
     */
    @Test
    void aSecondDeadline_saysTheOutcomeIsUnknown_andTheTransactionIdToReconcileBy() throws Exception {
        port = made(remoteWords(400));
        server.stallEveryWrite(900);

        Throwable t = catchThrowable(() -> port.reserve(tier(11L), new BigDecimal("10"), "u-1#L0"));

        assertThat(t).isInstanceOf(LedgerFault.class);
        assertThat(server.writesSeen).as("sent twice, never a third time").hasSize(2);
        assertThat(t.getMessage()).contains("UNKNOWN outcome: batch " + server.writesSeen.get(0) + " sent twice, no answer within 400 ms — reconcile by this transaction id");
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private Map<String, Object> remoteWords(long deadlineMs) {
        return Map.of("store", "remote", "address", server.address(), "deadline-ms", String.valueOf(deadlineMs), "token-var", "LAB_LEDGER_TOKEN");
    }

    private LedgerPort made(Map<String, Object> ledgerWords) {
        return new SwitchLedgerMaker().make(btcl(ledgerWords), context(Map.of("LAB_LEDGER_TOKEN", server.token)));
    }

    /** The root as the bootstrap gives it: the kind stays switch; the application keeps its login of the switch database (the tiers' columns are checked with it). */
    private RootResources btcl(Map<String, Object> ledgerWords) {
        return new RootResources("btcl", new RootResources.SwitchDb(lab.url(), "btcl", "sa", null), new RootResources.KafkaRoad("10.10.188.2:9092", 1, (short) 1), null,
            new RootResources.Ledger(RootResources.Ledger.SWITCH, new HashMap<>(ledgerWords)), dir);
    }

    private LedgerMaker.Context context(Map<String, String> environment) {
        Deployment d = new Deployment("platform", "ad-sphere", "bed", List.of("btcl"), RoutesphereSettings.defaults(), ZoneId.of("Asia/Dhaka"),
            List.of(), Map.of(), "10.10.188.2:9092", null, Map.of(), "e1");
        return new LedgerMaker.Context(d, trees, Clock.systemUTC(), environment::get);
    }

    private static LevelAdmission tier(Long accountId) {
        Partner partner = new Partner();
        partner.setIdPartner(501);
        PackageAccount account = new PackageAccount();
        account.setId(accountId);
        account.setUom("OTH_ea");
        LevelAdmission level = new LevelAdmission(0, new Tenant("btcl_res_5"), partner, account);
        level.setReservedAmount(BigDecimal.ZERO);
        return level;
    }

    /** The base records a reserve on its level ({@code AdmissionChain.recordReserve}); a direct test does the same by hand. */
    private static void record(LevelAdmission level, Reservation held, String reference) {
        level.setDebitReference(reference);
        level.setReservedAmount(held.reserved());
        level.setBalanceAfter(held.balanceAfter());
        level.incrementReservationCount();
    }
}
