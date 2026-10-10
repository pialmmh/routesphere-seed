package com.telcobright.seed.switchledger;

import com.telcobright.core.cache.CacheableEntity;
import com.telcobright.seed.switchledger.dependencies.SwitchLedgerSettings;
import com.telcobright.seed.switchledger.dependencies.SwitchLedgers;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ARCH-0077-A item 6 — the module registers exactly two entities, PackageAccount and PackageAccountReserve, in the MemLedger it is handed:
 * any other registered entity is refused at the build, in words. packagepurchase is never written: a ledger that holds it is not this
 * module's to drive.
 */
class TwoEntitiesTest {

    /** A purchase row as someone might register it: the third entity the switch ledger must refuse. */
    @Entity
    @Table(name = "packagepurchase")
    public static class PurchaseRow implements CacheableEntity<Long, Object> {
        @Id @Column(name = "id_packagepurchase") private Long id;
        @Column(name = "status") private String status;
        @Override public Long getKey() { return id; }
        @Override public CacheableEntity<Long, Object> applyUpdate(Object delta) { return this; }
        @Override public Class<Object> getDeltaClass() { return Object.class; }
    }

    private final LedgerLab lab = LedgerLab.open().schema("res_2").account("res_2", 11, 1, "OTH_ea", "100");

    @AfterEach
    void closeTheLab() { lab.close(); }

    @Test
    void aThirdRegisteredEntity_isRefusedAtTheBuild_inWords() {
        lab.alsoRegister("PackagePurchase", PurchaseRow.class, "CREATE TABLE %s.packagepurchase (id_packagepurchase BIGINT NOT NULL PRIMARY KEY, status VARCHAR(45))").start();

        assertThatThrownBy(() -> SwitchLedgers.over(lab.ledger(), SwitchLedgerSettings.standard(), Clock.systemUTC()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("exactly two entities")
            .hasMessageContaining("PackagePurchase")
            .hasMessageContaining("packagepurchase is never written");
    }

    @Test
    void theTwoEntities_areAccepted() {
        lab.start();
        assertThat(SwitchLedgers.over(lab.ledger(), SwitchLedgerSettings.standard(), Clock.systemUTC()).port()).isNotNull();
    }

    @Test
    void aLedgerMissingOneOfTheTwo_isRefusedToo() {
        try (LedgerLab bare = LedgerLab.open().schema("res_2").account("res_2", 11, 1, "OTH_ea", "100")) {
            bare.start();
            assertThatThrownBy(() -> SwitchLedgers.over(new OnlyAccounts(bare), SwitchLedgerSettings.standard(), Clock.systemUTC()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("PackageAccountReserve");
        }
    }
}
