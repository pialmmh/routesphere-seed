package com.telcobright.seed.callflow.testkit;

import com.telcobright.rtc.domainmodel.LevelAdmission;
import com.telcobright.seed.callflow.api.TierSettlement;
import com.telcobright.seed.callflow.spi.LedgerPort;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A ledger in memory, for the tests of every product on the call flow: one balance per (tenant, partner), and the three
 * verbs with their real rules — a reserve holds, a settle keeps the charge and gives the rest back (or takes the
 * shortfall), a release gives everything back, and every verb moves money once per reference.
 *
 * <p>It can be told to fail like a real ledger: {@link #faultOn} (it does not answer), {@link #refuseOn} (it refuses
 * with a code of its own), {@link #failSettlements}. {@link #openReserves()} is the leak check: after every call has
 * ended it must be zero.
 */
public final class InMemoryLedger implements LedgerPort {

    /** One line of what the ledger was asked. */
    public record Entry(String verb, String reference, String tenant, int partnerId, BigDecimal amount, String note) {}

    private final Map<String, BigDecimal> balances = new HashMap<>();
    private final Map<String, String> units = new HashMap<>();
    private final Map<String, Reservation> reservesByReference = new HashMap<>();
    private final Map<String, BigDecimal> heldByTier = new HashMap<>();
    private final Map<String, TierSettlement> closedTiers = new HashMap<>();
    private final Set<String> faulting = new HashSet<>();
    private final Map<String, String> refusing = new HashMap<>();
    private final List<Entry> journal = new CopyOnWriteArrayList<>();
    private final Map<String, Integer> asked = new HashMap<>();
    private boolean settlementsFail;

    // ── setting the scene ───────────────────────────────────────────────────

    /** Give a partner money ({@code BDT}). */
    public synchronized InMemoryLedger fund(String tenant, int partnerId, String amount) { return fund(tenant, partnerId, amount, "BDT"); }

    /** Give a partner a balance in a unit of its own ({@code TF_min}, {@code OTH_ea}). */
    public synchronized InMemoryLedger fund(String tenant, int partnerId, String amount, String uom) {
        balances.put(keyOf(tenant, partnerId), new BigDecimal(amount));
        units.put(keyOf(tenant, partnerId), uom);
        return this;
    }

    /** From now on the ledger does not answer for this partner (a {@link LedgerFault}). */
    public synchronized void faultOn(String tenant, int partnerId) { faulting.add(keyOf(tenant, partnerId)); }

    /** From now on the ledger refuses this partner with a code of its own (a {@link LedgerRefusal}). */
    public synchronized void refuseOn(String tenant, int partnerId, String code) { refusing.put(keyOf(tenant, partnerId), code); }

    /** From now on the ledger does not take a settlement or a release. */
    public synchronized void failSettlements(boolean fail) { this.settlementsFail = fail; }

    public synchronized void heal() {
        faulting.clear();
        refusing.clear();
        settlementsFail = false;
    }

    // ── looking at it ───────────────────────────────────────────────────────

    public synchronized BigDecimal balanceOf(String tenant, int partnerId) {
        return balances.getOrDefault(keyOf(tenant, partnerId), BigDecimal.ZERO);
    }

    /** Reserves that were neither settled nor released. Zero when no call is live. */
    public synchronized int openReserves() { return heldByTier.size(); }

    public List<Entry> journal() { return List.copyOf(journal); }

    /** How often a verb MOVED money ({@code reserve}, {@code settle}, {@code release}; {@code refused} = a reserve that could not be paid). */
    public long count(String verb) { return journal.stream().filter(e -> e.verb().equals(verb)).count(); }

    /** How often a verb was ASKED, a repeat of the same reference included: a pipeline that settles a tier twice shows here. */
    public synchronized int timesAsked(String verb) { return asked.getOrDefault(verb, 0); }

    // ── the three verbs ─────────────────────────────────────────────────────

    @Override
    public synchronized Optional<Reservation> reserve(LevelAdmission level, BigDecimal amount, String reference) {
        asked.merge("reserve", 1, Integer::sum);
        String key = keyOf(level.getDbName(), level.getPartnerId());
        if (faulting.contains(key)) throw new LedgerFault("the ledger did not answer (scripted) for " + key);
        if (refusing.containsKey(key)) throw new LedgerRefusal(refusing.get(key), "scripted for " + key);
        Reservation seen = reservesByReference.get(reference);
        if (seen != null) return Optional.of(new Reservation(seen.account(), seen.uom(), seen.reserved(), seen.balanceBefore(), seen.balanceAfter(), true));
        BigDecimal before = balances.getOrDefault(key, BigDecimal.ZERO);
        if (before.compareTo(amount) < 0) {
            note("refused", reference, level, amount, "balance " + before);
            return Optional.empty();
        }
        BigDecimal after = before.subtract(amount);
        balances.put(key, after);
        Reservation held = new Reservation(accountOf(key), units.getOrDefault(key, "BDT"), amount, before, after, false);
        reservesByReference.put(reference, held);
        heldByTier.merge(tierOf(reference), amount, BigDecimal::add);
        note("reserve", reference, level, amount, null);
        return Optional.of(held);
    }

    @Override
    public synchronized TierSettlement settle(LevelAdmission level, BigDecimal charged) {
        asked.merge("settle", 1, Integer::sum);
        String tier = level.getDebitReference();
        TierSettlement done = closedTiers.get(tier);
        if (done != null) return done;
        if (settlementsFail) throw new LedgerFault("the ledger did not take the settlement (scripted) of " + tier);
        String key = keyOf(level.getDbName(), level.getPartnerId());
        BigDecimal held = heldByTier.remove(tier);
        if (held == null) held = BigDecimal.ZERO;
        BigDecimal after = balances.getOrDefault(key, BigDecimal.ZERO).add(held.subtract(charged));
        balances.put(key, after);
        TierSettlement settlement = new TierSettlement(level.getLevelIndex(), held, charged, held.subtract(charged), after, true, null);
        closedTiers.put(tier, settlement);
        note("settle", tier, level, charged, "returned " + held.subtract(charged));
        return settlement;
    }

    @Override
    public synchronized void release(LevelAdmission level, String why) {
        asked.merge("release", 1, Integer::sum);
        String tier = level.getDebitReference();
        if (closedTiers.containsKey(tier)) return;
        if (settlementsFail) throw new LedgerFault("the ledger did not take the release (scripted) of " + tier);
        String key = keyOf(level.getDbName(), level.getPartnerId());
        BigDecimal held = heldByTier.remove(tier);
        if (held == null) held = BigDecimal.ZERO;
        BigDecimal after = balances.getOrDefault(key, BigDecimal.ZERO).add(held);
        balances.put(key, after);
        closedTiers.put(tier, new TierSettlement(level.getLevelIndex(), held, BigDecimal.ZERO, held, after, true, why));
        note("release", tier, level, held, why);
    }

    private void note(String verb, String reference, LevelAdmission level, BigDecimal amount, String text) {
        journal.add(new Entry(verb, reference, level.getDbName(), level.getPartnerId(), amount, text));
    }

    /** A window's reserve ({@code <tier>#W2}) belongs to its tier's reference. */
    private static String tierOf(String reference) {
        int window = reference.indexOf("#W");
        return window < 0 ? reference : reference.substring(0, window);
    }

    private static String keyOf(String tenant, int partnerId) { return tenant + "#" + partnerId; }

    private static Long accountOf(String key) { return (long) Math.abs(key.hashCode() % 100_000); }
}
