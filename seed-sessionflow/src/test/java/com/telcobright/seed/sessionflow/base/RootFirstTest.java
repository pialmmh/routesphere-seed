package com.telcobright.seed.sessionflow.base;

import com.telcobright.seed.sessionflow.api.Candidate;
import com.telcobright.seed.sessionflow.api.CdrEvent;
import com.telcobright.seed.sessionflow.api.IdentificationRule;
import com.telcobright.seed.sessionflow.api.Ingress;
import com.telcobright.seed.sessionflow.api.RoutesphereFlow;
import com.telcobright.seed.sessionflow.api.SessionCause;
import com.telcobright.seed.sessionflow.api.Tier;
import com.telcobright.seed.sessionflow.dependencies.RoutesphereKit;
import com.telcobright.seed.sessionflow.spi.CdrSink;
import com.telcobright.seed.sessionflow.spi.TenantLookup;
import com.telcobright.seed.sessionflow.testkit.InMemoryLedger;
import com.telcobright.statewalk.session.Outcome;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * §C · (a) identifyTenant — the root first, per protocol: the ingress → the ROOT by the bootstrap's rules, and from then on
 * every lookup is scoped to that root's tree. Two roots whose partner ids overlap; a request per root; no lookup crosses roots; no match
 * refused NO_TENANT; a rule naming an unknown root refused at load.
 */
class RootFirstTest {

    private Lab lab;

    @AfterEach
    void close() { if (lab != null) lab.close(); }

    @Test
    void twoRootsWithOverlappingPartnerIds_eachSessionClimbsItsOwnRootOnly() {
        lab = new Lab();
        lab.dial("b-1", Lab.BTCL_DOOR, 1);
        lab.dial("t-1", Lab.TELE2_DOOR, 1);
        lab.answer("b-1"); lab.hangup("b-1");
        lab.answer("t-1"); lab.hangup("t-1");

        assertThat(lab.ctx("b-1").root).isEqualTo("btcl");
        assertThat(lab.ctx("t-1").root).isEqualTo("tele2");
        assertThat(lab.ctx("b-1").tiers).extracting(t -> t.dbName + "/" + t.partnerId).containsExactly("btcl/1");
        assertThat(lab.ctx("t-1").tiers).extracting(t -> t.dbName + "/" + t.partnerId).containsExactly("tele2/1");
        assertThat(lab.ledger.balanceOf("btcl", 1)).isEqualByComparingTo("99.00");
        assertThat(lab.ledger.balanceOf("tele2", 1)).isEqualByComparingTo("99.00");
        assertThat(lab.cdr.of("b-1").get(0).tiers()).extracting(r -> r.tenant).containsExactly("btcl");
        assertThat(lab.cdr.of("t-1").get(0).tiers()).extracting(r -> r.tenant).containsExactly("tele2");
    }

    @Test
    void aPartnerOfTheOtherRoot_isNotFound_andTheOtherRootsLedgerIsNeverAsked() {
        lab = new Lab();
        lab.dial("t-44", Lab.TELE2_DOOR, 44);                 // 44 is btcl's reseller, not tele2's
        lab.dial("b-9", Lab.BTCL_DOOR, 9);                    // 9 is tele2's reseller, not btcl's

        assertThat(lab.ctx("t-44").endCause).isEqualTo(SessionCause.PARTNER_NOT_FOUND);
        assertThat(lab.ctx("b-9").endCause).isEqualTo(SessionCause.PARTNER_NOT_FOUND);
        assertThat(lab.ledger.journal()).isEmpty();
        assertThat(lab.cdr.of("t-44").get(0).tiers()).extracting(r -> r.tenant).containsExactly("tele2");
        assertThat(lab.cdr.of("b-9").get(0).tiers()).extracting(r -> r.tenant).containsExactly("btcl");
    }

    @Test
    void aThreeTierChain_isWalkedLeafToRoot_inTheRootsOwnTree() {
        lab = new Lab();
        lab.dial("b-3", Lab.BTCL_DOOR, 3);
        lab.dial("t-3", Lab.TELE2_DOOR, 3);

        assertThat(lab.ctx("b-3").tiers).extracting(t -> t.dbName + "/" + t.partnerId).containsExactly("res_44_7/3", "res_44/7", "btcl/44");
        assertThat(lab.ctx("t-3").tiers).extracting(t -> t.dbName + "/" + t.partnerId).containsExactly("res_9/3", "tele2/9");
    }

    @Test
    void anIngressNoRuleNames_isRefusedNoTenant_neverADefaultRoot() {
        lab = new Lab();
        lab.dial("n-1", Lab.NOBODYS_DOOR, 1);

        LabContext ctx = lab.ctx("n-1");
        assertThat(ctx.outcome).isEqualTo(Outcome.FAILED);
        assertThat(ctx.endCause).isEqualTo(RoutesphereFlow.NO_TENANT);
        assertThat(ctx.root).isNull();
        assertThat(lab.ledger.journal()).isEmpty();
        assertThat(lab.cdr.of("n-1")).as("no tree to write a record on").isEmpty();
        assertThat(lab.kit.counters().cdrLost.get()).isEqualTo(1);
        assertThat(lab.engine.registry().activeCellCount()).isZero();
    }

    @Test
    void aRuleNamingARootThisProcessDoesNotServe_isRefusedAtLoad() {
        assertThatThrownBy(() -> RoutesphereKit.builder()
                .tenants(TenantLookup.of(Lab.btclTree()))
                .identification(List.of(new IdentificationRule("esl", "10.10.199.9:8021", "btcl"), new IdentificationRule("listen", "10.10.188.5:7443", "ccl")))
                .ledger(new InMemoryLedger()).cdr((id, tiers) -> { }).zone(ZoneId.of("Asia/Dhaka"))
                .build())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("'ccl'").hasMessageContaining("refused at load");
    }

    @Test
    void anOverrideThatFindsThePayerInAnotherRootsTree_isRefusedPartnerNotFound_theBelt() {
        lab = new Lab(Lab.settings(0), kit -> new LabFlow(kit, Map.of(), Lab.hops("carrier-a"), Set.of()) {
            @Override protected Tier identifyEntryPartner(LabContext ctx, Candidate candidate) {
                var tele2 = kit.tenants().snapshot("tele2").orElseThrow();            // a lookup across roots: the bug the belt stops
                return Tier.entry(tele2.entryOf(1));
            }
        });
        lab.dial("x-1", Lab.BTCL_DOOR, 1);

        assertThat(lab.ctx("x-1").endCause).isEqualTo(SessionCause.PARTNER_NOT_FOUND);
        assertThat(lab.ledger.journal()).isEmpty();
    }

    @Test
    void theCdrGoesOnTheRootsOwnRoad() {
        List<String> roads = new CopyOnWriteArrayList<>();
        CdrSink perRoot = new CdrSink() {
            @Override public void publish(String callId, List<CdrEvent> tiers) { roads.add("?" + callId); }
            @Override public void publish(String root, String callId, List<CdrEvent> tiers) { roads.add("cdr_" + root + ":" + callId); }
        };
        lab = new Lab();
        RoutesphereKit kit = RoutesphereKit.builder().tenants(TenantLookup.of(lab.btcl, lab.tele2)).identification(Lab.RULES)
            .ledger(lab.ledger).cdr(perRoot).zone(ZoneId.of("Asia/Dhaka")).settings(Lab.settings(0)).build();
        LabFlow flow = new LabFlow(kit, Map.of(), Lab.hops("carrier-a"), Set.of());
        LabContext b = new LabContext();
        b.facts = new LabFacts("r-1", 1L, Lab.BTCL_DOOR, 1, "8801");
        LabContext t = new LabContext();
        t.facts = new LabFacts("r-2", 1L, Lab.TELE2_DOOR, 3, "8801");
        for (LabContext c : List.of(b, t)) {
            flow.onStart(c, new com.telcobright.statewalk.session.events.SessionStart(c.facts));
            c.candidates = List.of(Candidate.SELF);
            flow.admit(c);
            flow.release(c);
            flow.record(c, Outcome.FAILED);
        }
        assertThat(roads).containsExactly("cdr_btcl:r-1", "cdr_tele2:r-2");
    }

    @Test
    void theFourIngressKinds_writeTheRulesMatch() {
        assertThat(Ingress.listen("10.10.188.5", 7443)).isEqualTo(new Ingress("listen", "10.10.188.5:7443"));
        assertThat(Ingress.esl("10.10.188.9", 8021)).isEqualTo(new Ingress("esl", "10.10.188.9:8021"));
        assertThat(Ingress.vlan("gw-7", 310)).isEqualTo(new Ingress("vlan", "gw-7:310"));
        assertThat(Ingress.topic("sms_in_btcl")).isEqualTo(new Ingress("topic", "sms_in_btcl"));
        assertThatThrownBy(() -> new Ingress("esl", " ")).isInstanceOf(IllegalArgumentException.class);
    }
}
