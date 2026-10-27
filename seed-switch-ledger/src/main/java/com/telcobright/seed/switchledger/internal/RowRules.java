package com.telcobright.seed.switchledger.internal;

import com.telcobright.seed.switchledger.internal.LedgerEntities.Digits;
import com.telcobright.seed.switchledger.internal.LedgerEntities.Known;
import com.telcobright.seed.switchledger.internal.LedgerEntities.Shape;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Objects;

/**
 * C2 · A unit's rows checked against what their tables hold, BEFORE the record is written. A batch the database refuses is rolled back,
 * read again and — after 10 failures in a row — stops mem-ledger's WAL consumer; the root's money then lives in RAM only. So the cached
 * ledger refuses here, in words, what it can see the database would refuse, or what would break a session:
 *
 * <ul>
 *   <li>a required field empty, a text longer than its column, a number with more digits than its column (more decimals too: the database
 *       would round it, the cache would not);</li>
 *   <li>an account put on a purchase its tier does not hold (where the ledger caches purchases) — the account → purchase foreign key;</li>
 *   <li>a purchase deleted while an account is on it; an account deleted while a session holds a reserve on it.</li>
 * </ul>
 * What it cannot see stays the caller's to get right: a purchase's package (the package table is not cached).
 */
final class RowRules {

    private RowRules() {}

    /** The first row of the unit that would be refused, in words; null = none. */
    static String refusalOf(UnitOfWork work, Books books) {
        for (UnitOfWork.Row row : work.rows()) {
            String refusal = row.op() == UnitOfWork.Op.DELETE ? refusalOfDelete(work, row) : refusalOfImage(work, books, row);
            if (refusal != null) return refusal;
        }
        return null;
    }

    private static String refusalOfImage(UnitOfWork work, Books books, UnitOfWork.Row row) {
        Known known = LedgerEntities.ofName(row.entity()).orElse(null);
        if (known == null) return null;
        String what = row.entity() + " " + row.id() + " of " + row.tier();
        Object before = row.op() == UnitOfWork.Op.INSERT ? null : books.ledger().getEntity(row.tier(), row.entity(), row.id());
        String outOfShape = refusalOfShape(known.shape(), row.image(), before, what);
        if (outOfShape != null) return outOfShape;
        String taken = refusalOfATakenValue(work, known, row, what);
        if (taken != null) return taken;
        return Books.ACCOUNT.equals(row.entity()) && books.keepsPurchases() ? refusalOfItsPurchase(work, books, row, what) : null;
    }

    /**
     * Field by field, in the class's order: empty where it may not be, too long, too many digits — only what the unit WRITES new: every
     * field of a new row, the changed fields of a row the database already holds (an old value it holds, the database took: an old row
     * on PostgreSQL may have no name, and still takes a move of its balance).
     */
    private static String refusalOfShape(Shape shape, Object image, Object before, String what) {
        for (Map.Entry<String, Object> field : RowFields.valuesOf(image).entrySet()) {
            if (before != null && Objects.equals(field.getValue(), RowFields.get(before, field.getKey()))) continue;
            String refusal = refusalOfField(shape, field.getKey(), field.getValue());
            if (refusal != null) return what + ": " + refusal;
        }
        return null;
    }

    private static String refusalOfField(Shape shape, String field, Object value) {
        if (value == null) return shape.required().contains(field) ? field + " is empty, its column may not be" : null;
        Integer longest = shape.textLimits().get(field);
        if (longest != null && value.toString().length() > longest) return field + " is " + value.toString().length() + " characters, its column holds " + longest;
        Digits digits = shape.digits().get(field);
        if (digits != null && !fits(CrudMoves.decimalOf(value), digits)) {
            return field + " " + CrudMoves.said(value) + " does not fit its column (" + digits.precision() + " digits, " + digits.scale() + " after the point)";
        }
        return null;
    }

    /**
     * Every number of the unit's rows at its column's scale (200 → 200.000000), after {@link #refusalOf} let them through (so no digit is
     * lost): the cache then holds — and answers — what the database holds.
     */
    static void scaleToColumns(UnitOfWork work) {
        for (UnitOfWork.Row row : work.rows()) {
            Known known = row.op() == UnitOfWork.Op.DELETE ? null : LedgerEntities.ofName(row.entity()).orElse(null);
            if (known == null) continue;
            known.shape().digits().forEach((field, digits) -> {
                if (RowFields.get(row.image(), field) instanceof BigDecimal number && number.scale() != digits.scale()) {
                    RowFields.set(row.image(), field, number.setScale(digits.scale()));
                }
            });
        }
    }

    /** C2b · A unique column (a purchase's {@code paymentRef}): no other row of the tier, as the unit leaves them, holds the same value. */
    private static String refusalOfATakenValue(UnitOfWork work, Known known, UnitOfWork.Row row, String what) {
        for (String field : known.shape().unique()) {
            Object value = RowFields.get(row.image(), field);
            if (value == null) continue;
            for (Object other : work.rowsAsLeft(row.tier(), row.entity())) {
                if (!String.valueOf(RowFields.keyOf(other)).equals(String.valueOf(row.id())) && value.equals(RowFields.get(other, field))) {
                    return what + ": " + field + " " + value + " is " + row.entity() + " " + RowFields.keyOf(other) + "'s already — the column is unique";
                }
            }
        }
        return null;
    }

    /** At most {@code scale} decimals, at most {@code precision − scale} digits before the point. */
    static boolean fits(BigDecimal n, Digits d) {
        BigDecimal plain = n.stripTrailingZeros();
        return Math.max(plain.scale(), 0) <= d.scale() && plain.precision() - plain.scale() <= d.precision() - d.scale();
    }

    /** An account made or moved onto a purchase must find it in its tier (an account already on its purchase is not asked again). */
    private static String refusalOfItsPurchase(UnitOfWork work, Books books, UnitOfWork.Row row, String what) {
        Object purchase = RowFields.get(row.image(), "idpackagePurchase");
        Object cached = row.op() == UnitOfWork.Op.INSERT ? null : books.ledger().getEntity(row.tier(), Books.ACCOUNT, row.id());
        boolean moved = cached == null || !Objects.equals(String.valueOf(RowFields.get(cached, "idpackagePurchase")), String.valueOf(purchase));
        if (!moved || work.read(row.tier(), Books.PURCHASE, purchase) != null) return null;
        return what + ": its purchase " + purchase + " is not held in " + row.tier();
    }

    private static String refusalOfDelete(UnitOfWork work, UnitOfWork.Row row) {
        if (Books.PURCHASE.equals(row.entity())) {
            for (Object account : work.rowsAsLeft(row.tier(), Books.ACCOUNT)) {
                if (String.valueOf(RowFields.get(account, "idpackagePurchase")).equals(String.valueOf(row.id()))) {
                    return "PackagePurchase " + row.id() + " of " + row.tier() + ": account " + RowFields.keyOf(account) + " is on it — move or delete its accounts first";
                }
            }
        }
        if (Books.ACCOUNT.equals(row.entity())) {
            for (Object reserve : work.rowsAsLeft(row.tier(), Books.RESERVE)) {
                if (String.valueOf(RowFields.get(reserve, "idPackageAccount")).equals(String.valueOf(row.id()))) {
                    return "PackageAccount " + row.id() + " of " + row.tier() + ": a session holds a reserve on it (" + RowFields.keyOf(reserve) + ")";
                }
            }
        }
        return null;
    }
}
