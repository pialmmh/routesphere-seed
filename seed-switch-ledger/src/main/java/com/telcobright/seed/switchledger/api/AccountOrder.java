package com.telcobright.seed.switchledger.api;

import com.telcobright.rtc.domainmodel.mysqlentity.PackageAccount;
import com.telcobright.rtc.domainmodel.mysqlentity.Partner;
import com.telcobright.rtc.domainmodel.nonentity.DynamicContext;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The account order of the call switch, as pure functions over the tree (ARCH-0077-A item 2). The partner's package accounts are the
 * tree's ({@link DynamicContext#getPartnerIdWisePackageAccounts()}); only the UNEXPIRED ones are fundable ({@link PackageAccount#isExpiredAt},
 * checked per request — routesphere-core {@code ReserveBalanceStep.unexpired}, lines 187–207: "config-manager filters expired purchases only
 * when it builds the tree, so one that lapses afterwards is still in the list … CCL partner 1264's package expired on 2 Oct 2026 and paid
 * for 14 more calls"); the order is package units before money, then the rest ({@code ReserveBalanceStep.orderedAccounts}, lines 159–185:
 * "an exhausted package falls through to the customer's cash instead of rejecting the call outright" — partner 349, sbc1, 2026-08-05).
 *
 * <p>Nothing here reads a balance: the tree's rows are the CATALOG (id, purchase, unit, expiry); the live balance is the ledger's, and the
 * reserve is the affordability test ({@code ReserveBalanceStep.doExecute}: "attempting the reserve IS the affordability test").
 */
public final class AccountOrder {

    /** Talk-time minutes: the call's package unit. */
    public static final String TF_MIN = "TF_min";
    /** Counted events: the SMS's (and the WiFi's) package unit. */
    public static final String OTH_EA = "OTH_ea";
    /** Money. */
    public static final String BDT = "BDT";

    private AccountOrder() {}

    /**
     * Every account that could pay, best first: the unit buckets ({@code TF_min}, {@code OTH_ea}) in the tree's order, then cash
     * ({@code BDT}) in the tree's order, then anything else — unexpired only. Empty = nothing the partner owns can fund a request.
     */
    public static List<PackageAccount> fundable(Partner partner, DynamicContext tree, LocalDateTime now) {
        List<PackageAccount> live = unexpired(accountsOf(partner, tree), now);
        List<PackageAccount> ordered = new ArrayList<>(live.size());
        for (PackageAccount a : live) if (isBucket(a)) ordered.add(a);
        for (PackageAccount a : live) if (BDT.equals(a.getUom())) ordered.add(a);
        for (PackageAccount a : live) if (!ordered.contains(a)) ordered.add(a);
        return List.copyOf(ordered);
    }

    /** The unit buckets ONLY ({@code TF_min}, {@code OTH_ea}), unexpired — no cash: the owner, "if there is no package, no wifi". */
    public static List<PackageAccount> buckets(Partner partner, DynamicContext tree, LocalDateTime now) {
        List<PackageAccount> out = new ArrayList<>();
        for (PackageAccount a : unexpired(accountsOf(partner, tree), now)) if (isBucket(a)) out.add(a);
        return List.copyOf(out);
    }

    /** Cash ONLY ({@code BDT}), unexpired — the call's international rule ({@code ReserveBalanceStep.bdtAccounts}, lines 209–219). */
    public static List<PackageAccount> cashOnly(Partner partner, DynamicContext tree, LocalDateTime now) {
        List<PackageAccount> out = new ArrayList<>();
        for (PackageAccount a : unexpired(accountsOf(partner, tree), now)) if (BDT.equals(a.getUom())) out.add(a);
        return List.copyOf(out);
    }

    /** The partner's accounts whose purchase is still valid at {@code now}; an unknown expiry never expires. */
    static List<PackageAccount> unexpired(List<PackageAccount> accounts, LocalDateTime now) {
        List<PackageAccount> valid = new ArrayList<>(accounts.size());
        for (PackageAccount a : accounts) if (a != null && !a.isExpiredAt(now)) valid.add(a);
        return valid;
    }

    private static boolean isBucket(PackageAccount a) { return TF_MIN.equals(a.getUom()) || OTH_EA.equals(a.getUom()); }

    private static List<PackageAccount> accountsOf(Partner partner, DynamicContext tree) {
        Map<Long, List<PackageAccount>> map = tree == null ? null : tree.getPartnerIdWisePackageAccounts();
        List<PackageAccount> accounts = map == null || partner == null ? null : map.get((long) partner.getIdPartner());
        return accounts == null ? List.of() : accounts;
    }
}
