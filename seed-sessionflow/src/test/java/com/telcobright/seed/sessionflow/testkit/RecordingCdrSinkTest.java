package com.telcobright.seed.sessionflow.testkit;

import com.telcobright.seed.sessionflow.api.CdrEvent;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P1 §L.1: billing is idempotent on (tenant, channelCallUuid). A session ended twice — restored from an older row after a crash
 * between its settle and its final row — offers its CDR again; the sink's {@code records()} keeps one per key, {@code published()}
 * shows both offers.
 */
class RecordingCdrSinkTest {

    private static CdrEvent tier(String tenant, String uuid) {
        CdrEvent e = new CdrEvent();
        e.tenant = tenant;
        e.channelCallUuid = uuid;
        return e;
    }

    @Test
    void a_second_offer_of_the_same_session_is_kept_once_per_tier_and_counted() {
        RecordingCdrSink sink = new RecordingCdrSink();
        sink.publish("s-1", List.of(tier("res_44", "s-1"), tier("btcl", "s-1")));
        sink.publish("s-2", List.of(tier("btcl", "s-2")));
        sink.publish("s-1", List.of(tier("res_44", "s-1"), tier("btcl", "s-1")));      // the restored session ends again

        assertThat(sink.count()).isEqualTo(3);
        assertThat(sink.of("s-1")).hasSize(2);
        assertThat(sink.records()).extracting(e -> e.tenant + "/" + e.channelCallUuid)
            .containsExactly("res_44/s-1", "btcl/s-1", "btcl/s-2");
        assertThat(sink.duplicates()).isEqualTo(2);
    }

    @Test
    void a_record_without_its_own_uuid_is_keyed_by_the_call_id() {
        RecordingCdrSink sink = new RecordingCdrSink();
        sink.publish("s-9", List.of(tier("btcl", null)));
        sink.publish("s-9", List.of(tier("btcl", null)));
        assertThat(sink.records()).hasSize(1);
        assertThat(RecordingCdrSink.billingKeyOf("s-9", tier("btcl", null))).isEqualTo("btcl|s-9");
    }
}
