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
 * the same way: the id the tenant's database name ends with, else the partner named as the tenant; no partner type is asked. P1-O (the
 * architect's A2/A7): the name is EXACT and asked only when the id names nobody — one get, never a walk of the parent's partners.
 */
class ResellerPartnerRuleTest {

    private final Tenant root = new TenantTreeBuilder()
        .root("btcl")
            .partner(233, "Gamma Distribution", PartnerType.CUSTOMER)     // stands for res_233: by the id its name ends with
            .partner(12, "res_acme", PartnerType.CUSTOMER)                // stands for res_acme: by its exact name
            .partner(13, "RES_BETA", PartnerType.CUSTOMER)                // P1-O: another case is another name — stands for nothing
            .partner(4, "res_4", PartnerType.RESELLER)                    // must never stand for res_44
            .partner(9, "res_233", PartnerType.RESELLER)                  // a name that points at res_233 while id 233 exists
            .and()
        .tenant("res_233", "btcl")
            .partner(2, "Delta Sub", PartnerType.CUSTOMER)                // stands for res_233_2
            .partner(233, "Not The Reseller", PartnerType.CUSTOMER)       // the same NUMBER one tier down: not the root's 233
            .and()
        .tenant("res_233_2", "res_233").partner(9001, "Leaf", PartnerType.CUSTOMER).and()
        .tenant("res_acme", "btcl").partner(801, "Shop", PartnerType.CUSTOMER).and()
        .tenant("res_beta", "btcl").partner(803, "Stall", PartnerType.CUSTOMER).and()
        .tenant("res_44", "btcl").partner(802, "Other", PartnerType.CUSTOMER)
            .partner(804, "res_acme", PartnerType.CUSTOMER)               // review fix: the SAME name as the root's 12, one tier down
            .and()
        .build();

    private Partner standsFor(String child, String parent) {
        return RoutesphereFlow.resellerPartnerOf(root.findTenantByDbName(child), root.findTenantByDbName(parent));
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
    void aTenantNamedByHand_isStoodForByThePartnerOfExactlyItsName() {
        assertThat(standsFor("res_acme", "btcl").getIdPartner()).isEqualTo(12);
        assertThat(standsFor("res_beta", "btcl")).as("RES_BETA is not res_beta: the name is exact (no case-blind loop)").isNull();
    }

    @Test
    void whenTheNameAndTheIdDisagree_theIdWins() {
        assertThat(standsFor("res_233", "btcl").getIdPartner()).as("partner 9 is only NAMED res_233").isEqualTo(233);
    }

    @Test
    void aPartnerWhoseNameTheDatabaseNameMerelyStartsWith_standsForNothing() {
        assertThat(standsFor("res_44", "btcl")).as("res_4 is not res_44, and the root has no partner 44").isNull();
    }

    @Test
    void throughTheIndex_aNameAnotherTierAlsoHolds_stillNamesTheParentsOwnPartner() {
        RootIndex index = new RootTreeInstaller().install("btcl", 1, root).index();
        assertThat(index.partnerNamed("res_acme")).as("tree-wide the name is held by two: it names nobody").isNull();
        Partner above = RoutesphereFlow.partnerStandingFor(root.findTenantByDbName("res_acme"), root, index);
        assertThat(above).as("the reseller fallback is scoped to the PARENT tier, as before P1-O").isNotNull();
        assertThat(above.getIdPartner()).isEqualTo(12);
    }
}
