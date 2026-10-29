package com.telcobright.seed.prepaid.api;

import com.telcobright.rtc.domainmodel.LevelAdmission;
import com.telcobright.rtc.domainmodel.mysqlentity.PackageAccount;
import com.telcobright.seed.prepaid.dependencies.PrepaidSettings;
import com.telcobright.seed.prepaid.internal.PrepaidEngine;
import com.telcobright.seed.prepaid.internal.PrepaidRules;
import com.telcobright.seed.sessionflow.api.LaneAnswers;
import com.telcobright.seed.sessionflow.api.RoutesphereContext;
import com.telcobright.seed.sessionflow.dependencies.RoutesphereKit;
import com.telcobright.seed.sessionflow.spi.MoneyEngine;
import com.telcobright.seed.sessionflow.spi.MoneyRules;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * The prepaid money of ONE lane, as the application writes it (design {@code prepaid-engine-design.md} §10, agreed with the owner
 * 2026-10-26): how money is taken at admission, what one unit costs on an account at a tier, which accounts may pay, what the end does —
 * and the seed's ONE engine runs it (the base's money steps, moved to seed-prepaid in L2a §11). The lane gives it to its flow
 * ({@code RoutesphereApp.Builder.prepaid}); a lane that gives none runs on its own answers ({@link #ofTheLanesAnswers}).
 *
 * <pre>
 *   Prepaid&lt;CallContext&gt; call = Prepaid.&lt;CallContext&gt;builder("call")
 *       .admission(Admission.HOLD_WINDOW).window(cfg.window()).firstRenewal(cfg.firstRenewal())     // 60 s, 58 s
 *       .units(c -&gt; intlOut(c) ? Set.of() : Set.of("TF_min"))               // the best matching minutes package first, then cash (abroad: cash only)
 *       .rate((c, tier, account) -&gt; rates.unitRate(c, tier, account))       // a package: 1 minute; cash: the rate plan's price
 *       .noBalanceCause((c, tier) -&gt; intlOut(c) ? NO_BALANCE_INT_OUT : INSUFFICIENT_BALANCE)
 *       .actualCharge((c, tier) -&gt; rates.cost(c, tier))                    // finalizeBalance: holds back, the actual charge taken
 *       .build(kit);
 *
 *   Prepaid&lt;AdViewContext&gt; ad = Prepaid.&lt;AdViewContext&gt;builder("ad")
 *       .admission(Admission.CHARGE_NOW)                                      // charged at admission; finalizeBalance does nothing
 *       .units(v -&gt; v.isVideo() ? Set.of("AD_sec") : Set.of("AD_view"))
 *       .rate((v, tier, account) -&gt; switch (account.getUom()) { case "AD_view" -&gt; ONE; case "AD_sec" -&gt; v.seconds(); default -&gt; ratePlanPrice(v, tier); })
 *       .build(kit);
 * </pre>
 *
 * C3 (design §14.1, the owner 2026-10-28: "the base prepaid lib for the routesphere like realtime applications"): the SPI is the units,
 * the rate, the actual charge and the no-balance cause; the config is charge now or windowed, the window, the first renewal, the
 * accounts — nothing more (the lane's own record, a low-balance notice, a gate before a hold, an exhausted renewal's word are the
 * lane's business on its own flow: a refused renewal already ends the session through the base).
 *
 * <p>The lambdas are the lane's RULES, shared by every session: they keep nothing of a session. A lambda that throws at admission refuses
 * the session ({@code INTERNAL_ERROR}, every hold given back); at the end it leaves the tier's money held and the session OWED (one ERROR
 * names the reference). {@code finalize} is asked once per tier at the end (a rule of the context: the same answer each time).
 *
 * @param <C> the lane's context
 */
public final class Prepaid<C extends RoutesphereContext> implements MoneyRules<C> {

    /** (ctx, tier, account) → what ONE unit costs on this account at this tier (a window, a view, a message). Null = {@code UNRATED}. */
    @FunctionalInterface
    public interface RateRule<C> { BigDecimal rate(C ctx, PayingTier tier, PackageAccount account); }

    /** (ctx, tier) → the ACTUAL charge of a tier at the end (the call: the real seconds × that tier's rate). */
    @FunctionalInterface
    public interface ActualCharge<C> { BigDecimal cost(C ctx, LevelAdmission tier); }

    /** (ctx, tier) → the cause when no account of the tier can pay (the call abroad: {@code NO_BALANCE_INT_OUT}). */
    @FunctionalInterface
    public interface NoBalanceCause<C> { String cause(C ctx, PayingTier tier); }

    /**
     * §15 · (ctx, tier, match) → what THIS request costs in the MATCHED ITEM's unit: a video on the "video 30 s" item is ONE view, a call
     * window on the "880" minutes item one minute. Null = {@code UNRATED}. Cash accounts are still priced by {@link RateRule}.
     */
    @FunctionalInterface
    public interface ItemRate<C> { BigDecimal units(C ctx, PayingTier tier, PackageItemMatch match); }

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
     * The money roads of this Prepaid's kit: a purchase through the ledger (ids from the application's source: {@code admin().withIds(…)}),
     * a credit or a reparent of an account through the ledger port, a partner's accounts as the ledger (else the tree) names them with their
     * live balances. The application mounts them (R0).
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
        private Function<C, Set<String>> units = ctx -> PackageMatch.CALL_AND_SMS_UNITS;
        private NoBalanceCause<C> noBalanceCause;
        private Function<C, String> reference;
        private Function<C, List<String>> productKeys;
        private ItemRate<C> itemRate;
        private Function<C, Integer> serviceGroup;
        private boolean sealed;

        private Builder(String name) {
            if (name == null || name.isBlank()) throw new IllegalArgumentException("a Prepaid needs its name (the lane's: call, sms, ad, wifi)");
            this.name = name;
        }

        /** ■ How money is taken at admission: HOLD_WINDOW (the call, the WiFi), CHARGE_NOW (the ad view, the SMS). */
        public Builder<C> admission(Admission v) { open(); this.admission = v; return this; }
        /** HOLD_WINDOW ■ · one window (the call: 60 s) — the kit's renewal period must be the same. */
        public Builder<C> window(Duration v) { open(); this.window = v; return this; }
        /** HOLD_WINDOW ■ · the first renewal after the answer (the call: 58 s) — the kit's first renewal must be the same. */
        public Builder<C> firstRenewal(Duration v) { open(); this.firstRenewal = v; return this; }
        /** The values at once, from the application's config (R0): admission, window, first renewal, accounts. */
        public Builder<C> settings(PrepaidSettings v) {
            open();
            admission = v.admission();
            window = v.window();
            firstRenewal = v.firstRenewal();
            accounts = v.accounts();
            return this;
        }
        /** ■ What ONE unit costs on an account at a tier (null = UNRATED). */
        public Builder<C> rate(RateRule<C> v) { open(); this.rate = v; return this; }
        /** ■ What the end does with every tier's money (a CHARGE_NOW lane may say nothing: its charge stands). */
        public Builder<C> finalize(Function<C, Final> v) { open(); this.finalize = v; return this; }

        /**
         * Windowed lanes · {@code finalizeBalance} the call's way (the owner, 2026-10-27: "for calls, we calculate call's actual charge, return
         * balance and apply the final charge"): answered → every tier's holds go back and its {@code actualCharge} is taken; never answered
         * → every hold goes back. The same as {@code finalize(ctx -> ctx.answered() ? Final.charge(...) : Final.releaseAll())}.
         */
        public Builder<C> actualCharge(ActualCharge<C> v) {
            Objects.requireNonNull(v, "actualCharge");
            return finalize(ctx -> ctx.answered() ? Final.charge(tier -> v.cost(ctx, tier)) : Final.releaseAll());
        }

        /**
         * The package units that may pay THIS request — the best matching active package of them pays first, then cash at the lane's rate
         * ({@link PackageMatch}; the owner, 2026-10-27); none = cash only (the call abroad). The ad: an image view {@code AD_view}, a video
         * {@code AD_sec}. Default: the call's minutes and the SMS's messages ({@code TF_min}, {@code OTH_ea}).
         */
        public Builder<C> units(Function<C, Set<String>> v) { open(); this.units = Objects.requireNonNull(v, "units"); return this; }
        /** Which accounts may pay (default UNITS_THEN_CASH: package units before cash, unexpired only). */
        public Builder<C> accounts(AccountRule v) { open(); this.accounts = Objects.requireNonNull(v, "accounts"); return this; }
        /** The cause when no account of a tier can pay (default {@code INSUFFICIENT_BALANCE}). */
        public Builder<C> noBalanceCause(NoBalanceCause<C> v) { open(); this.noBalanceCause = v; return this; }
        /** Each tier's money is held under {@code <reference>#L<tier>} (default: the session's id). */
        public Builder<C> reference(Function<C, String> v) { open(); this.reference = v; return this; }

        /**
         * §15 (the owner, 2026-10-29) · The PRODUCT KEYS this request may consume from a package, best first ({@link ProductKeys}): the ad's
         * product — the Odoo product id the items carry in {@code packageitem.prefix} — or the call's prefixes, longest first. Given, the
         * walk relates every package account to the ITEM of its package by key (one hashtable get per key, {@code ItemCatalog}) and only an
         * account whose item matches pays; no package matches → cash where the rule allows it, else {@code PACKAGE_ITEM_NOT_FOUND}. Not
         * given: packages match by unit alone, as before §15 (the live call's parity).
         */
        public Builder<C> productKeys(Function<C, List<String>> v) { open(); this.productKeys = v; return this; }
        /** §15 · What the request costs on the MATCHED item (default: the {@code rate} rule on the item's account). Needs {@link #productKeys}. */
        public Builder<C> itemRate(ItemRate<C> v) { open(); this.itemRate = v; return this; }
        /**
         * §15.3 (the owner, 2026-10-30) · The SERVICE GROUP of THIS request — the revenue stream it belongs to (the call at home 1, abroad 2,
         * the ad view 30, the WiFi 101). Step 1b of the walk: a package account whose item's product belongs to another group pays nothing
         * for it (a domestic call never pays from the video views), before any key is tried; an item of no product, or an account of no
         * item, is not held to it. The matched product's group becomes the charge record's. Null = no such check. Needs {@link #productKeys}.
         */
        public Builder<C> serviceGroup(Function<C, Integer> v) { open(); this.serviceGroup = v; return this; }

        /** The lane's Prepaid on this kit — refused in words when a required part is missing or disagrees with the kit's cadence. */
        public Prepaid<C> build(RoutesphereKit kit) {
            Objects.requireNonNull(kit, "kit");
            refuseAMissingPart();
            refuseADisagreeingCadence(kit);
            sealed = true;
            Function<C, Final> end = finalize != null ? finalize : ctx -> Final.nothing();     // a charge-now lane: the charge stands
            return new Prepaid<>(kit, new PrepaidRules<>(name, admission, window, firstRenewal, rate, end, accounts, noBalanceCause, reference, units, productKeys, itemRate, serviceGroup));
        }

        private void refuseAMissingPart() {
            List<String> missing = new java.util.ArrayList<>();
            if (admission == null) missing.add("admission(HOLD_WINDOW | CHARGE_NOW) — how is money taken at admission?");
            if (rate == null) missing.add("rate((ctx, tier, account) -> amount) — what does one unit cost?");
            if (finalize == null && admission != Admission.CHARGE_NOW) missing.add("finalize(ctx -> Final) or actualCharge((ctx, tier) -> cost) — what does the end do with the money?");
            if (admission == Admission.HOLD_WINDOW && window == null) missing.add("window(duration) — how long is one window (the call: 60 s)?");
            if (admission == Admission.HOLD_WINDOW && firstRenewal == null) missing.add("firstRenewal(duration) — when is the first renewal (the call: 58 s)?");
            if (itemRate != null && productKeys == null) missing.add("productKeys(ctx -> keys) — an itemRate prices a matched item: which product keys may this request consume (ProductKeys)?");
            if (serviceGroup != null && productKeys == null) missing.add("productKeys(ctx -> keys) — a serviceGroup narrows the items matched by key: which product keys may this request consume?");
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
