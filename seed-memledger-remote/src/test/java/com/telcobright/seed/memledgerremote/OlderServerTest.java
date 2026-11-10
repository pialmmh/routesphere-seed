package com.telcobright.seed.memledgerremote;

import com.telcobright.rtc.domainmodel.LevelAdmission;
import com.telcobright.rtc.domainmodel.mysqlentity.PackageAccount;
import com.telcobright.rtc.domainmodel.mysqlentity.PackageAccountReserve;
import com.telcobright.rtc.domainmodel.mysqlentity.Partner;
import com.telcobright.rtc.domainmodel.nonentity.Tenant;
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
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ARCH-0118 item 7 (one way) — the NEW client on an OLDER server (no capabilities in its Health, the stub here): it sends no rows as
 * read with its writes, and the ghost a reload brings back is taken out by the client itself, as before ARCH-0118 — one writer per
 * instance. The other way (an old client on the new server) is recall-db's {@code CompatibilityTest}.
 */
class OlderServerTest {

    private final LedgerLab lab = LedgerLab.open().schema("btcl").schema("btcl_res_9").account("btcl", 1, 10, "BDT", "100.00")
        .reserveRow("btcl", "s-1#L0", 1, 10, "BDT", "2.00", "2026-10-24 00:00:00");                    // a reserve the ledger holds at its start
    private StubServer server;
    private RemoteMemLedger client;

    @BeforeEach
    void serve() {
        lab.start();
        server = new StubServer(lab.ledger());
        client = RemoteMemLedger.connect(new RemoteLedgerSettings(server.address(), 2_000, "LAB_LEDGER_TOKEN"), server.token,
            Map.of(Books.ACCOUNT, LedgerAccount.class, Books.RESERVE, PackageAccountReserve.class));
    }

    @AfterEach
    void close() {
        client.shutdown();
        server.close();
        lab.close();
    }

    @Test
    void theNewClientOnAnOlderServer_sendsNoRowsAsRead_andTakesOutTheGhostOfAReloadItself() {
        MemLedgerPort port = (MemLedgerPort) SwitchLedgers.memLedger(client, SwitchLedgerSettings.standard(), Clock.systemUTC());
        assertThat(port.reserve(tier(), new BigDecimal("1.00"), "r-1#L0")).as("an engine write through the client").isPresent();
        lab.reserveRow("btcl", "s-7#L0", 1, 10, "BDT", "5.00", "2026-10-24 00:00:00");                     // gone from the cache, still in the table

        new OwnedMemLedgerPort(port, client, Set.of("btcl"), tier -> true).tierAdded("btcl_res_9");    // the tier's merge of new rows

        assertThat(client.takesOutGhosts()).as("the older server says no 'ghosts'").isFalse();
        assertThat(client.checksExpectations()).as("the older server says no 'expect'").isFalse();
        assertThat(server.rowsAsReadSeen).as("every write carried no rows as read").isNotEmpty().containsOnly(0);
        assertThat(lab.cachedRow("btcl", "s-7#L0")).as("the ghost, taken out by the client: the server does not").isNull();
        assertThat(lab.cachedRow("btcl", "s-1#L0")).as("a reserve the ledger held stays").isNotNull();
        assertThat(lab.cachedRow("btcl", "r-1#L0")).as("the engine's own hold stays").isNotNull();
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
