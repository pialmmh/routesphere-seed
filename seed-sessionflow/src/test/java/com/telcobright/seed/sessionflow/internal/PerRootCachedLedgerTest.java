package com.telcobright.seed.sessionflow.internal;

import com.telcobright.rtc.domainmodel.LevelAdmission;
import com.telcobright.seed.sessionflow.api.CachedLedger;
import com.telcobright.seed.sessionflow.api.TierSettlement;
import com.telcobright.seed.sessionflow.spi.LedgerPort;
import com.telcobright.seed.sessionflow.testkit.InMemoryLedger;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * C1 · the kit's ledger of many roots hands out each root's OWN cached ledger (design §14.4: {@code kit.ledger().cachedLedgerOf(root)}) —
 * never another root's; a root it does not serve, or a root whose ledger caches nothing (orchestrix's roads, the testkit's), is empty.
 */
class PerRootCachedLedgerTest {

    @Test
    void eachRootAnswersItsOwnCachedLedger_noneElsewhere() {
        CachedLedger btcls = new NamedLedger("btcl"), ccls = new NamedLedger("ccl");
        PerRootLedger ledgers = new PerRootLedger(PerRootLedger::rootByTheChain);
        ledgers.add("btcl", caching(btcls));
        ledgers.add("ccl", caching(ccls));
        ledgers.add("plain", new InMemoryLedger());

        assertThat(ledgers.cachedLedgerOf("btcl")).containsSame(btcls);
        assertThat(ledgers.cachedLedgerOf("ccl")).containsSame(ccls);
        assertThat(ledgers.cachedLedgerOf("plain")).as("a ledger that caches nothing").isEmpty();
        assertThat(ledgers.cachedLedgerOf("nowhere")).isEmpty();
        assertThat(ledgers.cachedLedgerOf(null)).isEmpty();
    }

    /** A root's ledger that caches tables: its cached ledger, whatever root it is asked for (it is one root's). */
    private static LedgerPort caching(CachedLedger own) {
        return new LedgerPort() {
            @Override public Optional<Reservation> reserve(LevelAdmission level, BigDecimal amount, String reference) { return Optional.empty(); }
            @Override public TierSettlement settle(LevelAdmission level, BigDecimal charged) { return null; }
            @Override public void release(LevelAdmission level, String why) { }
            @Override public Optional<CachedLedger> cachedLedgerOf(String root) { return Optional.of(own); }
        };
    }

    /** A cached ledger that only has a name: which one answers is what is tested. */
    private record NamedLedger(String name) implements CachedLedger {
        @Override public <T> Optional<T> get(String tier, Class<T> type, Object id) { return Optional.empty(); }
        @Override public <T> List<T> find(String tier, Class<T> type, Map<String, ?> equals) { return List.of(); }
        @Override public CachedLedger withIds(LedgerPort.Ids ids) { return this; }
        @Override public Unit unit() { return null; }
        @Override public Unit unit(String reference) { return null; }
    }
}
