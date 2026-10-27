package com.telcobright.seed.prepaid.internal;

import com.telcobright.seed.prepaid.api.AccountRule;
import com.telcobright.seed.prepaid.api.Admission;
import com.telcobright.seed.prepaid.api.Final;
import com.telcobright.seed.prepaid.api.Prepaid;
import com.telcobright.seed.prepaid.spi.PrepaidHooks;
import com.telcobright.seed.sessionflow.api.RoutesphereContext;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Set;
import java.util.function.Function;

/**
 * The sealed rules of ONE lane's Prepaid, as its builder checked them (the engine reads them; nothing here moves money).
 *
 * @param finalRule      the lane's {@code finalize(ctx -> Final)}
 * @param chooseAccounts null = the {@code accounts} rule's order
 * @param refusalCause   null = {@code INSUFFICIENT_BALANCE}
 * @param reference      null = the session's id
 * @param lowBalanceAt   null = never told
 * @param packageUnits   the package units that may pay THIS request (PackageMatch); the default: the call's minutes and the SMS's messages
 */
public record PrepaidRules<C extends RoutesphereContext>(String name, Admission admission, Duration window, Duration firstRenewal,
                                                         Prepaid.RateRule<C> rate, Function<C, Final> finalRule, AccountRule accounts,
                                                         Prepaid.AccountChoice<C> chooseAccounts, Prepaid.RefusalCause<C> refusalCause,
                                                         PrepaidHooks<C> hooks, BigDecimal lowBalanceAt, Function<C, String> reference,
                                                         Function<C, Set<String>> packageUnits) {

    public boolean windowed() { return admission == Admission.HOLD_WINDOW; }

    public boolean chargesNow() { return admission == Admission.CHARGE_NOW; }
}
