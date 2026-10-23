package com.telcobright.seed.sessionflow.api;

import com.telcobright.rtc.domainmodel.PartnerType;
import com.telcobright.rtc.domainmodel.nonentity.Tenant;
import com.telcobright.seed.sessionflow.base.LabContext;
import com.telcobright.seed.sessionflow.base.LabFacts;
import com.telcobright.seed.sessionflow.base.LabFlow;
import com.telcobright.seed.sessionflow.dependencies.RoutesphereKit;
import com.telcobright.seed.sessionflow.spi.TenantLookup;
import com.telcobright.seed.sessionflow.testkit.InMemoryLedger;
import com.telcobright.seed.sessionflow.testkit.TenantTreeBuilder;
import com.telcobright.statewalk.session.SessionFacts;
import com.telcobright.statewalk.session.events.SessionStart;
import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P1-E item 1 · (a) {@code identifyTenant} — every lane names the root operator of its own door (R1, R2: "root level tenant identification
 * logic will vary for call, sms, wifi"); the base's one-line helpers ask the kit's identification rules; null = refused NO_TENANT, never a
 * default root; facts that are not {@code RoutesphereFacts} name no door and are refused with their class in the session's history.
 */
class IdentifyTenantTest {

    static final List<IdentificationRule> RULES = List.of(
        new IdentificationRule("listen", "10.10.188.5:7443", "btcl"), new IdentificationRule("esl", "10.10.188.9:8021", "btcl"),
        new IdentificationRule("vlan", "10.20.0.1:2104", "tele2"), new IdentificationRule("topic", "sms_in_tele2", "tele2"));

    private static Tenant root(String name) {
        TenantTreeBuilder b = new TenantTreeBuilder();
        b.root(name).partner(1, name + "-client", PartnerType.CUSTOMER);
        return b.build();
    }

    private static RoutesphereKit kit() {
        return RoutesphereKit.builder().tenants(TenantLookup.of(root("btcl"), root("tele2"))).identification(RULES)
            .ledger(new InMemoryLedger()).cdr((id, tiers) -> { }).zone(ZoneId.of("Asia/Dhaka")).build();
    }

    private static LabFlow lab(RoutesphereKit kit) { return new LabFlow(kit, Map.of(), List.of(), Set.of()); }

    private static LabContext started(RoutesphereFlow<LabContext> flow, SessionFacts facts) {
        LabContext ctx = new LabContext();
        ctx.facts = facts;
        flow.onStart(ctx, new SessionStart(facts));
        return ctx;
    }

    @Test
    void theHelpers_findTheRootByEachKindOfDoor_andADoorNoRuleNamesIsNobodys() {
        LabFlow flow = lab(kit());
        assertThat(flow.byListener("10.10.188.5", 7443)).isEqualTo("btcl");
        assertThat(flow.byListener("10.10.188.5:7443")).isEqualTo("btcl");
        assertThat(flow.byEslSocket("10.10.188.9", 8021)).isEqualTo("btcl");
        assertThat(flow.byEslSocket("10.10.188.9:8021")).isEqualTo("btcl");
        assertThat(flow.byVlan("10.20.0.1", 2104)).isEqualTo("tele2");
        assertThat(flow.byTopic("sms_in_tele2")).isEqualTo("tele2");
        assertThat(flow.byIngress(Ingress.topic("sms_in_tele2"))).isEqualTo("tele2");

        assertThat(flow.byListener("10.10.188.5", 9999)).as("a door no rule names").isNull();
        assertThat(flow.byEslSocket("10.10.188.5:7443")).as("the listener's address asked as an ESL socket: another kind").isNull();
        assertThat(flow.byIngress(null)).isNull();
        assertThat(flow.byTopic(null)).as("no door at all").isNull();
        assertThat(flow.byListener(" ")).isNull();
    }

    @Test
    void aLaneThatNamesNoRoot_isRefusedNoTenant_neverADefaultRoot() {
        LabFlow flow = new LabFlow(kit(), Map.of(), List.of(), Set.of()) {
            @Override protected String identifyTenant(RoutesphereFacts facts) { return null; }
        };
        LabContext ctx = started(flow, new LabFacts("n-1", 0, Ingress.esl("10.10.188.9", 8021), 1, "8801"));
        assertThat(ctx.startRefusal).isEqualTo(RoutesphereFlow.NO_TENANT);
        assertThat(ctx.root).isNull();
    }

    @Test
    void theLanesAnswer_isTheSessionsRoot() {
        LabContext ctx = started(lab(kit()), new LabFacts("b-1", 0, Ingress.esl("10.10.188.9", 8021), 1, "8801"));
        assertThat(ctx.startRefusal).isNull();
        assertThat(ctx.root).isEqualTo("btcl");
        assertThat(ctx.tree.rootDbName()).isEqualTo("btcl");
    }

    /** A lane's facts that implement statewalk's SessionFacts only: no ingress, no door. */
    record PlainFacts(String sessionId, long atMs) implements SessionFacts {}

    @Test
    void factsThatAreNotRoutesphereFacts_areRefusedNoTenant_andTheHistoryNamesTheirClass() {
        LabContext ctx = started(lab(kit()), new PlainFacts("p-1", 0));
        assertThat(ctx.startRefusal).isEqualTo(RoutesphereFlow.NO_TENANT);
        assertThat(ctx.history.snapshot()).anyMatch(r -> r.isNote() && r.cause().contains("PlainFacts") && r.cause().contains("RoutesphereFacts"));
    }
}
