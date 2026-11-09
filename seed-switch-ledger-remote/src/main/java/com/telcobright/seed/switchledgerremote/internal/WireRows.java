package com.telcobright.seed.switchledgerremote.internal;

import com.telcobright.rtc.domainmodel.mysqlentity.PackageAccount;
import com.telcobright.seed.sessionflow.spi.LedgerPort;
import com.telcobright.seed.sessionflow.spi.LedgerPort.LedgerRefusal;
import com.telcobright.seed.switchledger.api.LedgerAccount;
import com.telcobright.seed.switchledgerremote.wire.Account;
import com.telcobright.seed.switchledgerremote.wire.Accounts;
import com.telcobright.seed.switchledgerremote.wire.Bucket;
import com.telcobright.seed.switchledgerremote.wire.Offer;
import com.telcobright.seed.switchledgerremote.wire.PurchaseRow;
import com.telcobright.seed.switchledgerremote.wire.Purchased;
import com.telcobright.seed.switchledgerremote.wire.Purchases;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static com.telcobright.seed.switchledgerremote.internal.Wire.dateTime;
import static com.telcobright.seed.switchledgerremote.internal.Wire.decimal;
import static com.telcobright.seed.switchledgerremote.internal.Wire.nullIfEmpty;
import static com.telcobright.seed.switchledgerremote.internal.Wire.orEmpty;
import static com.telcobright.seed.switchledgerremote.internal.Wire.text;
import static com.telcobright.seed.switchledgerremote.internal.Wire.throwIfRefused;

/** The wire's words for the rows the ledger answers — an account, a purchase, an offer — both ways. */
public final class WireRows {

    private WireRows() {}

    // ── an account ──────────────────────────────────────────────────────────

    /** The account row as the ledger holds it, with its purchase's expiry where the ledger knows it ({@code expires}; null = unknown). */
    public static Account accountOf(PackageAccount a, LocalDateTime expires) {
        Account.Builder out = Account.newBuilder().setPresent(true)
            .setId(a.getId() == null ? 0 : a.getId())
            .setPurchaseId(a.getIdpackagePurchase() == null ? 0 : a.getIdpackagePurchase())
            .setName(orEmpty(a.getName())).setUom(orEmpty(a.getUom()))
            .setBalanceBefore(text(a.getBalanceBefore())).setBalanceAfter(text(a.getBalanceAfter())).setLastAmount(text(a.getLastAmount()))
            .setExpires(text(expires != null ? expires : a.getExpireDate()));
        if (a instanceof LedgerAccount l) {
            if (l.getIdPackageItem() != null) out.setItemId(l.getIdPackageItem());
            if (l.getCreditLimit() != null) out.setCreditLimit(text(l.getCreditLimit()));
            out.setSelected(l.isSelected());
        }
        return out.build();
    }

    public static Account noAccount() { return Account.newBuilder().setPresent(false).build(); }

    /** The row as the client holds it: a {@link LedgerAccount} (every column of {@code packageaccount}), its expiry set where the wire said one. */
    public static Optional<PackageAccount> accountOf(Account a) {
        if (!a.getPresent()) return Optional.empty();
        LedgerAccount row = new LedgerAccount();
        row.setId(a.getId() == 0 ? null : a.getId());
        row.setIdpackagePurchase(a.getPurchaseId() == 0 ? null : a.getPurchaseId());
        row.setName(nullIfEmpty(a.getName()));
        row.setUom(nullIfEmpty(a.getUom()));
        row.setBalanceBefore(decimal(a.getBalanceBefore()));
        row.setBalanceAfter(decimal(a.getBalanceAfter()));
        row.setLastAmount(decimal(a.getLastAmount()));
        row.setExpireDate(dateTime(a.getExpires()));
        if (a.hasItemId()) row.setIdPackageItem(a.getItemId());
        if (a.hasCreditLimit()) row.setCreditLimit(decimal(a.getCreditLimit()));
        row.setSelected(a.getSelected());
        return Optional.of(row);
    }

    public static Accounts accountsOf(Optional<List<PackageAccount>> answer) {
        if (answer.isEmpty()) return Accounts.newBuilder().setPresent(false).build();
        Accounts.Builder out = Accounts.newBuilder().setPresent(true);
        for (PackageAccount a : answer.get()) out.addAccounts(accountOf(a, null));
        return out.build();
    }

    public static Accounts refusedAccounts(LedgerRefusal refusal) { return Accounts.newBuilder().setRefusal(Wire.refusalOf(refusal)).build(); }

    public static Optional<List<PackageAccount>> accountsOf(Accounts answer) {
        throwIfRefused(answer.hasRefusal(), answer.getRefusal());
        if (!answer.getPresent()) return Optional.empty();
        List<PackageAccount> rows = new ArrayList<>(answer.getAccountsCount());
        for (Account a : answer.getAccountsList()) accountOf(a).ifPresent(rows::add);
        return Optional.of(List.copyOf(rows));
    }

    // ── a purchase ──────────────────────────────────────────────────────────

    public static Purchases purchasesOf(Optional<List<LedgerPort.Purchase>> answer) {
        if (answer.isEmpty()) return Purchases.newBuilder().setPresent(false).build();
        Purchases.Builder out = Purchases.newBuilder().setPresent(true);
        for (LedgerPort.Purchase p : answer.get()) {
            out.addPurchases(PurchaseRow.newBuilder().setId(p.id()).setPackageId(p.packageId()).setAt(text(p.at())).setExpires(text(p.expires()))
                .setStatus(orEmpty(p.status())).setPrice(text(p.price())).setPriority(p.priority()));
        }
        return out.build();
    }

    public static Purchases refusedPurchases(LedgerRefusal refusal) { return Purchases.newBuilder().setRefusal(Wire.refusalOf(refusal)).build(); }

    public static Optional<List<LedgerPort.Purchase>> purchasesOf(Purchases answer) {
        throwIfRefused(answer.hasRefusal(), answer.getRefusal());
        if (!answer.getPresent()) return Optional.empty();
        List<LedgerPort.Purchase> rows = new ArrayList<>(answer.getPurchasesCount());
        for (PurchaseRow p : answer.getPurchasesList()) {
            rows.add(new LedgerPort.Purchase(p.getId(), p.getPackageId(), dateTime(p.getAt()), dateTime(p.getExpires()), nullIfEmpty(p.getStatus()), decimal(p.getPrice()), p.getPriority()));
        }
        return Optional.of(List.copyOf(rows));
    }

    public static Purchased purchasedOf(LedgerPort.Purchased p) {
        return Purchased.newBuilder().setPurchaseId(p.purchaseId()).addAllAccounts(p.accounts()).setRepeated(p.repeated()).build();
    }

    public static Purchased refusedPurchased(LedgerRefusal refusal) { return Purchased.newBuilder().setRefusal(Wire.refusalOf(refusal)).build(); }

    public static LedgerPort.Purchased purchasedOf(Purchased p) {
        throwIfRefused(p.hasRefusal(), p.getRefusal());
        return new LedgerPort.Purchased(p.getPurchaseId(), List.copyOf(p.getAccountsList()), p.getRepeated());
    }

    // ── an offer ────────────────────────────────────────────────────────────

    public static Offer offerOf(LedgerPort.Offer offer) {
        Offer.Builder out = Offer.newBuilder().setPackageId(offer.packageId()).setName(orEmpty(offer.name())).setPrice(text(offer.price())).setAt(text(offer.at())).setExpires(text(offer.expires()));
        for (LedgerPort.Bucket b : offer.buckets()) {
            Bucket.Builder bucket = Bucket.newBuilder().setUom(orEmpty(b.uom())).setOpening(text(b.opening()));
            if (b.itemId() != null) bucket.setItemId(b.itemId());
            out.addBuckets(bucket);
        }
        return out.build();
    }

    public static LedgerPort.Offer offerOf(Offer offer) {
        List<LedgerPort.Bucket> buckets = new ArrayList<>(offer.getBucketsCount());
        for (Bucket b : offer.getBucketsList()) buckets.add(new LedgerPort.Bucket(nullIfEmpty(b.getUom()), decimal(b.getOpening()), b.hasItemId() ? b.getItemId() : null));
        return new LedgerPort.Offer(offer.getPackageId(), nullIfEmpty(offer.getName()), decimal(offer.getPrice()), dateTime(offer.getAt()), dateTime(offer.getExpires()), buckets);
    }
}
