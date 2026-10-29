package com.telcobright.seed.prepaid.internal;

import com.telcobright.seed.prepaid.api.AccountRule;
import com.telcobright.seed.prepaid.api.Admission;
import com.telcobright.seed.prepaid.api.Final;
import com.telcobright.seed.prepaid.api.Prepaid;
import com.telcobright.seed.sessionflow.api.RoutesphereContext;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * The sealed rules of ONE lane's Prepaid, as its builder checked them (the engine reads them; nothing here moves money).
 *
 * @param finalRule      the lane's {@code finalize(ctx -> Final)} (or {@code actualCharge}'s)
 * @param noBalanceCause null = {@code INSUFFICIENT_BALANCE}
 * @param reference      null = the session's id
 * @param units          the package units that may pay THIS request (PackageMatch), then cash; none = cash only
 * @param productKeys    §15 · the product keys THIS request may consume from a package, best first; null = packages match by unit alone
 * @param itemRate       §15 · the cost on a matched item; null = the rate rule prices the item's account
 * @param serviceGroup   §15.3 · the request's revenue stream; null = no narrowing by group
 */
public record PrepaidRules<C extends RoutesphereContext>(String name, Admission admission, Duration window, Duration firstRenewal,
                                                         Prepaid.RateRule<C> rate, Function<C, Final> finalRule, AccountRule accounts,
                                                         Prepaid.NoBalanceCause<C> noBalanceCause, Function<C, String> reference,
                                                         Function<C, Set<String>> units, Function<C, List<String>> productKeys,
                                                         Prepaid.ItemRate<C> itemRate, Function<C, Integer> serviceGroup) {

    /** The rules of before §15: no product keys, no item rate, no group. */
    public PrepaidRules(String name, Admission admission, Duration window, Duration firstRenewal, Prepaid.RateRule<C> rate, Function<C, Final> finalRule,
                        AccountRule accounts, Prepaid.NoBalanceCause<C> noBalanceCause, Function<C, String> reference, Function<C, Set<String>> units) {
        this(name, admission, window, firstRenewal, rate, finalRule, accounts, noBalanceCause, reference, units, null, null, null);
    }

    /** §15 · Does this lane relate package accounts to their items by product key? */
    public boolean matchesItems() { return productKeys != null; }

    public boolean windowed() { return admission == Admission.HOLD_WINDOW; }

    public boolean chargesNow() { return admission == Admission.CHARGE_NOW; }
}
