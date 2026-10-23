package com.telcobright.seed.naming.api;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** R9 · Redis, the topic's equivalent: {@code <root>:<app>:<name>}; a part that would read as another root's is refused. */
class RedisKeysTest {

    @Test
    void aKeyIsRootAppName() {
        assertThat(RedisKeys.of("link3", "ad-sphere", "quota")).isEqualTo("link3:ad-sphere:quota");
    }

    @Test
    void aPartWithAColonABlankOrASpace_isRefused() {
        assertThatThrownBy(() -> RedisKeys.of("link3:x", "ad-sphere", "quota")).hasMessageContaining("':'");
        assertThatThrownBy(() -> RedisKeys.of("link3", "", "quota")).hasMessageContaining("app is missing");
        assertThatThrownBy(() -> RedisKeys.of("link3", "ad sphere", "quota")).hasMessageContaining("a space");
    }
}
