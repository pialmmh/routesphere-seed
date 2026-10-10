package com.telcobright.seed.sessionflow;

import com.telcobright.rtc.domainmodel.nonentity.Tenant;
import com.telcobright.seed.sessionflow.spi.TenantLookup;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ARCH-0077-E (3b) / prime-context F4: a rule names a tenant by CODE; the lookup turns the code into the served root, whose database
 * name may differ (a store row names the schema). A schema name is never a code; two roots with one code are refused in words.
 */
class TenantLookupCodeTest {

    private static Tenant root(String dbName, String code) {
        Tenant t = new Tenant(dbName);
        t.setName(code);
        t.rebuildTenantIndex();
        t.computeAncestorChains();
        return t;
    }

    @Test
    void aCode_findsItsRoot_evenWhenTheSchemaNameDiffers() {
        TenantLookup lookup = TenantLookup.of(root("btcl", "btcl"), root("acme_sw", "acme"));
        assertThat(lookup.rootOfCode("acme")).map(Tenant::getDbName).contains("acme_sw");
        assertThat(lookup.rootOfCode("btcl")).map(Tenant::getDbName).contains("btcl");
    }

    @Test
    void aSchemaName_isNotACode_andAnUnknownCode_isEmpty() {
        TenantLookup lookup = TenantLookup.of(root("btcl", "btcl"), root("acme_sw", "acme"));
        assertThat(lookup.rootOfCode("acme_sw")).isEmpty();
        assertThat(lookup.rootOfCode("nobody")).isEmpty();
        assertThat(lookup.rootOfCode(null)).isEmpty();
    }

    @Test
    void aRootWithoutACode_neverMatches() {
        Tenant unnamed = new Tenant("legacy");
        unnamed.rebuildTenantIndex();
        unnamed.computeAncestorChains();
        assertThat(TenantLookup.of(unnamed).rootOfCode("legacy")).isEmpty();
    }

    @Test
    void twoRootsWithOneCode_areRefusedInWords() {
        assertThatThrownBy(() -> TenantLookup.of(root("acme_sw", "acme"), root("acme_old", "acme")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("two served trees carry the tenant code 'acme'");
    }
}
