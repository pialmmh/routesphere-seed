package com.telcobright.seed.prepaid.api;

import com.telcobright.rtc.domainmodel.LevelAdmission;
import com.telcobright.rtc.domainmodel.mysqlentity.PackageAccount;
import com.telcobright.seed.prepaid.dependencies.PrepaidSettings;
import com.telcobright.seed.prepaid.internal.LaneHooks;
import com.telcobright.seed.prepaid.internal.PrepaidEngine;
import com.telcobright.seed.prepaid.internal.PrepaidRules;
import com.telcobright.seed.prepaid.spi.PrepaidHooks;
import com.telcobright.seed.sessionflow.api.LaneAnswers;
import com.telcobright.seed.sessionflow.api.RoutesphereContext;
import com.telcobright.seed.sessionflow.api.TierSettlement;
import com.telcobright.seed.sessionflow.dependencies.RoutesphereKit;
import com.telcobright.seed.sessionflow.spi.MoneyEngine;
import com.telcobright.seed.sessionflow.spi.MoneyRules;
import com.telcobright.statewalk.session.Verdict;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * The prepaid money of ONE lane, as the application writes it (design {@code prepaid-engine-design.md} §10, agreed with the owner
 * 2026-10-26): how money is taken at admission, what one unit costs on an account at a tier, which accounts may pay, what the end does —
 * and the seed's ONE engine runs it (the base's money steps, moved to seed-prepaid in L2a §11). The lane gives it to its flow
 * ({@code RoutesphereApp.Builder.prepaid}); a lane that gives none runs on its own answers ({@link #ofTheLanesAnswers}).
 *
 * <pre>
 *   Prepaid&lt;CallContext&gt; prepaid = Prepaid.&lt;CallContext&gt;builder("call")
 *       .admission(Admission.HOLD_WINDOW).window(cfg.window()).firstRenewal(cfg.firstRenewal())     // 60 s, 58 s
 *       .rate((call, tier, account) -&gt; rates.unitRate(call, tier, account))
 *       .chooseAccounts((call, tier, fundable) -&gt; intlOut(call) ? cashOnly(fundable) : fundable)
 *       .refusalCause((call, tier) -&gt; intlOut(call) ? NO_BALANCE_INT_OUT : INSUFFICIENT_BALANCE)
 *       .finalize(call -&gt; call.answered() ? Final.charge(tier -&gt; rates.cost(call, tier)) : Final.releaseAll())
 *       .build(kit);
 * </pre>
 *
 * The lambdas are the lane's RULES, shared by every session: they keep nothing of a session. A lambda that throws at admission refuses
 * the session ({@code INTERNAL_ERROR}, every hold given back); at the end it leaves the tier's money held and the session OWED (one ERROR
 * names the reference). {@code finalize} is asked once per tier at the end (a rule of the context: the same answer each time).
 *
 * @param <C> the lane's context
 */
public final class Prepaid<C extends RoutesphereContext> implements MoneyRules<C> {

    /** (ctx, tier, account) → what ONE unit costs on this account at this tier (a window, a view, a message). Null = {@code UNRATED}. */
    @FunctionalInterface
    public interface RateRule<C> { BigDecimal rate(C ctx, PayingTier tier, PackageAccount account); }

    /** (ctx, tier, fundable) → the accounts to try, in order, from the fundable ones (the call abroad: cash only). */
    @FunctionalInterface
    public interface AccountChoice<C> { List<PackageAccount> choose(C ctx, PayingTier tier, List<PackageAccount> fundable); }

    /** (ctx, tier) → the cause when no account of the tier can pay (the call abroad: {@code NO_BALANCE_INT_OUT}). */
    @FunctionalInterface
    public interface RefusalCause<C> { String cause(C ctx, PayingTier tier); }

    /** (ctx, tier, account) → ok = hold it; a reject = the session is refused with its cause. */
    @FunctionalInterface
    public interface HoldGate<C> { Verdict check(C ctx, PayingTier tier, PackageAccount account); }

    /** (ctx, tier, balance after) → the lane says it its own way (a notifier). */
    @FunctionalInterface
    public interface LowBalance<C> { void warn(C ctx, LevelAdmission tier, BigDecimal balanceAfter); }

    private final RoutesphereKit kit;
    private final PrepaidRules<C> rules;

    private Prepaid(RoutesphereKit kit, PrepaidRules<C> rules) {
        this.kit = kit;
        this.rules = rules;
    }

    public static <C extends RoutesphereContext> Builder<C> builder(String name) { return new Builder<>(name); }

    /**
     * The Prepaid of a lane that gives none (design §11: ONE engine): nothing of its own — the engine reads the lane's existing answers
     * ({@code rateAtLevel}, {@code refundPolicy}, {@code settleOutcome}, {@code billedUnits}, {@code rateNextWindow}) exactly as before.
     */
    public static <C extends RoutesphereContext> Prepaid<C> ofTheLanesAnswers(RoutesphereKit kit) { return new Prepaid<>(Objects.requireNonNull(kit, "kit"), null); }

    /** The lane's name (its log lines); a Prepaid of the lane's answers has none of its own. */
    public String name() { return rules == null ? "(the lane's answers)" : rules.name(); }

    /** True = rules of its own (the builder's); false = the lane's answers ({@link #ofTheLanesAnswers}). */
    public boolean ownsItsRules() { return rules != null; }

    /** How money is taken at admission; null for a Prepaid of the lane's answers. */
    public Admission admission() { return rules == null ? null : rules.admission(); }

    /** The engine of ONE flow on these rules — the base's moved money steps over the lane's answers (refused for a flow on another kit). */
    @Override
    public MoneyEngine<C> engineFor(LaneAnswers<C> lane) {
        if (lane.kit() != kit) {
            throw new IllegalStateException("[" + lane.name() + "] the Prepaid '" + name() + "' was built on another kit: a Prepaid serves the kit it"
                + " was built with (its ledger, its trees, its cadence) — build it with the flow's kit");
        }
        return new PrepaidEngine<>(lane, rules);
    }

    /**
     * The money roads of this Prepaid's kit (item 5): a credit or a reparent of an account through the ledger port, a partner's accounts as
     * the tree names them today with their live balances; a purchase refused in words (round L2b's). The application mounts them (R0).
     */
    public PrepaidAdmin admin() { return new PrepaidAdmin(kit); }

    /**
     * The rules of one lane's Prepaid. Each method names the question it answers (■ = required); {@link #build} refuses a missing or
     * disagreeing part in words. Once built, the rules are sealed.
     */
    public static final class Builder<C extends RoutesphereContext> {
        private final String name;
        private Admission admission;
        private Duration window;
        private Duration firstRenewal;
        private RateRule<C> rate;
        private Function<C, Final> finalize;
        private AccountRule accounts = AccountRule.UNITS_THEN_CASH;
        private AccountChoice<C> chooseAccounts;
        private RefusalCause<C> refusalCause;
        private PrepaidHooks<C> hooks;
        private HoldGate<C> beforeHold;
        private Consumer<C> onExhausted;
        private BigDecimal lowBalanceAt;
        private LowBalance<C> onLowBalance;
        private BiConsumer<C, List<TierSettlement>> onFinished;
        private Function<C, String> reference;
        private boolean sealed;

        private Builder(String name) {
            if (name == null || name.isBlank()) throw new IllegalArgumentException("a Prepaid needs its name (the lane's: call, sms, ad, wifi)");
            this.name = name;
        }

        /** ■ How money is taken at admission: HOLD_WINDOW (the call), HOLD (the ad), CHARGE_NOW (the SMS). */
        public Builder<C> admission(Admission v) { open(); this.admission = v; return this; }
        /** HOLD_WINDOW ■ · one window (the call: 60 s) — the kit's renewal period must be the same. */
        public Builder<C> window(Duration v) { open(); this.window = v; return this; }
        /** HOLD_WINDOW ■ · the first renewal after the answer (the call: 58 s) — the kit's first renewal must be the same. */
        public Builder<C> firstRenewal(Duration v) { open(); this.firstRenewal = v; return this; }
        /** The values at once, from the application's config (R0): admission, window, first renewal, accounts, the low-balance mark. */
        public Builder<C> settings(PrepaidSettings v) {
            open();
            admission = v.admission();
            window = v.window();
            firstRenewal = v.firstRenewal();
            accounts = v.accounts();
            if (v.lowBalanceAt() != null) lowBalanceAt = v.lowBalanceAt();
            return this;
        }
        /** ■ What ONE unit costs on an account at a tier (null = UNRATED). */
        public Builder<C> rate(RateRule<C> v) { open(); this.rate = v; return this; }
        /** ■ What the end does with every tier's money. */
        public Builder<C> finalize(Function<C, Final> v) { open(); this.finalize = v; return this; }
        /** Which accounts may pay (default UNITS_THEN_CASH: package units before cash, unexpired only). */
        public Builder<C> accounts(AccountRule v) { open(); this.accounts = Objects.requireNonNull(v, "accounts"); return this; }
        /** The accounts to try, in order, from the rule's fundable ones (the call abroad: cash only). */
        public Builder<C> chooseAccounts(AccountChoice<C> v) { open(); this.chooseAccounts = v; return this; }
        /** The cause when no account of a tier can pay (default {@code INSUFFICIENT_BALANCE}). */
        public Builder<C> refusalCause(RefusalCause<C> v) { open(); this.refusalCause = v; return this; }
        /** A gate before each account is held: ok = hold it; a reject = the session is refused with its cause. */
        public Builder<C> beforeHold(HoldGate<C> v) { open(); this.beforeHold = v; return this; }
        /** Windowed lanes · a renewal found the money ending (the base cuts the session; the lane says it its own way). */
        public Builder<C> onExhausted(Consumer<C> v) { open(); this.onExhausted = v; return this; }
        /** Under {@code threshold} after a hold or a charge, the lane is told. */
        public Builder<C> onLowBalance(BigDecimal threshold, LowBalance<C> v) { open(); this.lowBalanceAt = threshold; this.onLowBalance = v; return this; }
        /** The final state, after every tier was settled: the money per tier (the leaf first) for the lane's own record. */
        public Builder<C> onFinished(BiConsumer<C, List<TierSettlement>> v) { open(); this.onFinished = v; return this; }
        /** Every optional callback as one class (a lambda given on this builder wins over its method). */
        public Builder<C> hooks(PrepaidHooks<C> v) { open(); this.hooks = v; return this; }
        /** Each tier's money is held under {@code <reference>#L<tier>} (default: the session's id). */
        public Builder<C> reference(Function<C, String> v) { open(); this.reference = v; return this; }

        /** The lane's Prepaid on this kit — refused in words when a required part is missing or disagrees with the kit's cadence. */
        public Prepaid<C> build(RoutesphereKit kit) {
            Objects.requireNonNull(kit, "kit");
            refuseAMissingPart();
            refuseADisagreeingCadence(kit);
            sealed = true;
            PrepaidHooks<C> merged = LaneHooks.of(hooks, beforeHold, onExhausted, onLowBalance, onFinished);
            return new Prepaid<>(kit, new PrepaidRules<>(name, admission, window, firstRenewal, rate, finalize, accounts, chooseAccounts, refusalCause,
                merged, lowBalanceAt, reference));
        }

        private void refuseAMissingPart() {
            List<String> missing = new java.util.ArrayList<>();
            if (admission == null) missing.add("admission(HOLD_WINDOW | HOLD | CHARGE_NOW) — how is money taken at admission?");
            if (rate == null) missing.add("rate((ctx, tier, account) -> amount) — what does one unit cost?");
            if (finalize == null) missing.add("finalize(ctx -> Final) — what does the end do with the money?");
            if (admission == Admission.HOLD_WINDOW && window == null) missing.add("window(duration) — how long is one window (the call: 60 s)?");
            if (admission == Admission.HOLD_WINDOW && firstRenewal == null) missing.add("firstRenewal(duration) — when is the first renewal (the call: 58 s)?");
            if (missing.isEmpty()) return;
            throw new IllegalStateException("[" + name + "] the Prepaid has no " + String.join("; no ", missing));
        }

        /** The base's budget child renews on the KIT's cadence: a windowed Prepaid must say the same, a per-event one must not renew. */
        private void refuseADisagreeingCadence(RoutesphereKit kit) {
            long period = kit.settings().reservePeriodSec();
            if (admission != Admission.HOLD_WINDOW) {
                if (window != null || firstRenewal != null || period > 0) {
                    throw new IllegalStateException("[" + name + "] a " + admission + " lane holds no window, but " + (period > 0
                        ? "the kit renews every " + period + " s — set the kit's reserve period to 0" : "the builder gives one — drop window/firstRenewal"));
                }
                return;
            }
            if (window.getSeconds() != period || firstRenewal.getSeconds() != kit.settings().firstRenewalSec()) {
                throw new IllegalStateException("[" + name + "] the Prepaid's window is " + window.getSeconds() + " s, first renewal " + firstRenewal.getSeconds()
                    + " s, but the kit renews every " + period + " s, first after " + kit.settings().firstRenewalSec() + " s — one cadence: give both the"
                    + " same values from the application's config");
            }
        }

        private void open() {
            if (sealed) throw new IllegalStateException("[" + name + "] the Prepaid is built: its rules are sealed");
        }
    }
}
