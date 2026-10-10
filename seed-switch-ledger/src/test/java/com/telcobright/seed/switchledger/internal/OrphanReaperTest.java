package com.telcobright.seed.switchledger.internal;

import com.telcobright.seed.sessionflow.testkit.ManualClock;
import com.telcobright.seed.switchledger.LedgerLab;
import com.telcobright.seed.switchledger.dependencies.SwitchLedgerSettings;
import com.telcobright.seed.switchledger.dependencies.SwitchLedgers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ARCH-0077-A item 4 — the reaper of the reserve rows a dead session left behind ({@code PrepaidServiceWithCompensation.reapOrphanReserves},
 * 533–576): a row older than {@code reaperMaxAgeMinutes} is given back to its account and dies; a younger one is a live session's and is
 * left; at most {@code reaperBatchLimit} per pass; a row whose time cannot be read is never touched ("don't release something we can't date").
 */
class OrphanReaperTest {

    private static final Instant T = Instant.parse("2026-10-10T12:00:00Z");
    private final ManualClock clock = new ManualClock(T, ZoneOffset.UTC);
    private final SwitchLedgerSettings settings = new SwitchLedgerSettings(60, 2, 5000);

    private String at(long minutesAgo) { return LocalDateTime.ofInstant(T, ZoneOffset.UTC).minusMinutes(minutesAgo).toString(); }

    private final LedgerLab lab = LedgerLab.open()
        .schema("res_2")
        .account("res_2", 11, 1, "OTH_ea", "70")
        .reserveRow("res_2", "old-1#L0", 11, 1, "OTH_ea", "20", at(120))
        .reserveRow("res_2", "young#L0", 11, 1, "OTH_ea", "10", at(1))
        .reserveRow("res_2", "undated#L0", 11, 1, "OTH_ea", "5", "when?");

    @AfterEach
    void closeTheLab() { lab.close(); }

    @Test
    void anOldRowIsGivenBack_itsAccountCredited_aYoungRowUntouched_anUndatedRowNever() {
        lab.start();
        OrphanReaper reaper = SwitchLedgers.reaper(lab.ledger(), settings, clock);

        assertThat(reaper.reap("res_2")).isEqualTo(1);

        assertThat(lab.cachedBalance("res_2", 11)).isEqualByComparingTo("90");
        assertThat(lab.cachedRow("res_2", "old-1#L0")).isNull();
        assertThat(lab.cachedRow("res_2", "young#L0").getReserveUnit()).isEqualByComparingTo("10");
        assertThat(lab.cachedRow("res_2", "undated#L0")).isNotNull();
        assertThat(reaper.reap("res_2")).as("a second pass finds nothing").isZero();
        LedgerLab.await("the write-behind", () -> lab.dbReserveRows("res_2") == 2 && lab.dbBalance("res_2", 11).compareTo(new BigDecimal("90")) == 0);
    }

    @Test
    void theYoungRowBecomesAnOrphanWhenItAges_pastTheMaxAge() {
        lab.start();
        OrphanReaper reaper = SwitchLedgers.reaper(lab.ledger(), settings, clock);
        reaper.reap("res_2");
        clock.advance(61 * 60_000L);

        assertThat(reaper.reap("res_2")).isEqualTo(1);
        assertThat(lab.cachedRow("res_2", "young#L0")).isNull();
        assertThat(lab.cachedBalance("res_2", 11)).isEqualByComparingTo("100");
    }

    @Test
    void theBatchLimitBoundsOnePass_theNextPassTakesTheRest() {
        lab.reserveRow("res_2", "old-2#L0", 11, 1, "OTH_ea", "1", at(200)).reserveRow("res_2", "old-3#L0", 11, 1, "OTH_ea", "1", at(300)).start();
        OrphanReaper reaper = SwitchLedgers.reaper(lab.ledger(), settings, clock);

        assertThat(reaper.reap("res_2")).as("the limit is 2").isEqualTo(2);
        assertThat(reaper.reap("res_2")).isEqualTo(1);
        assertThat(reaper.reap("res_2")).isZero();
        assertThat(lab.cachedBalance("res_2", 11)).isEqualByComparingTo("92");
    }
}
