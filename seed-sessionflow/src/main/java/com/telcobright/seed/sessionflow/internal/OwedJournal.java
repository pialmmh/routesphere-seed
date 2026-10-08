package com.telcobright.seed.sessionflow.internal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.telcobright.rtc.domainmodel.LevelAdmission;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What a ledger owes a partner, what it may have taken without telling, and what became of each reserve that had to go
 * back: one durable line per fact, in one file. The file is appended and flushed line by line; it is never rewritten.
 * The LAST line of a reference tells where that reserve stands.
 *
 * <ul>
 *   <li>{@code returning} — the reserve must go back and the ledger's return road is being asked. Written BEFORE the
 *       road is asked, so that a process that dies in between leaves the fact behind: the next start asks again
 *       ({@link #open()}; the road is idempotent by the reference).</li>
 *   <li>{@code returned} — the road gave it back. Closed.</li>
 *   <li>{@code not-charged} — the road says nothing was charged under the reference. Closed.</li>
 *   <li>{@code officer} — the road will not return it by itself (the bucket's purchase was cancelled): an officer
 *       decides. Never asked again.</li>
 *   <li>{@code owed} — it could NOT be given back: the ledger has no return road, refused, or did not answer after the
 *       tries. The amount is certain: an officer credits it. One ERROR in the log.</li>
 *   <li>{@code unsure} — a reserve the ledger did not answer in time. The call was not charged on it, but the ledger may
 *       have taken the money after the switch stopped waiting. One ERROR in the log.</li>
 * </ul>
 *
 * <p>A line: the kind, when, the reference of the reserve, the tier, the tenant, the partner, its accounting partner,
 * the ledger account, the amount, the unit, why.
 */
public final class OwedJournal {

    public static final String OWED = "owed";
    public static final String UNSURE = "unsure";
    public static final String RETURNING = "returning";
    public static final String RETURNED = "returned";
    public static final String NOT_CHARGED = "not-charged";
    public static final String OFFICER = "officer";

    private static final Logger log = LoggerFactory.getLogger(OwedJournal.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * The facts of one reserve that must go back, copied out of the tier. The tier itself belongs to the call and is
     * reused by the next one, so nothing that outlives the call may hold it.
     */
    public record Entry(String reference, int tier, String tenant, int partnerId, String billingAccount, Long account,
                        BigDecimal amount, String uom) {

        public static Entry of(LevelAdmission level, String reference, BigDecimal amount) {
            String billing = level.getPartner() == null ? null : level.getPartner().getBillingAccountId();
            return new Entry(reference, level.getLevelIndex(), level.getDbName(), level.getPartnerId(),
                billing == null || billing.isBlank() ? null : billing.trim(), level.getPackageAccountId(), amount, level.getUom());
        }
    }

    private final Path file;
    private final Clock clock;

    public OwedJournal(Path file, Clock clock) {
        this.file = file;
        this.clock = clock;
        try {
            if (file.getParent() != null) Files.createDirectories(file.getParent());
        } catch (IOException e) {
            throw new UncheckedIOException("the owed journal's directory " + file.getParent() + " cannot be made", e);
        }
    }

    public Path file() { return file; }

    /** Write down that {@code amount} of the tier's reserve is owed to its partner. */
    public void owe(LevelAdmission level, BigDecimal amount, String why) {
        owe(Entry.of(level, level.getDebitReference(), amount), why);
    }

    /** Write down that the reserve could not be given back: an officer credits it. */
    public synchronized void owe(Entry e, String why) {
        log.error("OWED: {} {} of the reserve {} (tier {} {}, partner {}, account {}) could not be given back by the ledger: {} — written to {}",
            e.amount(), e.uom(), e.reference(), e.tier(), e.tenant(), e.partnerId(), e.account(), why, file);
        append(lineOf(OWED, e, why));
    }

    /**
     * Write down that the reserve {@code reference} got no answer: the ledger may or may not have taken {@code amount}.
     * The tier never held this reserve, so the reference is given, not read from the tier.
     */
    public synchronized void unsure(LevelAdmission level, String reference, BigDecimal amount, String why) {
        log.error("UNSURE: the reserve {} of {} (tier {} {}, partner {}) got no answer from the ledger: {} — it MAY have been taken; look the reference up at the ledger. Written to {}",
            reference, amount, level.getLevelIndex(), level.getDbName(), level.getPartnerId(), why, file);
        append(lineOf(UNSURE, Entry.of(level, reference, amount), why));
    }

    /** One more line of a return's story ({@code returning}, {@code returned}, {@code not-charged}, {@code officer}); the caller logs. */
    public synchronized void note(String kind, Entry e, String why) {
        append(lineOf(kind, e, why));
    }

    /**
     * The reserves whose last line is {@code returning}: the return road was being asked and the story has no end — the
     * process stopped in between. A line that cannot be read is skipped and said once in the log.
     */
    public synchronized List<Entry> open() {
        if (!Files.isRegularFile(file)) return List.of();
        Map<String, JsonNode> last = new LinkedHashMap<>();
        try {
            for (String text : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (text.isBlank()) continue;
                JsonNode line = readable(text);
                if (line != null && line.hasNonNull("reference")) last.put(line.get("reference").asText(), line);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("the owed journal " + file + " could not be read", e);
        }
        List<Entry> open = new ArrayList<>();
        for (JsonNode line : last.values()) {
            if (RETURNING.equals(line.path("kind").asText())) open.add(entryOf(line));
        }
        return open;
    }

    private JsonNode readable(String text) {
        try {
            return JSON.readTree(text);
        } catch (IOException e) {
            log.warn("owed journal {}: a line that is not JSON is skipped: {}", file, text.length() > 120 ? text.substring(0, 120) + "…" : text);
            return null;
        }
    }

    private static Entry entryOf(JsonNode line) {
        return new Entry(line.get("reference").asText(), line.path("tier").asInt(), text(line, "tenant"), line.path("partnerId").asInt(),
            text(line, "billingAccount"), line.hasNonNull("account") ? line.get("account").asLong() : null,
            line.hasNonNull("amount") ? line.get("amount").decimalValue() : BigDecimal.ZERO, text(line, "uom"));
    }

    private static String text(JsonNode line, String field) { return line.hasNonNull(field) ? line.get(field).asText() : null; }

    private Map<String, Object> lineOf(String kind, Entry e, String why) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("kind", kind);
        line.put("at", clock.instant().toString());
        line.put("reference", e.reference());
        line.put("tier", e.tier());
        line.put("tenant", e.tenant());
        line.put("partnerId", e.partnerId());
        line.put("billingAccount", e.billingAccount());
        line.put("account", e.account());
        line.put("amount", e.amount());
        line.put("uom", e.uom());
        line.put("why", why);
        return line;
    }

    private void append(Map<String, Object> line) {
        try {
            Files.writeString(file, JSON.writeValueAsString(line) + System.lineSeparator(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.SYNC);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("an owed line could not be written as JSON: " + e.getMessage(), e);
        } catch (IOException e) {
            throw new UncheckedIOException("the owed journal " + file + " could not be written — the line is in the log above", e);
        }
    }
}
