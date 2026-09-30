package com.telcobright.seed.callflow.testkit;

import com.telcobright.rtc.domainmodel.LevelAdmission;
import com.telcobright.seed.callflow.api.LevelCharge;
import com.telcobright.seed.callflow.spi.AdBillingPort;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The ledger of the tests: one cash balance per partner, the road-16 rules in memory (idempotent by reference, refused
 * when the balance cannot cover it, a scripted system fault), every debit and credit recorded with its reference so a
 * test can prove the mutation it expects — and nothing else.
 */
public final class FakeBillingPort implements AdBillingPort {

    public record Debit(int partnerId, String tenant, BigDecimal amount, String reference) {}
    public record Credit(int partnerId, Long account, BigDecimal amount, String reference, String reason) {}

    private final Map<Integer, BigDecimal> balances = new ConcurrentHashMap<>();
    private final Map<String, LevelCharge> byReference = new ConcurrentHashMap<>();
    public final List<Debit> debits = new CopyOnWriteArrayList<>();
    public final List<Credit> credits = new CopyOnWriteArrayList<>();
    /** A partner whose every debit is a system fault (the ledger down for that call). */
    public volatile Integer faultingPartner;
    /** Every call a system fault. */
    public volatile boolean faulting;
    private long accounts = 1000;

    public FakeBillingPort balance(int partnerId, String bdt) { balances.put(partnerId, new BigDecimal(bdt)); return this; }

    public BigDecimal balanceOf(int partnerId) { return balances.getOrDefault(partnerId, BigDecimal.ZERO); }

    @Override
    public synchronized Optional<LevelCharge> debit(LevelAdmission level, BigDecimal amount, String reference) {
        int partner = level.getPartnerId();
        if (faulting || (faultingPartner != null && faultingPartner == partner)) throw new BillingSystemFault("ledger unreachable (scripted)");
        LevelCharge earlier = byReference.get(reference);
        if (earlier != null) return Optional.of(new LevelCharge(earlier.levelIndex(), earlier.tenant(), earlier.partnerId(), earlier.chargeAccount(), earlier.chargeUom(),
            earlier.chargeUnits(), earlier.chargeBdt(), earlier.balanceBefore(), earlier.balanceAfter(), reference, true));
        BigDecimal before = balanceOf(partner);
        debits.add(new Debit(partner, level.getDbName(), amount, reference));
        if (before.compareTo(amount) < 0) return Optional.empty();
        BigDecimal after = before.subtract(amount);
        balances.put(partner, after);
        LevelCharge c = new LevelCharge(level.getLevelIndex(), level.getDbName(), partner, ++accounts, "BDT", null, amount, before, after, reference, false);
        byReference.put(reference, c);
        return Optional.of(c);
    }

    @Override
    public synchronized Optional<BigDecimal> credit(LevelCharge charge, String reference, String reason) {
        if (faulting) throw new BillingSystemFault("ledger unreachable (scripted)");
        BigDecimal after = balanceOf(charge.partnerId()).add(charge.amount());
        balances.put(charge.partnerId(), after);
        credits.add(new Credit(charge.partnerId(), charge.chargeAccount(), charge.amount(), reference, reason));
        return Optional.of(after);
    }
}
