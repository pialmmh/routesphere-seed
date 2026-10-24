package com.telcobright.seed.sessionflow.api;

import com.telcobright.rtc.domainmodel.LevelAdmission;
import com.telcobright.rtc.domainmodel.mysqlentity.Partner;
import com.telcobright.seed.sessionflow.dependencies.RefundPolicy;
import com.telcobright.seed.sessionflow.dependencies.RoutesphereKit;
import com.telcobright.statewalk.session.Outcome;
import com.telcobright.statewalk.session.Verdict;
import com.telcobright.statewalk.session.events.Established;
import com.telcobright.statewalk.session.events.ServiceEnd;
import com.telcobright.statewalk.session.events.SignalingProgress;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;
import java.util.function.Function;

/**
 * A routesphere application in builder syntax (R5, the owner: "for defining a new routesphere application we shall use builder syntax like
 * the new statewalk implementations"): the same questions a subclass of {@link RoutesphereFlow} answers, as lambdas — a small lane writes
 * a handful of lines; a big lane subclasses. The seven forced answers are REQUIRED ({@link Builder#build} refuses a missing one in words);
 * every optional hook keeps the call's default when it is not given.
 *
 * <pre>
 *   RoutesphereFlow&lt;SmsContext&gt; flow = RoutesphereApp.&lt;SmsContext&gt;builder("SmsSupervisor")
 *       .serviceGroup(0)
 *       .identifyRootLevelTenant((facts, find) -&gt; find.byIngress(facts.ingress()))                       // which root owns this door?
 *       .identifyPartner((sms, candidate, find) -&gt; find.byPartnerId(sms, accounts.get(sms.user))) // who pays at the leaf?
 *       .rateAtLevel((sms, tier, partner) -&gt; rates.perMessage(tier, partner, sms.segments))       // what does this tier charge?
 *       .route(sms -&gt; RoutePlan.of(smscs))                                                  // where does it go, in order?
 *       .delivered((sms, outcome) -&gt; "DELIVERED".equals(sms.endCause))                         // did the service happen?
 *       .refundPolicy(sms -&gt; RefundPolicy.of(Rule.REFUND, Rule.REFUND))                         // what does a failed one pay?
 *       .rerouteActionFor(cause -&gt; RerouteAction.FAIL_TERMINAL)                                  // retry, next hop, or stop?
 *       .build(kit);
 * </pre>
 *
 * The lambdas run under the base's guards exactly as overrides do (a throw at admission refuses the candidate with {@code INTERNAL_ERROR};
 * a throw anywhere else is logged with the session and the session still ends). They are the lane's RULES, shared by every session: they
 * keep nothing of a session (the pool rule) — what they learn goes onto the context.
 */
public final class RoutesphereApp {

    private RoutesphereApp() {}

    /** A new application named as its supervisor is registered ({@code AdSupervisor}, {@code SmsSupervisor}): statewalk's table and the log. */
    public static <C extends RoutesphereContext> Builder<C> builder(String name) { return new Builder<>(name); }

    /** (a) Which root operator owns this door? — the facts, and the base's lookups ({@code find.byListener(…)}); null = {@code NO_TENANT}. */
    @FunctionalInterface
    public interface TenantRule { String identify(RoutesphereFacts facts, Lookups find); }

    /** (c) Who pays at the leaf for this candidate? — {@code find.byPartnerId(ctx, id)}; null = {@code PARTNER_NOT_FOUND}. */
    @FunctionalInterface
    public interface PartnerRule<C> { Tier identify(C ctx, Candidate candidate, Lookups find); }

    /** (e) What does this tier charge, and how much does admission reserve? Null = {@code UNRATED}. */
    @FunctionalInterface
    public interface RateRule<C> { TierRate rate(C ctx, Tier tier, Partner partner); }

    /** A gate at one tier (authorize, the root's rules): ok, or the cause. */
    @FunctionalInterface
    public interface TierGate<C> { Verdict check(C ctx, Tier tier, Partner partner); }

    /** The lane's own fields on one tier's CDR record ({@code tier} null = nobody admitted). */
    @FunctionalInterface
    public interface CdrFill<C> { void fill(C ctx, LevelAdmission tier, CdrEvent cdr); }

    /**
     * The answers of one application. Each method names the question it answers; the forced seven are refused at {@link #build} when
     * missing. Once built, the answers are sealed: a flow never changes its rules under a running session.
     *
     * @param <C> the application's context
     */
    public static final class Builder<C extends RoutesphereContext> {
        final String name;
        Integer serviceGroup;
        TenantRule identifyRootLevelTenant;
        PartnerRule<C> identifyPartner;
        RateRule<C> rateAtLevel;
        Function<C, RoutePlan> route;
        BiPredicate<C, Outcome> delivered;
        Function<C, RefundPolicy> refundPolicy;
        Function<String, RerouteAction> rerouteActionFor;
        Function<C, String> buildTask;
        Function<C, List<Candidate>> selectCandidates;
        TierGate<C> authorize;
        TierGate<C> applyRootRules;
        BiFunction<C, Candidate, Verdict> confirmAdmission;
        Function<C, SettleOutcome> settleOutcome;
        Function<C, BigDecimal> billedUnits;
        BiFunction<C, LevelAdmission, TierRate> rateNextWindow;
        BiConsumer<C, SignalingProgress> onSignalingProgress;
        BiConsumer<C, Established> onAnswered;
        BiConsumer<C, ServiceEnd> onServiceEnded;
        CdrFill<C> fillCdr;
        BiFunction<C, Outcome, Object> buildSdr;
        BiConsumer<C, Outcome> onEnded;
        Function<String, List<String>> topicsOf;
        private boolean sealed;

        private Builder(String name) {
            if (name == null || name.isBlank()) throw new IllegalArgumentException("a routesphere application needs its name (its supervisor's: AdSupervisor, SmsSupervisor, …)");
            this.name = name;
        }

        // ── the forced answers (R2): every lane states them ──

        /** ■ The CDR's service group: 0 = billing detects it (a voice call); 30 = an ad view, taken as given. */
        public Builder<C> serviceGroup(int v) { open(); this.serviceGroup = v; return this; }
        /** ■ (a) Which root operator owns this door? */
        public Builder<C> identifyRootLevelTenant(TenantRule v) { open(); this.identifyRootLevelTenant = v; return this; }
        /** @deprecated the old name of {@link #identifyRootLevelTenant}. */
        @Deprecated public Builder<C> identifyTenant(TenantRule v) { return identifyRootLevelTenant(v); }
        /** ■ (c) Who pays at the leaf? */
        public Builder<C> identifyPartner(PartnerRule<C> v) { open(); this.identifyPartner = v; return this; }
        /** ■ (e) What does this tier charge; how much is reserved? */
        public Builder<C> rateAtLevel(RateRule<C> v) { open(); this.rateAtLevel = v; return this; }
        /** ■ (d) Where does it go, in failover order? */
        public Builder<C> route(Function<C, RoutePlan> v) { open(); this.route = v; return this; }
        /** @deprecated the old name of {@link #route}. */
        @Deprecated public Builder<C> routing(Function<C, RoutePlan> v) { return route(v); }
        /** ■ Did the service happen? (the call: answered; the SMS: its delivery report; the ad: shown) */
        public Builder<C> delivered(BiPredicate<C, Outcome> v) { open(); this.delivered = v; return this; }
        /** ■ What does a session not delivered pay? (the call: {@code RefundPolicy.callsRule()}) */
        public Builder<C> refundPolicy(Function<C, RefundPolicy> v) { open(); this.refundPolicy = v; return this; }
        /** ■ (f) After a signaling failure: the same hop, the next hop, or the end? */
        public Builder<C> rerouteActionFor(Function<String, RerouteAction> v) { open(); this.rerouteActionFor = v; return this; }

        // ── the optional hooks: the call's default when not given ──

        /** INIT · the request → the session's task on the context; the refusal, or null. */
        public Builder<C> buildTask(Function<C, String> v) { open(); this.buildTask = v; return this; }
        /** INIT · who may serve this session, in failover order (default: one candidate, itself). */
        public Builder<C> selectCandidates(Function<C, List<Candidate>> v) { open(); this.selectCandidates = v; return this; }
        /** ADMITTING, every tier, after the leaf's slot · the lane's gates. */
        public Builder<C> authorize(TierGate<C> v) { open(); this.authorize = v; return this; }
        /** ADMITTING, the root tier · the root's rules on the task (a call: the digit filter). */
        public Builder<C> applyRootRules(TierGate<C> v) { open(); this.applyRootRules = v; return this; }
        /** ADMITTING, last · what must hold before the session may start (an ad: claim one view of the quota). */
        public Builder<C> confirmAdmission(BiFunction<C, Candidate, Verdict> v) { open(); this.confirmAdmission = v; return this; }
        /** The final state · what the session came to, for the refund rule (default: answered = consumed, else failed). */
        public Builder<C> settleOutcome(Function<C, SettleOutcome> v) { open(); this.settleOutcome = v; return this; }
        /** The final state · how many of the rate's units are billed (default: the units reserved). */
        public Builder<C> billedUnits(Function<C, BigDecimal> v) { open(); this.billedUnits = v; return this; }
        /** ACTIVE, every renewal · what this tier reserves for the next window (default: it does not renew). */
        public Builder<C> rateNextWindow(BiFunction<C, LevelAdmission, TierRate> v) { open(); this.rateNextWindow = v; return this; }
        /** CONNECTING · a phase the signaling reported (ringing, the ad shown). */
        public Builder<C> onSignalingProgress(BiConsumer<C, SignalingProgress> v) { open(); this.onSignalingProgress = v; return this; }
        /** CONNECTING → ACTIVE · the answer. */
        public Builder<C> onAnswered(BiConsumer<C, Established> v) { open(); this.onAnswered = v; return this; }
        /** ACTIVE → SUCCEEDED · the service ended. */
        public Builder<C> onServiceEnded(BiConsumer<C, ServiceEnd> v) { open(); this.onServiceEnded = v; return this; }
        /** The CDR · the lane's own fields on one tier's record. */
        public Builder<C> fillCdr(CdrFill<C> v) { open(); this.fillCdr = v; return this; }
        /** The finish · the lane's session record (the SDR), or null for none. */
        public Builder<C> buildSdr(BiFunction<C, Outcome, Object> v) { open(); this.buildSdr = v; return this; }
        /** The finish, last · the lane's own closing. */
        public Builder<C> onEnded(BiConsumer<C, Outcome> v) { open(); this.onEnded = v; return this; }

        /** The start · the lane's own Kafka topics of one root ({@code <app>_<in|out>_<name>_<root>}); default: none. */
        public Builder<C> topicsOf(Function<String, List<String>> v) { open(); this.topicsOf = v; return this; }

        /**
         * The application's flow on this kit — refused in words when a forced answer is missing (a lane that forgot one would be billed like
         * a call). The answers are sealed from here on; the same builder may make the flow of another kit.
         */
        public RoutesphereFlow<C> build(RoutesphereKit kit) {
            refuseAMissingAnswer();
            sealed = true;
            return new AppFlow<>(Objects.requireNonNull(kit, "kit"), this);
        }

        private void refuseAMissingAnswer() {
            List<String> missing = new ArrayList<>();
            if (serviceGroup == null) missing.add("serviceGroup(int) — the CDR's service group");
            if (identifyRootLevelTenant == null) missing.add("identifyRootLevelTenant((facts, find) -> root) — which root operator owns this door?");
            if (identifyPartner == null) missing.add("identifyPartner((ctx, candidate, find) -> tier) — who pays at the leaf?");
            if (rateAtLevel == null) missing.add("rateAtLevel((ctx, tier, partner) -> rate) — what does this tier charge?");
            if (route == null) missing.add("route(ctx -> plan) — where does it go, in failover order?");
            if (delivered == null) missing.add("delivered((ctx, outcome) -> yes/no) — did the service happen?");
            if (refundPolicy == null) missing.add("refundPolicy(ctx -> policy) — what does a session not delivered pay?");
            if (rerouteActionFor == null) missing.add("rerouteActionFor(cause -> action) — retry, the next hop, or stop?");
            if (missing.isEmpty()) return;
            throw new IllegalStateException("[" + name + "] every lane states its rules (R2) — the builder has no " + String.join("; no ", missing));
        }

        private void open() {
            if (sealed) throw new IllegalStateException("[" + name + "] the application is built: its answers are sealed (a flow never changes its rules under a running session)");
        }
    }
}
