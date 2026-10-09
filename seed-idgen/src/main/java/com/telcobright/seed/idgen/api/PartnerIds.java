package com.telcobright.seed.idgen.api;

import com.telcobright.seed.idgen.spi.IdSource;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * The owner's rule of 2026-10-09: <b>partner ids are unique within a tenant</b>, by an external unique-id generator for partner CRUD.
 * A tenant is one operator's whole tree (the root and every nested reseller tier), so the generator keeps ONE counter per tree —
 * entity {@code partner.<root>}, type {@code int} (the switch's {@code partner.idPartner}). Every road that creates a partner in the
 * tree takes its id from here, whichever process it runs in: prime-context's partner road, the WiFi's, the BSS's.
 *
 * <p>A tree that existed before the generator keeps its ids: every mint is above the highest id the tree holds today, on whichever
 * shard mints it.
 */
public final class PartnerIds {

    private static final String ENTITY_PREFIX = "partner.";
    private static final Pattern ROOT_NAME = Pattern.compile("[A-Za-z0-9_-]+");

    private final IdSource ids;

    public PartnerIds(IdSource ids) { this.ids = Objects.requireNonNull(ids, "ids"); }

    /** The generator's entity for the partners of the tree of {@code root} (the root tenant's database name: {@code btcl}). */
    public static String entityOf(String root) {
        if (root == null || !ROOT_NAME.matcher(root).matches()) {
            throw new IllegalArgumentException("a tree's root is a database name ([A-Za-z0-9_-]+), not '" + root + "'");
        }
        return ENTITY_PREFIX + root;
    }

    /**
     * A new partner id for the tree of {@code root}: unique within the tenant, and above {@code highestInTree} — the highest
     * {@code idPartner} any tier of the tree holds today.
     */
    public int next(String root, int highestInTree) {
        return Integer.parseInt(ids.mintAbove(entityOf(root), IdKind.INT, 1, highestInTree).get(0));
    }
}
