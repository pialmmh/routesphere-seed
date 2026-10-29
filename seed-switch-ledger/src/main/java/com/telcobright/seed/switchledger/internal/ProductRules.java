package com.telcobright.seed.switchledger.internal;

import com.telcobright.seed.sessionflow.spi.LedgerPort.Product;
import com.telcobright.seed.switchledger.api.LedgerProduct;

import java.util.Locale;

/**
 * §15.3 · What a product row must be, refused in words before the record is written (beside the shape rules of {@link RowRules}):
 * the kind one of the platform's services; the source portal or odoo; an ad product with a media (video, image, audio), seconds only
 * with a timed media; a service group the tier knows where the ledger caches the groups; a product named by an item is deactivated,
 * never deleted. The case of kind, media and source is folded to lower case before the check — the row is written as given.
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
        return null;
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
