package com.telcobright.seed.sessionflow.api;

import com.telcobright.rtc.domainmodel.PartnerType;
import com.telcobright.rtc.domainmodel.nonentity.Tenant;
import com.telcobright.seed.sessionflow.base.LabContext;
import com.telcobright.seed.sessionflow.base.LabFlow;
import com.telcobright.seed.sessionflow.dependencies.RoutesphereKit;
import com.telcobright.seed.sessionflow.spi.TenantLookup;
import com.telcobright.seed.sessionflow.testkit.InMemoryLedger;
import com.telcobright.seed.sessionflow.testkit.TenantTreeBuilder;
import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P1-E item 2 · (c) {@code identifyPartner} — who pays at the leaf, every lane's own answer (R2), written with the base's helpers that find
 * a partner INSIDE the session's own tree: {@code byPartnerId} (the ad's advertiser, the lab's dial) and {@code byPartnerName}. A partner
 * of another root, a name two partners share, no id — nobody (refused PARTNER_NOT_FOUND by the base).
 */
class IdentifyPartnerTest {

    private static Tenant btcl() {
        TenantTreeBuilder b = new TenantTreeBuilder();
        b.root("btcl").partner(1, "btcl-client", PartnerType.CUSTOMER).partner(44, "res_44", PartnerType.RESELLER);
        b.tenant("res_44", "btcl").partner(5, "Unilever", PartnerType.CUSTOMER).partner(6, "Twin", PartnerType.CUSTOMER).partner(7, "res_44_7", PartnerType.RESELLER);
        b.tenant("res_44_7", "res_44").partner(3, "twin", PartnerType.CUSTOMER);
        return b.build();
    }

    private static Tenant tele2() {
        TenantTreeBuilder b = new TenantTreeBuilder();
        b.root("tele2").partner(9, "tele2-client", PartnerType.CUSTOMER);
        return b.build();
    }

    private final Tenant btcl = btcl();
    private final LabFlow flow = new LabFlow(RoutesphereKit.builder().tenants(TenantLookup.of(btcl, tele2())).ledger(new InMemoryLedger())
        .cdr((id, tiers) -> { }).zone(ZoneId.of("Asia/Dhaka")).build(), Map.of(), List.of(), Set.of());

    private LabContext onBtcl() {
        LabContext ctx = new LabContext();
        ctx.tree = RootTree.of("btcl", 0, btcl);
        return ctx;
    }

    @Test
    void byPartnerId_findsTheLeafInTheSessionsOwnTree() {
        Tier leaf = flow.byPartnerId(onBtcl(), 5);
        assertThat(leaf.dbName()).isEqualTo("res_44");
        assertThat(leaf.partner().getIdPartner()).isEqualTo(5);
        assertThat(leaf.isLeaf()).isTrue();
        assertThat(flow.byPartnerId(onBtcl(), 3).dbName()).isEqualTo("res_44_7");
    }

    @Test
    void byPartnerId_ofAnotherRoot_noId_orNoTree_isNobody() {
        assertThat(flow.byPartnerId(onBtcl(), 9)).as("tele2's partner, asked inside btcl's tree").isNull();
        assertThat(flow.byPartnerId(onBtcl(), null)).as("no id").isNull();
        assertThat(flow.byPartnerId(new LabContext(), 5)).as("a session with no tree yet").isNull();
    }

    @Test
    void byPartnerName_findsTheOnePartnerWithThatName_withoutCase() {
        assertThat(flow.byPartnerName(onBtcl(), "Unilever").dbName()).isEqualTo("res_44");
        assertThat(flow.byPartnerName(onBtcl(), "unilever").partner().getIdPartner()).isEqualTo(5);
        assertThat(flow.byPartnerName(onBtcl(), "btcl-client").dbName()).isEqualTo("btcl");
    }

    @Test
    void byPartnerName_thatTwoPartnersShare_orNobodyHas_isNobody() {
        assertThat(flow.byPartnerName(onBtcl(), "twin")).as("Twin of res_44 and twin of res_44_7: a name two partners share names nobody").isNull();
        assertThat(flow.byPartnerName(onBtcl(), "tele2-client")).as("another root's").isNull();
        assertThat(flow.byPartnerName(onBtcl(), null)).isNull();
        assertThat(flow.byPartnerName(onBtcl(), " ")).isNull();
    }
}
