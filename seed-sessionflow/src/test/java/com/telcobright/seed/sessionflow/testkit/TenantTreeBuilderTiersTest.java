package com.telcobright.seed.sessionflow.testkit;

import com.telcobright.rtc.domainmodel.PartnerType;
import com.telcobright.rtc.domainmodel.nonentity.Tenant;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The testkit's tree builder for the P1 scenarios (§H): reseller tiers of any depth, named by the house rule, and two roots with
 * overlapping partner ids as two trees.
 */
class TenantTreeBuilderTiersTest {

    @Test
    void a_reseller_declares_its_partner_above_and_its_own_tier_below_named_by_the_house_rule_three_tiers_deep() {
        TenantTreeBuilder b = new TenantTreeBuilder();
        b.root("btcl").partner(9, "DirectCo", PartnerType.CUSTOMER)
            .reseller(44, "Res44").partner(701, "Unilever", PartnerType.CUSTOMER)
                .reseller(7, "Sub7").partner(801, "Walton", PartnerType.CUSTOMER);
        Tenant root = b.build();

        Tenant res44 = root.findTenantByDbName("res_44");
        Tenant res447 = root.findTenantByDbName("res_44_7");
        assertThat(res44).isNotNull();
        assertThat(res447).isNotNull();
        assertThat(res447.getParent()).isEqualTo("res_44");
        assertThat(root.getContext().getPartners().get(44).getPartnerType()).isEqualTo(PartnerType.RESELLER.code());
        assertThat(res44.getContext().getPartners().get(7).getPartnerType()).isEqualTo(PartnerType.RESELLER.code());
        assertThat(res447.getContext().getPartners()).containsOnlyKeys(801);
        List<String> chain = res447.getAncestorChain().stream().map(Tenant::getDbName).toList();
        assertThat(chain).containsExactlyInAnyOrder("res_44_7", "res_44", "btcl");
        assertThat(b.spec("res_44_7").up().dbName()).isEqualTo("res_44");
        assertThat(b.spec("res_44_7").up().up().dbName()).isEqualTo("btcl");
        assertThatThrownBy(() -> b.spec("btcl").up()).hasMessageContaining("btcl is a root");
    }

    @Test
    void two_roots_with_the_same_partner_ids_are_two_trees_and_a_name_is_declared_once_per_tree() {
        Tenant btcl = new TenantTreeBuilder().root("btcl").partner(5, "BTCL Network", PartnerType.CUSTOMER)
            .reseller(44, "Res44").partner(701, "Unilever", PartnerType.CUSTOMER).and().build();
        Tenant ccl = new TenantTreeBuilder().root("ccl").partner(5, "CCL Network", PartnerType.CUSTOMER)
            .reseller(44, "Res44 of ccl").partner(701, "Someone else", PartnerType.CUSTOMER).and().build();

        assertThat(btcl.findTenantByDbName("res_44").getContext().getPartners().get(701).getPartnerName()).isEqualTo("Unilever");
        assertThat(ccl.findTenantByDbName("res_44").getContext().getPartners().get(701).getPartnerName()).isEqualTo("Someone else");
        assertThat(btcl.findTenantByDbName("res_44")).isNotSameAs(ccl.findTenantByDbName("res_44"));
        assertThatThrownBy(() -> new TenantTreeBuilder().root("btcl").reseller(44, "a").and().tenant("res_44", "btcl"))
            .hasMessageContaining("res_44 is declared twice");
    }
}
