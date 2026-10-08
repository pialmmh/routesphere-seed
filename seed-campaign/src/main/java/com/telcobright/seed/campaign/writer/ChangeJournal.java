package com.telcobright.seed.campaign.writer;

import com.telcobright.seed.campaign.spi.StoreChange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * The changes of ONE store on disk — one line per change, in the order they were told (ARCH-0065 F10: EVERY change is a line, written
 * before it is queued, so a death of the process loses nothing the service told):
 *
 * <ul>
 *   <li>{@code <name>.jsonl} — the journal: every change told since the file was last emptied; {@code <name>.applied} counts its lines
 *       that are in the store (the writer marks them, batch by batch). A start replays the lines after that count; the file is emptied
 *       when everything in it is applied.</li>
 *   <li>{@code <name>.replaying.jsonl} — the journal while its lines are being written into the store: it was the journal, set aside
 *       in one rename so that new lines start a new journal; {@code <name>.replaying.done} counts its lines already in the store.</li>
 *   <li>{@code <name>.rejected.jsonl} — a change the store refused by its own rules, with why: never written again by itself; an
 *       officer reads it.</li>
 * </ul>
 * A start reads the replaying file after its count, then the journal, and writes them before the first task.
 * A line that cannot be read is skipped and said once per file.
 */
final class ChangeJournal {

    private static final Logger log = LoggerFactory.getLogger(ChangeJournal.class);

    private final Path main;
    private final Path replaying;
    private final Path done;
    private final Path applied;
    private final Path rejected;
    private final ChangeCodec codec = new ChangeCodec();

    ChangeJournal(Path dir, String name) {
        this.main = dir.resolve(name + ".jsonl");
        this.replaying = dir.resolve(name + ".replaying.jsonl");
        this.done = dir.resolve(name + ".replaying.done");
        this.applied = dir.resolve(name + ".applied");
        this.rejected = dir.resolve(name + ".rejected.jsonl");
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new UncheckedIOException("the journal's directory " + dir + " cannot be made", e);
        }
    }

    Path file() { return main; }

    Path rejectedFile() { return rejected; }

    /** One more line at the end of the journal. */
    void append(StoreChange change) {
        write(main, codec.write(change) + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    /** Changes OLDER than every line of the journal: they go before those lines (a stop: what was still queued). */
    void prepend(List<StoreChange> older) {
        if (older.isEmpty()) return;
        Path whole = main.resolveSibling(main.getFileName() + ".tmp");
        try (BufferedWriter out = Files.newBufferedWriter(whole, StandardCharsets.UTF_8)) {
            for (StoreChange change : older) { out.write(codec.write(change)); out.write('\n'); }
            if (Files.exists(main)) {
                try (BufferedReader in = Files.newBufferedReader(main, StandardCharsets.UTF_8)) {
                    for (String line = in.readLine(); line != null; line = in.readLine()) { out.write(line); out.write('\n'); }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("the journal " + main + " could not be written", e);
        }
        move(whole, main);
    }

    /** A change the store will not take: kept with why, for an officer. */
    void reject(StoreChange change, String why) {
        String line = codec.write(change);
        write(rejected, "{\"why\":" + quoted(why) + ",\"change\":" + line + "}\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    /** Is there anything a stopped process left? */
    boolean somethingIsLeft() { return Files.exists(replaying) || Files.exists(main); }

    /** Does the journal hold a line now? */
    boolean hasLines() { return Files.exists(main); }

    // ── F10: the journal's lines are the changes told; the writer marks how many are in the store ──

    /** The lines of the journal after the first {@code skip}: what a start replays, what the writer reads when the queue could not hold a change. */
    Lines mainLines(long skip) { return new Lines(main, skip); }

    /** That many lines of the journal, from its first, are in the store (one small file, written whole and renamed into place). */
    void applied(long lines) {
        Path next = applied.resolveSibling(applied.getFileName() + ".tmp");
        write(next, Long.toString(lines), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        move(next, applied);
    }

    /** How many lines of the journal a stopped process had marked as in the store (0 = none, or the mark cannot be read). */
    long appliedLines() {
        if (!Files.exists(applied)) return 0;
        try {
            return Long.parseLong(Files.readString(applied, StandardCharsets.UTF_8).trim());
        } catch (IOException | NumberFormatException e) {
            log.warn("{} could not be read ({}): the journal is written into the store from its first line again — a task row is made once, the counters are set at the start", applied, e.toString());
            return 0;
        }
    }

    /** Everything in the journal is in the store: the file and its mark go. */
    void emptied() {
        delete(main);
        delete(applied);
    }

    /** The journal's lines pushed to the disk (the writer does it once per batch it applied — never a teller). */
    void fsync() {
        if (!Files.exists(main)) return;
        try (java.nio.channels.FileChannel ch = java.nio.channels.FileChannel.open(main, StandardOpenOption.WRITE)) {
            ch.force(false);
        } catch (IOException e) {
            log.warn("the journal {} could not be pushed to the disk ({}): a power cut could lose its last lines; a kill cannot", main, e.toString());
        }
    }

    /** The journal is set aside to be written into the store; new lines start a new journal. False = there is no journal. */
    boolean setAsideForReplay() {
        if (!Files.exists(main)) return false;
        delete(done);
        move(main, replaying);
        return true;
    }

    /** The lines of the file that was set aside, after the ones already in the store. */
    Lines linesSetAside() { return new Lines(replaying, linesDone()); }

    /** That many lines of the file set aside are in the store now. */
    void wroteSoFar(long lines) {
        Path next = done.resolveSibling(done.getFileName() + ".tmp");
        write(next, Long.toString(lines), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        move(next, done);
    }

    /** Every line of the file set aside is in the store. */
    void replayed() {
        delete(replaying);
        delete(done);
    }

    private long linesDone() {
        if (!Files.exists(done)) return 0;
        try {
            return Long.parseLong(Files.readString(done, StandardCharsets.UTF_8).trim());
        } catch (IOException | NumberFormatException e) {
            log.warn("{} could not be read ({}): the file beside it is written from its first line again — its task rows are made once, its counters are set at the start", done, e.toString());
            return 0;
        }
    }

    /** A file's changes in its order, a batch at a time. */
    final class Lines implements AutoCloseable {
        private final Path file;
        private final BufferedReader in;
        private long read;
        private boolean saidUnreadable;

        private Lines(Path file, long skip) {
            this.file = file;
            try {
                this.in = Files.exists(file) ? Files.newBufferedReader(file, StandardCharsets.UTF_8) : null;
                for (long i = 0; in != null && i < skip; i++) if (in.readLine() == null) break;
            } catch (IOException e) {
                throw new UncheckedIOException("the journal " + file + " could not be read", e);
            }
            this.read = skip;
        }

        /** The next {@code most} changes; empty = the end. A line that is not a change is passed over (it still counts as a line). */
        List<StoreChange> next(int most) {
            List<StoreChange> batch = new ArrayList<>();
            if (in == null) return batch;
            try {
                while (batch.size() < most) {
                    String line = in.readLine();
                    if (line == null) break;
                    read++;
                    if (line.isBlank()) continue;
                    try {
                        batch.add(codec.read(line));
                    } catch (RuntimeException notAChange) {
                        if (!saidUnreadable) log.warn("the journal {}: line {} is not a change and is passed over ({}): {}", file, read, notAChange.getMessage(),
                            line.length() > 160 ? line.substring(0, 160) + "…" : line);
                        saidUnreadable = true;
                    }
                }
            } catch (IOException e) {
                throw new UncheckedIOException("the journal " + file + " could not be read", e);
            }
            return batch;
        }

        /** The lines read so far, the skipped ones and the unreadable ones among them. */
        long linesRead() { return read; }

        @Override
        public void close() {
            try { if (in != null) in.close(); } catch (IOException ignored) { /* a reader of a file we only read */ }
        }
    }

    private static void write(Path file, String text, StandardOpenOption... how) {
        try {
            Files.writeString(file, text, StandardCharsets.UTF_8, how);
        } catch (IOException e) {
            throw new UncheckedIOException(file + " could not be written", e);
        }
    }

    private static void move(Path from, Path to) {
        try {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException(from + " could not take the place of " + to, e);
        }
    }

    private static void delete(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            throw new UncheckedIOException(file + " could not be removed", e);
        }
    }

    private static String quoted(String text) {
        StringBuilder sb = new StringBuilder("\"");
        for (char ch : String.valueOf(text).toCharArray()) {
            switch (ch) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n', '\r', '\t' -> sb.append(' ');
                default -> { if (ch < 0x20) sb.append(' '); else sb.append(ch); }
            }
        }
        return sb.append('"').toString();
    }
}
