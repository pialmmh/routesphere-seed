package com.telcobright.seed.switchledger.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * L2b item 3 · One ring of a root's doorbell {@code config_event_loader_<root>} as prime-context sends it ({@code TreeDoorbell.message}):
 * {@code what} (change, start, stream-open, reload, provision, …) and change-wire's keys {@code changes} ({@code "tier.table:OPERATION"};
 * {@code "*:SNAPSHOT"} = everything).
 */
public record Doorbell(String what, List<String> changes) {

    private static final ObjectMapper JSON = new ObjectMapper();

    public Doorbell {
        what = what == null ? "" : what.trim();
        changes = changes == null ? List.of() : List.copyOf(changes);
    }

    /** One change key, read: the tier ({@code *} for all), the table (lower case), the operation (upper case). */
    public record Change(String tier, String table, String operation) {

        /** {@code "res_9.packageaccount:INSERT"} → (res_9, packageaccount, INSERT); {@code "*:SNAPSHOT"} → (*, *, SNAPSHOT); unreadable → null. */
        public static Change of(String key) {
            if (key == null) return null;
            int colon = key.lastIndexOf(':');
            if (colon <= 0) return null;
            String where = key.substring(0, colon).trim(), op = key.substring(colon + 1).trim().toUpperCase(Locale.ROOT);
            if ("*".equals(where)) return new Change("*", "*", op);
            int dot = where.indexOf('.');
            if (dot <= 0 || dot == where.length() - 1) return null;
            return new Change(where.substring(0, dot), where.substring(dot + 1).toLowerCase(Locale.ROOT), op);
        }
    }

    /** The ring's changes, read (the unreadable left out). */
    public List<Change> readChanges() {
        List<Change> read = new ArrayList<>(changes.size());
        for (String key : changes) {
            Change c = Change.of(key);
            if (c != null) read.add(c);
        }
        return read;
    }

    /** The doorbell's JSON ({@code {"what": …, "changes": [ … ]}}); a message without either is a ring of nothing in particular. */
    public static Doorbell parse(String json) {
        try {
            JsonNode node = JSON.readTree(json == null ? "{}" : json);
            List<String> changes = new ArrayList<>();
            JsonNode list = node.path("changes");
            if (list.isArray()) list.forEach(c -> changes.add(c.asText()));
            return new Doorbell(node.path("what").asText(""), changes);
        } catch (Exception e) {
            throw new IllegalArgumentException("not a doorbell message: " + e.getMessage(), e);
        }
    }
}
