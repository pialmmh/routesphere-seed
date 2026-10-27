package com.telcobright.seed.prepaid.admin.lab;

import com.telcobright.memledger.api.MemLedger;
import com.telcobright.memledger.config.ReplicationConfig;
import com.telcobright.rtc.domainmodel.PartnerType;
import com.telcobright.rtc.domainmodel.mysqlentity.PackageAccount;
import com.telcobright.rtc.domainmodel.nonentity.Tenant;
import com.telcobright.seed.idgen.spi.IdSource;
import com.telcobright.seed.prepaid.api.Prepaid;
import com.telcobright.seed.prepaid.api.PrepaidAdmin;
import com.telcobright.seed.sessionflow.api.RoutesphereContext;
import com.telcobright.seed.sessionflow.dependencies.RoutesphereKit;
import com.telcobright.seed.sessionflow.spi.LedgerPort;
import com.telcobright.seed.sessionflow.spi.TenantLookup;
import com.telcobright.seed.sessionflow.testkit.ManualClock;
import com.telcobright.seed.sessionflow.testkit.RecordingCdrSink;
import com.telcobright.seed.sessionflow.testkit.TenantTreeBuilder;
import com.telcobright.seed.switchledger.api.LedgerDb;
import com.telcobright.seed.switchledger.api.LedgerPurchase;
import com.telcobright.seed.switchledger.dependencies.LedgerDbSettings;
import com.telcobright.seed.switchledger.dependencies.SwitchLedgerSettings;
import com.telcobright.seed.switchledger.dependencies.SwitchLedgers;

import javax.sql.DataSource;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * L2c · The books a purchase scenario runs on: one engine's three tiers, the rows an outside writer laid down, and the switch ledger of
 * the root as a ledger DB naming its three tables (accounts, reserves cache-only, purchases — never the prefix {@code package}, which
 * would name {@code packageaccountcharge} too: the ad lane does not adopt it, the owner 2026-10-27) — built by {@code LedgerDb.register}, as the
 * switch ledger's maker builds it — with the kit and the money roads over it ({@code PrepaidAdmin.withIds(<the application's IdSource>)}).
 * A restart = the MemLedger shut down and a new one built over the same database and WAL, with a new switch ledger (its memory empty).
 */
public final class PurchaseLab implements AutoCloseable {

    public static final String ACCOUNT = "PackageAccount";
    public static final String RESERVE = "PackageAccountReserve";
    public static final String PURCHASE = "PackagePurchase";
    /** The client buying at the second tier; 702 buys at the third, 700 at the root. */
    public static final int CLIENT = 701;
    static final ZoneId DHAKA = ZoneId.of("Asia/Dhaka");

    private final PurchaseLabEngine engine;
    private final IdSource ids;
    private final Path dir;
    private final ManualClock clock = new ManualClock(Instant.parse("2026-10-27T00:00:00Z"), DHAKA);
    private MemLedger ledger;
    private LedgerPort port;
    private PrepaidAdmin admin;

    private PurchaseLab(PurchaseLabEngine engine, IdSource ids, Path dir) {
        this.engine = engine;
        this.ids = ids;
        this.dir = dir;
    }

    /** The engine's tiers made fresh; the ids from {@code ids} (an InMemoryIdSource, or two live bucket-next shards). */
    public static PurchaseLab over(PurchaseLabEngine engine, IdSource ids) throws Exception {
        engine.freshTiers();
        return new PurchaseLab(engine, ids, Files.createTempDirectory("l2c-purchase-" + engine.name().toLowerCase()));
    }

    public String tier(int i) { return engine.tiers().get(i); }
    public DataSource db() { return engine.labDataSource(); }
    public MemLedger ledger() { return ledger; }
    public LedgerPort port() { return port; }
    public PrepaidAdmin admin() { return admin; }
    public long walRecords() { return PurchaseLabRows.walRecords(dir.resolve("wal")); }

    /** Every tier: purchases 1..3 of packages 1..3 and accounts 1..3 (account k of purchase k); the third tier also purchase 300 and account 500. */
    public void layDownTheOutsideWritersRows() throws Exception {
        for (String tier : engine.tiers()) {
            for (long k = 1; k <= 3; k++) {
                PurchaseLabRows.purchase(db(), tier, k, CLIENT, k, LocalDateTime.of(2030, 1, 1, 0, 0));
                PurchaseLabRows.account(db(), tier, k, k, "BDT", "50");
            }
        }
        PurchaseLabRows.purchase(db(), tier(2), 300, 702, 300, LocalDateTime.of(2030, 1, 1, 0, 0));
        PurchaseLabRows.account(db(), tier(2), 500, 300, "BDT", "50");
    }

    /** The root's switch ledger as a ledger DB over the engine, and the money roads over it. */
    public void start() {
        LedgerDbSettings settings = new LedgerDbSettings(List.of("packageaccount", "packageaccountreserve", "packagepurchase"), List.of("packageaccountreserve"), dir.resolve("wal"), dir.resolve("queue"), 5_000);
        var builder = MemLedger.builder().dataSource(engine.ledgerDataSource()).databases(engine.tiers().toArray(String[]::new))
            .replicationMode(ReplicationConfig.Mode.STANDALONE);
        Set<String> configured = LedgerDb.register(builder, settings);
        ledger = builder.build();
        port = SwitchLedgers.over(ledger, SwitchLedgerSettings.standard(), clock, configured).port();
        RoutesphereKit kit = RoutesphereKit.builder().tenants(TenantLookup.of(tree())).ledger(port).cdr(new RecordingCdrSink()).zone(DHAKA).clock(clock).build();
        admin = Prepaid.<RoutesphereContext>ofTheLanesAnswers(kit).admin().withIds(ids);
    }

    /** The process dies and starts again over the same database and WAL. */
    public void restart() {
        ledger.shutdown();
        start();
    }

    /** Waits until the tier's accounts in the database are the cache's — the same ids, each with the cache's balance — and its purchases too. */
    public void awaitTheDatabaseEqualsTheCache(String tier) throws Exception {
        PurchaseLabRows.await("the database of " + tier + " = the cache: " + cacheBalances(tier), () -> databaseBalances(tier).equals(cacheBalances(tier))
            && PurchaseLabRows.purchaseIds(db(), tier).equals(new java.util.TreeSet<>(ledger.<Long, LedgerPurchase>getAllEntities(tier, PURCHASE).keySet())));
    }

    /** A purchase offer of one package (prime-context: one purchase per package per tier), two buckets: 60 BDT and 300 minutes. */
    public static LedgerPort.Offer offer(long packageId) {
        LocalDateTime at = LocalDateTime.of(2026, 10, 27, 6, 0);
        return new LedgerPort.Offer(packageId, "Combo-" + packageId, new BigDecimal("99.00"), at, at.plusDays(30),
            List.of(new LedgerPort.Bucket("BDT", new BigDecimal("60")), new LedgerPort.Bucket("TF_min", new BigDecimal("300"))));
    }

    @Override
    public void close() throws Exception {
        if (ledger != null) ledger.shutdown();
        engine.close();
        try (Stream<Path> files = Files.walk(dir)) {
            files.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException ignored) {
            // best effort: a mapped Chronicle file still held is left to the OS
        }
    }

    private Map<Long, String> cacheBalances(String tier) {
        Map<Long, String> balances = new TreeMap<>();
        ledger.<Long, PackageAccount>getAllEntities(tier, ACCOUNT).forEach((id, a) -> balances.put(id, a.getBalanceAfter().stripTrailingZeros().toPlainString()));
        return balances;
    }

    private Map<Long, String> databaseBalances(String tier) throws Exception {
        Map<Long, String> balances = new TreeMap<>();
        for (Long id : PurchaseLabRows.accountIds(db(), tier)) balances.put(id, PurchaseLabRows.balance(db(), tier, id).stripTrailingZeros().toPlainString());
        return balances;
    }

    private Tenant tree() {
        TenantTreeBuilder b = new TenantTreeBuilder();
        b.root(tier(0)).partner(2, tier(1), PartnerType.RESELLER).partner(3, tier(2), PartnerType.RESELLER).partner(700, "Root client", PartnerType.CUSTOMER);
        b.tenant(tier(1), tier(0)).partner(CLIENT, "Client", PartnerType.CUSTOMER);
        b.tenant(tier(2), tier(0)).partner(702, "Client of res_3", PartnerType.CUSTOMER);
        return b.build();
    }
}
