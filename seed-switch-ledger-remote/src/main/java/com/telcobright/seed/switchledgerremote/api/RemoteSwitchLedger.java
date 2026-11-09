package com.telcobright.seed.switchledgerremote.api;

import com.telcobright.rtc.domainmodel.LevelAdmission;
import com.telcobright.rtc.domainmodel.mysqlentity.PackageAccount;
import com.telcobright.seed.sessionflow.api.LogText;
import com.telcobright.seed.sessionflow.api.TierSettlement;
import com.telcobright.seed.sessionflow.spi.LedgerPort;
import com.telcobright.seed.sessionflow.spi.LedgerUnit;
import com.telcobright.seed.switchledger.api.LiveBalance;
import com.telcobright.seed.switchledgerremote.dependencies.RemoteLedgerSettings;
import com.telcobright.seed.switchledgerremote.internal.BearerHeader;
import com.telcobright.seed.switchledgerremote.internal.Calls;
import com.telcobright.seed.switchledgerremote.internal.CatalogCache;
import com.telcobright.seed.switchledgerremote.internal.RemoteAccounts;
import com.telcobright.seed.switchledgerremote.internal.RemoteCredit;
import com.telcobright.seed.switchledgerremote.internal.RemoteUnit;
import com.telcobright.seed.switchledgerremote.internal.Wire;
import com.telcobright.seed.switchledgerremote.internal.WireRows;
import com.telcobright.seed.switchledgerremote.wire.CreditRequest;
import com.telcobright.seed.switchledgerremote.wire.Empty;
import com.telcobright.seed.switchledgerremote.wire.PurchaseRequest;
import com.telcobright.seed.switchledgerremote.wire.ReparentRequest;
import com.telcobright.seed.switchledgerremote.wire.Tier;
import io.grpc.Channel;
import io.grpc.ClientInterceptors;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * The seed's {@link LedgerPort} as a gRPC client of the switch-ledger service (design §3.3, the owner's road B): one money process per
 * database, this application one of its callers. Every verb is one call bounded by {@code deadline-ms} (a reserve by
 * {@code min(deadline-ms, withinMs)}); a unit is one bidirectional stream; the catalog of a tier is a snapshot fetched at the start, on
 * each ring of the doorbell and when a tier is added — a map get in the hot path; {@code tierLoaded} answers from the last Health read.
 * A status that is not OK is a {@code LedgerFault} — the pipeline says BILLING_SYSTEM_ERROR, never a balance cause; the ledger's own
 * refusals travel in the reply and are thrown as {@code LedgerRefusal}, as the embedded port throws them. The bearer rides on every call
 * and is never logged. {@code purchase} ignores the caller's {@code Ids}: the service mints (seed-idgen) on its side.
 */
public final class RemoteSwitchLedger implements LedgerPort, LiveBalance, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(RemoteSwitchLedger.class);

    private final ManagedChannel channel;
    private final Calls calls;
    private final RemoteLedgerSettings settings;
    private final CatalogCache catalogs;
    private final CreditDoor credit;
    private final AccountReads accounts;
    private volatile LedgerHealth lastHealth = LedgerHealth.unknown();

    private RemoteSwitchLedger(ManagedChannel channel, Channel withBearer, RemoteLedgerSettings settings) {
        this.channel = channel;
        this.settings = settings;
        this.calls = new Calls(withBearer, settings);
        this.catalogs = new CatalogCache(db -> calls.call("catalog", s -> s.catalog(Tier.newBuilder().setDbName(db).build())));
        this.credit = new RemoteCredit(calls);
        this.accounts = new RemoteAccounts(calls);
    }

    /** A catalog snapshot carries every purchase's package: room for 10^5 purchases and more. */
    public static final int MAX_INBOUND_MESSAGE_BYTES = 32 * 1024 * 1024;

    /** A client of the service at {@code settings.address()}, plaintext on the private mesh, warmed (Health + the catalogs of its tiers). */
    public static RemoteSwitchLedger connect(RemoteLedgerSettings settings, String bearer) {
        ManagedChannel channel = ManagedChannelBuilder.forTarget(settings.address()).usePlaintext().maxInboundMessageSize(MAX_INBOUND_MESSAGE_BYTES).build();
        RemoteSwitchLedger ledger = over(channel, settings, bearer);
        ledger.warm();
        return ledger;
    }

    /** A client over a channel made elsewhere (the twice-run proof's loopback); {@code bearer} null = no header. */
    public static RemoteSwitchLedger over(ManagedChannel channel, RemoteLedgerSettings settings, String bearer) {
        Channel withBearer = bearer == null ? channel : ClientInterceptors.intercept(channel, new BearerHeader(bearer));
        return new RemoteSwitchLedger(channel, withBearer, settings);
    }

    /** Health read and the catalogs of the tiers it names fetched; a failure is said, not thrown — the first call will say it again. */
    public void warm() {
        try {
            refreshHealth();
            catalogs.refreshAll(lastHealth.tiers().keySet());
            log.info("switch-ledger client: {} ready={} tiers={} wal_lag={} version={}", settings.address(), lastHealth.ready(), lastHealth.tiers(), lastHealth.walLag(), lastHealth.version());
        } catch (RuntimeException e) {
            log.warn("switch-ledger client: {} did not answer at the start ({}) — every call will say so until it does", settings.address(), LogText.failureText(e));
        }
    }

    // ── the port's money verbs ──────────────────────────────────────────────

    @Override public Optional<Reservation> reserve(LevelAdmission level, BigDecimal amount, String reference) { return reserve(level, amount, reference, settings.deadlineMs()); }

    @Override public Optional<Reservation> reserve(LevelAdmission level, BigDecimal amount, String reference, long withinMs) {
        return Wire.reservationOf(calls.call("reserve", calls.within(withinMs), s -> s.reserve(Wire.moveOf(level, amount, reference))));
    }

    @Override public Optional<Reservation> charge(LevelAdmission level, BigDecimal amount, String reference) {
        return Wire.reservationOf(calls.call("charge", s -> s.charge(Wire.moveOf(level, amount, reference))));
    }

    @Override public TierSettlement settle(LevelAdmission level, BigDecimal charged) { return Wire.settlementOf(calls.call("settle", s -> s.settle(Wire.settlementOf(level, charged)))); }

    @Override public void release(LevelAdmission level, String why) { Wire.acked(calls.call("release", s -> s.release(Wire.releaseOf(level, why)))); }

    @Override public TierSettlement refund(LevelAdmission level, BigDecimal amount, String why) { return Wire.settlementOf(calls.call("refund", s -> s.refund(Wire.refundOf(level, amount, why)))); }

    @Override public LedgerUnit unit() { return new RemoteUnit(calls.async(), settings.deadlineMs(), settings.address(), settings.tokenVar()); }

    @Override public Credited credit(LevelAdmission account, BigDecimal amount, String reference) {
        CreditRequest request = CreditRequest.newBuilder().setAccount(Wire.tierOf(account)).setAmount(Wire.text(amount)).setReference(Wire.orEmpty(reference)).build();
        return Wire.portCreditedOf(calls.door("credit", s -> s.credit(request)));
    }

    @Override public Credited reparent(LevelAdmission account, long purchaseId) {
        ReparentRequest request = ReparentRequest.newBuilder().setAccount(Wire.tierOf(account)).setPurchaseId(purchaseId).build();
        return Wire.portCreditedOf(calls.door("reparent", s -> s.reparent(request)));
    }

    // ── purchases, reads, the catalog ───────────────────────────────────────

    /** The service mints the ids ({@code ids} is not used); the tier's catalog is fetched again after — its purchases' packages now name the new one, as the embedded index would. */
    @Override public Purchased purchase(LevelAdmission buyer, Offer offer, String reference, Ids ids) {
        PurchaseRequest request = PurchaseRequest.newBuilder().setBuyer(Wire.tierOf(buyer)).setOffer(WireRows.offerOf(offer)).setReference(Wire.orEmpty(reference)).build();
        var reply = calls.call("purchase", s -> s.purchase(request));
        Purchased made = WireRows.purchasedOf(reply);
        catalogs.refresh(buyer.getDbName());
        return made;
    }

    @Override public Optional<List<PackageAccount>> accountsOf(LevelAdmission buyer) {
        var reply = calls.call("accountsOf", s -> s.accountsOf(Wire.buyerOf(buyer)));
        return WireRows.accountsOf(reply);
    }

    @Override public Optional<List<Purchase>> purchasesOf(LevelAdmission buyer) {
        var reply = calls.call("purchasesOf", s -> s.purchasesOf(Wire.buyerOf(buyer)));
        return WireRows.purchasesOf(reply);
    }

    @Override public Optional<BigDecimal> balanceOf(LevelAdmission level) {
        var reply = calls.call("balanceOf", s -> s.balanceOf(Wire.tierOf(level)));
        return Wire.balanceOf(reply);
    }

    @Override public Optional<ItemCatalog> catalogOf(LevelAdmission tier) { return catalogs.of(tier.getDbName()); }

    // ── the ledger's life ───────────────────────────────────────────────────

    @Override public void tierAdded(String tierDb) {
        catalogs.refresh(tierDb);
        refreshHealthQuietly("tierAdded " + tierDb);
    }

    @Override public void doorbell(String what, List<String> changes) {
        refreshHealthQuietly("doorbell " + what);
        catalogs.refreshAll(lastHealth.tiers().keySet());
    }

    @Override public boolean tierLoaded(String tierDb) {
        if (!lastHealth.known()) refreshHealthQuietly("tierLoaded " + tierDb);
        return lastHealth.tierLoaded(tierDb);
    }

    @Override public long slowestAnswerMs() { return settings.deadlineMs(); }

    /** The credit door: Credit, Reparent, CapAt by schema and account id. */
    public CreditDoor credit() { return credit; }

    /** The three account reads. */
    public AccountReads accounts() { return accounts; }

    /** Health read now. */
    public LedgerHealth health() { return refreshHealth(); }

    @Override
    public void close() {
        channel.shutdownNow();
        try {
            channel.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private LedgerHealth refreshHealth() {
        lastHealth = LedgerHealth.of(calls.call("health", s -> s.health(Empty.getDefaultInstance())));
        return lastHealth;
    }

    private void refreshHealthQuietly(String on) {
        try {
            refreshHealth();
        } catch (RuntimeException e) {
            log.warn("switch-ledger client: health not read on {} ({}) — the last one stands", on, LogText.failureText(e));
        }
    }
}
