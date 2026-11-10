package com.telcobright.seed.memledgerremote;

import com.telcobright.rtc.domainmodel.LevelAdmission;
import com.telcobright.rtc.domainmodel.mysqlentity.PackageAccount;
import com.telcobright.rtc.domainmodel.mysqlentity.PackageAccountReserve;
import com.telcobright.rtc.domainmodel.mysqlentity.Partner;
import com.telcobright.rtc.domainmodel.nonentity.Tenant;
import com.telcobright.seed.memledgerremote.api.Capabilities;
import com.telcobright.seed.memledgerremote.api.RemoteMemLedger;
import com.telcobright.seed.memledgerremote.dependencies.RemoteLedgerSettings;
import com.telcobright.seed.switchledger.LedgerLab;
import com.telcobright.seed.switchledger.api.LedgerAccount;
import com.telcobright.seed.switchledger.dependencies.SwitchLedgerSettings;
import com.telcobright.seed.switchledger.dependencies.SwitchLedgers;
import com.telcobright.seed.switchledger.internal.Books;
import com.telcobright.seed.switchledger.internal.MemLedgerPort;
import com.telcobright.seed.switchledger.internal.OwnedMemLedgerPort;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ARCH-0118 review — the server restarted into ANOTHER version on the same address while the client runs (a roll-back, an upgrade):
 * the client follows the words of the server it now talks to, never those of the process it first met. Rolled back (no longer
 * {@code ghosts}): the client takes a reload's ghost out itself again and sends no rows as read. Upgraded ({@code expect}): its writes
 * carry the rows they read. The stub only SAYS the words: what is asserted is what the client does.
 */
class ServerSwapTest {

    private final LedgerLab lab = LedgerLab.open().schema("btcl").schema("btcl_res_9").account("btcl", 1, 10, "BDT", "100.00");
    private final String token = UUID.randomUUID().toString();
    private StubServer first;
    private StubServer restarted;
    private RemoteMemLedger client;

    @BeforeEach
    void start() { lab.start(); }

    @AfterEach
    void close() {
        if (client != null) client.shutdown();
        if (restarted != null) restarted.close();
        if (first != null) first.close();
        lab.close();
    }

    @Test
    void rolledBackUnderARunningClient_theClientTakesOutAReloadsGhostItselfAgain_andSendsNoRowsAsRead() {
        first = new StubServer(lab.ledger(), 0, token, List.of(Capabilities.GHOSTS, Capabilities.EXPECT));
        client = connectedTo(first);
        assertThat(client.takesOutGhosts()).as("the newer server, at the connect").isTrue();

        restarted = restartedAs(first, List.of());                                                    // the older version, the same address
        awaitTheClientsRules(() -> !client.takesOutGhosts());

        MemLedgerPort port = engine();
        assertThat(port.reserve(tier(), new BigDecimal("1.00"), "r-1#L0")).as("an engine write through the client").isPresent();
        lab.reserveRow("btcl", "s-7#L0", 1, 10, "BDT", "5.00", "2026-10-24 00:00:00");                 // a ghost: in the table, not in the cache
        new OwnedMemLedgerPort(port, client, Set.of("btcl"), tier -> true).tierAdded("btcl_res_9");    // the tier's merge of new rows

        assertThat(restarted.rowsAsReadSeen).as("the older server is sent no rows as read").isNotEmpty().containsOnly(0);
        assertThat(lab.cachedRow("btcl", "s-7#L0")).as("the ghost, taken out by the client: the older server does not").isNull();
        assertThat(lab.cachedRow("btcl", "r-1#L0")).as("the engine's own hold stays").isNotNull();
    }

    @Test
    void upgradedUnderARunningClient_theClientsWritesCarryTheRowsTheyRead() {
        first = new StubServer(lab.ledger(), 0, token, List.of());
        client = connectedTo(first);
        assertThat(client.checksExpectations()).as("the older server, at the connect").isFalse();

        restarted = restartedAs(first, List.of(Capabilities.GHOSTS, Capabilities.EXPECT));            // the newer version, the same address
        awaitTheClientsRules(client::checksExpectations);

        assertThat(engine().reserve(tier(), new BigDecimal("1.00"), "r-2#L0")).as("an engine write through the client").isPresent();
        assertThat(restarted.rowsAsReadSeen).as("every write carries the rows its unit read").isNotEmpty().allMatch(rows -> rows > 0);
        assertThat(client.takesOutGhosts()).as("and a merge leaves the ghosts to the server").isTrue();
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private RemoteMemLedger connectedTo(StubServer server) {
        return RemoteMemLedger.connect(new RemoteLedgerSettings(server.address(), 2_000, "LAB_LEDGER_TOKEN"), token,
            Map.of(Books.ACCOUNT, LedgerAccount.class, Books.RESERVE, PackageAccountReserve.class));
    }

    /** The process on {@code running}'s address stops; another version starts there, saying {@code says}. */
    private StubServer restartedAs(StubServer running, List<String> says) {
        int port = running.port();
        running.close();
        return new StubServer(lab.ledger(), port, token, says);
    }

    private MemLedgerPort engine() { return (MemLedgerPort) SwitchLedgers.memLedger(client, SwitchLedgerSettings.standard(), Clock.systemUTC()); }

    /** The client acts on the restarted server's words within 10 s: its channel notices the lost connection, its next question asks again. */
    private static void awaitTheClientsRules(BooleanSupplier followed) {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < until) {
            try {
                if (followed.getAsBoolean()) return;
            } catch (RuntimeException reconnecting) {
                // the restarted server does not answer yet: ask again
            }
            pause(50);
        }
        throw new AssertionError("after 10 s the client still acts on the words of the server it first met");
    }

    private static void pause(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static LevelAdmission tier() {
        Partner partner = new Partner();
        partner.setIdPartner(10);
        PackageAccount account = new PackageAccount();
        account.setId(1L);
        account.setUom("BDT");
        LevelAdmission level = new LevelAdmission(0, new Tenant("btcl"), partner, account);
        level.setReservedAmount(BigDecimal.ZERO);
        return level;
    }
}
