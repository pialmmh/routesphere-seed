package com.telcobright.seed.tenant;

import com.telcobright.rtc.domainmodel.nonentity.Tenant;
import com.telcobright.seed.tenant.internal.Trees;
import com.telcobright.seed.tenant.spi.TreeSource;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The wire's tree made walkable in one place. The rule broken once: a node's {@code parent} NAME dropped from the wire → the chain is
 * one tier ({@link #theChainIsWalkedByTheParentName_aNodeWithoutItIsItsOwnRoot}); a tree without a root dbName served → refused.
 */
class TreesTest {

    static final String WIRE = """
        {"dbName":"btcl","name":"btcl","parent":null,
         "children":{"res_45":{"dbName":"res_45","name":"res_45","parent":"btcl",
                      "children":{"res_45_7":{"dbName":"res_45_7","name":"res_45_7","parent":"res_45","children":{},"context":null}},
                      "context":null}},
         "context":null,"somethingTheModelDoesNotName":1}
        """;

    @Test
    void aParsedTree_hasItsIndexAndItsChains_andIgnoresWhatTheModelDoesNotName() {
        Tenant root = Trees.parse(WIRE);
        assertThat(root.getDbName()).isEqualTo("btcl");
        assertThat(root.getTenantIndex().keySet()).containsExactlyInAnyOrder("btcl", "res_45", "res_45_7");
        assertThat(root.findTenantByDbName("res_45_7").getAncestorChain().stream().map(Tenant::getDbName).toList()).containsExactly("res_45_7", "res_45", "btcl");
        assertThat(root.getAncestorChain().stream().map(Tenant::getDbName).toList()).containsExactly("btcl");
    }

    @Test
    void theChainIsWalkedByTheParentName_aNodeWithoutItIsItsOwnRoot() {
        Tenant root = Trees.parse(WIRE.replace("\"parent\":\"btcl\"", "\"parent\":null"));
        assertThat(root.getTenantIndex().keySet()).as("the index does not need the parent").containsExactlyInAnyOrder("btcl", "res_45", "res_45_7");
        assertThat(root.findTenantByDbName("res_45").getAncestorChain().stream().map(Tenant::getDbName).toList()).as("no parent name: a one-tier chain").containsExactly("res_45");
    }

    @Test
    void aTreeWithoutARootDbName_isRefused_neverServed() {
        assertThatThrownBy(() -> Trees.parse("{\"name\":\"no-db\",\"children\":{}}")).isInstanceOf(TreeSource.TreeUnavailable.class).hasMessageContaining("no root dbName");
        assertThatThrownBy(() -> Trees.parse("not json")).isInstanceOf(TreeSource.TreeUnavailable.class).hasMessageContaining("could not be parsed");
        assertThatThrownBy(() -> Trees.ready(null)).isInstanceOf(TreeSource.TreeUnavailable.class);
    }
}
