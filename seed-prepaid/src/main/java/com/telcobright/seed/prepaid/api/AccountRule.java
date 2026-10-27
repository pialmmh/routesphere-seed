package com.telcobright.seed.prepaid.api;

import com.telcobright.rtc.domainmodel.mysqlentity.PackageAccount;
import com.telcobright.rtc.domainmodel.mysqlentity.Partner;
import com.telcobright.rtc.domainmodel.nonentity.DynamicContext;
import com.telcobright.seed.switchledger.api.AccountOrder;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Which of a partner's accounts may pay, best first — unexpired only, as the tree names them today (the switch ledger's
 * {@link AccountOrder}, routesphere-core {@code ReserveBalanceStep} 159–219). The tier is held on the FIRST of them that pays.
 */
public enum AccountRule {
    /** Package units ({@code TF_min}, {@code OTH_ea}) before cash ({@code BDT}) — the call at home, the SMS. */
    UNITS_THEN_CASH,
    /** Package units only — the WiFi ("if there is no package, no wifi"). */
    UNITS_ONLY,
    /** Cash only — the call abroad ({@code ReserveBalanceStep.bdtAccounts} 209–219). */
    CASH_ONLY;

    /** The accounts this rule lets pay, best first, unexpired at {@code now}. */
    /** L2b item 4 · The same rule over the accounts the LEDGER names (its cached purchases and their expiry, S4). */
    public List<PackageAccount> fundable(List<PackageAccount> accounts, LocalDateTime now) {
        return PackageMatch.payable(accounts, PackageMatch.CALL_AND_SMS_UNITS, this, now);
    }

    public List<PackageAccount> fundable(Partner partner, DynamicContext tree, LocalDateTime now) {
        return fundable(PackageMatch.accountsOf(partner, tree), now);
    }
}
