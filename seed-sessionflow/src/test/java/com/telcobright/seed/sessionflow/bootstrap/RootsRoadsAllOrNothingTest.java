package com.telcobright.seed.sessionflow.bootstrap;

import com.telcobright.rtc.domainmodel.LevelAdmission;
import com.telcobright.seed.sessionflow.api.CdrEvent;
import com.telcobright.seed.sessionflow.api.RootResources;
import com.telcobright.seed.sessionflow.api.RoutesphereEngine;
import com.telcobright.seed.sessionflow.api.TenantCreated;
import com.telcobright.seed.sessionflow.api.TierSettlement;
import com.telcobright.seed.sessionflow.base.LabCall;
import com.telcobright.seed.sessionflow.base.LabContext;
import com.telcobright.seed.sessionflow.base.LabFlow;
import com.telcobright.seed.sessionflow.base.LabSupervisor;
import com.telcobright.seed.sessionflow.dependencies.RoutesphereKit;
import com.telcobright.seed.sessionflow.dependencies.SessionJournals;
import com.telcobright.seed.sessionflow.internal.BootstrapRoad;
import com.telcobright.seed.sessionflow.spi.LedgerMaker;
import com.telcobright.seed.sessionflow.spi.LedgerPort;
import com.telcobright.seed.sessionflow.testkit.InMemoryLedger;
import com.telcobright.seed.sessionflow.testkit.MapBootstrapSource;
import com.telcobright.statewalk.channel.Delivery;
import com.telcobright.statewalk.channel.TestChannel;
import com.telcobright.statewalk.event.StatemachineEvent;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P1-H item 1 · a root's roads are all-or-nothing (the architect rates it a money risk). A failure while serving a root's roads closes what
 * THAT try made for the root — its ledger first, then its CDR road and its journal —, undoes the tree's serve, leaves the deployment and the
 * doors as they were, and the root goes pending; the next try serves it with ONE ledger. A try never opens a second ledger for a root while
 * one is open: two MemLedgers over one switch database and one directory would be two write-behind consumers for the same money.
 */
class RootsRoadsAllOrNothingTest {

    /** A root's ledger that says whether it is open; its close can be scripted to fail. */
    static final class CountedLedger implements LedgerPort, AutoCloseable {
        final InMemoryLedger money = new InMemoryLedger();
        final AtomicInteger closeRefusals;
        volatile boolean open = true;
        CountedLedger(AtomicInteger closeRefusals) { this.closeRefusals = closeRefusals; }
        @Override public Optional<Reservation> reserve(LevelAdmission level, BigDecimal amount, String reference) { return money.reserve(level, amount, reference); }
        @Override public TierSettlement settle(LevelAdmission level, BigDecimal charged) { return money.settle(level, charged); }
        @Override public void release(LevelAdmission level, String why) { money.release(level, why); }
        @Override public void close() {
            if (closeRefusals.getAndUpdate(n -> Math.max(0, n - 1)) > 0) throw new IllegalStateException("the MemLedger did not shut down (scripted)");
            open = false;
        }
    }

    private static final String KAFKA = "10.10.188.2:9092";

    private final BootstrapWorld w = new BootstrapWorld();
    private final Map<String, Object> sample = PendingRootTest.retryEverySecond(BootstrapWorld.small(MapBootstrapSource.read("/bootstrap/deployment.json")));
    private final Map<String, List<CountedLedger>> made = new ConcurrentHashMap<>();
    private final AtomicInteger journalRefusals = new AtomicInteger(), closeRefusals = new AtomicInteger();
    private RoutesphereEngine<LabContext> engine;
    private RoutesphereKit kit;

    @AfterEach
    void stop() { if (engine != null) engine.close(); }

    private void upOnBtclAlone() {
        w.source.serve(BootstrapWorld.servingOnly(sample, "btcl"));
        kit = RoutesphereKit.fromBootstrap(w.source, "ad-sphere", w.makers(countedLedgers(), dir -> {
            boolean link3 = dir.toString().endsWith("link3");
            if (link3 && journalRefusals.getAndUpdate(n -> Math.max(0, n - 1)) > 0) throw new IllegalStateException("link3's journal directory cannot be opened (scripted)");
            return SessionJournals.none();
        }));
        engine = RoutesphereEngine.of(new LabSupervisor(new LabFlow(kit, Map.of(), List.of(), Set.of()))).signaling(LabCall.spec())
            .channel(new TestChannel<String, StatemachineEvent>("lab"), (id, frame) -> Delivery.to(LabCall.TYPE, frame)).start();
    }

    private LedgerMaker countedLedgers() {
        return new LedgerMaker() {
            @Override public String kind() { return RootResources.Ledger.SWITCH; }
            @Override public LedgerPort make(RootResources root, Context context) {
                CountedLedger ledger = new CountedLedger(closeRefusals);
                made.computeIfAbsent(root.root(), r -> new CopyOnWriteArrayList<>()).add(ledger);
                return ledger;
            }
        };
    }

    private long openLedgersOf(String root) { return made.getOrDefault(root, List.of()).stream().filter(l -> l.open).count(); }

    private BootstrapRoad road() { return kit.bootstrapRoad().orElseThrow(); }

    private List<String> served() { return kit.deployment().orElseThrow().roots(); }

    private static TenantCreated rootCreated(String root) { return new TenantCreated(root, root, null, null, "root", List.of("ad-sphere"), "2026-10-24T01:20:00Z"); }

    private static CdrEvent recordOf(String root, String id) {
        CdrEvent e = new CdrEvent();
        e.callId = id;
        e.tenant = root;
        return e;
    }

    @Test
    void roadsFailingHalfWay_onTheEventsRoad_leaveNothingServed_theRootPending_andTheNextTryServesItWithOneLedger() {
        upOnBtclAlone();
        journalRefusals.set(1);
        w.source.serve(sample);                                                            // the document lists link3

        road().onTenantCreated(rootCreated("link3"));                                      // its journal cannot be opened, this once

        assertThat(served()).as("the deployment as it was").containsExactly("btcl");
        assertThat(w.trees.roots()).as("the tree's serve undone").containsExactly("btcl");
        assertThat(openLedgersOf("link3")).as("the ledger this try made, closed again").isZero();
        assertThat(road().pendingRoots()).containsExactly("link3");
        await(() -> served().contains("link3") && road().pendingRoots().isEmpty());       // the next try
        assertThat(openLedgersOf("link3")).as("ONE ledger over link3's switch database").isEqualTo(1);
        assertThat(made.get("link3")).as("two made in all, the first closed").hasSize(2);
    }

    @Test
    void aTryOfThePendingRoots_neverLeavesTwoLedgersOpen() {
        upOnBtclAlone();
        road().onTenantCreated(rootCreated("link3"));                                      // not listed yet: pending
        journalRefusals.set(1);
        w.source.serve(sample);                                                            // listed: a try makes its roads, its journal fails once

        await(() -> served().contains("link3"));

        assertThat(openLedgersOf("link3")).isEqualTo(1);
        assertThat(made.get("link3")).hasSize(2);
    }

    @Test
    void aLedgerThatDidNotClose_isClosedBeforeAnotherIsOpened_neverTwoOpenAtOnce() {
        upOnBtclAlone();
        journalRefusals.set(1);
        closeRefusals.set(1);                                                              // the failed try's ledger does not shut down at once
        w.source.serve(sample);

        road().onTenantCreated(rootCreated("link3"));
        assertThat(openLedgersOf("link3")).as("still open: its close failed").isEqualTo(1);

        await(() -> served().contains("link3"));
        assertThat(openLedgersOf("link3")).as("the stuck one closed first, then one more").isEqualTo(1);
        assertThat(made.get("link3")).hasSize(2);
    }

    @Test
    void theUndo_leavesTheOtherRootsRoadsAsTheyWere() {
        upOnBtclAlone();
        journalRefusals.set(1);
        w.source.serve(sample);
        road().onTenantCreated(rootCreated("link3"));

        kit.cdr().publish("link3", "v-1", List.of(recordOf("link3", "v-1")));            // link3 has no road now: published nowhere
        kit.cdr().publish("btcl", "v-2", List.of(recordOf("btcl", "v-2")));              // btcl's road as it was

        assertThat(w.kafka.producers.get(KAFKA).history()).extracting(ProducerRecord::topic).containsExactly("cdr_btcl");
        assertThat(w.kafka.producers.get(KAFKA).closed()).as("the producer btcl shares, not closed by link3's undo").isFalse();
        assertThat(openLedgersOf("btcl")).isEqualTo(1);
    }

    @Test
    void aStartRefusedHalfWay_closesWhatItOpened() {
        journalRefusals.set(Integer.MAX_VALUE);                                            // link3's journal never opens
        w.source.serve(sample);                                                            // the start serves btcl and link3

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> RoutesphereKit.fromBootstrap(w.source, "ad-sphere", w.makers(countedLedgers(), dir -> {
            if (dir.toString().endsWith("link3") && journalRefusals.get() > 0) throw new IllegalStateException("link3's journal directory cannot be opened (scripted)");
            return SessionJournals.none();
        }))).hasMessageContaining("link3's journal directory");

        assertThat(openLedgersOf("btcl")).as("btcl's ledger, opened before the refusal, closed").isZero();
        assertThat(openLedgersOf("link3")).isZero();
    }

    private static void await(BooleanSupplier condition) {
        long until = System.currentTimeMillis() + 30_000;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < until) {
            try { Thread.sleep(20); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
        }
        assertThat(condition.getAsBoolean()).as("within 30 s").isTrue();
    }
}
