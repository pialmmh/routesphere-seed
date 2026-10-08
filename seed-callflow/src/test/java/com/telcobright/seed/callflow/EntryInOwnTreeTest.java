package com.telcobright.seed.callflow;

import com.telcobright.rtc.domainmodel.LevelAdmission;
import com.telcobright.rtc.domainmodel.PartnerType;
import com.telcobright.rtc.domainmodel.nonentity.Tenant;
import com.telcobright.seed.callflow.api.CallCause;
import com.telcobright.seed.callflow.api.EntryPartner;
import com.telcobright.seed.callflow.dependencies.CallFlowKit;
import com.telcobright.seed.callflow.samples.Scene;
import com.telcobright.seed.callflow.samples.VoiceFlow;
import com.telcobright.seed.callflow.spi.TenantLookup;
import com.telcobright.seed.callflow.testkit.InMemoryLedger;
import com.telcobright.seed.callflow.testkit.RecordingCdrSink;
import com.telcobright.seed.callflow.testkit.TenantTreeBuilder;
import com.telcobright.statewalk.pipeline.StepMode;
import com.telcobright.statewalk.session.AdmissionVerdict;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A call never leaves its own tenant's tree. One process serves TWO trees that share partner ids AND a tier name — every operator's
 * root starts at partner 1, and every tree with a reseller 44 has a tier {@code res_44}:
 *
 * <pre>
 *   alpha     partner 44 (stands for res_44) · partner 9 "Alpha direct"          beta      partner 44 · partner 9 "Beta direct"
 *   └ res_44  partner 701 "Alpha client"                                         └ res_44  partner 701 "Beta client" · partner 702 "Only beta"
 * </pre>
 *
 * A partner id alone names nobody: the lookups are asked inside the call's own tree (the root it names), and the base refuses a chain
 * that ends at another root — whatever an application's own step handed back.
 */
class EntryInOwnTreeTest {

    private final Tenant alpha = tree("alpha");
    private final Tenant beta = tree("beta", 702);
    private final InMemoryLedger ledger = new InMemoryLedger()
        .fund("res_44", 701, "100.00").fund("alpha", 44, "100.00").fund("beta", 44, "100.00").fund("alpha", 9, "100.00").fund("beta", 9, "100.00");
    private final RecordingCdrSink cdrs = new RecordingCdrSink();

    /** @param moreClients partners of the tier beside 701 (702 is the one only beta holds) */
    private static Tenant tree(String root, int... moreClients) {
        TenantTreeBuilder.TenantSpec tier = new TenantTreeBuilder()
            .root(root)
                .partner(44, root + " reseller", PartnerType.RESELLER)
                .partner(9, root + " direct", PartnerType.CUSTOMER)
                .and()
            .tenant("res_44", root)
                .partner(701, root + " client", PartnerType.CUSTOMER);
        for (int client : moreClients) tier.partner(client, "client " + client + " of " + root, PartnerType.CUSTOMER);
        return tier.and().build();
    }

    private VoiceFlow voice(TenantLookup tenants) {
        CallFlowKit kit = CallFlowKit.builder().tenants(tenants).ledger(ledger).cdrSink(cdrs).sdrSink(record -> { })
            .clock(Clock.system(Scene.DHAKA)).zone(Scene.DHAKA).settings(Scene.settings(4)).build();
        return new VoiceFlow(kit, Map.of("10.0.0.7", 701, "10.0.0.9", 9, "10.0.0.2", 702), RATES, List.of(new VoiceFlow.Route("017", "GP-trunk", 5)));
    }

    private static final Map<String, BigDecimal> RATES = Map.of("res_44#701", new BigDecimal("0.60"), "res_44#702", new BigDecimal("0.60"),
        "alpha#44", new BigDecimal("0.40"), "beta#44", new BigDecimal("0.30"), "alpha#9", new BigDecimal("0.50"), "beta#9", new BigDecimal("0.20"));

    private static VoiceFlow.Call callOf(String tenant, String id, String sourceIp) {
        VoiceFlow.Call call = Scene.call(id, sourceIp, "01712345678");
        call.tenantName = tenant;
        return call;
    }

    private static AdmissionVerdict admit(VoiceFlow flow, VoiceFlow.Call call) {
        assertThat(flow.preprocess(call)).isNull();
        return flow.admission(call, StepMode.LIVE);
    }

    // ── the lookup: every question inside one tree ───────────────────────────

    @Test
    void theLookupAnswersInsideTheNamedRootsTree_neverAcrossTheTreesServed() {
        for (TenantLookup tenants : List.of(TenantLookup.of(alpha, beta), TenantLookup.of(beta, alpha))) {
            assertThat(tenants.root("alpha")).containsSame(alpha);
            assertThat(tenants.root("beta")).containsSame(beta);
            assertThat(tenants.root("res_44")).as("a tier is not a root: a call names the root of its tree").isEmpty();
            assertThat(tenants.root("nobody")).isEmpty();
            assertThat(tenants.root(null)).isEmpty();

            assertThat(tenants.tenantOfPartner("alpha", 701)).containsSame(alpha.findTenantByDbName("res_44"));
            assertThat(tenants.tenantOfPartner("beta", 701)).containsSame(beta.findTenantByDbName("res_44"));
            assertThat(tenants.tenantOfPartner("alpha", 9)).containsSame(alpha);
            assertThat(tenants.tenantOfPartner("beta", 9)).containsSame(beta);
            assertThat(tenants.tenantOfPartner("beta", 702)).containsSame(beta.findTenantByDbName("res_44"));
            assertThat(tenants.tenantOfPartner("alpha", 702)).as("702 lives in beta's tree only: for alpha it does not exist").isEmpty();
            assertThat(tenants.tenantOfPartner("nobody", 701)).isEmpty();

            assertThat(tenants.tenantByDbName("alpha", "res_44")).containsSame(alpha.findTenantByDbName("res_44"));
            assertThat(tenants.tenantByDbName("beta", "res_44")).containsSame(beta.findTenantByDbName("res_44"));
            assertThat(tenants.tenantByDbName("alpha", "alpha")).containsSame(alpha);
            assertThat(tenants.tenantByDbName("alpha", "beta")).as("the other tree's root is no tenant of this tree").isEmpty();
            assertThat(tenants.tenantByDbName("alpha", null)).isEmpty();
        }
    }

    @Test
    void twoRootsOfOneName_cannotBeToldApart_andAreRefused() {
        assertThatThrownBy(() -> TenantLookup.of(alpha, tree("alpha"))).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("'alpha'");
    }

    // ── a call: it climbs its own tree, in both orders of the roots ──────────

    @Test
    void eachTenantsCall_climbsItsOwnTree_whicheverTreeTheProcessWasGivenFirst() {
        for (TenantLookup tenants : List.of(TenantLookup.of(alpha, beta), TenantLookup.of(beta, alpha))) {
            VoiceFlow flow = voice(tenants);

            VoiceFlow.Call ofBeta = callOf("beta", "b-" + System.nanoTime(), "10.0.0.7");
            assertThat(admit(flow, ofBeta).accepted()).isTrue();
            assertThat(ofBeta.entryTenant).as("beta's partner 701: beta's own tier, though alpha holds a 701 in a res_44 too").isSameAs(beta.findTenantByDbName("res_44"));
            assertThat(ofBeta.levels).extracting(LevelAdmission::getDbName).containsExactly("res_44", "beta");
            assertThat(ofBeta.levels).extracting(LevelAdmission::getTenant).containsExactly(beta.findTenantByDbName("res_44"), beta);
            assertThat(ofBeta.levels.get(1).getRate()).as("the root's rate of beta, never alpha's").isEqualByComparingTo("0.30");

            VoiceFlow.Call ofAlpha = callOf("alpha", "a-" + System.nanoTime(), "10.0.0.7");
            assertThat(admit(flow, ofAlpha).accepted()).isTrue();
            assertThat(ofAlpha.entryTenant).isSameAs(alpha.findTenantByDbName("res_44"));
            assertThat(ofAlpha.levels).extracting(LevelAdmission::getTenant).containsExactly(alpha.findTenantByDbName("res_44"), alpha);
            assertThat(ofAlpha.levels.get(1).getRate()).isEqualByComparingTo("0.40");

            VoiceFlow.Call directOfBeta = callOf("beta", "bd-" + System.nanoTime(), "10.0.0.9");
            assertThat(admit(flow, directOfBeta).accepted()).isTrue();
            assertThat(directOfBeta.levels).extracting(LevelAdmission::getTenant).as("a direct partner of the root: one tier, its own root").containsExactly(beta);
            assertThat(directOfBeta.levels.get(0).getRate()).isEqualByComparingTo("0.20");
        }
    }

    @Test
    void aPartnerThatOnlyTheOtherTreeHolds_doesNotExistForThisCall() {
        for (TenantLookup tenants : List.of(TenantLookup.of(alpha, beta), TenantLookup.of(beta, alpha))) {
            VoiceFlow.Call call = callOf("alpha", "a702-" + System.nanoTime(), "10.0.0.2");     // 702 lives in beta's tree only

            AdmissionVerdict verdict = admit(voice(tenants), call);

            assertThat(verdict.accepted()).isFalse();
            assertThat(verdict.rejectCause()).isEqualTo(CallCause.PARTNER_NOT_FOUND);
            assertThat(call.entryTenant).isNull();
        }
        assertThat(ledger.journal()).as("nothing was asked of the ledger").isEmpty();
    }

    @Test
    void aCallThatNamesNoTenant_hasNoTreeToBeFoundIn() {
        VoiceFlow.Call call = callOf(null, "nameless-1", "10.0.0.7");

        AdmissionVerdict verdict = admit(voice(TenantLookup.of(alpha, beta)), call);

        assertThat(verdict.rejectCause()).as("a partner id alone names nobody").isEqualTo(CallCause.PARTNER_NOT_FOUND);
        assertThat(ledger.journal()).isEmpty();
    }

    // ── the belt: an application's own step cannot take a call out of its tree ──

    /** An application whose own entry step searched "everything served" and came back with the first tier that holds the id. */
    private VoiceFlow anApplicationThatHandsBackTheOtherTreesTier(TenantLookup tenants, Tenant foreignTier) {
        CallFlowKit kit = CallFlowKit.builder().tenants(tenants).ledger(ledger).cdrSink(cdrs).sdrSink(record -> { })
            .clock(Clock.system(Scene.DHAKA)).zone(Scene.DHAKA).settings(Scene.settings(4)).build();
        return new VoiceFlow(kit, Map.of("10.0.0.7", 701), RATES, List.of(new VoiceFlow.Route("017", "GP-trunk", 5))) {
            @Override protected EntryPartner identifyEntryPartner(Call call) {
                return new EntryPartner(foreignTier, foreignTier.getContext().getPartners().get(701));
            }
        };
    }

    @Test
    void anOverrideThatHandsBackTheOtherTreesTier_isRefused_andNothingOfTheCallIsPutOnThatTree() {
        Tenant alphasTier = alpha.findTenantByDbName("res_44");
        VoiceFlow.Call call = callOf("beta", "leak-1", "10.0.0.7");
        PrintStream err = System.err;
        ByteArrayOutputStream logged = new ByteArrayOutputStream();
        AdmissionVerdict verdict;
        try {
            System.setErr(new PrintStream(logged, true, StandardCharsets.UTF_8));
            verdict = admit(anApplicationThatHandsBackTheOtherTreesTier(TenantLookup.of(alpha, beta), alphasTier), call);
        } finally {
            System.setErr(err);
        }

        assertThat(verdict.accepted()).isFalse();
        assertThat(verdict.rejectCause()).as("for the call's own tenant that partner does not exist").isEqualTo(CallCause.PARTNER_NOT_FOUND);
        assertThat(call.entryTenant).as("the call was never put on the foreign tier: its record stays on its own tenant").isNull();
        assertThat(call.partner).isNull();
        assertThat(call.levels).isEmpty();
        assertThat(ledger.journal()).as("no reserve on the other tree's accounts").isEmpty();
        assertThat(ledger.openReserves()).isZero();
        assertThat(call.history.snapshot()).extracting(line -> line.cause()).anySatisfy(note -> assertThat(note).contains("'alpha'").contains("'beta'"));
        assertThat(logged.toString(StandardCharsets.UTF_8)).as("one ERROR line naming both roots").containsOnlyOnce("a call never leaves its own tree")
            .contains("ERROR").contains("the tree of 'alpha'").contains("this call's tenant is 'beta'").contains("leak-1");
    }

    @Test
    void theSameOverride_onACallOfTheTreeItHandsBack_passes() {
        Tenant alphasTier = alpha.findTenantByDbName("res_44");
        VoiceFlow.Call call = callOf("alpha", "own-1", "10.0.0.7");

        assertThat(admit(anApplicationThatHandsBackTheOtherTreesTier(TenantLookup.of(alpha, beta), alphasTier), call).accepted()).isTrue();
        assertThat(call.levels).extracting(LevelAdmission::getTenant).containsExactly(alphasTier, alpha);
    }
}
