package com.telcobright.seed.sessionflow.internal;

import com.telcobright.rtc.domainmodel.LevelAdmission;
import com.telcobright.rtc.domainmodel.PartnerType;
import com.telcobright.rtc.domainmodel.nonentity.Tenant;
import com.telcobright.seed.sessionflow.api.TierSettlement;
import com.telcobright.seed.sessionflow.spi.LedgerPort;
import com.telcobright.seed.sessionflow.testkit.TenantTreeBuilder;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The architect's review of P1-G (2026-10-24) · a tier whose live load the ledger SAID failed (the switch ledger logs, never throws) was
 * marked handed by the gate and never handed again: its money refused until a restart. Now the ledger answers {@code tierLoaded} false and
 * the gate hands the tier again on the next tree; a tier that loaded is still handed once.
 */
class TierLoadRetriedTest {

    /** Books per tier that SAY a failed load, as the switch ledger does: the first {@code failures} hands fail quietly. */
    static final class SayingBooks implements LedgerPort {
        final Set<String> loaded = ConcurrentHashMap.newKeySet();
        final List<String> hands = new CopyOnWriteArrayList<>();
        final AtomicInteger failures;

        SayingBooks(int failures) { this.failures = new AtomicInteger(failures); loaded.add("btcl"); }

        @Override public void tierAdded(String tierDb) {
            hands.add(tierDb);
            if (failures.getAndUpdate(n -> Math.max(0, n - 1)) > 0) return;             // said in a log line, not thrown
            loaded.add(tierDb);
        }
        @Override public boolean tierLoaded(String tierDb) { return loaded.contains(tierDb); }
        @Override public Optional<Reservation> reserve(LevelAdmission level, BigDecimal amount, String reference) { throw new UnsupportedOperationException("no money here"); }
        @Override public TierSettlement settle(LevelAdmission level, BigDecimal charged) { throw new UnsupportedOperationException("no money here"); }
        @Override public void release(LevelAdmission level, String why) { throw new UnsupportedOperationException("no money here"); }
    }

    static Tenant btclAlone() {
        TenantTreeBuilder b = new TenantTreeBuilder();
        b.root("btcl").partner(1, "btcl-client", PartnerType.CUSTOMER);
        return b.build();
    }

    static Tenant btclWithTheNewTier() {
        TenantTreeBuilder b = new TenantTreeBuilder();
        b.root("btcl").partner(11, "btcl-res-11", PartnerType.RESELLER);
        b.tenant("btcl_res_11", "btcl").partner(601, "btcl-res-11-client", PartnerType.CUSTOMER);
        return b.build();
    }

    private static TiersFirst gateOver(SayingBooks books) {
        PerRootLedger ledgers = new PerRootLedger(PerRootLedger::rootByTheChain);
        ledgers.add("btcl", books);
        TiersFirst gate = new TiersFirst(ledgers);
        gate.builtOver("btcl", btclAlone());
        return gate;
    }

    @Test
    void aTierWhoseLoadWasSaidToFail_isHandedAgainOnTheNextTree() {
        SayingBooks books = new SayingBooks(1);
        TiersFirst gate = gateOver(books);

        gate.pass("btcl", btclWithTheNewTier());                                       // the doorbell's ring: the load fails, said
        assertThat(books.loaded).as("the first load failed").doesNotContain("btcl_res_11");

        gate.pass("btcl", btclWithTheNewTier());                                       // the event, or the next ring
        assertThat(books.loaded).as("handed again, loaded now — no restart needed").contains("btcl_res_11");
        assertThat(books.hands).containsExactly("btcl_res_11", "btcl_res_11");
    }

    @Test
    void aTierThatLoaded_isHandedOnce() {
        SayingBooks books = new SayingBooks(0);
        TiersFirst gate = gateOver(books);

        gate.pass("btcl", btclWithTheNewTier());
        gate.hand("btcl", "btcl_res_11");                                              // the event after the ring: one hand
        gate.pass("btcl", btclWithTheNewTier());

        assertThat(books.hands).containsExactly("btcl_res_11");
        assertThat(books.loaded).contains("btcl_res_11");
    }

    @Test
    void aRootWithoutALedgerHere_countsAsLoaded() {
        PerRootLedger ledgers = new PerRootLedger(PerRootLedger::rootByTheChain);
        assertThat(ledgers.tierLoaded("link3", "link3_res_5")).isTrue();
    }
}
