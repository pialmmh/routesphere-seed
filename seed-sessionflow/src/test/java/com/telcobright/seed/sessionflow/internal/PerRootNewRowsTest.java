package com.telcobright.seed.sessionflow.internal;

import com.telcobright.rtc.domainmodel.LevelAdmission;
import com.telcobright.rtc.domainmodel.mysqlentity.Partner;
import com.telcobright.rtc.domainmodel.nonentity.Tenant;
import com.telcobright.seed.sessionflow.api.TierSettlement;
import com.telcobright.seed.sessionflow.spi.LedgerPort;
import com.telcobright.seed.sessionflow.testkit.InMemoryLedger;
import com.telcobright.seed.sessionflow.testkit.TenantTreeBuilder;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * I266 · the kit's ledger of many roots passes a lane's named account to the tier's OWN root's ledger ({@code mergeIfUnknown}) — a
 * wrapper that kept the SPI's default would answer "nothing new" for every root, and the walk would never see a purchase written after
 * its partner's first admission. A root whose ledger merges nothing (the testkit's) answers nothing new.
 */
class PerRootNewRowsTest {

    @Test
    void aNamedAccountIsAskedOfItsOwnRootsLedger_aLedgerThatMergesNothingHasNothingNew() {
        List<String> asked = new CopyOnWriteArrayList<>();
        PerRootLedger ledgers = new PerRootLedger(PerRootLedger::rootByTheChain);
        ledgers.add("btcl", merging("btcl", asked, new LedgerPort.NewRows(true, true)));
        ledgers.add("ccl", merging("ccl", asked, LedgerPort.NewRows.KNOWN));
        ledgers.add("plain", new InMemoryLedger());

        assertThat(ledgers.mergeIfUnknown(tierOf("btcl"), 56)).as("merged and found at btcl").isEqualTo(new LedgerPort.NewRows(true, true));
        assertThat(ledgers.mergeIfUnknown(tierOf("ccl"), 57)).isEqualTo(LedgerPort.NewRows.KNOWN);
        assertThat(ledgers.mergeIfUnknown(tierOf("plain"), 58)).as("the SPI's default").isEqualTo(LedgerPort.NewRows.NOTHING_NEW);
        assertThat(asked).as("each root's own ledger, once").containsExactly("btcl#56", "ccl#57");
    }

    /** A root's ledger that answers {@code answer} for every named account, saying which root was asked. */
    private static LedgerPort merging(String root, List<String> asked, LedgerPort.NewRows answer) {
        return new LedgerPort() {
            @Override public Optional<Reservation> reserve(LevelAdmission level, BigDecimal amount, String reference) { return Optional.empty(); }
            @Override public TierSettlement settle(LevelAdmission level, BigDecimal charged) { return null; }
            @Override public void release(LevelAdmission level, String why) { }
            @Override public NewRows mergeIfUnknown(LevelAdmission tier, long accountId) {
                asked.add(root + "#" + accountId);
                return answer;
            }
        };
    }

    /** A tier of the root {@code root} (the root's own tier), its partner 1. */
    private static LevelAdmission tierOf(String root) {
        Tenant tenant = new TenantTreeBuilder().root(root).partner(1, "client", com.telcobright.rtc.domainmodel.PartnerType.CUSTOMER).and().build();
        Partner partner = new Partner();
        partner.setIdPartner(1);
        partner.setPartnerName("client");
        return new LevelAdmission(0, tenant, partner, null);
    }
}
