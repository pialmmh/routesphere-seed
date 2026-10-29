package com.telcobright.seed.switchledger.internal;

import com.telcobright.memledger.core.metadata.EntityMetadata;
import com.telcobright.memledger.core.metadata.EntityMetadataExtractor;
import com.telcobright.memledger.core.metadata.FieldMetadata;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * C2b · Every tier holds every column the ledger writes — checked before a root's ledger starts and before a tier is loaded live.
 *
 * <p>mem-ledger writes a row's mapped columns (its own extractor: a persistent parent's columns too), but its loader passes over a column
 * the table lacks in silence: the cache loads, and the first write of the row is refused by the database — the WAL consumer rolls back,
 * tries again and stops after 10 tries, the root's money then in RAM only. So the ledger refuses to start there instead, in words that
 * name the tier, the table and the column, and what brings them.
 *
 * <p>C4 · On PostgreSQL the same holds for the RIGHTS: the ledger's login must hold SELECT, INSERT, UPDATE and DELETE on every table it
 * caches (the cached ledger may write each of them); a right it lacks would refuse a row in the same silent way. Read as the login holds
 * them ({@code has_table_privilege}); another engine's grants are not read here.
 */
public final class TierColumns {

    /** What brings the ledger's columns into a tier. */
    public static final String WHAT_BRINGS_THEM = "run prime-context's migrate-all on this database first (its changelog brings the ledger's columns — 006: creditLimit,"
        + " isSelected, paymentRef; 007 (pc-prod 009): id_packageitem, product_catalog — and its provisioning gives the ledger's login its rights: primecontext.provision.ledger-*, C4)";

    /** What the ledger's writes need on every table it caches. */
    private static final List<String> RIGHTS = List.of("SELECT", "INSERT", "UPDATE", "DELETE");

    private static final int NAMED_AT_MOST = 12;

    private final String root;
    private final DataSource db;
    private final Map<String, Set<String>> columnsByTable;

    private TierColumns(String root, DataSource db, Map<String, Set<String>> columnsByTable) {
        this.root = root;
        this.db = db;
        this.columnsByTable = columnsByTable;
    }

    /** The columns these entity types write, as mem-ledger maps them — table and column names in lower case. */
    public static TierColumns of(String root, DataSource db, Collection<Class<?>> types) {
        EntityMetadataExtractor extractor = new EntityMetadataExtractor();
        Map<String, Set<String>> columnsByTable = new LinkedHashMap<>();
        for (Class<?> type : types) {
            EntityMetadata metadata = extractor.extractMetadata(type);
            Set<String> columns = columnsByTable.computeIfAbsent(lower(metadata.getTableName()), t -> new TreeSet<>());
            for (FieldMetadata field : metadata.getPersistableFields()) columns.add(lower(field.getColumnName()));
        }
        return new TierColumns(root, db, columnsByTable);
    }

    /** Every tier holds every column the ledger writes; otherwise refused in words — the tiers, tables and columns that lack. */
    public void requireIn(Collection<String> tiers) {
        List<String> lacking = lackingIn(tiers);
        if (lacking.isEmpty()) return;
        throw new IllegalStateException("the root '" + root + "': its ledger does not start — a tier lacks what the ledger writes (the database"
            + " would refuse the row and the write-behind would stop): " + named(lacking) + ". " + WHAT_BRINGS_THEM);
    }

    private List<String> lackingIn(Collection<String> tiers) {
        List<String> lacking = new ArrayList<>();
        try (Connection c = db.getConnection()) {
            for (String tier : tiers) lacking.addAll(lackingIn(c, tier));
        } catch (SQLException e) {
            throw new IllegalStateException("the root '" + root + "': its ledger could not read its tiers' columns: " + e.getMessage(), e);
        }
        return lacking;
    }

    private List<String> lackingIn(Connection c, String tier) throws SQLException {
        Map<String, Set<String>> held = heldIn(c, tier);
        boolean postgres = c.getMetaData().getDatabaseProductName().toLowerCase(Locale.ROOT).contains("postgres");
        List<String> lacking = new ArrayList<>();
        for (Map.Entry<String, Set<String>> table : columnsByTable.entrySet()) {
            Set<String> there = held.get(table.getKey());
            if (there == null) {
                lacking.add(tier + " has no table " + table.getKey());
                continue;
            }
            for (String column : table.getValue()) if (!there.contains(column)) lacking.add(tier + " lacks " + table.getKey() + "." + column);
            if (postgres) lacking.addAll(rightsLackingOn(c, tier, table.getKey()));
        }
        return lacking;
    }

    /** C4 · PostgreSQL: the rights the ledger's writes need on a table it caches, as its login holds them. */
    private static List<String> rightsLackingOn(Connection c, String tier, String table) throws SQLException {
        List<String> lacking = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT has_table_privilege(?, ?)")) {
            for (String right : RIGHTS) {
                ps.setString(1, tier + "." + table);
                ps.setString(2, right);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next() && !rs.getBoolean(1)) lacking.add(tier + " grants the ledger's login no " + right + " on " + table);
                }
            }
        }
        return lacking;
    }

    /** The tier's columns of the ledger's tables, as the database's information_schema lists them (PostgreSQL, MySQL, H2 alike). */
    private Map<String, Set<String>> heldIn(Connection c, String tier) throws SQLException {
        String tables = String.join(", ", columnsByTable.keySet().stream().map(t -> "?").toList());
        String sql = "SELECT LOWER(table_name), LOWER(column_name) FROM information_schema.columns WHERE LOWER(table_schema) = ? AND LOWER(table_name) IN (" + tables + ")";
        Map<String, Set<String>> held = new HashMap<>();
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            int i = 1;
            ps.setString(i++, lower(tier));
            for (String table : columnsByTable.keySet()) ps.setString(i++, table);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) held.computeIfAbsent(rs.getString(1), t -> new TreeSet<>()).add(rs.getString(2));
            }
        }
        return held;
    }

    private static String named(List<String> lacking) {
        if (lacking.size() <= NAMED_AT_MOST) return String.join("; ", lacking);
        return String.join("; ", lacking.subList(0, NAMED_AT_MOST)) + "; and " + (lacking.size() - NAMED_AT_MOST) + " more";
    }

    private static String lower(String name) { return name.toLowerCase(Locale.ROOT); }
}
