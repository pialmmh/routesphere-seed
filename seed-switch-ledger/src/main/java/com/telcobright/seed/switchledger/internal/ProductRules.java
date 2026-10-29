package com.telcobright.seed.switchledger.internal;

import com.telcobright.seed.sessionflow.spi.LedgerPort.Product;
import com.telcobright.seed.switchledger.api.LedgerProduct;

import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * §15.3 · What a product row must be, refused in words before the record is written (beside the shape rules of {@link RowRules}):
 * the kind one of the platform's services; the source portal or odoo; an ad product with a media (video, image, audio), seconds only
 * with a timed media; a service group the tier knows where the ledger caches the groups; a product named by an item is deactivated,
 * never deleted. The case of kind, media and source is folded to lower case before the check — the row is written as given.
 *
 * <p>ONE SERVICE GROUP PER PACKAGE (the WiFi architect's ask, ruled 2026-10-30): the BSS invoices a package as one line and a postpaid
 * invoice is cut per group, so a package never mixes revenue streams — by construction. An item write, or a product's group change, that
 * would leave a package holding items whose products are of two groups is refused; an item that names no product row (the call's
 * prefix) carries no group and is not held to it. A mixed need is two packages.
 */
final class ProductRules {

    private ProductRules() {}

    /** The refusal of this product image, or null. */
    static String refusalOf(UnitOfWork work, Books books, UnitOfWork.Row row, String what) {
        if (!(row.image() instanceof LedgerProduct p)) return null;
        String kind = lower(p.getKind()), media = lower(p.getMedia()), source = lower(p.getSource());
        if (!Product.KINDS.contains(kind)) return what + ": kind '" + p.getKind() + "' is not one of " + Product.KINDS;
        if (!Product.SOURCES.contains(source)) return what + ": source '" + p.getSource() + "' is not one of " + Product.SOURCES;
        if (media != null && !Product.MEDIA.contains(media)) return what + ": media '" + p.getMedia() + "' is not one of " + Product.MEDIA;
        if ("ad".equals(kind) && media == null) return what + ": an ad product names its media (" + Product.MEDIA + ")";
        if (p.getSeconds() != null && (media == null || "image".equals(media))) return what + ": seconds belong to a video or audio product, not to " + (media == null ? "a product without media" : media);
        if (p.getSeconds() != null && p.getSeconds() <= 0) return what + ": seconds must be above 0";
        if (books.keepsServiceGroups() && work.read(row.tier(), Books.SERVICE_GROUP, p.getServiceGroupId()) == null) {
            return what + ": service group " + p.getServiceGroupId() + " is not one " + row.tier() + " knows";
        }
        return refusalOfAGroupThatMixesAPackage(work, row, what);
    }

    /** An item made or changed: its package must stay of ONE service group, as the unit leaves the tier's items and products. */
    static String refusalOfAMixedPackage(UnitOfWork work, Books books, UnitOfWork.Row row, String what) {
        if (!books.keepsProducts() || row.image() == null) return null;
        Object pkg = RowFields.get(row.image(), "idPackage");
        return pkg == null ? null : mixedGroupsOf(work, row.tier(), pkg, what);
    }

    /** A product made or changed: every package that holds an item naming it must stay of ONE service group with its new group. */
    static String refusalOfAGroupThatMixesAPackage(UnitOfWork work, UnitOfWork.Row row, String what) {
        if (!(row.image() instanceof LedgerProduct p) || p.getProductKey() == null) return null;
        String key = p.getProductKey().trim();
        for (Object item : work.rowsAsLeft(row.tier(), Books.PACKAGE_ITEM)) {
            Object prefix = RowFields.get(item, "prefix");
            Object pkg = RowFields.get(item, "idPackage");
            if (prefix == null || pkg == null || !key.equals(prefix.toString().trim())) continue;
            String mixed = mixedGroupsOf(work, row.tier(), pkg, what);
            if (mixed != null) return mixed;
        }
        return null;
    }

    /** The groups of the products a package's items name, as the unit leaves them; a refusal when there are two or more. */
    private static String mixedGroupsOf(UnitOfWork work, String tier, Object pkg, String what) {
        Set<Integer> groups = new TreeSet<>();
        for (Object item : work.rowsAsLeft(tier, Books.PACKAGE_ITEM)) {
            if (!String.valueOf(RowFields.get(item, "idPackage")).equals(String.valueOf(pkg))) continue;
            Integer group = groupOfItem(work, tier, item);
            if (group != null) groups.add(group);
        }
        if (groups.size() <= 1) return null;
        return what + ": package " + pkg + " of " + tier + " would hold items of service groups " + groups + " — one service group per package (a mixed offer is two packages)";
    }

    /** The service group of the product an item names (its prefix = the product key), as the unit leaves it; null = it names no product row. */
    private static Integer groupOfItem(UnitOfWork work, String tier, Object item) {
        Object prefix = RowFields.get(item, "prefix");
        String key = prefix == null ? "" : prefix.toString().trim();
        if (key.isEmpty()) return null;
        Object product = work.read(tier, Books.PRODUCT, key);
        return product instanceof LedgerProduct p ? p.getServiceGroupId() : null;
    }

    /** A product deleted while an item names it (prefix = key): refused — deactivate it instead. */
    static String refusalOfDelete(UnitOfWork work, UnitOfWork.Row row) {
        for (Object other : work.rowsAsLeft(row.tier(), Books.PACKAGE_ITEM)) {
            Object prefix = RowFields.get(other, "prefix");
            if (prefix != null && prefix.toString().trim().equals(String.valueOf(row.id()).trim())) {
                return "Product " + row.id() + " of " + row.tier() + ": " + Books.PACKAGE_ITEM + " " + RowFields.keyOf(other) + " names it — deactivate the product instead";
            }
        }
        return null;
    }

    private static String lower(String s) { return s == null ? null : s.trim().toLowerCase(Locale.ROOT); }
}
