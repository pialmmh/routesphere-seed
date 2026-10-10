package com.telcobright.seed.sessionflow;

import com.telcobright.seed.sessionflow.api.IdentificationRule;
import com.telcobright.seed.sessionflow.api.RequestFacts;
import com.telcobright.seed.sessionflow.dependencies.IdentificationRules;
import com.telcobright.seed.sessionflow.dependencies.TenantResolvers;
import com.telcobright.seed.sessionflow.spi.TenantResolver;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ARCH-0077-A item 8 — the product's answer to "which tenant did this request come in for?", from the facade's identification rules:
 * an exact match on (kind, match); no match is EMPTY, never a default; two rules on one (kind, match) are refused in words; the bootstrap
 * reader tolerates the key's absence.
 */
class TenantResolverTest {

    private final List<IdentificationRule> rules = List.of(
        new IdentificationRule("listen", "10.10.191.5:1812", "btcl"),
        new IdentificationRule("listen", "10.10.191.6:1812", "tele2"),
        new IdentificationRule("esl", "127.0.0.1:8021", "btcl"));
    private final TenantResolver resolver = TenantResolvers.ofRules(rules);

    @Test
    void aListenRuleResolves_anEslRuleToo() {
        assertThat(resolver.tenantOf(RequestFacts.listen("10.10.191.5", 1812))).contains("btcl");
        assertThat(resolver.tenantOf(RequestFacts.listen("10.10.191.6", 1812))).contains("tele2");
        assertThat(resolver.tenantOf(RequestFacts.esl("127.0.0.1", 8021))).contains("btcl");
    }

    @Test
    void anUnknownAddress_isEmpty_neverADefault() {
        assertThat(resolver.tenantOf(RequestFacts.listen("10.10.191.9", 1812))).isEmpty();
        assertThat(resolver.tenantOf(RequestFacts.listen("10.10.191.5", 1813))).as("the port is part of the match").isEmpty();
        assertThat(resolver.tenantOf(new RequestFacts("esl", "10.10.191.5:1812"))).as("the kind is part of the match").isEmpty();
        assertThat(TenantResolvers.ofRules(List.of(new IdentificationRule("listen", "1.2.3.4:1812", "only"))).tenantOf(RequestFacts.listen("9.9.9.9", 1812)))
            .as("one rule is not a default").isEmpty();
        assertThat(TenantResolvers.none().tenantOf(RequestFacts.listen("10.10.191.5", 1812))).isEmpty();
    }

    @Test
    void twoRulesOnOneKindAndMatch_areRefusedInWords() {
        List<IdentificationRule> twice = List.of(
            new IdentificationRule("listen", "10.10.191.5:1812", "btcl"),
            new IdentificationRule("listen", "10.10.191.5:1812", "tele2"));

        assertThatThrownBy(() -> TenantResolvers.ofRules(twice))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("listen 10.10.191.5:1812").hasMessageContaining("btcl").hasMessageContaining("tele2");
    }

    @Test
    void theBootstrapReader_withTheKey_andWithout() {
        Map<String, Object> bootstrap = Map.of("name", "wifi-sphere", IdentificationRules.KEY, List.of(
            Map.of("kind", "listen", "match", "10.10.191.5:1812", "tenant", "btcl"),
            Map.of("kind", "esl", "match", "127.0.0.1:8021", "tenant", "btcl")));

        assertThat(IdentificationRules.fromBootstrap(bootstrap)).containsExactly(
            new IdentificationRule("listen", "10.10.191.5:1812", "btcl"), new IdentificationRule("esl", "127.0.0.1:8021", "btcl"));
        assertThat(IdentificationRules.fromBootstrap(Map.of("name", "wifi-sphere"))).as("no key = no rules").isEmpty();
        assertThat(IdentificationRules.fromBootstrap(null)).isEmpty();
        assertThatThrownBy(() -> IdentificationRules.fromBootstrap(Map.of(IdentificationRules.KEY, List.of(Map.of("kind", "listen", "match", "1.2.3.4:1812")))))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("names no tenant");
        assertThatThrownBy(() -> IdentificationRules.fromBootstrap(Map.of(IdentificationRules.KEY, "listen")))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("not a list");
    }

    @Test
    void theFactsAndTheRules_refuseBlanks() {
        assertThatThrownBy(() -> new RequestFacts("listen", " ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new IdentificationRule("", "x", "t")).isInstanceOf(IllegalArgumentException.class);
        assertThat(new IdentificationRule("listen", "a:1", "t").toString()).isEqualTo("listen a:1 → t");
    }
}
