package com.telcobright.seed.campaign.writer;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.telcobright.seed.campaign.api.CampaignKind;
import com.telcobright.seed.campaign.api.CampaignTask;
import com.telcobright.seed.campaign.api.TaskCharge;
import com.telcobright.seed.campaign.api.TaskState;
import com.telcobright.seed.campaign.spi.StoreChange;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One {@link StoreChange} as one line of JSON, and back: the journal's own format, written field by field (a record that gains a
 * field does not change what an older line means).
 *
 * <pre>
 * {"kind":"insert","task":{"uniqueId":"…","tenantId":"…","campaignId":7,…,"detail":{…}}}
 * {"kind":"update","task":{…}}
 * {"kind":"bump","tenant":"…","campaign":7,"sent":1,"failed":0,"pending":-1}
 * {"kind":"complete","tenant":"…","campaign":7}
 * </pre>
 */
final class ChangeCodec {

    /** Decimals as they are: 0.30 stays 0.30 (the default node factory would strip its zero), and comes back a decimal, not a double. */
    private static final ObjectMapper JSON = new ObjectMapper().setNodeFactory(JsonNodeFactory.withExactBigDecimals(true))
        .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

    String write(StoreChange change) {
        ObjectNode line = JSON.createObjectNode();
        switch (change) {
            case StoreChange.TaskInserted c -> { line.put("kind", "insert"); line.set("task", taskNode(c.task())); }
            case StoreChange.TaskUpdated c -> { line.put("kind", "update"); line.set("task", taskNode(c.task())); }
            case StoreChange.CountersBumped c -> line.put("kind", "bump").put("tenant", c.tenantId()).put("campaign", c.campaignId())
                .put("sent", c.sent()).put("failed", c.failed()).put("pending", c.pending());
            case StoreChange.CampaignCompleted c -> line.put("kind", "complete").put("tenant", c.tenantId()).put("campaign", c.campaignId());
        }
        return line.toString();
    }

    /** @throws IllegalArgumentException a line that is not a change (the journal skips it and says so) */
    StoreChange read(String text) {
        JsonNode line;
        try {
            line = JSON.readTree(text);
        } catch (IOException e) {
            throw new IllegalArgumentException("not JSON: " + e.getMessage());
        }
        String kind = line.path("kind").asText("");
        return switch (kind) {
            case "insert" -> new StoreChange.TaskInserted(taskOf(line.path("task")));
            case "update" -> new StoreChange.TaskUpdated(taskOf(line.path("task")));
            case "bump" -> new StoreChange.CountersBumped(text(line, "tenant"), line.path("campaign").asInt(), line.path("sent").asInt(),
                line.path("failed").asInt(), line.path("pending").asInt());
            case "complete" -> new StoreChange.CampaignCompleted(text(line, "tenant"), line.path("campaign").asInt());
            default -> throw new IllegalArgumentException("no kind '" + kind + "'");
        };
    }

    private static ObjectNode taskNode(CampaignTask t) {
        ObjectNode n = JSON.createObjectNode();
        n.put("uniqueId", t.uniqueId()).put("tenantId", t.tenantId()).put("campaignId", t.campaignId()).put("partnerId", t.partnerId());
        n.put("kind", t.kind() == null ? null : t.kind().name());
        n.put("subject", t.subject()).put("creativeId", t.creativeId()).put("zone", t.zone()).put("site", t.site()).put("clientRef", t.clientRef());
        n.put("state", t.state() == null ? null : t.state().name());
        n.put("createdAt", instant(t.createdAt())).put("answeredAt", instant(t.answeredAt())).put("endedAt", instant(t.endedAt()));
        n.put("billsec", t.billsec()).put("endCause", t.endCause());
        TaskCharge c = t.charge();
        if (c != null) {
            ObjectNode charge = n.putObject("charge");
            if (c.packageAccountId() != null) charge.put("packageAccountId", c.packageAccountId());
            charge.put("uom", c.uom()).put("packageAmount", c.packageAmount()).put("cost", c.cost()).put("matchedPattern", c.matchedPattern());
        }
        n.set("detail", detailNode(t.detail()));
        return n;
    }

    /** The detail as JSON; a value JSON cannot carry is kept as its text, so a line is always written. */
    private static ObjectNode detailNode(Map<String, Object> detail) {
        ObjectNode n = JSON.createObjectNode();
        for (Map.Entry<String, Object> e : detail.entrySet()) {
            try {
                n.set(e.getKey(), JSON.valueToTree(e.getValue()));
            } catch (IllegalArgumentException notJson) {
                n.put(e.getKey(), String.valueOf(e.getValue()));
            }
        }
        return n;
    }

    private static CampaignTask taskOf(JsonNode n) {
        if (!n.hasNonNull("uniqueId") || !n.hasNonNull("tenantId")) throw new IllegalArgumentException("a task with no id or no tenant");
        JsonNode c = n.path("charge");
        TaskCharge charge = c.isObject()
            ? new TaskCharge(c.hasNonNull("packageAccountId") ? c.get("packageAccountId").asLong() : null, text(c, "uom"), decimal(c, "packageAmount"),
                decimal(c, "cost"), text(c, "matchedPattern"))
            : null;
        return new CampaignTask(n.get("uniqueId").asText(), n.get("tenantId").asText(), n.path("campaignId").asInt(), n.path("partnerId").asInt(),
            n.hasNonNull("kind") ? CampaignKind.valueOf(n.get("kind").asText()) : null, text(n, "subject"), text(n, "creativeId"), text(n, "zone"),
            text(n, "site"), text(n, "clientRef"), n.hasNonNull("state") ? TaskState.valueOf(n.get("state").asText()) : null,
            instantOf(n, "createdAt"), instantOf(n, "answeredAt"), instantOf(n, "endedAt"), n.path("billsec").asInt(), text(n, "endCause"), charge,
            detailOf(n.path("detail")));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> detailOf(JsonNode n) {
        if (!n.isObject()) return Map.of();
        Map<String, Object> read = JSON.convertValue(n, LinkedHashMap.class);
        read.values().removeIf(v -> v == null);                  // the task's detail is an immutable map: it holds no null
        return read;
    }

    private static String instant(Instant at) { return at == null ? null : at.toString(); }

    private static Instant instantOf(JsonNode n, String field) { return n.hasNonNull(field) ? Instant.parse(n.get(field).asText()) : null; }

    private static String text(JsonNode n, String field) { return n.hasNonNull(field) ? n.get(field).asText() : null; }

    private static BigDecimal decimal(JsonNode n, String field) { return n.hasNonNull(field) ? n.get(field).decimalValue() : null; }
}
