package com.telcobright.seed.switchledger.internal;

import com.telcobright.rtc.domainmodel.LevelAdmission;
import com.telcobright.rtc.domainmodel.mysqlentity.PackageAccount;
import com.telcobright.rtc.domainmodel.mysqlentity.Partner;
import com.telcobright.rtc.domainmodel.nonentity.Tenant;
import com.telcobright.seed.sessionflow.spi.LedgerPort;
import com.telcobright.seed.sessionflow.spi.LedgerPort.LedgerFault;
import com.telcobright.seed.sessionflow.spi.LedgerUnit;
import com.telcobright.seed.switchledger.LedgerLab;
import com.telcobright.seed.switchledger.dependencies.SwitchLedgerSettings;
import com.telcobright.seed.switchledger.dependencies.SwitchLedgers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * L2b item 2 · ONE unit of the switch ledger over THREE tiers (btcl → res_2 → res_3, one account each, 10 BDT), on the REAL MemLedger:
 * the hold of every tier = one record; a third tier that cannot pay, or whose lock is not free in time, leaves tiers 1–2 untouched (no
 * balance moved, no hold row, no record); two units over the same three accounts in OPPOSITE tier orders, from two threads, every round at
 * once, both finish — the accounts' locks are taken in one global order whatever order the caller names them in.
 */
class SwitchUnitTest {

    private static final List<String> TIERS = List.of("btcl", "res_2", "res_3");
    private static final long LOCK_TIMEOUT_MS = 400;

    private LedgerLab lab;
    private LedgerPort port;

    @BeforeEach
    void open() {
        lab = LedgerLab.open();
        for (String tier : TIERS) lab.schema(tier).account(tier, 1, 10, "BDT", "10");
        lab.start();
        port = SwitchLedgers.over(lab.ledger(), new SwitchLedgerSettings(60, 500, LOCK_TIMEOUT_MS), Clock.systemUTC()).port();
    }

    @AfterEach
    void close() { lab.close(); }

    @Test
    void theHoldOfThreeTiers_isOneRecord() {
        long before = lab.walRecords();
        try (LedgerUnit unit = port.unit()) {
            unit.lock(levels(TIERS));
            for (LevelAdmission level : levels(TIERS)) assertThat(unit.reserve(level, BigDecimal.ONE, "h1#L" + level.getLevelIndex(), Long.MAX_VALUE)).isPresent();
            unit.commit("hold three tiers");
        }
        assertThat(lab.walRecords()).isEqualTo(before + 1);
        assertThat(lab.entriesOfTheLastRecord()).isEqualTo(6);
        for (String tier : TIERS) assertThat(lab.cachedBalance(tier, 1)).isEqualByComparingTo("9");
    }

    @Test
    void aThirdTierThatCannotPay_leavesTiersOneAndTwoUntouched_noRow_noRecord() {
        long before = lab.walRecords();
        List<LevelAdmission> tiers = levels(TIERS);
        try (LedgerUnit unit = port.unit()) {
            unit.lock(tiers);
            assertThat(unit.reserve(tiers.get(0), new BigDecimal("6"), "h2#L0", Long.MAX_VALUE)).isPresent();
            assertThat(unit.reserve(tiers.get(1), new BigDecimal("6"), "h2#L1", Long.MAX_VALUE)).isPresent();
            assertThat(unit.reserve(tiers.get(2), new BigDecimal("60"), "h2#L2", Long.MAX_VALUE)).as("the third tier is short").isEmpty();
        }                                                                                   // the engine's rule: one short tier → no commit
        assertNothingAnywhere(before);
    }

    @Test
    void aLockNotFreeOnTheThirdTier_isAFaultInWords_tiersOneAndTwoUntouched_noRecord() throws Exception {
        long before = lab.walRecords();
        CountDownLatch taken = new CountDownLatch(1), letGo = new CountDownLatch(1);
        Thread holder = new Thread(() -> {
            try (LedgerUnit other = port.unit()) {
                other.lock(levels(List.of("res_3")));
                taken.countDown();
                letGo.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        holder.start();
        assertThat(taken.await(5, TimeUnit.SECONDS)).isTrue();

        try (LedgerUnit unit = port.unit()) {
            assertThatThrownBy(() -> unit.lock(levels(TIERS))).isInstanceOf(LedgerFault.class)
                .hasMessageContaining("the lock of account 1 in res_3 was not free within " + LOCK_TIMEOUT_MS + " ms");
        }
        letGo.countDown();
        holder.join(5_000);
        assertNothingAnywhere(before);
        try (LedgerUnit unit = port.unit()) {
            unit.lock(levels(TIERS));
            assertThat(unit.reserve(levels(TIERS).get(0), BigDecimal.ONE, "after", Long.MAX_VALUE)).as("the locks taken were all given back").isPresent();
        }
    }

    @Test
    void twoUnitsOverTheSameThreeAccounts_inOppositeTierOrders_fromTwoThreads_bothFinishEveryRound() throws Exception {
        int rounds = 150;
        CyclicBarrier together = new CyclicBarrier(2);
        List<String> failures = new CopyOnWriteArrayList<>();
        List<String> reversed = new ArrayList<>(TIERS);
        Collections.reverse(reversed);
        Thread a = new Thread(() -> holdRounds("A", TIERS, rounds, together, failures));
        Thread b = new Thread(() -> holdRounds("B", reversed, rounds, together, failures));
        a.start();
        b.start();
        a.join(60_000);
        b.join(60_000);

        assertThat(failures).as("every round of both threads held its three tiers").isEmpty();
        for (String tier : TIERS) assertThat(lab.cachedBalance(tier, 1)).isEqualByComparingTo(new BigDecimal("10").subtract(new BigDecimal("0.01").multiply(BigDecimal.valueOf(2L * rounds))));
    }

    private void holdRounds(String name, List<String> order, int rounds, CyclicBarrier together, List<String> failures) {
        for (int round = 0; round < rounds; round++) {
            try {
                together.await(10, TimeUnit.SECONDS);
                List<LevelAdmission> tiers = levels(order);
                try (LedgerUnit unit = port.unit()) {
                    unit.lock(tiers);
                    for (LevelAdmission level : tiers) unit.reserve(level, new BigDecimal("0.01"), name + round + "#" + level.getDbName(), Long.MAX_VALUE);
                    unit.commit(name + " round " + round);
                }
            } catch (Exception e) {
                failures.add(name + " round " + round + ": " + e.getMessage());
                together.reset();
                return;
            }
        }
    }

    private void assertNothingAnywhere(long recordsBefore) {
        assertThat(lab.walRecords()).as("no record").isEqualTo(recordsBefore);
        for (String tier : TIERS) {
            assertThat(lab.cachedBalance(tier, 1)).as(tier + " untouched").isEqualByComparingTo("10");
            assertThat(lab.cachedReserveRows(tier)).as(tier + ": no hold row").isZero();
        }
    }

    /** One level per tier, in the order given, each naming its tier's account 1. */
    private static List<LevelAdmission> levels(List<String> tiers) {
        List<LevelAdmission> levels = new ArrayList<>();
        for (String tier : tiers) {
            Partner partner = new Partner();
            partner.setIdPartner(7);
            PackageAccount account = new PackageAccount();
            account.setId(1L);
            levels.add(new LevelAdmission(TIERS.indexOf(tier), new Tenant(tier), partner, account));
        }
        return levels;
    }
}
