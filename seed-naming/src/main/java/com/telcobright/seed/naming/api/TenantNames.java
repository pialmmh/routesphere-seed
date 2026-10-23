package com.telcobright.seed.naming.api;

import java.util.Collection;
import java.util.Comparator;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * How a root's reseller tiers are named (R9) — prime-context's ONE rule, copied here so every application reads a tier's name the way
 * prime-context makes it: {@code prime-context …/config/dynamic/core/RootNaming.java} (ARCH-0077-E item 3, PC-0016).
 * <ul>
 *   <li>the root's database is the root's name: {@code link3}, {@code btcl};</li>
 *   <li>a reseller tier is {@code <root>_res_<partnerId>}, nested {@code _<partnerId>} per level ({@code link3_res_5}, {@code link3_res_5_2});
 *       the id is the partner's id in the parent's database — the call switch's live rule reads it at the end of the name; ids are digits
 *       only (STRICT); a word like {@code reseller1} is a display name, never a database name;</li>
 *   <li>the SEED root — the one the bootstrap names, never a name in this code — keeps the LEGACY prefix {@code res_} and its LOOSE rule:
 *       any name under the prefix is its tier ({@code res_2}, {@code res_2_1}, the lab's {@code res_44}).</li>
 * </ul>
 * A tier's parent is found by splitting on its root's prefix, never by counting underscores: {@code res_5} is never acme's.
 */
public final class TenantNames {

    /** The legacy prefix of the seed root's tiers (prime-context's {@code primecontext.reseller-db-prefix} default). */
    public static final String LEGACY_PREFIX = "res_";

    private static final Pattern AN_ID_PER_LEVEL = Pattern.compile("[1-9][0-9]{0,9}(_[1-9][0-9]{0,9})*");

    private final String seedRoot;

    private TenantNames(String seedRoot) { this.seedRoot = seedRoot == null || seedRoot.isBlank() ? null : seedRoot.trim(); }

    /** The rule of a deployment whose seed root (the legacy {@code res_<id>} tiers) is {@code seedRoot}, as its bootstrap names it. */
    public static TenantNames withSeedRoot(String seedRoot) { return new TenantNames(seedRoot); }

    /** The rule of a deployment with no seed root: every root's tiers are {@code <root>_res_<id>}. */
    public static TenantNames strict() { return new TenantNames(null); }

    /** The seed root, or empty. */
    public Optional<String> seedRoot() { return Optional.ofNullable(seedRoot); }

    /** The prefix of a root's reseller tiers: {@code res_} for the seed root alone, {@code <root>_res_} for every other. */
    public String resellerPrefixOf(String root) { return isTheSeed(named(root, "root")) ? LEGACY_PREFIX : root + "_res_"; }

    /**
     * A new reseller's tier: {@code <prefix><partnerId>} under the root ({@code parentDb} null or the root), {@code <parentDb>_<partnerId>}
     * under a tier of it. The partner id is the reseller's id in the parent's database (positive).
     */
    public String tierDb(String root, String parentDb, int partnerId) {
        if (partnerId <= 0) throw new IllegalArgumentException("a reseller tier is named by its partner id in the parent's database, a positive number — not " + partnerId);
        boolean underTheRoot = parentDb == null || parentDb.equals(named(root, "root"));
        if (!underTheRoot && !isTierOf(root, parentDb)) throw new IllegalArgumentException("'" + parentDb + "' is not a tier of the root '" + root + "': no tier is named under it");
        return (underTheRoot ? resellerPrefixOf(root) : parentDb + "_") + partnerId;
    }

    /** Is {@code db} a tier of {@code root}: the seed (loose) — any name under its prefix; every other root (strict) — one id per level. */
    public boolean isTierOf(String root, String db) {
        if (db == null || db.equals(named(root, "root")) || !db.startsWith(resellerPrefixOf(root))) return false;
        return isTheSeed(root) || AN_ID_PER_LEVEL.matcher(db.substring(resellerPrefixOf(root).length())).matches();
    }

    /** The tier above a tier of this root (the root itself for a first-level tier); empty when the name is not a tier of this root. */
    public Optional<String> parentOf(String root, String tierDb) {
        if (!isTierOf(root, tierDb)) return Optional.empty();
        String prefix = resellerPrefixOf(root);
        String rest = tierDb.substring(prefix.length());
        int cut = rest.lastIndexOf('_');
        return Optional.of(cut < 0 ? root : prefix + rest.substring(0, cut));
    }

    /** The root a database name belongs to, among the roots named: the root itself, or the root whose tier it is (the longest prefix). */
    public Optional<String> rootOf(String db, Collection<String> roots) {
        if (db == null) return Optional.empty();
        if (roots.contains(db)) return Optional.of(db);
        return roots.stream().filter(root -> isTierOf(root, db)).max(Comparator.comparingInt(root -> resellerPrefixOf(root).length()));
    }

    private boolean isTheSeed(String root) { return root.equals(seedRoot); }

    private static String named(String value, String what) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("a tier's " + what + " is missing");
        return value;
    }
}
