package com.telcobright.seed.naming.api;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** R9 · the platform topics ({@code <name>_<root>}, unchanged for billing-core) and an application's own ({@code <app>_<in|out>_<name>_<root>}). */
class TopicsTest {

    @Test
    void thePlatformTopics_areNameUnderscoreRoot() {
        assertThat(Topics.platform("cdr", "btcl")).isEqualTo("cdr_btcl");
        assertThat(Topics.platform("cdr_summary_ping", "link3")).isEqualTo("cdr_summary_ping_link3");
        assertThat(Topics.platform("config_event_loader", "link3")).isEqualTo("config_event_loader_link3");
        assertThat(Topics.TENANT_CREATED).isEqualTo("tenant_created");
    }

    @Test
    void aServiceCategorysPlatformTopic_isNameUnderscoreCategoryUnderscoreRoot() {
        assertThat(Topics.platform("cdr", "ad", "btcl")).isEqualTo("cdr_ad_btcl");
        assertThat(Topics.platform("cdr", "wifi", "link3")).isEqualTo("cdr_wifi_link3");
        assertThatThrownBy(() -> Topics.platform("cdr", " ", "btcl")).hasMessageContaining("category is missing");
        assertThatThrownBy(() -> Topics.platform("cdr", "a.d", "btcl")).hasMessageContaining("'.'");
    }

    @Test
    void anApplicationsOwnTopic_carriesItsDirection_soPublishAndSubscribeNeverShareOne() {
        assertThat(Topics.app("ad-sphere", Direction.IN, "requests", "link3")).isEqualTo("ad-sphere_in_requests_link3");
        assertThat(Topics.app("ad-sphere", Direction.OUT, "requests", "link3")).isEqualTo("ad-sphere_out_requests_link3");
        assertThatThrownBy(() -> Topics.app("ad-sphere", null, "requests", "link3")).hasMessageContaining("direction");
    }

    @Test
    void aNameKafkaWouldRefuse_orThatCollides_isRefusedInWords() {
        assertThatThrownBy(() -> Topics.platform("cdr.v2", "btcl")).hasMessageContaining("'.'");
        assertThatThrownBy(() -> Topics.platform("cdr", "bt cl")).hasMessageContaining("a-z A-Z 0-9 _ -");
        assertThatThrownBy(() -> Topics.platform("cdr", "")).hasMessageContaining("root is missing");
        assertThatThrownBy(() -> Topics.app(" ", Direction.IN, "x", "btcl")).hasMessageContaining("app is missing");
        String longRoot = "r".repeat(Topics.MAX_LENGTH);
        assertThatThrownBy(() -> Topics.platform("cdr", longRoot)).hasMessageContaining("249 at most");
        assertThat(Topics.checked("x".repeat(Topics.MAX_LENGTH))).hasSize(249);
        assertThatThrownBy(() -> Topics.checked("x".repeat(Topics.MAX_LENGTH + 1))).as("one more than Kafka takes").hasMessageContaining("250 characters");
    }
}
