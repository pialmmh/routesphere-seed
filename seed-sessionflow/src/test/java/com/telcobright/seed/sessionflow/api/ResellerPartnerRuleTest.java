package com.telcobright.seed.sessionflow.api;

import com.telcobright.rtc.domainmodel.PartnerType;
import com.telcobright.rtc.domainmodel.mysqlentity.Partner;
import com.telcobright.rtc.domainmodel.nonentity.Tenant;
import com.telcobright.seed.sessionflow.testkit.TenantTreeBuilder;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The partner that stands, at a tier, for the tenant below it — the call switch's live rule
 * ({@code CallAdmissionController.identifyPartnerAtParentLevel}), which the base follows so that a call and an ad view climb one tree
 * the same way: the id the tenant's database name ends with, else the partner named as the tenant; no partner type is asked.
 */
class ResellerPartnerRuleTest {

    private final Tenant root = new TenantTreeBuilder()
        .root("btcl")
            .partner(233, "Gamma Distribution", PartnerType.CUSTOMER)     // stands for res_233: by the id its name ends with
            .partner(12, "RES_ACME", PartnerType.CUSTOMER)                // stands for res_acme: by its name, whatever the case
            .partner(4, "res_4", PartnerType.RESELLER)                    // must never stand for res_44
            .partner(9, "res_233", PartnerType.RESELLER)                  // a name that points at res_233 while id 233 exists
            .and()
        .tenant("res_233", "btcl")
            .partner(2, "Delta Sub", PartnerType.CUSTOMER)                // stands for res_233_2
            .partner(233, "Not The Reseller", PartnerType.CUSTOMER)       // the same NUMBER one tier down: not the root's 233
            .and()
        .tenant("res_233_2", "res_233").partner(9001, "Leaf", PartnerType.CUSTOMER).and()
        .tenant("res_acme", "btcl").partner(801, "Shop", PartnerType.CUSTOMER).and()
        .tenant("res_44", "btcl").partner(802, "Other", PartnerType.CUSTOMER).and()
        .build();

    private Partner standsFor(String child, String parent) {
        return SessionFlowSteps.resellerPartnerOf(root.findTenantByDbName(child), root.findTenantByDbName(parent));
    }

    @Test
    void theIdTheDatabaseNameEndsWith_namesTheReseller_whateverItsTypeOrName() {
        root.getContext().getPartners().get(233).setPartnerType(4);       // the call switch's code for a reseller; the ad's is 100
        assertThat(standsFor("res_233", "btcl").getIdPartner()).isEqualTo(233);
        root.getContext().getPartners().get(233).setPartnerType(null);
        assertThat(standsFor("res_233", "btcl").getIdPartner()).as("no partner type is asked").isEqualTo(233);
    }

    @Test
    void aNestedTenant_isStoodForByTheLastNumberOfItsName_inItsOwnParent() {
        Partner above = standsFor("res_233_2", "res_233");
        assertThat(above.getIdPartner()).isEqualTo(2);
        assertThat(above.getPartnerName()).isEqualTo("Delta Sub");
    }

    @Test
    void aTenantNamedByHand_isStoodForByThePartnerOfItsName_withoutCase() {
        assertThat(standsFor("res_acme", "btcl").getIdPartner()).isEqualTo(12);
    }

    @Test
    void whenTheNameAndTheIdDisagree_theIdWins() {
        assertThat(standsFor("res_233", "btcl").getIdPartner()).as("partner 9 is only NAMED res_233").isEqualTo(233);
    }

    @Test
    void aPartnerWhoseNameTheDatabaseNameMerelyStartsWith_standsForNothing() {
        assertThat(standsFor("res_44", "btcl")).as("res_4 is not res_44, and the root has no partner 44").isNull();
    }
}
