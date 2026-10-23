package com.telcobright.seed.sessionflow.base;

import com.telcobright.seed.sessionflow.api.Candidate;
import com.telcobright.seed.sessionflow.api.CdrEvent;
import com.telcobright.seed.sessionflow.api.SessionCause;
import com.telcobright.seed.sessionflow.dependencies.RoutesphereKit;
import com.telcobright.seed.sessionflow.dependencies.SessionJournals;
import com.telcobright.seed.sessionflow.internal.KafkaCdrSink;
import com.telcobright.seed.sessionflow.spi.CdrSink;
import com.telcobright.seed.sessionflow.spi.TenantLookup;
import com.telcobright.seed.sessionflow.testkit.InMemoryLedger;
import com.telcobright.seed.sessionflow.testkit.RecordingCdrSink;
import com.telcobright.statewalk.session.events.SessionStart;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R1-6 on the new base — the journal of the sessions in the air (kept from the 3.x base; a lane without statewalk's store still loses
 * nothing): every reserve is journaled as it is held (F9); a process that dies before the hand-over leaves reserves the next start gives
 * back (one record at 0.00 on the entry tier, LOST_AT_RESTART); one that dies after the hand-over leaves a session the next start charges
 * as reserved, closing each reserve as settled (A2). On the Kafka road the leftovers take their root's topic ({@code cdr_<root>}, P1-D §5).
 */
class SessionsInTheAirTest {

    @TempDir Path dir;

    private final InMemoryLedger ledger = new InMemoryLedger()
        .fund("res_44_7", 3, "100.00").fund("res_44", 7, "100.00").fund("btcl", 44, "100.00");

    private LabFlow flowOn(Path journal, CdrSink cdr) {
        RoutesphereKit kit = RoutesphereKit.builder().tenants(TenantLookup.of(Lab.btclTree())).identification(List.of(Lab.RULES.get(0)))
            .ledger(ledger).cdr(cdr).journal(SessionJournals.file(journal)).zone(ZoneId.of("Asia/Dhaka")).settings(Lab.settings(0)).build();
        return new LabFlow(kit, Map.of(), Lab.hops("carrier-a"), Set.of());
    }

    private static LabContext admitted(LabFlow flow, String id) {
        LabContext ctx = new LabContext();
        ctx.facts = new LabFacts(id, 1L, Lab.BTCL_DOOR, 3, "8801");
        flow.onStart(ctx, new SessionStart(ctx.facts));
        ctx.candidates = List.of(Candidate.SELF);
        assertThat(flow.admit(ctx).accepted()).isTrue();
        return ctx;
    }

    @Test
    void aDeathBeforeTheHandOver_theNextStartGivesEveryReserveBack_andWritesOneRecordAtZero() {
        Path journal = dir.resolve("in-the-air.log");
        admitted(flowOn(journal, new RecordingCdrSink()), "a-1");                    // three tiers reserved … and the process dies
        assertThat(ledger.openReserves()).isEqualTo(3);

        RecordingCdrSink published = new RecordingCdrSink();
        flowOn(journal, published).publishWhatWasLeftInTheAir();                      // the next start

        assertThat(ledger.openReserves()).isZero();
        assertThat(ledger.balanceOf("res_44_7", 3)).isEqualByComparingTo("100.00");
        assertThat(ledger.balanceOf("btcl", 44)).isEqualByComparingTo("100.00");
        List<CdrEvent> records = published.of("a-1").get(0).tiers();
        assertThat(records).extracting(r -> r.tenant + ":" + r.hangupCause + ":" + r.inPartnerCost.stripTrailingZeros().toPlainString())
            .containsExactly("res_44_7:" + SessionCause.LOST_AT_RESTART + ":0");
    }

    @Test
    void aDeathAfterTheHandOver_theNextStartChargesWhatWasReserved_andClosesEveryReserve() {
        Path journal = dir.resolve("in-the-air.log");
        LabFlow before = flowOn(journal, new RecordingCdrSink());
        LabContext ctx = admitted(before, "h-1");
        assertThat(before.handOver(ctx, () -> true)).isTrue();                        // handed to its user … and the process dies

        RecordingCdrSink published = new RecordingCdrSink();
        flowOn(journal, published).publishWhatWasLeftInTheAir();

        assertThat(ledger.openReserves()).isZero();
        assertThat(ledger.balanceOf("res_44_7", 3)).as("charged as reserved").isEqualByComparingTo("99.00");
        assertThat(published.of("h-1").get(0).tiers()).extracting(r -> r.tenant + ":" + r.hangupCause + ":" + r.inPartnerCost.stripTrailingZeros().toPlainString())
            .containsExactly("res_44_7:LOST_AT_RESTART:1", "res_44:LOST_AT_RESTART:1", "btcl:LOST_AT_RESTART:1");
    }

    @Test
    void theLeftoversOfADeadProcess_reachTheirRootsTopic_onAPerRootKafkaSink_bothKinds() {
        Path journal = dir.resolve("in-the-air.log");
        LabFlow before = flowOn(journal, new RecordingCdrSink());
        admitted(before, "a-2");                                                          // three tiers reserved, never handed over …
        LabContext handedOver = admitted(before, "h-2");
        assertThat(before.handOver(handedOver, () -> true)).isTrue();                    // … and one handed over; then the process dies

        MockProducer<String, String> kafka = new MockProducer<>(true, new StringSerializer(), new StringSerializer());
        flowOn(journal, KafkaCdrSink.perRoot(kafka, "cdr_")).publishWhatWasLeftInTheAir();

        assertThat(kafka.history()).extracting(r -> r.topic() + ":" + r.key())
            .as("each leftover on its ROOT's topic — never refused as naming no root, never the leaf's tier")
            .containsExactlyInAnyOrder("cdr_btcl:a-2", "cdr_btcl:h-2");
    }
}
