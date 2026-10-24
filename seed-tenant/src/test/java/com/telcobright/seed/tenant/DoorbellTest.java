package com.telcobright.seed.tenant;

import com.telcobright.rtc.domainmodel.PartnerType;
import com.telcobright.rtc.domainmodel.nonentity.Tenant;
import com.telcobright.seed.sessionflow.testkit.TenantTreeBuilder;
import com.telcobright.seed.tenant.api.Doorbell;
import com.telcobright.seed.tenant.api.TenantTrees;
import com.telcobright.seed.tenant.internal.KafkaDoorbell;
import com.telcobright.seed.tenant.testkit.ScriptedTrees;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The ringer. The rules broken once (seen red): no local ring before the record → {@link #aRing_reloadsThisInstance_andSendsOneRecordOnTheTenantsTopic}
 * (the reload count); a producer's failure thrown into the writer → {@link #aProducerThatFails_isLogged_neverThrownIntoTheWriter}.
 */
class DoorbellTest {

    static Tenant btcl() { return new TenantTreeBuilder().root("btcl").partner(1, "BTCL Network", PartnerType.CUSTOMER).and().build(); }

    private TenantTrees trees;
    private ScriptedTrees script;

    private TenantTrees trees() {
        script = new ScriptedTrees().serve("btcl", btcl());
        trees = TenantTrees.builder().directory(() -> Set.of("btcl")).source(script).debounceMs(20).build().start();
        return trees;
    }

    @AfterEach void closeAll() { if (trees != null) trees.close(); }

    private void awaitLoads(int n) throws InterruptedException {
        long until = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < until && script.loads("btcl") < n) Thread.sleep(10);
    }

    @Test
    void aRing_reloadsThisInstance_andSendsOneRecordOnTheTenantsTopic() throws Exception {
        MockProducer<String, String> producer = new MockProducer<>(true, new StringSerializer(), new StringSerializer());
        try (Doorbell bell = new KafkaDoorbell(trees(), producer, "ad-sphere")) {
            boolean left = bell.ring("btcl", "campaign 12 approved");
            awaitLoads(2);

            assertThat(left).isTrue();
            assertThat(script.loads("btcl")).as("the local ring reloaded this instance").isEqualTo(2);
            assertThat(producer.history()).hasSize(1);
            assertThat(producer.history().get(0).topic()).isEqualTo("config_event_loader_btcl");
            assertThat(producer.history().get(0).key()).isEqualTo("btcl");
            assertThat(producer.history().get(0).value()).contains("\"tenantId\":\"btcl\"").contains("\"source\":\"ad-sphere\"").contains("\"what\":\"campaign 12 approved\"").contains("\"at\":\"");
        }
    }

    /**
     * The base is ONE fact of the trees, read by both sides: a product that names its own base (the WiFi BSS on production, where
     * {@code config_event_loader_btcl} belongs to another config plane) listens on it AND rings on it; the default stays
     * {@code config_event_loader}. Seen red by the mutants: the ringer keeping the constant; the builder passing the constant to the
     * listener; a blank base accepted.
     */
    @Test
    void aTreesBuiltOnItsOwnBase_listensOnThatBase_andRingsOnIt_theDefaultUnchanged() throws Exception {
        assertThat(trees().doorbellTopics()).as("the default base, listener side").containsExactly("config_event_loader_btcl");
        assertThat(trees.doorbellTopic("btcl")).as("the default base, ringer side").isEqualTo("config_event_loader_btcl");
        trees.close();

        script = new ScriptedTrees().serve("btcl", btcl());
        trees = TenantTrees.builder().directory(() -> Set.of("btcl")).source(script).debounceMs(20)
            .doorbellBase("wifi_prime_doorbell").build().start();
        assertThat(trees.doorbellTopics()).as("the listener's names follow the base, broker or not").containsExactly("wifi_prime_doorbell_btcl");

        MockProducer<String, String> producer = new MockProducer<>(true, new StringSerializer(), new StringSerializer());
        try (Doorbell bell = new KafkaDoorbell(trees, producer, "wifi-sphere")) {
            assertThat(bell.ring("btcl", "site 7 moved")).isTrue();
            awaitLoads(2);
            assertThat(producer.history()).hasSize(1);
            assertThat(producer.history().get(0).topic()).as("the ringer's topic follows the same base").isEqualTo("wifi_prime_doorbell_btcl");
        }
    }

    @Test
    void aBlankBase_isRefusedByName() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> TenantTrees.builder().doorbellBase(" "))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("doorbellBase");
    }

    @Test
    void withoutABroker_onlyThisInstanceReloads() throws Exception {
        try (Doorbell bell = Doorbell.localOnly(trees())) {
            assertThat(bell.ring("btcl", "a write")).isFalse();
            awaitLoads(2);
            assertThat(script.loads("btcl")).isEqualTo(2);
        }
    }

    @Test
    void aProducerThatFails_isLogged_neverThrownIntoTheWriter() throws Exception {
        MockProducer<String, String> producer = new MockProducer<>(false, new StringSerializer(), new StringSerializer());
        try (Doorbell bell = new KafkaDoorbell(trees(), producer, "wifi-sphere")) {
            boolean left = bell.ring("btcl", "site 7 moved to res_45");
            producer.errorNext(new RuntimeException("broker gone (scripted)"));   // the send's callback sees the failure; the writer already returned
            awaitLoads(2);
            assertThat(left).isTrue();
            assertThat(script.loads("btcl")).as("the local ring happened whatever the broker did").isEqualTo(2);
        }
    }
}
