package com.telcobright.seed.sessionflow;

import com.telcobright.seed.sessionflow.api.CdrEvent;
import com.telcobright.seed.sessionflow.internal.KafkaCdrSink;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** §C · The CDR's road per root: a per-root sink names the topic by the session's root ({@code cdr_<root>}); the key is the session id. */
class KafkaCdrSinkPerRootTest {

    @Test
    void theRootNamesTheTopic_theSessionIdIsTheKey_oneMessagePerSession() {
        MockProducer<String, String> producer = new MockProducer<>(true, new StringSerializer(), new StringSerializer());
        KafkaCdrSink sink = KafkaCdrSink.perRoot(producer, "cdr_");
        CdrEvent leaf = new CdrEvent();
        leaf.callId = "s-1";
        leaf.tenant = "res_44";
        CdrEvent root = new CdrEvent();
        root.callId = "s-1";
        root.tenant = "btcl";

        sink.publish("btcl", "s-1", List.of(leaf, root));
        sink.publish("tele2", "s-2", List.of(new CdrEvent()));
        sink.publish("s-3", List.of(new CdrEvent()));                              // no root: a per-root sink publishes nothing

        assertThat(producer.history()).extracting(r -> r.topic() + ":" + r.key()).containsExactly("cdr_btcl:s-1", "cdr_tele2:s-2");
        assertThat(producer.history().get(0).value()).contains("\"res_44\"").contains("\"btcl\"");
    }
}
