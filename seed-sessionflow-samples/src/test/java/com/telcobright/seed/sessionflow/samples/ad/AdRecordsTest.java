package com.telcobright.seed.sessionflow.samples.ad;

import com.fasterxml.jackson.databind.JsonNode;
import com.telcobright.seed.sessionflow.dependencies.RecordStores;
import com.telcobright.seed.sessionflow.internal.FileSessionJournal;
import com.telcobright.seed.sessionflow.samples.bed.Crash;
import com.telcobright.seed.sessionflow.samples.scene.CrashAfterTheCommit;
import com.telcobright.seed.sessionflow.samples.scene.CrashingJournal;
import com.telcobright.seed.sessionflow.samples.scene.FlowBed;
import com.telcobright.seed.sessionflow.samples.scene.World;
import com.telcobright.seed.sessionflow.samples.scene.WorldRecords;
import com.telcobright.seed.sessionflow.spi.SessionJournal;
import org.h2.jdbcx.JdbcConnectionPool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static com.telcobright.seed.sessionflow.samples.scene.WorldRecords.column;
import static com.telcobright.seed.sessionflow.samples.scene.WorldRecords.count;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The ad sample on the records road (P1-R §C): the base writes each view's records into its root's own store — the CDR per tier in the tier's
 * schema, the summary input of the batch in each tier it wrote, the view's SDR as a row of its root's {@code ad_sdr} — in ONE transaction
 * per batch, and marks the view's line in the journal of the sessions in the air done after the commit.
 */
class AdRecordsTest {

    @TempDir Path dir;

    private final World w = new World();
    private final WorldRecords db = new WorldRecords();
    private final AdQuota quota = new AdQuota();
    private final List<AutoCloseable> open = new ArrayList<>();

    @AfterEach
    void tearDown() throws Exception {
        for (int i = open.size() - 1; i >= 0; i--) open.get(i).close();
        db.close();
    }

    private FlowBed<AdContext, AdOut, AdFrame> bed(RecordStores records, SessionJournal journal) {
        AdFlow flow = new AdFlow(w.kitOnRecords(World.settings(), records, journal), AdFlowTest.RULES, AdFlowTest.CAMPAIGNS, World.RATES, true, quota);
        FlowBed<AdContext, AdOut, AdFrame> b = new FlowBed<>(flow, AdContext::new, AdPlayback.spec(AdFlowTest.PLAY, AdPlaybackContext::new), new AdChannel(),
            AdFrames::decode);
        open.add(b);
        return b;
    }

    private RecordStores quickRetries() { return db.stores().retries(Duration.ofMillis(50), Duration.ofMillis(200)).build(); }

    private static void awaitWritten(FlowBed<AdContext, AdOut, AdFrame> b) { assertTrue(b.flow.kit().records().awaitIdle(20_000), "every batch committed"); }

    /** The (session, payer) of every cdr row of a tier. */
    private static Set<String> cdrRows(JdbcConnectionPool root, String tier) {
        return new TreeSet<>(column(root, "select ChannelCallUuid || '/' || InPartnerId from " + tier + ".cdr"));
    }

    /** The (session, payer) of every entry of a tier's summary rows, as summary-service decodes them. */
    private static List<String> summedEntries(JdbcConnectionPool root, String tier) {
        List<String> out = new ArrayList<>();
        for (List<JsonNode> row : WorldRecords.summaryRows(root, tier)) for (JsonNode e : row) out.add(e.get("Cdr").get("ChannelCallUuid").asText() + "/" + e.get("Cdr").get("InPartnerId").asInt());
        return out;
    }

    @Test
    void viewsOnTwoRootsAndOneTwoThreeTiers_oneCdrRowPerTierRecordInItsTiersSchema_theSummaryInputOfEachBatchInEachTier_theJournalEmptyAfterTheCommit_C1() throws Exception {
        FileSessionJournal journal = new FileSessionJournal(dir.resolve("in-the-air.log"));
        FlowBed<AdContext, AdOut, AdFrame> b = bed(quickRetries(), journal);
        AdFlowTest.watch(b, "v-1t", World.AD_BTCL, "zone-9");
        AdFlowTest.watch(b, "v-2t", World.AD_BTCL, "zone-701");
        AdFlowTest.watch(b, "v-3t", World.AD_BTCL, "zone-801");
        AdFlowTest.watch(b, "v-ccl", World.AD_CCL, "zone-701");
        awaitWritten(b);

        assertEquals(Set.of("v-1t/9", "v-2t/44", "v-3t/44"), cdrRows(db.btcl, "btcl"));
        assertEquals(Set.of("v-2t/701", "v-3t/7"), cdrRows(db.btcl, "res_44"));
        assertEquals(Set.of("v-3t/801"), cdrRows(db.btcl, "res_44_7"));
        assertEquals(Set.of("v-ccl/44"), cdrRows(db.ccl, "ccl"));
        assertEquals(Set.of("v-ccl/701"), cdrRows(db.ccl, "ccl_44"));
        assertEquals(List.of("30|R801|CREDITED|btcl > res_44 > res_44_7|1.00000000|1|switch:records|R801|BDT|v-3t|stamped"),
            column(db.btcl, "select ServiceGroup || '|' || OriginatingCalledNumber || '|' || HangupCause || '|' || ResellerHierarchy || '|' || InPartnerCost"
                + " || '|' || ChargingStatus || '|' || FileName || '|' || MatchedPrefixCustomer || '|' || InPartnerUom || '|' || UniqueBillId || '|'"
                + " || CASE WHEN Duration1 = DurationSec AND RoundedDuration = DurationSec THEN 'stamped' ELSE 'not stamped' END from res_44_7.cdr"),
            "the record's columns, as billing-core writes them (a stated group: the billed and rounded durations are the real one)");
        for (String tier : List.of("btcl", "res_44", "res_44_7")) {
            assertEquals(cdrRows(db.btcl, tier), new TreeSet<>(summedEntries(db.btcl, tier)), "the summary input of " + tier + " holds exactly its rows");
            assertEquals(cdrRows(db.btcl, tier).size(), summedEntries(db.btcl, tier).size(), "each once");
        }
        assertEquals(cdrRows(db.ccl, "ccl_44"), new TreeSet<>(summedEntries(db.ccl, "ccl_44")));
        long batches = b.flow.kit().counters().recordBatches.get();
        assertEquals(batches, count(db.btcl, "btcl.summary_affected") + count(db.ccl, "ccl.summary_affected"),
            "ONE summary row per batch in its root's tier (every view has a record there)");
        assertEquals(0, journal.inTheAir(), "every view's line done after its commit");
        assertEquals(4, b.flow.kit().counters().cdrPublished.get());
    }

    @Test
    void aDeathBetweenTheCommitAndTheJournalsDone_theNextStartWritesTheLeftoversAgain_noSecondCdrRow_noSecondSummaryEntry_C2() throws Exception {
        Crash crash = new Crash();
        Path file = dir.resolve("in-the-air.log");
        RecordStores nodeAsStores = RecordStores.builder().postgres(World.BTCL, new CrashAfterTheCommit(db.btcl, crash)).postgres(World.CCL, db.ccl)
            .batch(5, Duration.ofSeconds(10)).build();
        FlowBed<AdContext, AdOut, AdFrame> a = bed(nodeAsStores, new CrashingJournal(new FileSessionJournal(file), crash));
        AdFlowTest.watch(a, "v-c2", World.AD_BTCL, "zone-801");                         // three tiers, never handed over by the base
        a.frame("v-h", AdFlowTest.start(World.AD_BTCL, "zone-701"));                    // two tiers ...
        assertTrue(a.flow.handOver(a.session("v-h"), () -> true), "... handed over: its line holds them charged as reserved");
        a.frame("v-h", AdFrame.shown());
        a.frame("v-h", AdFrame.viewed(15));
        a.frame("v-h", AdFrame.credited());
        assertTrue(crash.awaitDeath(15_000), "node A died right after its commit (5 records: one batch)");

        Set<String> leafRows = cdrRows(db.btcl, "res_44_7"), resellerRows = cdrRows(db.btcl, "res_44"), rootRows = cdrRows(db.btcl, "btcl");
        int summaryRows = count(db.btcl, "btcl.summary_affected") + count(db.btcl, "res_44.summary_affected") + count(db.btcl, "res_44_7.summary_affected");
        assertEquals(Set.of("v-c2/801"), leafRows);
        FileSessionJournal journal = new FileSessionJournal(file);                    // node B opens the same journal ...
        assertEquals(2, journal.leftovers().size(), "the commit is in, the done never came: both views are left in the air");

        FlowBed<AdContext, AdOut, AdFrame> b = bed(quickRetries(), journal);           // ... and starts: the leftovers through its writer
        awaitWritten(b);

        assertEquals(leafRows, cdrRows(db.btcl, "res_44_7"), "no second cdr row");
        assertEquals(resellerRows, cdrRows(db.btcl, "res_44"));
        assertEquals(rootRows, cdrRows(db.btcl, "btcl"));
        assertEquals(summaryRows, count(db.btcl, "btcl.summary_affected") + count(db.btcl, "res_44.summary_affected")
            + count(db.btcl, "res_44_7.summary_affected"), "no second summary entry: nothing was written now");
        assertEquals(List.of("CREDITED", "CREDITED"), column(db.btcl, "select HangupCause from res_44.cdr order by ChannelCallUuid"),
            "the rows are the views' own records, not the start's stand-ins (LOST_AT_RESTART)");
        assertEquals(0, journal.inTheAir(), "node B's commit marked both lines done");
        assertEquals(0, b.flow.kit().counters().recordsWritten.get());
        assertEquals(3, b.flow.kit().counters().recordsWrittenBefore.get(), "the stand-ins: v-c2's entry tier at 0.00, v-h's two tiers as reserved");
        assertEquals(0, w.ledger.openReserves());
    }

    @Test
    void theViewsSdrIsARowInTheSameTransaction_andAFailingSdrRowRollsTheWholeBatchBack_C5() throws Exception {
        FileSessionJournal journal = new FileSessionJournal(dir.resolve("in-the-air.log"));
        FlowBed<AdContext, AdOut, AdFrame> b = bed(quickRetries(), journal);
        AdFlowTest.watch(b, "v-sdr", World.AD_BTCL, "zone-801");
        awaitWritten(b);
        assertEquals(List.of("v-sdr|81|c-81|TRUE|TRUE|15|SUCCEEDED"), column(db.btcl, "select session_id || '|' || campaign_id || '|' || creative_id || '|'"
            + " || handed_over || '|' || shown || '|' || watched_seconds || '|' || outcome from btcl.ad_sdr"));

        WorldRecords.execute(db.ccl, "DROP TABLE ccl.ad_sdr");                         // ccl's SDR row cannot be written
        AdFlowTest.watch(b, "v-bad", World.AD_CCL, "zone-701");
        Thread.sleep(500);
        assertEquals(0, count(db.ccl, "ccl.cdr") + count(db.ccl, "ccl_44.cdr"), "the failing SDR row rolled the view's CDR back");
        assertEquals(0, count(db.ccl, "ccl.summary_affected") + count(db.ccl, "ccl_44.summary_affected"), "and its summary input");
        assertEquals(1, journal.inTheAir(), "the view stays in the journal of the sessions in the air");

        WorldRecords.execute(db.ccl, "CREATE TABLE ccl.ad_sdr " + WorldRecords.AD_SDR);   // the store is right again: the batch is tried again
        awaitWritten(b);
        assertEquals(1, count(db.ccl, "ccl.ad_sdr"));
        assertEquals(Set.of("v-bad/701"), cdrRows(db.ccl, "ccl_44"));
        assertEquals(0, journal.inTheAir());
    }
}
