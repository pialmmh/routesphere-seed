package com.telcobright.seed.switchledger;

import com.telcobright.core.wal.WALEntryBatch;
import com.telcobright.memledger.api.MemLedger;
import com.telcobright.core.cache.CacheableEntity;
import com.telcobright.memledger.config.ReplicationConfig;
import com.telcobright.rtc.domainmodel.mysqlentity.PackageAccount;
import com.telcobright.rtc.domainmodel.mysqlentity.PackageAccountReserve;
import com.telcobright.seed.switchledger.internal.Books;
import net.openhft.chronicle.queue.ChronicleQueue;
import net.openhft.chronicle.queue.ExcerptTailer;
import net.openhft.chronicle.queue.RollCycles;
import net.openhft.chronicle.queue.impl.single.SingleChronicleQueueBuilder;
import net.openhft.chronicle.wire.DocumentContext;
import org.h2.jdbcx.JdbcDataSource;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;

/**
 * The lab of every test of the switch ledger: a REAL MemLedger (no mock) — H2 in MySQL mode, in memory, as its DataSource (the
 * {@code EntityCacheLoader} loads {@code SELECT * FROM schema.table} from it at the build; the WAL consumer writes {@code INSERT …},
 * {@code UPDATE …}, {@code DELETE …} and the offset's {@code INSERT … ON DUPLICATE KEY UPDATE} back to it), the Chronicle queue in a
 * directory of its own, STANDALONE. Each tier is a SCHEMA of the one database, as on the switch ({@code btcl}, {@code res_2} …).
 *
 * <p>Nothing listens on a port: H2 is in-process, the MemLedger runs no REST or gRPC. The lab says so first, as every lab does.
 */
public final class LedgerLab implements AutoCloseable {

    private static final AtomicInteger LABS = new AtomicInteger();

    private final String url;
    private final JdbcDataSource dataSource = new JdbcDataSource();
    private final Path dir;
    private final List<String> schemas = new ArrayList<>();
    private final Map<String, Class<? extends CacheableEntity>> more = new LinkedHashMap<>();
    /** The extra entities' tables: made in every schema, those made later too (a tier created live). */
    private final List<String> moreTables = new ArrayList<>();
    private MemLedger ledger;
    /** L2b item 3 · Built as a ledger DB (its entities by name or prefix, its cache-only tables); null = the two tables + the extra ones. */
    private com.telcobright.seed.switchledger.dependencies.LedgerDbSettings ledgerDb;

    private LedgerLab(Path dir) {
        this.dir = dir;
        this.url = "jdbc:h2:mem:switchledger" + LABS.incrementAndGet() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1";
        dataSource.setURL(url);
        dataSource.setUser("sa");
        dataSource.setPassword("");
    }

    public static LedgerLab open() {
        try {
            return new LedgerLab(Files.createTempDirectory("switch-ledger-lab"));
        } catch (IOException e) {
            throw new IllegalStateException("no temp directory for the lab", e);
        }
    }

    // ── the scene, before the ledger starts ─────────────────────────────────

    /** One tier's schema with the two tables of the switch ledger. */
    public LedgerLab schema(String name) {
        schemas.add(name);
        sql("CREATE SCHEMA IF NOT EXISTS " + name);
        sql("CREATE TABLE " + name + ".packageaccount (id_packageaccount BIGINT NOT NULL PRIMARY KEY, id_PackagePurchase BIGINT NOT NULL, name VARCHAR(255),"
            + " lastAmount DECIMAL(20,6) NOT NULL, balanceBefore DECIMAL(20,6) NOT NULL, balanceAfter DECIMAL(20,6) NOT NULL, uom VARCHAR(45) NOT NULL)");
        sql("CREATE TABLE " + name + ".packageaccountreserve (channel_call_uuid VARCHAR(255) NOT NULL PRIMARY KEY, id_packageaccount BIGINT NOT NULL,"
            + " id_PackagePurchase BIGINT NOT NULL, name VARCHAR(255) NOT NULL, reserveUnit DECIMAL(20,6) NOT NULL, uom VARCHAR(45) NOT NULL, time VARCHAR(64))");
        for (String ddl : moreTables) sql(String.format(ddl, name));
        return this;
    }

    /** A package account row, as the switch's MySQL holds it. */
    public LedgerLab account(String schema, long id, long purchase, String uom, String balance) {
        sql("INSERT INTO " + schema + ".packageaccount (id_packageaccount, id_PackagePurchase, name, lastAmount, balanceBefore, balanceAfter, uom) VALUES ("
            + id + ", " + purchase + ", '" + uom + "-" + id + "', 0, " + balance + ", " + balance + ", '" + uom + "')");
        return this;
    }

    /** A reserve row left behind (an orphan for the reaper). */
    public LedgerLab reserveRow(String schema, String key, long accountId, long purchase, String uom, String amount, String time) {
        sql("INSERT INTO " + schema + ".packageaccountreserve (channel_call_uuid, id_packageaccount, id_PackagePurchase, name, reserveUnit, uom, time) VALUES ('"
            + key + "', " + accountId + ", " + purchase + ", 'SESSION-Reserve-" + key + "', " + amount + ", '" + uom + "', '" + time + "')");
        return this;
    }

    /** A THIRD entity registered in the MemLedger (what the switch ledger refuses), with its table in every schema. */
    public LedgerLab alsoRegister(String entityName, Class<? extends CacheableEntity> entityClass, String tableDdlPerSchema) {
        more.put(entityName, entityClass);
        moreTables.add(tableDdlPerSchema);
        for (String schema : schemas) sql(String.format(tableDdlPerSchema, schema));
        return this;
    }

    /** L2b 2b · The charges' durable references: {@code packageaccountcharge} in every schema made so far, its entity registered. */
    public LedgerLab chargeReferences() {
        String ddl = "CREATE TABLE %s.packageaccountcharge (reference VARCHAR(255) NOT NULL PRIMARY KEY, id_packageaccount BIGINT NOT NULL, uom VARCHAR(45) NOT NULL,"
            + " amount DECIMAL(20,6) NOT NULL, balanceBefore DECIMAL(20,6) NOT NULL, balanceAfter DECIMAL(20,6) NOT NULL, refunded DECIMAL(20,6), time VARCHAR(64))";
        return alsoRegister(Books.CHARGE, com.telcobright.seed.switchledger.api.ChargeReference.class, ddl);
    }

    /** L2b item 4 · The purchases' table ({@code packagepurchase}, its columns as routesphere-core's) in every schema, made so far and later. */
    public LedgerLab purchaseTables() {
        String ddl = "CREATE TABLE %s.packagepurchase (id BIGINT NOT NULL PRIMARY KEY, id_package BIGINT NOT NULL, id_Partner BIGINT NOT NULL,"
            + " purchaseDate TIMESTAMP NOT NULL, expireDate TIMESTAMP NOT NULL, status VARCHAR(50) NOT NULL, paid BIGINT NOT NULL, autoRenewalStatus BOOLEAN NOT NULL,"
            + " price DECIMAL(10,2) NOT NULL, vat DECIMAL(10,2) NOT NULL, AIT DECIMAL(10,2) NOT NULL, priority INT NOT NULL, onSelectPriority INT, discount INT NOT NULL)";
        moreTables.add(ddl);
        for (String schema : schemas) sql(String.format(ddl, schema));
        return this;
    }

    /** L2b item 4 · A purchase row, as an outside writer (the billing portal) makes it. */
    public LedgerLab purchase(String schema, long id, long partnerId, String expires) {
        sql("INSERT INTO " + schema + ".packagepurchase (id, id_package, id_Partner, purchaseDate, expireDate, status, paid, autoRenewalStatus, price, vat, AIT, priority, discount)"
            + " VALUES (" + id + ", 1, " + partnerId + ", TIMESTAMP '2026-01-01 00:00:00', TIMESTAMP '" + expires + "', 'ACTIVE', 1, FALSE, 0, 0, 0, 1, 0)");
        return this;
    }

    /** L2b 2b · The process dies and starts again: the MemLedger shut down, a new one built over the same database and WAL (it replays, then loads). */
    public MemLedger restart() {
        ledger.shutdown();
        return start();
    }

    /** L2b item 3 · The ledger as a ledger DB: these entities (names or table prefixes), these cache-only tables, this reload spacing. */
    public LedgerLab asLedgerDb(List<String> entities, List<String> cacheOnly, long reloadSpacingMs) {
        ledgerDb = new com.telcobright.seed.switchledger.dependencies.LedgerDbSettings(entities, cacheOnly, dir.resolve("wal"), dir.resolve("queue"), reloadSpacingMs);
        return this;
    }

    public com.telcobright.seed.switchledger.dependencies.LedgerDbSettings ledgerDbSettings() { return ledgerDb; }

    /** Build the MemLedger: it loads every schema's two tables and replays the (empty) WAL. */
    public MemLedger start() {
        System.out.println("LedgerLab: no network listener — H2 in memory (" + url + "), MemLedger STANDALONE (no REST, no gRPC), queue at " + dir);
        var builder = MemLedger.builder()
            .dataSource(dataSource)
            .databases(schemas.toArray(String[]::new))
            .replicationMode(ReplicationConfig.Mode.STANDALONE);
        if (ledgerDb != null) {
            com.telcobright.seed.switchledger.api.LedgerDb.register(builder, ledgerDb);
        } else {
            builder.registerEntity(Books.ACCOUNT, PackageAccount.class).registerEntity(Books.RESERVE, PackageAccountReserve.class)
                .walPath(dir.resolve("wal").toString()).queuePath(dir.resolve("queue").toString());
            more.forEach(builder::registerEntity);
        }
        ledger = builder.build();
        return ledger;
    }

    public MemLedger ledger() { return ledger; }

    /** The lab's H2 url: a maker that opens its own MemLedger connects here (P1-E item 6). */
    public String url() { return url; }

    // ── looking at the books ────────────────────────────────────────────────

    public BigDecimal cachedBalance(String schema, long accountId) {
        PackageAccount a = ledger.getEntity(schema, Books.ACCOUNT, accountId);
        return a == null ? null : a.getBalanceAfter();
    }

    public PackageAccountReserve cachedRow(String schema, String key) { return ledger.getEntity(schema, Books.RESERVE, key); }

    public int cachedReserveRows(String schema) { return ledger.getAllEntities(schema, Books.RESERVE).size(); }

    /** What MySQL (H2) holds — the WAL consumer's write-behind, so a test waits for it with {@link #await}. */
    public BigDecimal dbBalance(String schema, long accountId) {
        return queryDecimal("SELECT balanceAfter FROM " + schema + ".packageaccount WHERE id_packageaccount = " + accountId);
    }

    public int dbReserveRows(String schema) { return queryDecimal("SELECT COUNT(*) FROM " + schema + ".packageaccountreserve").intValue(); }

    public BigDecimal dbReserveUnit(String schema, String key) {
        return queryDecimal("SELECT reserveUnit FROM " + schema + ".packageaccountreserve WHERE channel_call_uuid = '" + key + "'");
    }

    public BigDecimal dbPurchase(String schema, long accountId) {
        return queryDecimal("SELECT id_PackagePurchase FROM " + schema + ".packageaccount WHERE id_packageaccount = " + accountId);
    }

    /**
     * L2b · The records in the ledger's REAL WAL that carry at least one entry (the start's probe marker carries none), read by the lab's own
     * tailer over the same queue files — the way mem-ledger's L1 scenario counts them (one business operation = ONE record).
     */
    public long walRecords() { return walRecordsAndEntries()[0]; }

    /** L2b · The entries of the LAST record that carries any (0 when none): how many rows one record wrote. */
    public long entriesOfTheLastRecord() { return walRecordsAndEntries()[1]; }

    private long[] walRecordsAndEntries() {
        long records = 0, lastEntries = 0;
        try (ChronicleQueue queue = SingleChronicleQueueBuilder.binary(dir.resolve("wal").toString()).rollCycle(RollCycles.DAILY).build()) {
            ExcerptTailer tailer = queue.createTailer();
            tailer.toStart();
            while (true) {
                try (DocumentContext dc = tailer.readingDocument()) {
                    if (!dc.isPresent()) return new long[] {records, lastEntries};
                    WALEntryBatch record = dc.wire().read("batch").object(WALEntryBatch.class);
                    if (record != null && record.getEntries() != null && !record.getEntries().isEmpty()) {
                        records++;
                        lastEntries = record.getEntries().size();
                    }
                }
            }
        }
    }

    public static void await(String what, BooleanSupplier condition) {
        long deadline = System.currentTimeMillis() + 15_000;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) throw new AssertionError("timed out waiting for: " + what);
            try { Thread.sleep(25); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
        }
    }

    // ── the end ─────────────────────────────────────────────────────────────

    @Override
    public void close() {
        if (ledger != null) ledger.shutdown();
        sql("SHUTDOWN");
        try (Stream<Path> files = Files.walk(dir)) {
            files.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException ignored) {
            // best effort: a mapped Chronicle file still held is left to the OS
        }
    }

    private void sql(String statement) {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.execute(statement);
        } catch (SQLException e) {
            throw new IllegalStateException(statement, e);
        }
    }

    private BigDecimal queryDecimal(String query) {
        try (Connection c = dataSource.getConnection(); PreparedStatement s = c.prepareStatement(query); ResultSet rs = s.executeQuery()) {
            return rs.next() ? rs.getBigDecimal(1) : null;
        } catch (SQLException e) {
            throw new IllegalStateException(query, e);
        }
    }
}
