package com.telcobright.seed.switchledger.internal;

import com.fasterxml.jackson.core.StreamWriteFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * C2 · Rows and values on the wire (design §14, C2): a row is a JSON object of its fields by their Java names; a number is exact both
 * ways (never a double: money); a date is ISO text; {@code {"$key": n}} stands for the key of the unit's n-th insert (from 0), minted by
 * the ledger's application, so ONE request carries a purchase and the accounts that point at it.
 */
public final class WireJson {

    /**
     * Exact numbers in (a JSON decimal is read as BigDecimal, never a double) and out (never "1E+3"). A number's scale on the way in does not
     * matter: every number is written at its column's scale ({@code RowRules.scaleToColumns}) and compared by value.
     */
    public static final JsonMapper JSON = JsonMapper.builder()
        .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
        .enable(StreamWriteFeature.WRITE_BIGDECIMAL_AS_PLAIN)
        .build();

    private WireJson() {}

    /** The row as a JSON object of its fields. */
    public static ObjectNode rowOf(Object row) {
        ObjectNode node = JSON.createObjectNode();
        RowFields.valuesOf(row).forEach((name, value) -> put(node, name, value));
        return node;
    }

    /** One value into an object: a number exact, true/false, ISO text for a date, text for the rest. */
    public static void put(ObjectNode node, String name, Object value) {
        if (value == null) node.putNull(name);
        else if (value instanceof BigDecimal d) node.put(name, d);
        else if (value instanceof Long l) node.put(name, l);
        else if (value instanceof Integer i) node.put(name, i);
        else if (value instanceof Number n) node.put(name, new BigDecimal(n.toString()));
        else if (value instanceof Boolean b) node.put(name, b);
        else node.put(name, value.toString());
    }

    /** One value onto an array, as {@link #put} writes it. */
    public static void add(com.fasterxml.jackson.databind.node.ArrayNode array, Object value) {
        if (value instanceof Long l) array.add(l);
        else if (value instanceof Integer i) array.add(i);
        else if (value instanceof BigDecimal d) array.add(d);
        else if (value == null) array.addNull();
        else array.add(value.toString());
    }

    /** A JSON value as Java: a number (BigDecimal), text, true/false, null, or the key of the unit's n-th insert. */
    static Object valueOf(JsonNode node, List<Object> keys, String what) {
        if (node == null || node.isNull() || node.isMissingNode()) return null;
        if (node.isNumber()) return node.decimalValue();
        if (node.isTextual()) return node.textValue();
        if (node.isBoolean()) return node.booleanValue();
        if (node.isObject() && node.size() == 1 && node.has("$key")) return keyOf(node.get("$key"), keys, what);
        throw new IllegalArgumentException(what + " is a number, a text, true/false, null or {\"$key\": n} — not " + node);
    }

    /** A JSON object's fields as Java values, in order. */
    static Map<String, Object> fieldsOf(JsonNode node, List<Object> keys, String what) {
        if (node == null || !node.isObject()) throw new IllegalArgumentException(what + " is a JSON object of fields");
        Map<String, Object> fields = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> field : node.properties()) fields.put(field.getKey(), valueOf(field.getValue(), keys, what + "." + field.getKey()));
        return fields;
    }

    private static Object keyOf(JsonNode index, List<Object> keys, String what) {
        int n = index.canConvertToInt() ? index.asInt() : -1;
        if (!index.isIntegralNumber() || n < 0 || n >= keys.size()) {
            throw new IllegalArgumentException(what + " names {\"$key\": " + index + "}: the unit has " + keys.size() + " insert(s) before it (counted from 0)");
        }
        return keys.get(n);
    }
}
