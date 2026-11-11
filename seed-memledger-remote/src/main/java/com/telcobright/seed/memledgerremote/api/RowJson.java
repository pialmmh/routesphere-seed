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
        if (key.getType() != String.class) requireShort(text.trim(), "the key of a " + type.getSimpleName());
        try {
            return fromText(key.getType(), text);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("'" + cut(text) + "' is no key of " + type.getSimpleName() + " (its " + key.getName() + " is a " + key.getType().getSimpleName() + ")");
        }
    }

    // ── a number's text has a bound ─────────────────────────────────────────

    /**
     * A number's TEXT on the wire is at most this long, and a longer one is refused BEFORE it is parsed. Parsing a decimal's text costs
     * the square of its length: one of a million digits kept a worker busy for 41.8 s (routesphere's gate of the first-line guard,
     * 2026-11-10) — one megabyte of request from any holder of a bearer. The widest column of the prepaid model, numeric(20,6), needs 22
     * characters, and an exponent form is short by nature. ONE rule for every number read from text: a decimal, a whole number, a key.
     */
    public static final int NUMBER_TEXT_MAX = 64;

    /** The decimal a text holds ({@link NumberFormatException} when it holds none); a text longer than {@link #NUMBER_TEXT_MAX} is refused unparsed. */
    public static BigDecimal decimalOf(String text, String what) {
        requireShort(text, what);
        return new BigDecimal(text);
    }

    /** Refused in words, and the text is not read: the refusal says its length, never the text. */
    public static void requireShort(String text, String what) {
        if (text != null && text.length() > NUMBER_TEXT_MAX) {
            throw new IllegalArgumentException(what + " is a number of " + text.length() + " characters: a number's text is " + NUMBER_TEXT_MAX + " at most (it was not read)");
        }
    }

    /** A value as a refusal says it: never a megabyte of it in an answer and a log line. */
    private static String cut(String text) {
        return text.length() <= 80 ? text : text.substring(0, 80) + "… (" + text.length() + " characters)";
    }

    /** A key as the wire carries it; none = empty text. */
    public static String keyText(Object key) { return key == null ? "" : String.valueOf(key); }

    /**
     * ARCH-0118 S2 · How a row the cache holds differs from the SAME row as a client read it ({@code asRead}: its JSON, as
     * {@link #textOf} writes a row): null = the same; else words naming the FIRST field that differs, in the class's order. A decimal is
     * compared by its value ({@code compareTo}: 10 = 10.000000), every other field by {@code equals}. A field one side has and the other
     * lacks, or a value that is not its field's type, is no difference but an {@link IllegalArgumentException} naming it: the two sides
     * hold different classes, and a ledger does not guess. ONE helper: the server checks a batch's expectations with it, and a row's own
     * round trip is the same by it (so one client alone never reads a stale row of its own).
     */
    public static String differenceFrom(Object held, String asRead) {
        if (held == null) throw new IllegalArgumentException("no row held to compare");
        Class<?> type = held.getClass();
        JsonNode read = parsed(type, asRead);
        Map<String, Field> fields = fieldsOf(type);
        requireTheSameFields(type, fields, read);
        for (Field f : fields.values()) {
            Object was = valueOf(f, read.get(f.getName()));
            Object is = valueIn(held, f);
            if (!sameValue(is, was)) return f.getName() + " is " + said(is) + ", the batch read " + said(was);
        }
        return null;
    }

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
            if (type == BigDecimal.class && node.isTextual()) return decimalOf(node.textValue(), whatOf(f));
            if (node.isNumber()) requireShort(node.asText(), whatOf(f));           // the same bound for a number written as a JSON number
            if (type == BigDecimal.class && node.isNumber()) return node.decimalValue();
            if ((type == Long.class || type == long.class) && node.isIntegralNumber()) return node.bigIntegerValue().longValueExact();
            if ((type == Integer.class || type == int.class) && node.isIntegralNumber()) return node.bigIntegerValue().intValueExact();
            if ((type == Boolean.class || type == boolean.class) && node.isBoolean()) return node.booleanValue();
            if (type == LocalDateTime.class && node.isTextual()) return LocalDateTime.parse(node.textValue());
            if (type == LocalDate.class && node.isTextual()) return LocalDate.parse(node.textValue());
        } catch (ArithmeticException | NumberFormatException | java.time.format.DateTimeParseException e) {
            throw new IllegalArgumentException(whatOf(f) + " is a " + type.getSimpleName() + ": " + cut(node.toString()) + " is not one");
        }
        throw new IllegalArgumentException(whatOf(f) + " is a " + type.getSimpleName() + ": " + cut(node.toString()) + " is not one");
    }

    // ── a row as held vs as read ────────────────────────────────────────────

    private static void requireTheSameFields(Class<?> type, Map<String, Field> fields, JsonNode read) {
        for (Map.Entry<String, JsonNode> named : read.properties()) {
            if (!fields.containsKey(named.getKey())) throw new IllegalArgumentException(type.getSimpleName() + " has no field '" + named.getKey() + "' (its fields: " + fields.keySet() + ")");
        }
        for (String name : fields.keySet()) {
            if (!read.has(name)) throw new IllegalArgumentException("the row as read names no '" + name + "' of " + type.getSimpleName() + ": a field one side lacks is not compared");
        }
    }

    private static boolean sameValue(Object is, Object was) {
        if (is instanceof BigDecimal a && was instanceof BigDecimal b) return a.compareTo(b) == 0;
        return java.util.Objects.equals(is, was);
    }

    private static String said(Object value) { return value instanceof BigDecimal d ? d.toPlainString() : String.valueOf(value); }

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
