package com.telcobright.seed.tenant;

import com.telcobright.rtc.domainmodel.PartnerType;
import com.telcobright.rtc.domainmodel.nonentity.Tenant;
import com.telcobright.seed.context.publishes.ContextEvent;
import com.telcobright.seed.sessionflow.api.EntryPartner;
import com.telcobright.seed.sessionflow.testkit.TenantTreeBuilder;
import com.telcobright.seed.tenant.api.TenantTrees;
import com.telcobright.seed.tenant.testkit.ScriptedTrees;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The map of sibling tenants, each its own tree; nested tiers each their own context; the tree swapped on the notification. The rules
 * broken once (seen red): a lookup answering a sibling's tree → {@link #twoSiblingTenants_eachHasItsOwnTree_anIdIsFoundInsideItsOwnTreeOnly};
 * a reload that does not swap → {@link #aNotification_reloadsTheTenant_andTheNextLookupWalksTheNewTree}; a failed load replacing the
 * last good tree → {@link #aFailedReload_keepsTheLastGoodTree}; "the first tier found" for an id two tiers hold →
 * {@link #anIdTwoTiersOfOneTreeHold_namesNobody_andTheMapIsBuiltOncePerSnapshot}.
 */
class TenantTreesTest {

    static Tenant btcl(boolean with46) {
        TenantTreeBuilder.TenantSpec root = new TenantTreeBuilder().root("btcl")
            .partner(1, "BTCL Network", PartnerType.CUSTOMER)
            .partner(45, "res_45", PartnerType.RESELLER);
        if (with46) root.partner(46, "res_46", PartnerType.RESELLER);
        TenantTreeBuilder b = root.and()
            .tenant("res_45", "btcl").partner(7, "res_45_7", PartnerType.RESELLER).partner(8, "Site Owner Y", PartnerType.CUSTOMER).and()
            .tenant("res_45_7", "res_45").partner(2, "Deep Client", PartnerType.CUSTOMER).and();
        if (with46) b = b.tenant("res_46", "btcl").partner(3, "New Client", PartnerType.CUSTOMER).and();
        return b.build();
    }

    static Tenant tele2() {
        return new TenantTreeBuilder().root("tele2")
            .partner(1, "Tele2 Network", PartnerType.CUSTOMER).partner(45, "res_45", PartnerType.RESELLER).and()
            .tenant("res_45", "tele2").partner(9, "Tele2 Reseller Client", PartnerType.CUSTOMER).and()
            .build();
    }

    /** A tree built from live databases that each start at 1: partner 2 is in the root AND in res_45_7. */
    static Tenant collidingTree() {
        return new TenantTreeBuilder().root("btcl")
            .partner(1, "BTCL Network", PartnerType.CUSTOMER).partner(2, "Root Client Two", PartnerType.CUSTOMER).partner(45, "res_45", PartnerType.RESELLER).and()
            .tenant("res_45", "btcl").partner(7, "res_45_7", PartnerType.RESELLER).and()
            .tenant("res_45_7", "res_45").partner(2, "Deep Client Two", PartnerType.CUSTOMER).and()
            .build();
    }

    private final List<TenantTrees> open = new CopyOnWriteArrayList<>();
    private final List<ContextEvent> events = new CopyOnWriteArrayList<>();

    @AfterEach void closeAll() { open.forEach(TenantTrees::close); }

    private TenantTrees trees(ScriptedTrees script, Set<String> directory) {
        TenantTrees t = TenantTrees.builder().directory(() -> directory).source(script).listener(events::add).debounceMs(50).build().start();
        open.add(t);
        return t;
    }

    @Test
    void twoSiblingTenants_eachHasItsOwnTree_anIdIsFoundInsideItsOwnTreeOnly() {
        TenantTrees trees = trees(new ScriptedTrees().serve("btcl", btcl(false)).serve("tele2", tele2()), Set.of("btcl", "tele2"));

        assertThat(trees.tenants()).containsExactlyInAnyOrder("btcl", "tele2");
        assertThat(trees.root("btcl").get()).isNotSameAs(trees.root("tele2").get());
        assertThat(trees.tenantOfPartner("btcl", 45).map(Tenant::getDbName)).contains("btcl");     // partner 45 lives in BOTH trees
        assertThat(trees.tenantOfPartner("tele2", 45).map(Tenant::getDbName)).contains("tele2");
        assertThat(trees.tenantOfPartner("btcl", 9)).as("tele2's client is not btcl's").isEmpty();
        assertThat(trees.tenantOfPartner("tele2", 7)).as("btcl's sub-reseller is not tele2's").isEmpty();
        assertThat(trees.entryIn("btcl", 8)).extracting(e -> e.tenant().getDbName(), e -> e.partner().getPartnerName()).containsExactly("res_45", "Site Owner Y");
        assertThat(trees.entryIn("btcl", 9)).isNull();

        assertThat(trees.root("nobody")).as("a tenant the directory does not name is absent — never a sibling's tree").isEmpty();
        assertThat(trees.tenantOfPartner("nobody", 45)).isEmpty();
        assertThat(trees.entryIn("nobody", 45)).isNull();
    }

    @Test
    void rootOfCode_namesAServedRootByItsTenantCode_neverATierNorAStranger() {
        TenantTrees trees = trees(new ScriptedTrees().serve("btcl", btcl(false)).serve("tele2", tele2()), Set.of("btcl", "tele2"));

        assertThat(trees.rootOfCode("btcl").get()).as("the served root whose Tenant.name is the code").isSameAs(trees.root("btcl").get());
        assertThat(trees.rootOfCode("tele2").get()).isSameAs(trees.root("tele2").get());
        assertThat(trees.rootOfCode("res_45")).as("a tier's name is not a served root's code").isEmpty();
        assertThat(trees.rootOfCode("nobody")).isEmpty();
        assertThat(trees.rootOfCode(null)).isEmpty();
    }

    @Test
    void nestedResellers_eachTierHasItsOwnContext_andTheChainRunsLeafToRoot() {
        TenantTrees trees = trees(new ScriptedTrees().serve("btcl", btcl(false)), Set.of("btcl"));
        Tenant root = trees.root("btcl").orElseThrow();
        Tenant res45 = trees.tenantByDbName("btcl", "res_45").orElseThrow();
        Tenant res45_7 = trees.tenantByDbName("btcl", "res_45_7").orElseThrow();

        assertThat(res45.getContext()).isNotNull().isNotSameAs(root.getContext());
        assertThat(res45_7.getContext()).isNotNull().isNotSameAs(res45.getContext());
        assertThat(res45.getContext().getPartners()).containsKeys(7, 8);
        assertThat(res45_7.getContext().getPartners()).containsKey(2);
        assertThat(root.getContext().getPartners()).doesNotContainKey(2);
        assertThat(trees.tenantOfPartner("btcl", 2).map(Tenant::getDbName)).contains("res_45_7");
        assertThat(res45_7.getAncestorChain().stream().map(Tenant::getDbName).toList()).containsExactly("res_45_7", "res_45", "btcl");
        assertThat(trees.tenantByDbName("btcl", "res_99")).isEmpty();
    }

    @Test
    void aNotification_reloadsTheTenant_andTheNextLookupWalksTheNewTree() throws Exception {
        ScriptedTrees script = new ScriptedTrees().serve("btcl", btcl(false));
        TenantTrees trees = trees(script, Set.of("btcl"));
        long before = trees.find("btcl").orElseThrow().version();
        assertThat(trees.tenantOfPartner("btcl", 46)).isEmpty();

        script.serve("btcl", btcl(true));                               // an officer created res_46: prime-context now serves it
        assertThat(trees.tenantOfPartner("btcl", 46)).as("no reload yet: the old tree is served").isEmpty();

        trees.ring("btcl", "test: config_event_loader_btcl");           // the Kafka notification's twin
        long until = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < until && trees.find("btcl").orElseThrow().version() == before) Thread.sleep(20);

        assertThat(trees.find("btcl").orElseThrow().version()).isGreaterThan(before);
        assertThat(trees.tenantOfPartner("btcl", 46).map(Tenant::getDbName)).contains("btcl");
        assertThat(trees.tenantOfPartner("btcl", 3).map(Tenant::getDbName)).contains("res_46");
        assertThat(script.loads("btcl")).isEqualTo(2);
        assertThat(events).anyMatch(e -> e instanceof ContextEvent.Reloaded r && r.tenantId().equals("btcl"));

        script.serve("btcl", btcl(false));
        trees.reload("btcl", "test").get(5, TimeUnit.SECONDS);          // an explicit reload is the same road, awaited
        assertThat(trees.tenantOfPartner("btcl", 46)).isEmpty();
    }

    @Test
    void aFailedReload_keepsTheLastGoodTree() throws Exception {
        ScriptedTrees script = new ScriptedTrees().serve("btcl", btcl(false));
        TenantTrees trees = trees(script, Set.of("btcl"));
        long version = trees.find("btcl").orElseThrow().version();

        script.failing("btcl");
        assertThatThrownBy(() -> trees.reload("btcl", "test").get(5, TimeUnit.SECONDS)).hasMessageContaining("scripted");

        assertThat(trees.find("btcl").orElseThrow().version()).isEqualTo(version);
        assertThat(trees.tenantOfPartner("btcl", 45)).isPresent();
        assertThat(events).anyMatch(e -> e instanceof ContextEvent.LoadFailed f && f.tenantId().equals("btcl"));
    }

    @Test
    void anIdTwoTiersOfOneTreeHold_namesNobody_andTheMapIsBuiltOncePerSnapshot() throws Exception {
        ScriptedTrees script = new ScriptedTrees().serve("btcl", collidingTree());
        TenantTrees trees = trees(script, Set.of("btcl"));

        assertThat(trees.tenantOfPartner("btcl", 2)).as("D5: never the first tier found").isEmpty();
        assertThat(trees.entryIn("btcl", 2)).isNull();
        assertThat(trees.tiersHoldingTwice("btcl", 2)).containsExactly("btcl", "res_45_7");
        assertThat(trees.tiersHoldingTwice("btcl", 7)).isEmpty();
        assertThat(trees.tenantOfPartner("btcl", 7).map(Tenant::getDbName)).as("an id one tier holds is found").contains("res_45");
        assertThat(trees.walks()).as("one map per loaded snapshot, none on the second ask").isEqualTo(1);

        trees.reload("btcl", "test").get(5, TimeUnit.SECONDS);
        assertThat(trees.tenantOfPartner("btcl", 7)).isPresent();
        assertThat(trees.walks()).as("a new snapshot: one more map").isEqualTo(2);
        EntryPartner entry = trees.entryIn("btcl", 45);
        assertThat(entry.tenant().getDbName()).isEqualTo("btcl");
        assertThat(entry.partner().getIdPartner()).isEqualTo(45);
    }
}
