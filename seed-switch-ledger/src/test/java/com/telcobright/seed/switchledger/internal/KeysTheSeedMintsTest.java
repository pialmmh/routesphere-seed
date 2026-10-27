package com.telcobright.seed.switchledger.internal;

import com.telcobright.rtc.domainmodel.mysqlentity.PackageAccount;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L2c item 4 · Which money entities a ledger DB names as minted by the caller: exactly those whose key the database would generate. Of the
 * four the ledger knows, that is rtc-domain's {@code PackageAccount} ({@code @GeneratedValue(IDENTITY)}); the purchase's row
 * ({@code LedgerPurchase}) and the reference row ({@code ChargeReference}) carry keys the seed always wrote, the reserve its call's uuid.
 */
class KeysTheSeedMintsTest {

    @Test
    void ofTheFourKnownEntities_onlyTheAccountsKeyIsOneTheDatabaseWouldGenerate() {
        assertThat(LedgerEntities.keysTheDatabaseWouldGenerate(LedgerEntities.known())).containsExactly(PackageAccount.class);
    }
}
