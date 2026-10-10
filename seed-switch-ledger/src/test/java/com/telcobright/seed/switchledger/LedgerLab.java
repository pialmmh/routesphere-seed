package com.telcobright.seed.switchledger;

import com.telcobright.memledger.api.MemLedger;
import com.telcobright.core.cache.CacheableEntity;
import com.telcobright.memledger.config.ReplicationConfig;
import com.telcobright.rtc.domainmodel.mysqlentity.PackageAccount;
import com.telcobright.rtc.domainmodel.mysqlentity.PackageAccountReserve;
import com.telcobright.seed.switchledger.internal.Books;
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
    private MemLedger ledger;

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
        for (String schema : schemas) sql(String.format(tableDdlPerSchema, schema));
        return this;
    }

    /** Build the MemLedger: it loads every schema's two tables and replays the (empty) WAL. */
    public MemLedger start() {
        System.out.println("LedgerLab: no network listener — H2 in memory (" + url + "), MemLedger STANDALONE (no REST, no gRPC), queue at " + dir);
        var builder = MemLedger.builder()
            .dataSource(dataSource)
            .databases(schemas.toArray(String[]::new))
            .registerEntity(Books.ACCOUNT, PackageAccount.class)
            .registerEntity(Books.RESERVE, PackageAccountReserve.class)
            .walPath(dir.resolve("wal").toString())
            .queuePath(dir.resolve("queue").toString())
            .replicationMode(ReplicationConfig.Mode.STANDALONE);
        more.forEach(builder::registerEntity);
        ledger = builder.build();
        return ledger;
    }

    public MemLedger ledger() { return ledger; }

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
