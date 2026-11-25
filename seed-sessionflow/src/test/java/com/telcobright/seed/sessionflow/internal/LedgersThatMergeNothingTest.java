package com.telcobright.seed.sessionflow.internal;

import com.telcobright.rtc.domainmodel.LevelAdmission;
import com.telcobright.seed.sessionflow.api.TierSettlement;
import com.telcobright.seed.sessionflow.spi.LedgerPort;
import com.telcobright.seed.sessionflow.testkit.InMemoryLedger;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * I266 (the WiFi architect's suggestion, 2026-11-24) · a LedgerPort that KEEPS the SPI's default {@code mergeIfUnknown} says so ONCE per
 * class at WARN — I266 is off on it, and a forgotten wrapper shows in the first journey's log; the ledgers that decided to merge nothing
 * (the testkit's, orchestrix's roads) and the kit's ledger of many roots over them stay quiet.
 */
class LedgersThatMergeNothingTest {

    @Test
    void aLedgerThatKeepsTheDefault_saysSoOnce_whateverHowOftenItIsAsked() {
        LedgerPort forgetful = new ForgetfulWrapper();

        for (long id = 1; id <= 3; id++) assertThat(forgetful.mergeIfUnknown(null, id)).as("the default answer").isEqualTo(LedgerPort.NewRows.NOTHING_NEW);

        assertThat(LedgersThatMergeNothing.linesFor(ForgetfulWrapper.class)).as("ONE line for its class, three asks").isEqualTo(1);
    }

    @Test
    void theLedgersThatDecidedToMergeNothing_stayQuiet() {
        InMemoryLedger testkits = new InMemoryLedger();
        PerRootLedger roots = new PerRootLedger(PerRootLedger::rootByTheChain);
        roots.add("plain", testkits);

        assertThat(testkits.mergeIfUnknown(null, 7)).isEqualTo(LedgerPort.NewRows.NOTHING_NEW);
        assertThat(roots.mergeIfUnknown(tierOf("plain"), 8)).as("asked through the ledger of many roots").isEqualTo(LedgerPort.NewRows.NOTHING_NEW);

        assertThat(LedgersThatMergeNothing.linesFor(InMemoryLedger.class)).as("the testkit's: decided, quiet").isZero();
        assertThat(LedgersThatMergeNothing.linesFor(PerRootLedger.class)).as("the ledger of many roots passes it on").isZero();
        assertThat(overrides(OrchestrixLedger.class)).as("orchestrix's ledger overrides the road (decided)").isTrue();
        assertThat(overrides(InMemoryLedger.class)).isTrue();
        assertThat(overrides(PerRootLedger.class)).isTrue();
    }

    /** A tier of the root {@code root} (its own tier), its partner 1. */
    private static LevelAdmission tierOf(String root) {
        com.telcobright.rtc.domainmodel.nonentity.Tenant tenant = new com.telcobright.seed.sessionflow.testkit.TenantTreeBuilder().root(root)
            .partner(1, "client", com.telcobright.rtc.domainmodel.PartnerType.CUSTOMER).and().build();
        com.telcobright.rtc.domainmodel.mysqlentity.Partner partner = new com.telcobright.rtc.domainmodel.mysqlentity.Partner();
        partner.setIdPartner(1);
        partner.setPartnerName("client");
        return new LevelAdmission(0, tenant, partner, null);
    }

    /** Does {@code type} declare its own {@code mergeIfUnknown} (not the SPI's default)? */
    private static boolean overrides(Class<?> type) {
        try {
            return type.getDeclaredMethod("mergeIfUnknown", LevelAdmission.class, long.class).getDeclaringClass() == type;
        } catch (NoSuchMethodException none) {
            return false;
        }
    }

    /** A wrapper that forgot the road: it keeps the SPI's default. */
    private static final class ForgetfulWrapper implements LedgerPort {
        @Override public Optional<Reservation> reserve(LevelAdmission level, BigDecimal amount, String reference) { return Optional.empty(); }
        @Override public TierSettlement settle(LevelAdmission level, BigDecimal charged) { return null; }
        @Override public void release(LevelAdmission level, String why) { }
    }
}
