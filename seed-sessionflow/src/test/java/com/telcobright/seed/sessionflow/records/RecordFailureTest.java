package com.telcobright.seed.sessionflow.records;

import com.telcobright.seed.sessionflow.dependencies.RecordDialect;
import com.telcobright.seed.sessionflow.internal.FileSessionJournal;
import com.telcobright.seed.sessionflow.internal.FlowCounters;
import com.telcobright.seed.sessionflow.spi.SessionJournal;
import com.telcobright.seed.sessionflow.testkit.RecordingCdrSink;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Duration;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * C4 · The failure road: a store that does not take its batches → each try rolled back, tried again; after the budget the writer PARKS —
 * said once, its records counted, every session kept in the journal of the sessions in the air (nothing lost), one that journal does not
 * hold written to the park sink — and the store back → written, UN-PARKED once. A close leaves what it could not commit the same way.
 */
class RecordFailureTest {

    @TempDir Path dir;
    private RecordsLab lab;

    @AfterEach
    void close() { if (lab != null) lab.close(); }

    /** A journal with an open line for {@code id}: it holds that session until its records commit. */
    private FileSessionJournal journalHolding(String... ids) {
        FileSessionJournal journal = new FileSessionJournal(dir.resolve("in-the-air.log"));
        for (String id : ids) journal.reserved(id, 1L, "[]", new SessionJournal.Held(0, "btcl", "btcl", 44, null, "BDT", BigDecimal.ONE, id + "#L0"));
        return journal;
    }

    private static void await(String what, BooleanSupplier until) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (!until.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) throw new AssertionError("waited 10 s for: " + what);
            Thread.sleep(10);
        }
    }

    @Test
    void aFailingStore_isTriedAgain_parksOnce_losesNothing_andTheStoreBack_isWritten_unparkedOnce_C4() throws Exception {
        RecordingCdrSink park = new RecordingCdrSink();
        FileSessionJournal journal = journalHolding("f-1");
        lab = new RecordsLab(RecordDialect.POSTGRESQL, journal, b -> b.batch(10, Duration.ofMillis(30)).retries(Duration.ofMillis(10), Duration.ofMillis(40), 3).parkInto(park));
        FlowCounters counters = lab.flow.kit().counters();
        lab.db.down = true;
        lab.flow.write(RecordsLab.session("f-1", 3), null);                            // held by the journal
        lab.flow.write(RecordsLab.session("f-2", 1), null);                            // the journal has no line of it
        await("the writer parks", () -> counters.recordParks.get() == 1);
        Thread.sleep(300);                                                             // more tries fail at the cap: it is said once

        assertThat(counters.recordParks.get()).as("PARKED, said once").isEqualTo(1);
        assertThat(counters.recordsParked.get()).as("the parked batch's records").isEqualTo(4);
        assertThat(lab.calls().stream().filter("refused"::equals).count()).as("tried again and again").isGreaterThan(3);
        assertThat(journal.holds("f-1")).as("nothing lost: the session stays in the journal").isTrue();
        assertThat(park.of("f-2")).as("the one the journal does not hold went to the park sink").hasSize(1);
        assertThat(park.of("f-1")).isEmpty();
        lab.flow.write(RecordsLab.session("f-3", 2), null);                            // handed in while parked
        assertThat(park.of("f-3")).as("kept off this process alone at once").hasSize(1);

        lab.db.down = false;                                                            // the store is back
        assertThat(lab.awaitWritten()).isTrue();
        assertThat(lab.count("select count(*) from btcl.cdr")).isEqualTo(3);
        assertThat(lab.count("select count(*) from res_44.cdr")).isEqualTo(2);
        assertThat(counters.recordUnparks.get()).as("UNPARKED, said once").isEqualTo(1);
        assertThat(journal.holds("f-1")).as("its line done after the commit").isFalse();
        lab.flow.write(RecordsLab.session("f-4", 1), null);
        assertThat(lab.awaitWritten()).isTrue();
        assertThat(counters.recordUnparks.get()).as("a later commit says nothing more").isEqualTo(1);
        assertThat(counters.recordParks.get()).isEqualTo(1);
    }

    @Test
    void theClose_leavesWhatDidNotCommit_inTheJournalOrThePark_andSaysSo() throws Exception {
        RecordingCdrSink park = new RecordingCdrSink();
        FileSessionJournal journal = journalHolding("g-1");
        lab = new RecordsLab(RecordDialect.POSTGRESQL, journal, b -> b.batch(10, Duration.ofMillis(30)).retries(Duration.ofMillis(10), Duration.ofMillis(40), 10_000).parkInto(park));
        lab.db.down = true;
        lab.flow.write(RecordsLab.session("g-1", 2), null);
        lab.flow.write(RecordsLab.session("g-2", 1), null);
        await("a first try failed", () -> lab.calls().contains("refused"));
        assertThat(lab.flow.kit().counters().recordParks.get()).as("not parked: the budget is 10 000 tries").isZero();

        lab.flow.kit().records().close();

        assertThat(lab.flow.kit().counters().recordParks.get()).as("the close kept them, not a park").isZero();
        assertThat(journal.holds("g-1")).as("left in the journal for the next start").isTrue();
        assertThat(park.of("g-2")).as("left in the park sink").hasSize(1);
        assertThat(lab.count("select count(*) from btcl.cdr")).isZero();
    }
}
