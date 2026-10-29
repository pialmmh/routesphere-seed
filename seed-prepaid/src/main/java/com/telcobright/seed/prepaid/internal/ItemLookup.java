package com.telcobright.seed.prepaid.internal;

import com.telcobright.rtc.domainmodel.mysqlentity.PackageAccount;
import com.telcobright.seed.prepaid.api.PackageItemMatch;
import com.telcobright.seed.prepaid.api.PackageMatch;
import com.telcobright.seed.sessionflow.spi.LedgerPort.Item;
import com.telcobright.seed.sessionflow.spi.LedgerPort.ItemCatalog;
import com.telcobright.seed.switchledger.api.LedgerAccount;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * §15 · Steps 2 and 3 of the owner's ad flow, for every lane ("identify the item by prefix (must be a hashtable look up, not list lookup)
 * … relate product and items by primary key = prefix, if the item does not match, rate/package not found error"): over the accounts that
 * may pay (step 1's order: the best package first, then cash), each PACKAGE account is related to the item of its package that carries one
 * of the request's keys —
 *
 * <ul>
 *   <li>an account that names its item ({@code id_packageitem}): that item, if its key is one of the request's — else this account pays
 *       nothing for this request (a package of 30-second videos never pays an image);</li>
 *   <li>an account of no item (opened before §15): its purchase's package, the package's items by key — the first of the request's keys
 *       that hits — and the item must count the account's unit (a package of minutes and messages: the minutes' account pays the call);</li>
 * </ul>
 *
 * a CASH account ({@code BDT}) is kept as it is (pay as you go, priced by the lane's rate plan). §15.3, step 1b: with the request's
 * service group given, an item whose product belongs to ANOTHER group pays nothing for this request (a domestic call never pays from the
 * video views); an item of no product is not held to it. Pure: map gets on the catalog's snapshot.
 */
final class ItemLookup {

    /** The accounts that may pay this request, in order, each package account with its match; and whether any package matched. */
    record Payable(List<PackageAccount> accounts, Map<Long, PackageItemMatch> matches, boolean anyPackageMatched) {
        PackageItemMatch matchOf(PackageAccount account) { return account == null || account.getId() == null ? null : matches.get(account.getId()); }
    }

    private ItemLookup() {}

    static Payable match(List<PackageAccount> payable, List<String> keys, ItemCatalog catalog) { return match(payable, keys, catalog, null); }

    /** The same, narrowed to the request's service group ({@code null} = no narrowing). */
    static Payable match(List<PackageAccount> payable, List<String> keys, ItemCatalog catalog, Integer serviceGroup) {
        List<PackageAccount> accounts = new ArrayList<>(payable.size());
        Map<Long, PackageItemMatch> matches = new java.util.HashMap<>();
        boolean any = false;
        for (PackageAccount account : payable) {
            if (PackageMatch.CASH.equals(account.getUom())) { accounts.add(account); continue; }
            PackageItemMatch match = matchOf(account, keys, catalog);
            if (match == null || !ofTheGroup(match, serviceGroup)) continue;
            accounts.add(account);
            if (account.getId() != null) matches.put(account.getId(), match);
            any = true;
        }
        return new Payable(List.copyOf(accounts), Map.copyOf(matches), any);
    }

    /** The item of this package account for these keys, or null. */
    static PackageItemMatch matchOf(PackageAccount account, List<String> keys, ItemCatalog catalog) {
        Long itemId = account instanceof LedgerAccount la ? la.getIdPackageItem() : null;
        if (itemId != null) return namedItem(account, itemId, keys, catalog);
        return itemByKey(account, keys, catalog);
    }

    /** The account names its item: it pays when that item's key is asked. */
    private static PackageItemMatch namedItem(PackageAccount account, long itemId, List<String> keys, ItemCatalog catalog) {
        Optional<Item> item = catalog.itemOf(itemId);
        if (item.isEmpty()) return null;
        for (String key : keys) if (key.equals(item.get().key())) return matched(account, item.get(), key, catalog);
        return null;
    }

    /** An account of no item: its purchase's package, the first asked key its items carry, the item counting the account's unit. */
    private static PackageItemMatch itemByKey(PackageAccount account, List<String> keys, ItemCatalog catalog) {
        Long purchaseId = account.getIdpackagePurchase();
        Optional<Long> packageId = purchaseId == null ? Optional.empty() : catalog.packageOfPurchase(purchaseId);
        if (packageId.isEmpty()) return null;
        Map<String, Item> items = catalog.itemsOf(packageId.get());
        for (String key : keys) {
            Item item = items.get(key);
            if (item != null && item.uom() != null && item.uom().equals(account.getUom())) return matched(account, item, key, catalog);
        }
        return null;
    }

    /** §15.3 · The matched item's product belongs to the request's group, or names no product (the call's prefix), or no group was asked. */
    static boolean ofTheGroup(PackageItemMatch match, Integer serviceGroup) {
        Integer own = match.serviceGroupId();
        return serviceGroup == null || own == null || own.equals(serviceGroup);
    }

    private static PackageItemMatch matched(PackageAccount account, Item item, String key, ItemCatalog catalog) {
        return new PackageItemMatch(account, item, catalog.products().get(key), key);
    }
}
