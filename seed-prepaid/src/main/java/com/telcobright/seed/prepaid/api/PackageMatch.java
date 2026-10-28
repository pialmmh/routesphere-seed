package com.telcobright.seed.prepaid.api;

import com.telcobright.rtc.domainmodel.nonentity.DynamicContext;
import com.telcobright.rtc.domainmodel.mysqlentity.PackageAccount;
import com.telcobright.rtc.domainmodel.mysqlentity.Partner;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * THE package rule of every lane, in the prepaid library (the owner, 2026-10-27: "packages (assigned package_accounts) are the defaults
 * for all services. packages have higher priority like mobile operators prefers, then rate based deduction like pay as you go" · "best
 * match/active package finding logic can be re-usable and shall be pushed to the prepaid lib" · "package has higher priority, best package
 * to match. if no packages are matched, use rateplan based rate matching"):
 *
 * <pre>
 *   payable(accounts, units, rule, now)
 *     packages = the ACTIVE accounts (unexpired at now) whose unit is one the lane names for THIS request (its units),
 *                best match first = the CATALOG's order, which is prime-context's (AllCacheLoader.comparePurchases): the operator's
 *                own priority (onSelectPriority), then the soonest expiry, then the older purchase, then the lower id — the ledger's
 *                catalog keeps the same order (seed-switch-ledger Purchases, S6)
 *     cash     = the ACTIVE cash accounts (BDT), in the catalog's order — pay as you go, priced by the lane's rate plan
 *     UNITS_THEN_CASH → packages, then cash   (the default of every lane)
 *     UNITS_ONLY      → packages only          (the WiFi: no package, no wifi)
 *     CASH_ONLY       → cash only              (the call abroad)
 * </pre>
 *
 * The engine holds or charges the FIRST account that can pay — the reserve is the affordability test, as the live call's — and a tier
 * none can pay is refused with the lane's cause. A package of a unit the lane does not name for this request never pays it (an ad's views
 * never pay a call). Pure functions: nothing here reads a balance or writes.
 */
public final class PackageMatch {

    /** Cash: what pays when no package matches, at the lane's rate-plan price. */
    public static final String CASH = "BDT";

    /** The package units the call and the SMS bought before any lane named its own (the live call's buckets): minutes, messages. */
    public static final Set<String> CALL_AND_SMS_UNITS = Set.of("TF_min", "OTH_ea");

    private PackageMatch() {}

    /** The accounts that may pay this request, best first (see the class). */
    public static List<PackageAccount> payable(List<PackageAccount> accounts, Set<String> units, AccountRule rule, LocalDateTime now) {
        List<PackageAccount> active = active(accounts, now);
        return switch (rule) {
            case UNITS_THEN_CASH -> then(bestMatching(active, units), cash(active));
            case UNITS_ONLY -> bestMatching(active, units);
            case CASH_ONLY -> cash(active);
        };
    }

    /** The partner's accounts as the tenant's tree names them (the catalog: id, purchase, unit, expiry); none = empty. */
    public static List<PackageAccount> accountsOf(Partner partner, DynamicContext tree) {
        Map<Long, List<PackageAccount>> byPartner = tree == null ? null : tree.getPartnerIdWisePackageAccounts();
        List<PackageAccount> accounts = byPartner == null || partner == null ? null : byPartner.get((long) partner.getIdPartner());
        return accounts == null ? List.of() : accounts;
    }

    /** The accounts whose purchase is still valid at {@code now}; an unknown expiry never expires. */
    static List<PackageAccount> active(List<PackageAccount> accounts, LocalDateTime now) {
        List<PackageAccount> active = new ArrayList<>(accounts == null ? 0 : accounts.size());
        if (accounts != null) for (PackageAccount a : accounts) if (a != null && !a.isExpiredAt(now)) active.add(a);
        return active;
    }

    /**
     * The packages of the lane's units in the CATALOG's order — prime-context's: the operator's priority, then the soonest expiry (L3 S6:
     * never re-sorted by expiry alone, which would undo an operator's choice; the live call keeps the tree's order, AccountOrder.fundable).
     */
    static List<PackageAccount> bestMatching(List<PackageAccount> active, Set<String> units) {
        List<PackageAccount> matching = new ArrayList<>();
        for (PackageAccount a : active) if (units != null && units.contains(a.getUom())) matching.add(a);
        return List.copyOf(matching);
    }

    /** The cash accounts, in the catalog's order. */
    static List<PackageAccount> cash(List<PackageAccount> active) {
        List<PackageAccount> cash = new ArrayList<>();
        for (PackageAccount a : active) if (CASH.equals(a.getUom())) cash.add(a);
        return List.copyOf(cash);
    }

    private static List<PackageAccount> then(List<PackageAccount> first, List<PackageAccount> second) {
        List<PackageAccount> all = new ArrayList<>(first.size() + second.size());
        all.addAll(first);
        all.addAll(second);
        return List.copyOf(all);
    }
}
