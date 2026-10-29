package com.telcobright.seed.prepaid.internal;

import com.telcobright.rtc.domainmodel.LevelAdmission;
import com.telcobright.seed.sessionflow.internal.PerRootLedger;
import com.telcobright.seed.sessionflow.spi.LedgerPort;
import com.telcobright.seed.switchledger.internal.MemLedgerPort;
import com.telcobright.seed.switchledger.internal.OwnedMemLedgerPort;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * §15 · The catalog read reaches the walk through EVERY port the production kit stacks: the kit's ledger of many roots
 * ({@link PerRootLedger}) over the root's owned port ({@link OwnedMemLedgerPort}) over the switch's ({@link MemLedgerPort}). A wrapper
 * that forwarded {@code accountsOf} but inherited {@code catalogOf}'s default (empty) would silently walk every tier by unit — this test
 * reads the declarations. Mutation seen red: the forwarding dropped from either wrapper.
 */
class CatalogReachesThroughEveryPortTest {

    @Test
    void everyPortThatForwardsAccountsOf_declaresCatalogOfItself() throws Exception {
        for (Class<? extends LedgerPort> port : List.of(PerRootLedger.class, OwnedMemLedgerPort.class, MemLedgerPort.class)) {
            Method accounts = port.getDeclaredMethod("accountsOf", LevelAdmission.class);
            assertThat(accounts).as(port.getSimpleName() + " forwards accountsOf").isNotNull();
            Method catalog = port.getDeclaredMethod("catalogOf", LevelAdmission.class);
            assertThat(catalog.getDeclaringClass()).as(port.getSimpleName() + " declares catalogOf itself, never the SPI's empty default").isEqualTo(port);
        }
    }
}
