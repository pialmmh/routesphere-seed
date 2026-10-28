package com.telcobright.seed.ledgerclient.internal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamWriteFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.telcobright.seed.ledgerclient.api.RemoteLedger;
import com.telcobright.seed.ledgerclient.api.RemoteLedgerFault;
import com.telcobright.seed.ledgerclient.spi.Transport;

import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

/**
 * C2 · The client's side of the wire (seed-switch-ledger's {@code WireUnit} is the other): numbers exact both ways (never a double: money),
 * a date as ISO text, a key of the unit's n-th insert as {@code {"$key": n}}; paths and queries encoded.
 */
public final class Wire {

    public static final JsonMapper JSON = JsonMapper.builder()
        .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
        .enable(StreamWriteFeature.WRITE_BIGDECIMAL_AS_PLAIN)
        .nodeFactory(com.fasterxml.jackson.databind.node.JsonNodeFactory.withExactBigDecimals(true))
        .build();

    private Wire() {}

    /** A map of fields as a JSON object (a {@link RemoteLedger.Key} as {@code {"$key": n}}). */
    public static ObjectNode objectOf(Map<String, ?> fields) {
        ObjectNode node = JSON.createObjectNode();
        if (fields != null) fields.forEach((name, value) -> put(node, name, value));
        return node;
    }

    /** One value into an object: a number exact, true/false, a key placeholder, ISO text for a date, text for the rest. */
    public static void put(ObjectNode node, String name, Object value) {
        if (value == null) node.putNull(name);
        else if (value instanceof RemoteLedger.Key key) node.putObject(name).put("$key", key.index());
        else if (value instanceof BigDecimal d) node.put(name, d);
        else if (value instanceof Long l) node.put(name, l);
        else if (value instanceof Integer i) node.put(name, i);
        else if (value instanceof Number n) node.put(name, new BigDecimal(n.toString()));
        else if (value instanceof Boolean b) node.put(name, b);
        else node.put(name, value.toString());
    }

    /** A JSON object's fields as Java: numbers as BigDecimal (whole ones as Long), text, true/false, null. */
    public static Map<String, Object> fieldsOf(JsonNode node) {
        Map<String, Object> fields = new LinkedHashMap<>();
        if (node != null) for (Map.Entry<String, JsonNode> field : node.properties()) fields.put(field.getKey(), valueOf(field.getValue()));
        return fields;
    }

    public static List<Object> keysOf(JsonNode keys) {
        List<Object> out = new ArrayList<>();
        if (keys != null) keys.forEach(key -> out.add(valueOf(key)));
        return out;
    }

    public static List<RemoteLedger.Row> rowsOf(JsonNode rows) {
        List<RemoteLedger.Row> out = new ArrayList<>();
        if (rows != null) rows.forEach(row -> out.add(new RemoteLedger.Row(row.path("table").asText(), fieldsOf(row.get("row")))));
        return out;
    }

    /** The answer's JSON; a body that is not JSON is the application's fault (a proxy's page, a crash). */
    public static JsonNode body(Transport.Response answer) {
        try {
            JsonNode node = JSON.readTree(answer.body() == null || answer.body().isBlank() ? "{}" : answer.body());
            return node == null ? JSON.createObjectNode() : node;
        } catch (JsonProcessingException e) {
            throw new RemoteLedgerFault("the ledger's application answered " + answer.status() + " with no JSON: " + abbreviated(answer.body()));
        }
    }

    /** A 404 of a row read: the row is not there (the root is there). */
    public static boolean isAbsentRow(String body) {
        try {
            return JSON.readTree(body == null ? "{}" : body).path("error").asText("").startsWith("no ");
        } catch (JsonProcessingException e) {
            return false;
        }
    }

    /** {@code a/b/c}, each segment encoded. */
    public static String path(String... segments) {
        StringJoiner path = new StringJoiner("/");
        for (String segment : segments) path.add(URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20"));
        return path.toString();
    }

    /** {@code ?field=value&…}, encoded; empty for none. */
    public static String query(Map<String, ?> equals) {
        if (equals == null || equals.isEmpty()) return "";
        StringJoiner query = new StringJoiner("&", "?", "");
        equals.forEach((field, value) -> query.add(URLEncoder.encode(field, StandardCharsets.UTF_8) + "=" + URLEncoder.encode(String.valueOf(value), StandardCharsets.UTF_8)));
        return query.toString();
    }

    private static Object valueOf(JsonNode node) {
        if (node == null || node.isNull()) return null;
        if (node.isIntegralNumber()) return node.canConvertToLong() ? (Object) node.asLong() : node.decimalValue();
        if (node.isNumber()) return node.decimalValue();
        if (node.isBoolean()) return node.booleanValue();
        return node.asText();
    }

    private static String abbreviated(String body) { return body == null ? "" : body.length() > 200 ? body.substring(0, 200) + "…" : body; }
}
