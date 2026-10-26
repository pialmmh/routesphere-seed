package com.telcobright.seed.sessionflow.base;

import com.telcobright.seed.sessionflow.api.RoutesphereEngine;
import com.telcobright.seed.sessionflow.dependencies.RoutesphereKit;
import com.telcobright.seed.sessionflow.dependencies.SessionJournals;
import com.telcobright.seed.sessionflow.spi.TenantLookup;
import com.telcobright.seed.sessionflow.testkit.InMemoryLedger;
import com.telcobright.seed.sessionflow.testkit.RecordingCdrSink;
import com.telcobright.statewalk.channel.TestChannel;
import com.telcobright.statewalk.event.StatemachineEvent;
import com.telcobright.statewalk.persistence.InMemoryPersistenceProvider;
import com.telcobright.statewalk.persistence.PersistenceMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * P1-F3 · ONE recovery road per lane: the journal of the sessions in the air (R1-6: the next start closes a dead process's sessions) OR
 * statewalk's store in RESUME (the next node restores them and ends them itself) — never both, or a session in both is closed by the
 * journal (charged as reserved, a LOST_AT_RESTART record) and then restored and ended again. The engine refuses the pair at start; the
 * journal beside a store that only SAVEs (or none) stays allowed.
 */
class OneRecoveryRoadTest {

    @TempDir Path dir;

    private RoutesphereEngine.Builder<LabContext> engine(boolean journal) {
        RoutesphereKit.Builder kit = RoutesphereKit.builder().tenants(TenantLookup.of(Lab.btclTree())).identification(List.of(Lab.RULES.get(0)))
            .ledger(new InMemoryLedger()).cdr(new RecordingCdrSink()).zone(ZoneId.of("Asia/Dhaka")).settings(Lab.settings(0));
        if (journal) kit.journal(SessionJournals.file(dir.resolve("in-the-air.log")));
        LabFlow flow = new LabFlow(kit.build(), Map.of(), Lab.hops("carrier-a"), Set.of());
        return RoutesphereEngine.of(new LabSupervisor(flow)).signaling(LabCall.spec()).channel(new TestChannel<String, StatemachineEvent>("lab"), Lab::decode);
    }

    @Test
    void theJournalTogetherWithAResumingStore_isRefusedAtStart_inWordsThatNameBoth() {
        assertThatThrownBy(() -> engine(true).persistence(PersistenceMode.RESUME, InMemoryPersistenceProvider::new).start())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("journal").hasMessageContaining("RESUME").hasMessageContaining("one recovery road");
    }

    @Test
    void theJournalBesideAStoreThatOnlySaves_orNone_andAResumingStoreAlone_start() {
        for (RoutesphereEngine<LabContext> e : List.of(
                engine(true).persistence(PersistenceMode.SAVE, InMemoryPersistenceProvider::new).start(),
                engine(true).start(),
                engine(false).persistence(PersistenceMode.RESUME, InMemoryPersistenceProvider::new).start())) {
            assertThat(e.registry()).isNotNull();
            e.close();
        }
    }
}
