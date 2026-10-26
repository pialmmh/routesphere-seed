package com.telcobright.seed.sessionflow.bootstrap;

import com.telcobright.rtc.domainmodel.LevelAdmission;
import com.telcobright.seed.sessionflow.api.RootResources;
import com.telcobright.seed.sessionflow.api.RoutesphereEngine;
import com.telcobright.seed.sessionflow.api.TierSettlement;
import com.telcobright.seed.sessionflow.base.LabCall;
import com.telcobright.seed.sessionflow.base.LabContext;
import com.telcobright.seed.sessionflow.base.LabFlow;
import com.telcobright.seed.sessionflow.base.LabSupervisor;
import com.telcobright.seed.sessionflow.dependencies.RoutesphereKit;
import com.telcobright.seed.sessionflow.spi.LedgerMaker;
import com.telcobright.seed.sessionflow.spi.LedgerPort;
import com.telcobright.seed.sessionflow.testkit.InMemoryLedger;
import com.telcobright.statewalk.channel.Delivery;
import com.telcobright.statewalk.channel.TestChannel;
import com.telcobright.statewalk.event.StatemachineEvent;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P1-E (after the report) · the engine's close closes everything the bootstrap road opened for it: each root's ledger (a switch ledger's
 * MemLedger runs threads of its own), the {@code tenant_created} listener's consumer, the CDR road's producers.
 */
class RoadCloseTest {

    /** A root's ledger that says when it was closed. */
    static final class ClosingLedger implements LedgerPort, AutoCloseable {
        final InMemoryLedger books = new InMemoryLedger();
        volatile boolean closed;
        @Override public Optional<Reservation> reserve(LevelAdmission level, BigDecimal amount, String reference) { return books.reserve(level, amount, reference); }
        @Override public TierSettlement settle(LevelAdmission level, BigDecimal charged) { return books.settle(level, charged); }
        @Override public void release(LevelAdmission level, String why) { books.release(level, why); }
        @Override public void close() { closed = true; }
    }

    @Test
    void theEnginesClose_closesTheLedgersTheListenerAndTheProducersTheRoadOpened() {
        BootstrapWorld w = new BootstrapWorld();
        List<ClosingLedger> ledgers = new CopyOnWriteArrayList<>();
        w.source.serve(BootstrapWorld.small(w.source.envelope()));
        RoutesphereKit kit = RoutesphereKit.fromBootstrap(w.source, "ad-sphere", w.makers(new LedgerMaker() {
            @Override public String kind() { return RootResources.Ledger.SWITCH; }
            @Override public LedgerPort make(RootResources root, Context context) { ClosingLedger l = new ClosingLedger(); ledgers.add(l); return l; }
        }));
        RoutesphereEngine<LabContext> engine = RoutesphereEngine.of(new LabSupervisor(new LabFlow(kit, Map.of(), List.of(), Set.of())))
            .signaling(LabCall.spec()).channel(new TestChannel<String, StatemachineEvent>("lab"), (id, frame) -> Delivery.to(LabCall.TYPE, frame)).start();

        engine.close();

        assertThat(ledgers).hasSize(2).allSatisfy(l -> assertThat(l.closed).as("each root's ledger closed").isTrue());
        assertThat(w.kafka.consumers.get("10.10.188.2:9092").closed()).as("the tenant_created listener's consumer").isTrue();
        assertThat(w.kafka.producers.get("10.10.188.2:9092").closed()).as("the CDR road's producer").isTrue();
    }
}
