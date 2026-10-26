package com.telcobright.seed.prepaid.internal;

import com.telcobright.rtc.domainmodel.mysqlentity.PackageAccount;
import com.telcobright.rtc.domainmodel.mysqlentity.Partner;
import com.telcobright.seed.prepaid.api.PayingTier;
import com.telcobright.seed.sessionflow.api.LaneAnswers;
import com.telcobright.seed.sessionflow.api.RoutesphereContext;
import com.telcobright.seed.sessionflow.api.SessionCause;
import com.telcobright.seed.sessionflow.api.Tier;
import com.telcobright.seed.sessionflow.api.TierRate;
import com.telcobright.seed.sessionflow.spi.LedgerPort.LedgerRefusal;
import com.telcobright.statewalk.session.Verdict;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * ADMITTING, ONE tier of a lane with a Prepaid of its own (L2a 2b) — routesphere-core {@code ReserveBalanceStep} 62–135: the accounts
 * the rule lets pay (unexpired, packages before cash — or the lane's choice: the call abroad, cash only), each rated by the lane's
 * {@code rate} and gated by its {@code beforeHold}, the tier held on the FIRST that pays (82–123: "an exhausted package falls through to
 * the customer's cash") through the moved hold ({@link TierReserve}); none pays → the lane's {@code refusalCause}. The order of operations
 * is today's: this tier only, as the chain walks it. CHARGE_NOW (item 4): the account is charged at once instead of held — the SMS's
 * final debit, every tier as the chain walks it, no hold row. Stateless: one per ask.
 */
final class AccountWalk<C extends RoutesphereContext> {

    private final LaneAnswers<C> flow;
    private final PrepaidRules<C> rules;

    AccountWalk(LaneAnswers<C> flow, PrepaidRules<C> rules) {
        this.flow = flow;
        this.rules = rules;
    }

    Verdict admit(C ctx, Tier tier, Partner partner) {
        PayingTier paying = new PayingTier(tier, partner);
        List<PackageAccount> accounts = accountsToTry(ctx, paying);
        String reference = TierReserve.referenceOf(referenceOf(ctx), ctx.candidatesTried, tier.levelIndex());
        TierReserve.Held held = TierReserve.of(flow).holdOnTheFirstThatPays(ctx, tier, partner, accounts, account -> rated(ctx, paying, account),
            reference, () -> refusalCauseOf(ctx, paying), rules.chargesNow());
        if (held.level() != null) LowBalanceMark.check(flow, rules, ctx, held.level());
        return held.verdict();
    }

    /** The accounts the rule lets pay, unexpired at the session's clock — then the lane's own choice among them. */
    private List<PackageAccount> accountsToTry(C ctx, PayingTier tier) {
        LocalDateTime now = LocalDateTime.now(flow.kit().clock());
        List<PackageAccount> fundable = rules.accounts().fundable(tier.partner(), tier.tenant().getContext(), now);
        if (rules.chooseAccounts() == null) return fundable;
        List<PackageAccount> chosen = rules.chooseAccounts().choose(ctx, tier, fundable);
        return chosen == null ? List.of() : chosen;
    }

    /** One account's rate for ONE unit, after the lane's gate (a refusal of the gate refuses the session with its cause). */
    private TierRate rated(C ctx, PayingTier tier, PackageAccount account) {
        Verdict gate = rules.hooks().beforeHold(ctx, tier, account);
        if (gate != null && !gate.accepted()) throw new LedgerRefusal(gate.cause(), "the lane's beforeHold refused account " + account.getId());
        BigDecimal unit = flow.step(ctx, "RATE", () -> rules.rate().rate(ctx, tier, account));
        return unit == null ? null : TierRate.of(unit, unit, account.getUom(), null).onAccount(account);
    }

    private String refusalCauseOf(C ctx, PayingTier tier) {
        String cause = rules.refusalCause() == null ? null : rules.refusalCause().cause(ctx, tier);
        return cause == null ? SessionCause.INSUFFICIENT_BALANCE : cause;
    }

    private String referenceOf(C ctx) {
        String own = rules.reference() == null ? null : rules.reference().apply(ctx);
        return own == null ? ctx.sessionId() : own;
    }
}
