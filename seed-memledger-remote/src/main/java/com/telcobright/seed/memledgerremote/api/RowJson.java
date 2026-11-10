package com.telcobright.seed.memledgerremote.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A row on the wire (design §2.1): a JSON object of the row's fields by their Java names — every instance field of the class and its
 * parents — decoded on the other side into the SAME class, so what the cache holds there is what the application built here. Money is
 * decimal TEXT ({@code BigDecimal.toPlainString}: its scale is kept, never a float or a double), a whole number is a JSON number, a
 * date is ISO-8601 text, an empty field is {@code null}. A field the class does not have, or a value that is not the field's type, is an
 * {@link IllegalArgumentException} naming both: the two sides hold different classes, and a ledger does not guess.
 */
public final class RowJson {

    private static final JsonMapper JSON = JsonMapper.builder().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build();
    private static final Map<Class<?>, Map<String, Field>> FIELDS = new ConcurrentHashMap<>();

    private RowJson() {}

    /** The row as JSON text: every field, in the class's order, parents first. */
    public static String textOf(Object row) {
        if (row == null) throw new IllegalArgumentException("no row to write");
        ObjectNode node = JSON.createObjectNode();
        for (Field f : fieldsOf(row.getClass()).values()) put(node, f, valueIn(row, f));
        return node.toString();
    }

    /** The JSON text as a NEW row of {@code type}: every field the text names is set; one it leaves out stays as the constructor made it. */
    public static <T> T rowOf(Class<T> type, String json) {
        JsonNode node = parsed(type, json);
        T row = newInstance(type);
        Map<String, Field> fields = fieldsOf(type);
        for (Map.Entry<String, JsonNode> named : node.properties()) {
            Field f = fields.get(named.getKey());
            if (f == null) throw new IllegalArgumentException(type.getSimpleName() + " has no field '" + named.getKey() + "' (its fields: " + fields.keySet() + ")");
            set(row, f, valueOf(f, named.getValue()));
        }
        return row;
    }

    /** The key's text as the key type of {@code type} (its {@code @Id} field's): a number as its number, a text as it is. */
    public static Object keyOf(Class<?> type, String text) {
        Field key = keyField(type);
        if (text == null || text.isEmpty()) return null;
        try {
            return fromText(key.getType(), text);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("'" + text + "' is no key of " + type.getSimpleName() + " (its " + key.getName() + " is a " + key.getType().getSimpleName() + ")");
        }
    }

    /** A key as the wire carries it; none = empty text. */
    public static String keyText(Object key) { return key == null ? "" : String.valueOf(key); }

    // ── out ─────────────────────────────────────────────────────────────────

    private static void put(ObjectNode node, Field f, Object value) {
        String name = f.getName();
        if (value == null) node.putNull(name);
        else if (value instanceof BigDecimal d) node.put(name, d.toPlainString());
        else if (value instanceof Long l) node.put(name, l);
        else if (value instanceof Integer i) node.put(name, i);
        else if (value instanceof Boolean b) node.put(name, b);
        else if (value instanceof String s) node.put(name, s);
        else if (value instanceof LocalDateTime || value instanceof LocalDate) node.put(name, value.toString());
        else throw new IllegalArgumentException(whatOf(f) + " holds a " + value.getClass().getName() + ": a row's field on the wire is a text, a whole number, a decimal, true/false or a date");
    }

    // ── in ──────────────────────────────────────────────────────────────────

    private static JsonNode parsed(Class<?> type, String json) {
        if (json == null || json.isBlank()) throw new IllegalArgumentException("no JSON for a " + type.getSimpleName());
        try {
            JsonNode node = JSON.readTree(json);
            if (node == null || !node.isObject()) throw new IllegalArgumentException("a " + type.getSimpleName() + " on the wire is a JSON object of its fields");
            return node;
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("the JSON of a " + type.getSimpleName() + " cannot be read: " + e.getOriginalMessage());
        }
    }

    private static Object valueOf(Field f, JsonNode node) {
        Class<?> type = f.getType();
        if (node == null || node.isNull()) {
            if (type.isPrimitive()) throw new IllegalArgumentException(whatOf(f) + " cannot be empty");
            return null;
        }
        try {
            if (type == String.class && node.isTextual()) return node.textValue();
            if (type == BigDecimal.class && node.isTextual()) return new BigDecimal(node.textValue());
            if (type == BigDecimal.class && node.isNumber()) return node.decimalValue();
            if ((type == Long.class || type == long.class) && node.isIntegralNumber()) return node.bigIntegerValue().longValueExact();
            if ((type == Integer.class || type == int.class) && node.isIntegralNumber()) return node.bigIntegerValue().intValueExact();
            if ((type == Boolean.class || type == boolean.class) && node.isBoolean()) return node.booleanValue();
            if (type == LocalDateTime.class && node.isTextual()) return LocalDateTime.parse(node.textValue());
            if (type == LocalDate.class && node.isTextual()) return LocalDate.parse(node.textValue());
        } catch (ArithmeticException | NumberFormatException | java.time.format.DateTimeParseException e) {
            throw new IllegalArgumentException(whatOf(f) + " is a " + type.getSimpleName() + ": " + node + " is not one");
        }
        throw new IllegalArgumentException(whatOf(f) + " is a " + type.getSimpleName() + ": " + node + " is not one");
    }

    private static Object fromText(Class<?> type, String text) {
        if (type == String.class) return text;
        if (type == Long.class || type == long.class) return Long.valueOf(text.trim());
        if (type == Integer.class || type == int.class) return Integer.valueOf(text.trim());
        throw new IllegalArgumentException("a key is a text or a whole number, not a " + type.getSimpleName());
    }

    // ── the class's fields ──────────────────────────────────────────────────

    private static Field keyField(Class<?> type) {
        for (Field f : fieldsOf(type).values()) if (f.isAnnotationPresent(jakarta.persistence.Id.class)) return f;
        throw new IllegalArgumentException(type.getSimpleName() + " has no @Id field: its rows have no key on the wire");
    }

    private static Object valueIn(Object row, Field f) {
        try {
            return f.get(row);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void set(Object row, Field f, Object value) {
        try {
            f.set(row, value);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    private static <T> T newInstance(Class<T> type) {
        try {
            var constructor = type.getDeclaredConstructor();
            constructor.setAccessible(true);
            return constructor.newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalArgumentException("a " + type.getSimpleName() + " cannot be made (it needs a constructor with no arguments): " + e, e);
        }
    }

    private static String whatOf(Field f) { return f.getDeclaringClass().getSimpleName() + "." + f.getName(); }

    /** The instance fields of a type and its parents by name, parents first, made accessible once. */
    private static Map<String, Field> fieldsOf(Class<?> type) {
        return FIELDS.computeIfAbsent(type, t -> {
            List<Class<?>> chain = new ArrayList<>();
            for (Class<?> c = t; c != null && c != Object.class; c = c.getSuperclass()) chain.add(0, c);
            Map<String, Field> fields = new LinkedHashMap<>();
            for (Class<?> c : chain) {
                for (Field f : c.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers()) || f.isSynthetic()) continue;
                    f.setAccessible(true);
                    fields.put(f.getName(), f);
                }
            }
            return java.util.Collections.unmodifiableMap(fields);
        });
    }
}
