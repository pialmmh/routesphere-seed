package com.telcobright.seed.sessionflow.records;

import com.telcobright.seed.sessionflow.api.Candidate;
import com.telcobright.seed.sessionflow.base.Lab;
import com.telcobright.seed.sessionflow.base.LabContext;
import com.telcobright.seed.sessionflow.base.LabFacts;
import com.telcobright.seed.sessionflow.base.LabFlow;
import com.telcobright.seed.sessionflow.dependencies.RecordDialect;
import com.telcobright.seed.sessionflow.dependencies.RecordStores;
import com.telcobright.seed.sessionflow.dependencies.RoutesphereKit;
import com.telcobright.seed.sessionflow.internal.FileSessionJournal;
import com.telcobright.seed.sessionflow.spi.TenantLookup;
import com.telcobright.seed.sessionflow.testkit.InMemoryLedger;
import com.telcobright.seed.sessionflow.testkit.RecordTables;
import com.telcobright.statewalk.session.events.SessionStart;
import org.h2.jdbcx.JdbcConnectionPool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * §B · The leftovers at start go through the same writer: a session a stopped process handed over is written by the next start — its tiers
 * charged as reserved, ended LOST_AT_RESTART — in its tiers' schemas with their summary input, and its line in the journal is done after
 * the commit; a start after that has nothing left to write.
 */
class LeftoversThroughTheWriterTest {

    @TempDir Path dir;

    private final JdbcConnectionPool pool = JdbcConnectionPool.create(RecordTables.url("leftovers" + System.nanoTime(), RecordDialect.POSTGRESQL), "sa", "");
    private final InMemoryLedger ledger = new InMemoryLedger().fund("res_44_7", 3, "100.00").fund("res_44", 7, "100.00").fund("btcl", 44, "100.00");
    private final List<RoutesphereKit> kits = new ArrayList<>();

    @AfterEach
    void close() {
        kits.forEach(k -> k.records().close());
        pool.dispose();
    }

    private final RecordingDataSource store = new RecordingDataSource(pool, "leftovers-store");

    private LabFlow flowOn(FileSessionJournal journal) {
        RoutesphereKit kit = RoutesphereKit.builder().tenants(TenantLookup.of(Lab.btclTree())).identification(List.of(Lab.RULES.get(0))).ledger(ledger)
            .records(RecordStores.builder().postgres("btcl", store).retries(java.time.Duration.ofMillis(20), java.time.Duration.ofMillis(50)).build())
            .journal(journal).zone(ZoneId.of("Asia/Dhaka")).settings(Lab.settings(0)).build();
        kits.add(kit);
        return new LabFlow(kit, Map.of(), Lab.hops("carrier-a"), Set.of());
    }

    private int count(String table) throws Exception { return CdrTableSqlTest.count(pool, table); }

    @Test
    void aSessionHandedOverBeforeTheDeath_isWrittenByTheNextStartThroughTheWriter_andItsLineIsDoneAfterTheCommit() throws Exception {
        RecordTables.create(pool, RecordDialect.POSTGRESQL, false, "btcl", "res_44", "res_44_7");
        Path file = dir.resolve("in-the-air.log");
        LabFlow before = flowOn(new FileSessionJournal(file));
        LabContext ctx = new LabContext();
        ctx.facts = new LabFacts("h-1", 1L, Lab.BTCL_DOOR, 3, "8801");
        before.onStart(ctx, new SessionStart(ctx.facts));
        ctx.candidates = List.of(Candidate.SELF);
        assertThat(before.admit(ctx).accepted()).isTrue();
        assertThat(before.handOver(ctx, () -> true)).isTrue();                       // handed to its user … and the process dies

        FileSessionJournal journal = new FileSessionJournal(file);
        LabFlow next = flowOn(journal);
        store.down = true;                                                             // the start's store does not answer at first
        next.publishWhatWasLeftInTheAir();
        Thread.sleep(200);
        assertThat(journal.holds("h-1")).as("the leftover stays in the journal until ITS commit").isTrue();
        store.down = false;
        assertThat(next.kit().records().awaitIdle(10_000)).isTrue();

        assertThat(CdrTableSqlTest.row(pool, "res_44_7.cdr")).containsEntry("hangupcause", "LOST_AT_RESTART").containsEntry("channelcalluuid", "h-1");
        assertThat(count("res_44.cdr") + count("btcl.cdr")).isEqualTo(2);
        assertThat(count("res_44_7.summary_affected") + count("res_44.summary_affected") + count("btcl.summary_affected")).isEqualTo(3);
        assertThat(journal.inTheAir()).as("its line done after the commit").isZero();
        assertThat(ledger.openReserves()).as("closed as reserved").isZero();

        LabFlow third = flowOn(new FileSessionJournal(file));
        third.publishWhatWasLeftInTheAir();
        assertThat(third.kit().records().awaitIdle(10_000)).isTrue();
        assertThat(count("res_44_7.cdr")).as("nothing was left for a third start").isEqualTo(1);
        assertThat(third.kit().counters().recordBatches.get()).isZero();
    }
}
