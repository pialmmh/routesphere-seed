package com.telcobright.seed.switchledger;

import com.telcobright.rtc.domainmodel.LevelAdmission;
import com.telcobright.rtc.domainmodel.mysqlentity.PackageAccount;
import com.telcobright.rtc.domainmodel.mysqlentity.Partner;
import com.telcobright.rtc.domainmodel.nonentity.Tenant;
import com.telcobright.seed.ledgerclient.api.RemoteLedger;
import com.telcobright.seed.ledgerclient.api.RemoteLedger.Committed;
import com.telcobright.seed.ledgerclient.api.RemoteLedger.Key;
import com.telcobright.seed.ledgerclient.api.RemoteLedger.Refused;
import com.telcobright.seed.ledgerclient.api.RemoteLedgerFault;
import com.telcobright.seed.ledgerclient.dependencies.RemoteLedgers;
import com.telcobright.seed.sessionflow.spi.LedgerPort;
import com.telcobright.seed.switchledger.api.CachedLedgerRoads;
import com.telcobright.seed.switchledger.dependencies.SwitchLedgerSettings;
import com.telcobright.seed.switchledger.dependencies.SwitchLedgers;
import com.telcobright.seed.switchledger.internal.Books;
import com.telcobright.seed.switchledger.spi.LedgerGuard;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * C2 · TelcoREST's writes (RTC-Manager e20ea766, design §14.3) as the client TelcoREST will use sends them to the application's roads —
 * the same JVM, a REAL MemLedger and WAL over the H2 lab with TelcoREST's own columns beside the ledger's ({@code creditLimit},
 * {@code isSelected}, {@code paymentRef}). Each write is ONE request, ONE record; TelcoREST's rules (which purchase, how much) stay in
 * the test as they stay in TelcoREST. The door: the service token, the fields the road writes, a new row without its key, a row the
 * database would refuse.
 */
class TelcoRestContractTest {

    private static final byte[] SALT = "c2-lab-salt".getBytes(StandardCharsets.UTF_8);
    private static final String TOKEN = "telcorest-lab-token";
    private static final List<String> PURCHASE_FIELDS = List.of("idPackage", "idPartner", "purchaseDate", "expireDate", "status", "paid",
        "autoRenewalStatus", "price", "vat", "ait", "priority", "onSelectPriority", "discount", "paymentRef");
    /** C2b · every column of the account, TelcoREST's two included: nothing is written beside the ledger by SQL. */
    private static final List<String> ACCOUNT_FIELDS = List.of("idpackagePurchase", "name", "uom", "lastAmount", "balanceBefore", "balanceAfter", "creditLimit", "isSelected");

    private final LedgerLab lab = LedgerLab.open().schema("btcl")
        .account("btcl", 30, 9, "BDT", "50").account("btcl", 31, 8, "BDT", "1000").account("btcl", 32, 9, "OTH_ea", "100").account("btcl", 33, 9, "LEN_A", "5")
        .chargeReferences().purchaseTables()
        .purchase("btcl", 9, 4, "2030-01-01 00:00:00").purchase("btcl", 8, 5, "2030-01-01 00:00:00")
        .asLedgerDb(List.of("package"), List.of(), 5_000);
    private LedgerPort port;
    private RoadsInTheSameJvm roads;
    private RemoteLedger telcoRest;

    @BeforeEach
    void start() {
        lab.execute("UPDATE btcl.packageaccount SET name = 'TopUp' WHERE id_packageaccount = 30");
        lab.execute("UPDATE btcl.packageaccount SET name = 'Postpaid_Credit', creditLimit = 1000 WHERE id_packageaccount = 31");
        lab.start();
        port = SwitchLedgers.over(lab.ledger(), SwitchLedgerSettings.standard(), Clock.systemUTC(), LedgerLab.EVERY_ENTITY).port();
        roads = new RoadsInTheSameJvm(CachedLedgerRoads.over(port)
            .guard(LedgerGuard.ofTokenHash(SALT, LedgerGuard.sha256(SALT, TOKEN)))
            .ids((table, highestInUse) -> highestInUse + 1)
            .writes("packagepurchase", PURCHASE_FIELDS)
            .writes("packageaccount", ACCOUNT_FIELDS)
            .build());
        telcoRest = RemoteLedgers.builder().root("btcl").token(() -> TOKEN).transport(roads).build();
    }

    @AfterEach
    void close() { lab.close(); }

    @Test
    void aNewPurchaseIsOneRequest_itsAccountsPointAtItsMintedKey() {
        long records = lab.walRecords();
        RemoteLedger.Unit unit = telcoRest.unit("purchase-package:new-1");
        Key purchase = unit.insert("btcl", "packagepurchase", purchase(7, 4, 1, "ACTIVE"));
        Key minutes = unit.insert("btcl", "packageaccount", account(purchase, "PBX", "TF_min", "300"));
        Key channels = unit.insert("btcl", "packageaccount", account(purchase, "PBX", "OTH_cd", "2"));

        Committed done = (Committed) unit.commit();

        assertThat(lab.walRecords()).as("ONE record").isEqualTo(records + 1);
        assertThat(done.keys()).containsExactly(10L, 34L, 35L);
        assertThat(done.rows()).extracting(RemoteLedger.Row::table).containsExactly("packagepurchase", "packageaccount", "packageaccount");
        long minutesId = (Long) done.keyOf(minutes);
        LedgerLab.await("the accounts in the database under their minted keys", () -> lab.dbBalance("btcl", minutesId) != null);
        assertThat(lab.dbPurchase("btcl", minutesId)).isEqualByComparingTo(String.valueOf(done.keyOf(purchase)));
        assertThat(telcoRest.row("btcl", "packageaccount", done.keyOf(channels)).orElseThrow()).containsEntry("uom", "OTH_cd");
    }

    @Test
    void aRenewalMovesTheExpiryAndGrowsTheSlab_inOneRecord() {
        long records = lab.walRecords();

        Committed done = (Committed) telcoRest.unit("purchase-package:renew-9")
            .update("btcl", "packagepurchase", 9, Map.of("expireDate", "2030-01-31T00:00:00"))
            .add("btcl", "packageaccount", 32, "balanceAfter", new BigDecimal("200"))
            .commit();

        assertThat(lab.walRecords()).isEqualTo(records + 1);
        Map<String, Object> slab = telcoRest.row("btcl", "packageaccount", 32).orElseThrow();
        assertThat((BigDecimal) slab.get("balanceAfter")).isEqualByComparingTo("300");
        assertThat((BigDecimal) slab.get("balanceBefore")).isEqualByComparingTo("100");
        assertThat((BigDecimal) slab.get("lastAmount")).isEqualByComparingTo("200");
        assertThat(telcoRest.row("btcl", "packagepurchase", 9).orElseThrow()).containsEntry("expireDate", "2030-01-31T00:00");
        assertThat(done.repeated()).isFalse();
    }

    @Test
    void anUpgradeCarriesTheOldBalanceOverOnlyAsItWasRead() {
        BigDecimal read = (BigDecimal) telcoRest.row("btcl", "packageaccount", 30).orElseThrow().get("balanceAfter");
        port.charge(level(30), BigDecimal.TEN, "call-1#L0").orElseThrow();

        RemoteLedger.Outcome stale = upgrade(read).commit();
        BigDecimal again = (BigDecimal) telcoRest.row("btcl", "packageaccount", 30).orElseThrow().get("balanceAfter");
        Committed done = (Committed) upgrade(again).commit();

        assertThat(((Refused) stale).reason()).contains("balanceAfter is 40", "not 50");
        assertThat((BigDecimal) telcoRest.row("btcl", "packageaccount", done.keys().get(1)).orElseThrow().get("balanceAfter")).as("the plan's 100 + the 40 as read again").isEqualByComparingTo("140");
        assertThat(telcoRest.row("btcl", "packagepurchase", 9).orElseThrow()).containsEntry("status", "INACTIVE");
    }

    @Test
    void addingAgentsGrowsTheCount_andWritesTheInvoiceRow() {
        Committed done = (Committed) telcoRest.unit("add-agents:4:3").add("btcl", "packageaccount", 33, "balanceAfter", new BigDecimal("3")).commit();
        RemoteLedger.Unit invoice = telcoRest.unit("add-agents:4:3:invoice");
        invoice.insert("btcl", "packagepurchase", purchase(1, 4, 1, "ADD_AGENTS"));

        assertThat(invoice.commit()).isInstanceOf(Committed.class);
        assertThat((BigDecimal) telcoRest.row("btcl", "packageaccount", 33).orElseThrow().get("balanceAfter")).isEqualByComparingTo("8");
        assertThat(done.keys()).isEmpty();
    }

    @Test
    void aWelcomeIsGrantedOnce() {
        Map<String, Object> welcome = Map.of("idpackagePurchase", 9, "uom", "TF_min");

        RemoteLedger.Outcome first = grant(welcome), second = grant(welcome);

        assertThat(first).isInstanceOf(Committed.class);
        assertThat(((Refused) second).reason()).as("TelcoREST's ALREADY_GRANTED").contains("(require none)");
        assertThat(telcoRest.rows("btcl", "packageaccount", welcome)).hasSize(1);
    }

    @Test
    void theOperatorsPrioritiesAreOneRecord() {
        long records = lab.walRecords();

        telcoRest.unit().update("btcl", "packagepurchase", 9, Map.of("onSelectPriority", 2)).update("btcl", "packagepurchase", 8, Map.of("onSelectPriority", 1)).commit();

        assertThat(lab.walRecords()).isEqualTo(records + 1);
        assertThat(telcoRest.rows("btcl", "packagepurchase", Map.of("onSelectPriority", 1))).extracting(row -> row.get("id")).containsExactly(8L);
    }

    @Test
    void aTopUpIsOneRequest_aLostAnswerSentAgainCreditsOnce() {
        long topUp = (Long) telcoRest.rows("btcl", "packageaccount", Map.of("name", "TopUp", "uom", "BDT")).get(0).get("id");
        roads.loseTheNextAnswer();

        assertThatThrownBy(() -> topUp(topUp, "pay-77").commit()).isInstanceOf(RemoteLedgerFault.class);
        Committed again = (Committed) topUp(topUp, "pay-77").commit();

        assertThat(again.repeated()).as("written the first time: the same reference answers it").isTrue();
        Map<String, Object> account = telcoRest.row("btcl", "packageaccount", topUp).orElseThrow();
        assertThat((BigDecimal) account.get("balanceAfter")).as("credited once").isEqualByComparingTo("550");
        assertThat(account.get("idpackagePurchase")).as("on the top-up's purchase").isEqualTo(again.keys().get(0));
        LedgerLab.await("the write-behind", () -> lab.dbBalance("btcl", topUp).compareTo(new BigDecimal("550")) == 0);
        assertThat(telcoRest.row("btcl", "packagepurchase", again.keys().get(0)).orElseThrow()).as("the purchase carries its payment").containsEntry("paymentRef", "pay-77");
    }

    @Test
    void aPaymentIsOnePurchase_aSecondUnitForItIsRefused_byTheCheckOrByTheUniqueColumn() {
        long topUp = (Long) telcoRest.rows("btcl", "packageaccount", Map.of("name", "TopUp", "uom", "BDT")).get(0).get("id");
        assertThat(topUp(topUp, "pay-88").commit()).isInstanceOf(Committed.class);
        long records = lab.walRecords();

        RemoteLedger.Unit redriven = telcoRest.unit("gateway-redrive-2").requireNone("btcl", "packagepurchase", Map.of("paymentRef", "pay-88"));
        redriven.insert("btcl", "packagepurchase", fields(purchase(2, 4, 2, "ACTIVE"), "paymentRef", "pay-88"));
        RemoteLedger.Unit unchecked = telcoRest.unit("gateway-redrive-3");
        unchecked.insert("btcl", "packagepurchase", fields(purchase(2, 4, 2, "ACTIVE"), "paymentRef", "pay-88"));

        assertThat(((Refused) redriven.commit()).reason()).as("TelcoREST's AlreadyApplied, from the data itself").contains("(require none)");
        assertThat(((Refused) unchecked.commit()).reason()).as("never a row the database's unique key would refuse").contains("paymentRef pay-88 is PackagePurchase", "the column is unique");
        assertThat(lab.walRecords()).isEqualTo(records);
        assertThat((BigDecimal) telcoRest.row("btcl", "packageaccount", topUp).orElseThrow().get("balanceAfter")).as("credited once").isEqualByComparingTo("550");
    }

    @Test
    void aPostpaidLimitGoesThroughTheLedger_andASessionsMoveKeepsIt() {
        RemoteLedger.Unit setup = telcoRest.unit("postpaid:6:setup");
        Key purchase = setup.insert("btcl", "packagepurchase", purchase(9999, 6, 3, "ACTIVE"));
        Key credit = setup.insert("btcl", "packageaccount", fields(account(purchase, "Postpaid_Credit", "BDT", "5000"), "creditLimit", new BigDecimal("5000")));
        long account = (Long) ((Committed) setup.commit()).keyOf(credit);

        port.charge(level(account), new BigDecimal("100"), "call-6#L0").orElseThrow();

        LedgerLab.await("the session's move in the database", () -> lab.dbBalance("btcl", account) != null && lab.dbBalance("btcl", account).compareTo(new BigDecimal("4900")) == 0);
        assertThat(lab.dbDecimal("SELECT creditLimit FROM btcl.packageaccount WHERE id_packageaccount = " + account))
            .as("written by the ledger, and every copy of the row keeps it").isEqualByComparingTo("5000");
        assertThat((BigDecimal) telcoRest.row("btcl", "packageaccount", account).orElseThrow().get("creditLimit")).isEqualByComparingTo("5000");
    }

    @Test
    void aPostpaidLimitIsLowered_theAvailableBalanceWithIt() {
        BigDecimal available = (BigDecimal) telcoRest.row("btcl", "packageaccount", 31).orElseThrow().get("balanceAfter");
        BigDecimal oldLimit = new BigDecimal("1000"), newLimit = new BigDecimal("600");
        BigDecimal newAvailable = available.add(newLimit.subtract(oldLimit)).max(BigDecimal.ZERO).min(newLimit);

        RemoteLedger.Unit unit = telcoRest.unit("postpaid:5:600");
        Key purchase = unit.insert("btcl", "packagepurchase", purchase(9999, 5, 3, "ACTIVE"));
        unit.requireEqual("btcl", "packageaccount", 31, "balanceAfter", available)
            .update("btcl", "packageaccount", 31, fields("idpackagePurchase", purchase, "balanceAfter", newAvailable, "lastAmount", oldLimit.subtract(newLimit), "creditLimit", newLimit));

        assertThat(unit.commit()).as("today CANNOT_DEBIT").isInstanceOf(Committed.class);
        LedgerLab.await("the write-behind", () -> lab.dbBalance("btcl", 31).compareTo(new BigDecimal("600")) == 0);
        assertThat(lab.dbDecimal("SELECT creditLimit FROM btcl.packageaccount WHERE id_packageaccount = 31")).as("the limit with the balance, ONE record").isEqualByComparingTo("600");
    }

    // ── the door ────────────────────────────────────────────────────────────

    @Test
    void aCallerWithoutTheToken_isRefusedAtTheDoor() {
        RemoteLedger stranger = RemoteLedgers.builder().root("btcl").token(() -> "guess").transport(roads).build();

        assertThatThrownBy(() -> stranger.row("btcl", "packageaccount", 30)).isInstanceOf(IllegalStateException.class).hasMessageContaining("not allowed");
        assertThatThrownBy(() -> stranger.unit().add("btcl", "packageaccount", 30, "balanceAfter", BigDecimal.ONE).commit()).isInstanceOf(IllegalStateException.class);
        assertThat(lab.cachedBalance("btcl", 30)).isEqualByComparingTo("50");
    }

    @Test
    void aFieldTheRoadDoesNotWrite_aNewRowWithItsKey_areRefused_nothingWritten() {
        long records = lab.walRecords();
        Map<String, Object> withKey = new LinkedHashMap<>(account(9, "x", "BDT", "1"));
        withKey.put("id", 99);

        assertThatThrownBy(() -> telcoRest.unit().update("btcl", "packageaccount", 30, Map.of("expireDate", "2031-01-01T00:00:00")).commit())
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("does not write packageaccount.expireDate");
        assertThatThrownBy(() -> { RemoteLedger.Unit u = telcoRest.unit(); u.insert("btcl", "packageaccount", withKey); u.commit(); })
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("mints it");
        assertThatThrownBy(() -> telcoRest.unit().add("btcl", "packageaccountreserve", "x", "reserveUnit", BigDecimal.ONE).commit())
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("does not write packageaccountreserve");
        assertThat(lab.walRecords()).isEqualTo(records);
    }

    @Test
    void aRowTheDatabaseWouldRefuseIsRefusedFirst_theWriteBehindGoesOn() {
        RemoteLedger.Unit tooLong = telcoRest.unit();
        tooLong.insert("btcl", "packageaccount", account(9, "x", "U".repeat(46), "1"));
        RemoteLedger.Unit tooBig = telcoRest.unit();
        tooBig.insert("btcl", "packagepurchase", fields(purchase(1, 4, 1, "ACTIVE"), "price", new BigDecimal("100000000")));
        RemoteLedger.Unit onNoPurchase = telcoRest.unit();
        onNoPurchase.insert("btcl", "packageaccount", account(777, "x", "BDT", "1"));

        assertThat(((Refused) tooLong.commit()).reason()).contains("uom is 46 characters, its column holds 45");
        assertThat(((Refused) tooBig.commit()).reason()).contains("price 100000000 does not fit its column (10 digits, 2 after the point)");
        assertThat(((Refused) onNoPurchase.commit()).reason()).contains("its purchase 777 is not held in btcl");
        telcoRest.unit().add("btcl", "packageaccount", 30, "balanceAfter", BigDecimal.ONE).commit();
        LedgerLab.await("the write-behind still runs", () -> lab.dbBalance("btcl", 30).compareTo(new BigDecimal("51")) == 0);
    }

    // ── TelcoREST's moves, as it would send them ────────────────────────────

    private RemoteLedger.Unit topUp(long account, String paymentRef) {
        RemoteLedger.Unit unit = telcoRest.unit("topup:" + paymentRef);
        Key purchase = unit.insert("btcl", "packagepurchase", fields(purchase(2, 4, 2, "ACTIVE"), "paymentRef", paymentRef));
        return unit.update("btcl", "packageaccount", account, Map.of("idpackagePurchase", purchase))
            .add("btcl", "packageaccount", account, "balanceAfter", new BigDecimal("500"));
    }

    private RemoteLedger.Unit upgrade(BigDecimal oldBalanceAsRead) {
        RemoteLedger.Unit unit = telcoRest.unit();
        unit.requireEqual("btcl", "packageaccount", 30, "balanceAfter", oldBalanceAsRead).update("btcl", "packagepurchase", 9, Map.of("status", "INACTIVE"));
        Key purchase = unit.insert("btcl", "packagepurchase", purchase(3, 4, 1, "ACTIVE"));
        unit.insert("btcl", "packageaccount", account(purchase, "Gold", "BDT", new BigDecimal("100").add(oldBalanceAsRead).toPlainString()));
        return unit;
    }

    private RemoteLedger.Outcome grant(Map<String, Object> welcome) {
        RemoteLedger.Unit unit = telcoRest.unit().requireNone("btcl", "packageaccount", welcome);
        unit.insert("btcl", "packageaccount", account(9, "Welcome Talktime", "TF_min", "60"));
        return unit.commit();
    }

    private static Map<String, Object> purchase(long packageId, long partnerId, int priority, String status) {
        return fields("idPackage", packageId, "idPartner", partnerId, "purchaseDate", "2026-10-28T03:00:00", "expireDate", "2026-11-28T03:00:00",
            "status", status, "paid", 1, "autoRenewalStatus", false, "price", new BigDecimal("500.00"), "vat", BigDecimal.ZERO, "ait", BigDecimal.ZERO,
            "priority", priority, "discount", 0);
    }

    private static Map<String, Object> account(Object purchase, String name, String uom, String opening) {
        return fields("idpackagePurchase", purchase, "name", name, "uom", uom, "balanceBefore", BigDecimal.ZERO, "balanceAfter", new BigDecimal(opening),
            "lastAmount", new BigDecimal(opening));
    }

    private static Map<String, Object> fields(Object... namesAndValues) {
        Map<String, Object> fields = new LinkedHashMap<>();
        for (int i = 0; i < namesAndValues.length; i += 2) fields.put((String) namesAndValues[i], namesAndValues[i + 1]);
        return fields;
    }

    private static Map<String, Object> fields(Map<String, Object> base, String name, Object value) {
        Map<String, Object> fields = new LinkedHashMap<>(base);
        fields.put(name, value);
        return fields;
    }

    private static LevelAdmission level(long accountId) {
        Partner partner = new Partner();
        partner.setIdPartner(4);
        PackageAccount account = new PackageAccount();
        account.setId(accountId);
        return new LevelAdmission(0, new Tenant("btcl"), partner, account);
    }
}
