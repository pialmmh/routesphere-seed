package com.telcobright.seed.callflow.internal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.telcobright.seed.callflow.spi.CallJournal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The {@link CallJournal} in one file of JSON lines, appended to and never edited in place:
 *
 * <pre>
 *   {"k":"v","id":"…","at":1791134779751,"r":[ …the records… ]}      the hand-over
 *   {"k":"n","id":"…","at":…,"ans":1791134780102,"sec":5.0}          what was learned since (the last one counts)
 *   {"k":"d","id":"…"}                                               the call's own record was published
 * </pre>
 *
 * A line is ONE write of the process: a process killed by a signal leaves what it wrote (the kernel has it); a power cut may take
 * the last lines — no write is forced to the disk, a view does not wait for the disk. The file holds the calls in the air only:
 * when no line is open it is emptied, and when it outgrows {@code rollBytes} under steady traffic it is written again with the open
 * lines only (one rename). A line that cannot be read (the half line of a power cut) is skipped with one WARN.
 */
public final class FileCallJournal implements CallJournal {

    private static final Logger log = LoggerFactory.getLogger(FileCallJournal.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    /** Written again with the open lines only when it reaches this size: a line per call in the air stays, nothing else. */
    public static final long ROLL_BYTES = 16L * 1024 * 1024;

    private final Path file;
    private final long rollBytes;
    /** The calls in the air: their lines as written, for the roll. */
    private final Map<String, Open> open = new LinkedHashMap<>();
    private final List<Leftover> leftovers;
    private FileChannel channel;
    private long size;

    private record Open(String handOver, String note) {
        Open noted(String line) { return new Open(handOver, line); }
        String lines() { return note == null ? handOver : handOver + note; }
    }

    public FileCallJournal(Path file) { this(file, ROLL_BYTES); }

    public FileCallJournal(Path file, long rollBytes) {
        this.file = file.toAbsolutePath();
        this.rollBytes = rollBytes;
        this.leftovers = List.copyOf(readWhatAStoppedProcessLeft());
        this.channel = openForAppend();
        this.size = sizeOf(this.file);
    }

    @Override
    public synchronized void handedOver(String callId, long atMs, String records) {
        String line = "{\"k\":\"v\",\"id\":" + quoted(callId) + ",\"at\":" + atMs + ",\"r\":" + records + "}\n";
        append(line);
        open.put(callId, new Open(line, null));
    }

    @Override
    public synchronized void noted(String callId, long atMs, long answeredAtMs, double billedSec) {
        Open was = open.get(callId);
        if (was == null) return;
        String line = "{\"k\":\"n\",\"id\":" + quoted(callId) + ",\"at\":" + atMs + ",\"ans\":" + answeredAtMs + ",\"sec\":" + billedSec + "}\n";
        append(line);
        open.put(callId, was.noted(line));
    }

    @Override
    public synchronized void done(String callId) {
        if (open.remove(callId) == null) return;
        append("{\"k\":\"d\",\"id\":" + quoted(callId) + "}\n");
        rollWhenItIsTime();
    }

    @Override public List<Leftover> leftovers() { return leftovers; }

    @Override public String where() { return file.toString(); }

    /** The calls in the air now: for a test and a log line. */
    public synchronized int inTheAir() { return open.size(); }

    @Override
    public synchronized void close() {
        try {
            channel.close();
        } catch (IOException e) {
            log.warn("the journal of the calls in the air {} did not close: {}", file, e.toString());
        }
    }

    // ── writing ─────────────────────────────────────────────────────────────

    private void append(String line) {
        byte[] bytes = line.getBytes(StandardCharsets.UTF_8);
        try {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) channel.write(buffer);
            size += bytes.length;
        } catch (IOException e) {
            throw new UncheckedIOException("the journal of the calls in the air " + file + " did not take a line: " + e.getMessage(), e);
        }
    }

    /** Nothing open: the file is emptied. Too large under steady traffic: written again with the open lines only. */
    private void rollWhenItIsTime() {
        try {
            if (open.isEmpty()) {
                if (size > 0) { channel.truncate(0); size = 0; }
                return;
            }
            if (size >= rollBytes) writeTheOpenLinesOnly();
        } catch (IOException e) {
            log.warn("the journal of the calls in the air {} could not be rolled (it goes on growing): {}", file, e.toString());
        }
    }

    private void writeTheOpenLinesOnly() throws IOException {
        Path next = file.resolveSibling(file.getFileName() + ".rolling");
        StringBuilder lines = new StringBuilder();
        for (Open o : open.values()) lines.append(o.lines());
        byte[] bytes = lines.toString().getBytes(StandardCharsets.UTF_8);
        Files.write(next, bytes);
        channel.close();
        Files.move(next, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        channel = openForAppend();
        size = bytes.length;
    }

    private FileChannel openForAppend() {
        try {
            if (file.getParent() != null) Files.createDirectories(file.getParent());
            return FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException("the journal of the calls in the air " + file + " cannot be opened: " + e.getMessage(), e);
        }
    }

    // ── reading what a stopped process left ─────────────────────────────────

    private List<Leftover> readWhatAStoppedProcessLeft() {
        if (!Files.exists(file)) return List.of();
        Map<String, Left> calls = new LinkedHashMap<>();
        int unreadable = 0;
        for (String line : linesOf(file)) {
            if (line.isBlank()) continue;
            if (!takeLine(line, calls)) unreadable++;
        }
        if (unreadable > 0) {
            log.warn("the journal of the calls in the air {}: {} line(s) could not be read and are skipped — the half line a power cut leaves", file, unreadable);
        }
        List<Leftover> out = new ArrayList<>();
        for (Map.Entry<String, Left> c : calls.entrySet()) {
            Left l = c.getValue();
            if (l.done || l.records == null) continue;
            out.add(new Leftover(c.getKey(), l.handedOverAt, l.records, l.answeredAt, l.billedSec, Math.max(l.handedOverAt, l.notedAt)));
            open.put(c.getKey(), new Open(l.handOverLine, l.noteLine));
        }
        return out;
    }

    /** The facts of one call, from its lines in their order. */
    private static final class Left {
        String records, handOverLine, noteLine;
        long handedOverAt, answeredAt, notedAt;
        double billedSec;
        boolean done;
    }

    private static boolean takeLine(String line, Map<String, Left> calls) {
        try {
            JsonNode n = JSON.readTree(line);
            String id = n.path("id").asText(null);
            if (id == null) return false;
            Left l = calls.computeIfAbsent(id, k -> new Left());
            switch (n.path("k").asText()) {
                case "v" -> { l.records = n.path("r").toString(); l.handedOverAt = n.path("at").asLong(); l.handOverLine = line + "\n"; }
                case "n" -> { l.answeredAt = n.path("ans").asLong(); l.billedSec = n.path("sec").asDouble(); l.notedAt = n.path("at").asLong(); l.noteLine = line + "\n"; }
                case "d" -> l.done = true;
                default -> { return false; }
            }
            return true;
        } catch (JsonProcessingException notJson) {
            return false;
        }
    }

    /** Read as bytes: a line cut in the middle of a character is still read (and skipped), never a failure of the start. */
    private static List<String> linesOf(Path file) {
        try {
            return List.of(new String(Files.readAllBytes(file), StandardCharsets.UTF_8).split("\n"));
        } catch (IOException e) {
            throw new UncheckedIOException("the journal of the calls in the air " + file + " cannot be read: " + e.getMessage(), e);
        }
    }

    private static long sizeOf(Path file) {
        try {
            return Files.size(file);
        } catch (IOException e) {
            return 0;
        }
    }

    private static String quoted(String text) {
        try {
            return JSON.writeValueAsString(text);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("a call id that is not text: " + text, e);
        }
    }
}
