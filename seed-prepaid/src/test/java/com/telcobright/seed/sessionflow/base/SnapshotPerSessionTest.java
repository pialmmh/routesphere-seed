package com.telcobright.seed.sessionflow.base;

import com.telcobright.rtc.domainmodel.PartnerType;
import com.telcobright.rtc.domainmodel.nonentity.Tenant;
import com.telcobright.seed.sessionflow.api.Candidate;
import com.telcobright.seed.sessionflow.testkit.TenantTreeBuilder;
import com.telcobright.statewalk.persistence.SnapshotSerializer;
import com.telcobright.statewalk.session.Outcome;
import com.telcobright.statewalk.session.events.Established;
import com.telcobright.statewalk.session.events.SessionStart;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * §D · The dynamic context per root: a session takes its root's snapshot at its start and keeps it to its end; a reload of the root (the
 * doorbell) moves only the sessions that start after it; the other root is untouched; a session restored from the store takes its root's
 * snapshot again by the root and says when the version moved (§L.6).
 */
class SnapshotPerSessionTest {

    private final VersionedTrees trees = new VersionedTrees().serve(Lab.btclTree()).serve(Lab.tele2Tree());
    private final Lab lab = new Lab(Lab.settings(0), kit -> new LabFlow(kit, Map.of(), Lab.hops("carrier-a"), Set.of()), trees);

    @AfterEach
    void close() { lab.close(); }

    @Test
    void aSessionStartedBeforeTheReload_finishesOnItsSnapshot_aNewSessionSeesTheNewOne_theOtherRootIsUntouched() {
        lab.dial("old", Lab.BTCL_DOOR, 3);
        LabContext old = lab.ctx("old");
        Tenant v1Leaf = old.levels.get(0).getTenant();
        assertThat(old.treeVersion).isEqualTo(1);

        trees.serve(btclWithPartner8());                                       // the doorbell's reload of btcl: version 2
        lab.dial("new", Lab.BTCL_DOOR, 8);
        lab.dial("t", Lab.TELE2_DOOR, 1);
        lab.answer("old");
        lab.hangup("old");

        assertThat(lab.ctx("new").treeVersion).isEqualTo(2);
        assertThat(lab.ctx("new").tiers).extracting(t -> t.dbName + "/" + t.partnerId).containsExactly("btcl/8");
        assertThat(lab.ctx("t").treeVersion).as("tele2 was not reloaded").isEqualTo(1);
        assertThat(old.outcome).isEqualTo(Outcome.SUCCEEDED);
        assertThat(old.treeVersion).isEqualTo(1);
        assertThat(old.tree.version()).isEqualTo(1);
        assertThat(old.levels.get(0).getTenant()).as("settled on the tree it started with").isSameAs(v1Leaf);
        assertThat(lab.ctx("old").settlements).extracting(s -> s.charged().toPlainString()).containsExactly("1.00", "1.00", "1.00");
    }

    @Test
    void aSessionRestoredFromItsRow_takesItsRootsSnapshotAgain_andSaysTheVersionMoved() {
        LabContext live = new LabContext();
        live.facts = new LabFacts("r-1", 1L, Lab.BTCL_DOOR, 3, "8801");
        lab.flow.onStart(live, new SessionStart(live.facts));
        live.candidates = List.of(Candidate.SELF);
        assertThat(lab.flow.admit(live).accepted()).isTrue();
        live.answer = new Established(2L, "carrier-a", null, null);
        LabContext restored = (LabContext) SnapshotSerializer.contextFromBase64Json(SnapshotSerializer.contextToBase64Json(live), LabContext.class.getName());
        trees.serve(btclWithPartner8());

        lab.flow.settle(restored, Outcome.SUCCEEDED);

        assertThat(restored.tree.version()).isEqualTo(2);
        assertThat(restored.restoredAtMs).isPositive();
        assertThat(restored.history.snapshot()).anyMatch(r -> r.isNote() && r.cause().contains("restored on the root's snapshot v2 (it started on v1)"));
        assertThat(restored.settlements).extracting(s -> s.charged().toPlainString()).containsExactly("1.00", "1.00", "1.00");
        assertThat(lab.ledger.openReserves()).isZero();
    }

    private static Tenant btclWithPartner8() {
        TenantTreeBuilder b = new TenantTreeBuilder();
        b.root("btcl").partner(1, "btcl-client", PartnerType.CUSTOMER).partner(8, "new-client", PartnerType.CUSTOMER).partner(44, "res_44", PartnerType.RESELLER);
        b.tenant("res_44", "btcl").partner(5, "res44-client", PartnerType.CUSTOMER).partner(7, "res_44_7", PartnerType.RESELLER);
        b.tenant("res_44_7", "res_44").partner(3, "res447-client", PartnerType.CUSTOMER);
        return b.build();
    }
}
