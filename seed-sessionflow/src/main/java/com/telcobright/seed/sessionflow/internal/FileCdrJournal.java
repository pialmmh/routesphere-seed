package com.telcobright.seed.sessionflow.internal;

import com.telcobright.seed.sessionflow.api.CdrEvent;
import com.telcobright.seed.sessionflow.spi.CdrSink;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * The switch's local journal of its CDRs: one file per minute, one line per call — the call id, a tab, and exactly the
 * JSON the Kafka message carries. It is the per-minute file of the call switch, kept so that a minute Kafka never took
 * can be sent again ({@link #replay}). Billing takes a call once per tier, so sending a file twice is safe.
 */
public final class FileCdrJournal implements CdrSink {

    private static final Logger log = LoggerFactory.getLogger(FileCdrJournal.class);
    private static final DateTimeFormatter MINUTE = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm");

    private final Path directory;
    private final Clock clock;
    private final ZoneId zone;

    private String openMinute;
    private BufferedWriter writer;

    public FileCdrJournal(Path directory, Clock clock, ZoneId zone) {
        this.directory = directory;
        this.clock = clock;
        this.zone = zone;
        try {
            Files.createDirectories(directory);
        } catch (IOException e) {
            throw new UncheckedIOException("the CDR journal directory " + directory + " cannot be made", e);
        }
        log.info("CDR journal: {} (one file per minute)", directory);
    }

    @Override
    public synchronized void publish(String callId, List<CdrEvent> tiers) {
        try {
            BufferedWriter out = writerOfThisMinute();
            out.write(callId);
            out.write('\t');
            out.write(CdrJson.ofCall(tiers));
            out.newLine();
            out.flush();
        } catch (IOException | RuntimeException e) {
            log.error("the CDR of call {} was NOT written to the journal {}: {}", callId, directory, e.toString());
        }
    }

    @Override
    public synchronized void close() {
        closeWriter();
    }

    /** The journal file of one minute ({@code yyyyMMdd-HHmm}). */
    public Path fileOfMinute(String minute) { return directory.resolve("cdr-" + minute + ".jsonl"); }

    /**
     * Send every call of one journal file again.
     *
     * @return how many calls were sent
     */
    public static int replay(Path journalFile, CdrSink to) throws IOException {
        int sent = 0;
        for (String line : Files.readAllLines(journalFile, StandardCharsets.UTF_8)) {
            int tab = line.indexOf('\t');
            if (tab <= 0) continue;
            to.publish(line.substring(0, tab), CdrJson.toCall(line.substring(tab + 1)));
            sent++;
        }
        return sent;
    }

    private BufferedWriter writerOfThisMinute() throws IOException {
        String minute = MINUTE.format(clock.instant().atZone(zone));
        if (!minute.equals(openMinute)) {
            closeWriter();
            writer = Files.newBufferedWriter(fileOfMinute(minute), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            openMinute = minute;
        }
        return writer;
    }

    private void closeWriter() {
        if (writer == null) return;
        try {
            writer.close();
        } catch (IOException e) {
            log.warn("the CDR journal file of minute {} did not close cleanly: {}", openMinute, e.toString());
        }
        writer = null;
        openMinute = null;
    }
}
