package com.telcobright.seed.prepaid.admin.lab;

import com.telcobright.core.wal.WALEntryBatch;
import net.openhft.chronicle.queue.ChronicleQueue;
import net.openhft.chronicle.queue.ExcerptTailer;
import net.openhft.chronicle.queue.RollCycles;
import net.openhft.chronicle.queue.impl.single.SingleChronicleQueueBuilder;
import net.openhft.chronicle.wire.DocumentContext;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Set;
import java.util.TreeSet;

/** L2c · The lab's own hands on a tier's money tables (the same SQL on the three engines), and the WAL read by the lab's own tailer. */
public final class PurchaseLabRows {

    private PurchaseLabRows() {}

    /** A purchase row as an outside writer laid it down before the ledger started (its package must be one of the tier's). */
    public static void purchase(DataSource db, String tier, long id, long partner, long packageId, LocalDateTime expires) throws SQLException {
        String sql = "INSERT INTO " + tier + ".packagepurchase (id, AIT, autoRenewalStatus, discount, expireDate, id_Partner, paid, price, priority, purchaseDate, "
            + "status, vat, id_package) VALUES (?, 0, FALSE, 0, ?, ?, 1, 0, 1, ?, 'ACTIVE', 0, ?)";
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, id);
            ps.setTimestamp(2, Timestamp.valueOf(expires));
            ps.setLong(3, partner);
            ps.setTimestamp(4, Timestamp.valueOf(LocalDateTime.of(2026, 1, 1, 0, 0)));
            ps.setLong(5, packageId);
            ps.executeUpdate();
        }
    }

    /** An account row of a purchase, with its balance. */
    public static void account(DataSource db, String tier, long id, long purchase, String uom, String balance) throws SQLException {
        String sql = "INSERT INTO " + tier + ".packageaccount (id_packageaccount, balanceAfter, balanceBefore, id_PackagePurchase, lastAmount, name, uom) "
            + "VALUES (?, ?, ?, ?, 0, ?, ?)";
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, id);
            ps.setBigDecimal(2, new BigDecimal(balance));
            ps.setBigDecimal(3, new BigDecimal(balance));
            ps.setLong(4, purchase);
            ps.setString(5, uom + "-" + id);
            ps.setString(6, uom);
            ps.executeUpdate();
        }
    }

    public static Set<Long> accountIds(DataSource db, String tier) throws SQLException {
        return ids(db, "SELECT id_packageaccount FROM " + tier + ".packageaccount");
    }

    public static Set<Long> purchaseIds(DataSource db, String tier) throws SQLException {
        return ids(db, "SELECT id FROM " + tier + ".packagepurchase");
    }

    /** An account's balanceAfter as its row holds it; null when there is no such row. */
    public static BigDecimal balance(DataSource db, String tier, long account) throws SQLException {
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT balanceAfter FROM " + tier + ".packageaccount WHERE id_packageaccount = ?")) {
            ps.setLong(1, account);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getBigDecimal(1) : null; }
        }
    }

    /** The rows of the reserve table that hold this account. */
    public static int reserveRowsOf(DataSource db, String tier, long account) throws SQLException {
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM " + tier + ".packageaccountreserve WHERE id_packageaccount = ?")) {
            ps.setLong(1, account);
            try (ResultSet rs = ps.executeQuery()) { rs.next(); return rs.getInt(1); }
        }
    }

    /** Waits (60 s at most) until the condition holds — the consumer's write-behind. */
    public static void await(String what, Check condition) throws Exception {
        long deadline = System.currentTimeMillis() + 60_000;
        while (!condition.holds()) {
            if (System.currentTimeMillis() > deadline) throw new AssertionError("not within 60 s: " + what);
            Thread.sleep(50);
        }
    }

    /** A condition read from the database. */
    @FunctionalInterface
    public interface Check { boolean holds() throws Exception; }

    /** The records in the WAL with at least one entry (the start's probe marker has none). */
    public static long walRecords(Path walDir) {
        long records = 0;
        try (ChronicleQueue queue = SingleChronicleQueueBuilder.binary(walDir.toString()).rollCycle(RollCycles.DAILY).build()) {
            ExcerptTailer tailer = queue.createTailer();
            tailer.toStart();
            while (true) {
                try (DocumentContext dc = tailer.readingDocument()) {
                    if (!dc.isPresent()) return records;
                    WALEntryBatch record = dc.wire().read("batch").object(WALEntryBatch.class);
                    if (record != null && record.getEntries() != null && !record.getEntries().isEmpty()) records++;
                }
            }
        }
    }

    private static Set<Long> ids(DataSource db, String sql) throws SQLException {
        Set<Long> ids = new TreeSet<>();
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) ids.add(rs.getLong(1));
        }
        return ids;
    }
}
