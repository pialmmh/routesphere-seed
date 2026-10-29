package com.telcobright.seed.prepaid.internal;

import com.telcobright.rtc.domainmodel.LevelAdmission;
import com.telcobright.rtc.domainmodel.mysqlentity.PackageAccount;
import com.telcobright.rtc.domainmodel.mysqlentity.Partner;
import com.telcobright.seed.prepaid.api.PackageItemMatch;
import com.telcobright.seed.prepaid.api.PackageMatch;
import com.telcobright.seed.prepaid.api.PayingTier;
import com.telcobright.seed.sessionflow.api.LaneAnswers;
import com.telcobright.seed.sessionflow.api.RoutesphereContext;
import com.telcobright.seed.sessionflow.api.SessionCause;
import com.telcobright.seed.sessionflow.api.Tier;
import com.telcobright.seed.sessionflow.api.RoutesphereFlow;
import com.telcobright.seed.sessionflow.api.TierRate;
import com.telcobright.seed.sessionflow.spi.LedgerPort;
import com.telcobright.statewalk.session.Verdict;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ADMITTING, ONE tier of a lane with a Prepaid of its own (L2a 2b) — routesphere-core {@code ReserveBalanceStep} 62–135: the accounts
 * the rule lets pay (unexpired, the lane's units' packages before cash — none: cash only, the call abroad), each rated by the lane's
 * {@code rate}, the tier held on the FIRST that pays (82–123: "an exhausted package falls through to the customer's cash") through the
 * moved hold ({@link TierReserve}); none pays → the lane's {@code noBalanceCause}. The order of operations
 * is today's: this tier only, as the chain walks it. CHARGE_NOW (item 4): the account is charged at once instead of held — the SMS's
 * final debit, every tier as the chain walks it, no hold row. Stateless: one per ask.
 *
 * <p>§15 (the owner, 2026-10-29) · A lane that names {@code productKeys} walks the owner's four steps: (1) the accounts that may pay, the
 * best package first (the catalog's order, {@link PackageMatch}); (2) each package account's ITEM by the request's key — a hashtable get
 * on the ledger's cached catalog ({@link ItemLookup}); (3) no item matches → the account pays nothing for this request; no package at all
 * and no cash the rule allows → {@code PACKAGE_ITEM_NOT_FOUND}; (4) the first account that pays is charged or held. A tier whose ledger
 * caches no catalog is walked as before §15 (by unit), said once in the log.
 */
final class AccountWalk<C extends RoutesphereContext> {

    private static final Logger log = LoggerFactory.getLogger(RoutesphereFlow.class);
    /** The tiers said once: "<lane>|<db>" — a lane with product keys over a ledger that caches no catalog. */
    private static final Set<String> SAID_NO_CATALOG = ConcurrentHashMap.newKeySet();

    private final LaneAnswers<C> flow;
    private final PrepaidRules<C> rules;

    AccountWalk(LaneAnswers<C> flow, PrepaidRules<C> rules) {
        this.flow = flow;
        this.rules = rules;
    }

    /**
     * L2b item 2 · A dry run rates the first account at once (nothing held, as before); otherwise the tier is PLANNED — its accounts to lock
     * and its hold — and held with every other tier in the walk's one unit ({@code PrepaidEngine.reserveBalance}).
     */
    Verdict planReserve(C ctx, Tier tier, Partner partner) {
        PayingTier paying = new PayingTier(tier, partner);
        Candidates candidates = accountsToTry(ctx, paying);
        String reference = TierReserve.referenceOf(referenceOf(ctx), ctx.candidatesTried, tier.levelIndex());
        if (ctx.dryRun) return holdOnTheFirstThatPays(ctx, tier, partner, paying, candidates, reference).verdict();
        WalkPlan.of(ctx).add(levelsOf(tier, partner, candidates.accounts()), () -> causeOf(holdOnTheFirstThatPays(ctx, tier, partner, paying, candidates, reference)));
        return Verdict.ok();
    }

    /** The accounts to try in order, each package account's item match (§15; none when the lane names no keys), and the cause when none remains. */
    record Candidates(List<PackageAccount> accounts, Map<Long, PackageItemMatch> matches, String causeWhenNone) {
        PackageItemMatch matchOf(PackageAccount account) { return account == null || account.getId() == null ? null : matches.get(account.getId()); }
    }

    private TierReserve.Held holdOnTheFirstThatPays(C ctx, Tier tier, Partner partner, PayingTier paying, Candidates candidates, String reference) {
        TierReserve.Held held = TierReserve.of(flow).holdOnTheFirstThatPays(ctx, tier, partner, candidates.accounts(), account -> rated(ctx, paying, account, candidates.matchOf(account)),
            reference, () -> candidates.causeWhenNone() != null ? candidates.causeWhenNone() : noBalanceCauseOf(ctx, paying), rules.chargesNow());
        rememberTheGroupThatPaid(ctx, tier, candidates, held);
        return held;
    }

    /**
     * §15.3 · The group of the product that paid this tier goes on the session: the charge record of the tier carries it instead of the lane's.
     * A tier paid by cash or by an item of no product clears it — a later candidate's walk never keeps an earlier candidate's group.
     */
    private static void rememberTheGroupThatPaid(RoutesphereContext ctx, Tier tier, Candidates candidates, TierReserve.Held held) {
        if (held == null || held.level() == null) return;
        PackageItemMatch match = candidates.matchOf(held.level().getAccount());
        Integer group = match == null ? null : match.serviceGroupId();
        if (group != null && group > 0) ctx.tierServiceGroups.put(tier.levelIndex(), group);
        else ctx.tierServiceGroups.remove(tier.levelIndex());
    }

    private static String causeOf(TierReserve.Held held) { return held.verdict().accepted() ? null : held.verdict().cause(); }

    /** The tier's accounts as the ledger names them (its schema, the account): the unit locks every one before the first hold. */
    private static List<LevelAdmission> levelsOf(Tier tier, Partner partner, List<PackageAccount> accounts) {
        List<LevelAdmission> levels = new ArrayList<>(accounts.size());
        for (PackageAccount account : accounts) levels.add(new LevelAdmission(tier.levelIndex(), tier.tenant(), partner, account));
        return levels;
    }

    /** The accounts the rule lets pay, unexpired at the session's clock: the lane's units' packages, then cash — §15: each package account with its item. */
    private Candidates accountsToTry(C ctx, PayingTier tier) {
        List<PackageAccount> payable = fundable(ctx, tier, LocalDateTime.now(flow.kit().clock()));
        if (!rules.matchesItems()) return new Candidates(payable, Map.of(), null);
        Optional<LedgerPort.ItemCatalog> catalog = flow.kit().ledger().catalogOf(new LevelAdmission(tier.levelIndex(), tier.tenant(), tier.partner(), null));
        if (catalog.isEmpty()) return walkedByUnit(tier, payable);
        List<String> keys = flow.step(ctx, "PRODUCT_KEYS", () -> rules.productKeys().apply(ctx));
        Integer group = rules.serviceGroup() == null ? null : flow.step(ctx, "SERVICE_GROUP", () -> rules.serviceGroup().apply(ctx));
        ItemLookup.Payable matched = ItemLookup.match(payable, keys == null ? List.of() : keys, catalog.get(), group);
        String cause = !matched.anyPackageMatched() && matched.accounts().isEmpty() ? SessionCause.PACKAGE_ITEM_NOT_FOUND : null;
        return new Candidates(matched.accounts(), matched.matches(), cause);
    }

    /** §15 · The tier's ledger caches no catalog: the packages match by unit, as before §15 — said once per lane and tier. */
    private Candidates walkedByUnit(PayingTier tier, List<PackageAccount> payable) {
        if (SAID_NO_CATALOG.add(rules.name() + "|" + tier.dbName())) {
            log.warn("[{}] the ledger of {} caches no catalog (package, packageitem): the lane names product keys, but its packages are matched by unit alone — name the catalog"
                + " in the root's ledger DB entities", rules.name(), tier.dbName());
        }
        return new Candidates(payable, Map.of(), null);
    }

    /**
     * L3 · The library's ONE package rule ({@link PackageMatch}): the partner's accounts as the LEDGER names them (its purchases' expiry) when
     * it caches purchases, else the tree's; the best matching active package of the lane's units for THIS request first, then cash.
     */
    private List<PackageAccount> fundable(C ctx, PayingTier tier, LocalDateTime now) {
        Optional<List<PackageAccount>> ledgers = flow.kit().ledger().accountsOf(new LevelAdmission(tier.levelIndex(), tier.tenant(), tier.partner(), null));
        List<PackageAccount> catalog = ledgers.orElseGet(() -> PackageMatch.accountsOf(tier.partner(), tier.tenant().getContext()));
        return PackageMatch.payable(catalog, rules.units().apply(ctx), rules.accounts(), now);
    }

    /** One account's rate for ONE unit — §15: a matched item priced by the lane's {@code itemRate} where it gives one; the key is the CDR's matched prefix. */
    private TierRate rated(C ctx, PayingTier tier, PackageAccount account, PackageItemMatch match) {
        BigDecimal unit = flow.step(ctx, "RATE", () -> match != null && rules.itemRate() != null ? rules.itemRate().units(ctx, tier, match) : rules.rate().rate(ctx, tier, account));
        return unit == null ? null : TierRate.of(unit, unit, account.getUom(), match == null ? null : match.key()).onAccount(account);
    }

    private String noBalanceCauseOf(C ctx, PayingTier tier) {
        String cause = rules.noBalanceCause() == null ? null : rules.noBalanceCause().cause(ctx, tier);
        return cause == null ? SessionCause.INSUFFICIENT_BALANCE : cause;
    }

    private String referenceOf(C ctx) {
        String own = rules.reference() == null ? null : rules.reference().apply(ctx);
        return own == null ? ctx.sessionId() : own;
    }
}
