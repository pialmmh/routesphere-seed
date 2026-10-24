package com.telcobright.seed.campaign.writer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P1-M item 1 · the campaign store's writer says a change it puts aside, and a journal line it cannot read, on ONE line: a task id or a
 * line carrying a line break (or U+2028, or a terminal's escape) is written escaped, through seed-logtext's {@code LogText}.
 */
@org.junit.jupiter.api.Timeout(60)
class NoForgedStoreLogLinesTest {

    @TempDir Path dir;
    private final QueuedCampaignStoreTest.FakeStore store = new QueuedCampaignStoreTest.FakeStore();
    private QueuedCampaignStore queued;
    private final PrintStream realErr = System.err;
    private final ByteArrayOutputStream log = new ByteArrayOutputStream();

    @AfterEach
    void stop() {
        System.setErr(realErr);
        if (queued != null) queued.close();
    }

    private void captureTheLog() { System.setErr(new PrintStream(log, true, StandardCharsets.UTF_8)); }

    private String theLog() { return log.toString(StandardCharsets.UTF_8); }

    @Test
    void aChangeTheStoreRefuses_isSaidOnOneLine_itsTaskIdEscaped() throws Exception {
        captureTheLog();
        queued = QueuedCampaignStore.open("wroot", store, QueuedCampaignStoreTest.quick(), dir, null);

        queued.insertTask(QueuedCampaignStoreTest.task("bad\nFORGED-CHANGE"));
        assertThat(queued.awaitWritten(10_000)).isTrue();

        assertThat(theLog().lines()).noneMatch(l -> l.startsWith("FORGED"));
        assertThat(theLog().lines()).filteredOn(l -> l.contains("refuses this change by its own rules")).singleElement().asString()
            .contains("bad\\nFORGED-CHANGE");
    }

    @Test
    void aJournalLineThatIsNotAChange_isSaidEscaped() throws Exception {
        Files.writeString(dir.resolve("campaign-store-wroot.jsonl"), "not a change  FORGED-JOURNAL \u001b[2K\n");
        captureTheLog();

        queued = QueuedCampaignStore.open("wroot", store, QueuedCampaignStoreTest.quick(), dir, null);

        assertThat(theLog()).doesNotContain(" ").doesNotContain("\u001b");
        assertThat(theLog().lines()).filteredOn(l -> l.contains("is not a change and is passed over")).singleElement().asString()
            .contains("\\u2028FORGED-JOURNAL \\u001b[2K");
    }
}
