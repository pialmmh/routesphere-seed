package com.telcobright.seed.callflow.internal;

import com.fasterxml.jackson.core.JsonProcessingException;
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
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What a ledger owes a partner and could not give back by itself, and what it may have taken without telling: one
 * durable line per case, in one file, and one ERROR in the log. It exists because orchestrix's prepaid roads have no
 * "return by reference" yet.
 *
 * <ul>
 *   <li>{@code kind: owed} — a reserve that must go back (a later tier refused, a view never shown under the refund
 *       rule). The amount is certain: an officer credits it.</li>
 *   <li>{@code kind: unsure} — a reserve the ledger did not answer in time. The switch gave up and the call was not
 *       charged on it, but the ledger may have taken the money after the switch stopped waiting. An officer looks the
 *       reference up at the ledger first, and credits the amount only if it is there.</li>
 * </ul>
 *
 * <p>A line: the kind, when, the reference of the reserve, the tier, the tenant, the partner, the ledger account, the
 * amount, the unit, why. The file is appended and flushed line by line; it is never rewritten.
 */
public final class OwedJournal {

    private static final Logger log = LoggerFactory.getLogger(OwedJournal.class);
    private static final ObjectMapper JSON = new ObjectMapper();

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
    public synchronized void owe(LevelAdmission level, BigDecimal amount, String why) {
        log.error("OWED: {} {} of the reserve {} (tier {} {}, partner {}, account {}) could not be given back by the ledger: {} — written to {}",
            amount, level.getUom(), level.getDebitReference(), level.getLevelIndex(), level.getDbName(), level.getPartnerId(),
            level.getPackageAccountId(), why, file);
        append(lineOf("owed", level, level.getDebitReference(), amount, why));
    }

    /**
     * Write down that the reserve {@code reference} got no answer: the ledger may or may not have taken {@code amount}.
     * The tier never held this reserve, so the reference is given, not read from the tier.
     */
    public synchronized void unsure(LevelAdmission level, String reference, BigDecimal amount, String why) {
        log.error("UNSURE: the reserve {} of {} (tier {} {}, partner {}) got no answer from the ledger: {} — it MAY have been taken; look the reference up at the ledger. Written to {}",
            reference, amount, level.getLevelIndex(), level.getDbName(), level.getPartnerId(), why, file);
        append(lineOf("unsure", level, reference, amount, why));
    }

    private Map<String, Object> lineOf(String kind, LevelAdmission level, String reference, BigDecimal amount, String why) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("kind", kind);
        line.put("at", clock.instant().toString());
        line.put("reference", reference);
        line.put("tier", level.getLevelIndex());
        line.put("tenant", level.getDbName());
        line.put("partnerId", level.getPartnerId());
        line.put("account", level.getPackageAccountId());
        line.put("amount", amount);
        line.put("uom", level.getUom());
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
