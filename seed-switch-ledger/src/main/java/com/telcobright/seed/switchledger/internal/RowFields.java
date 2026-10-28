package com.telcobright.seed.switchledger.internal;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * C1 · The fields of a cached row, by their Java names: read one, set one (a value converted to the field's type: a number to the field's
 * number, an ISO text to a date), copy a row (a NEW object — the cached row is never changed in place), the key (the {@code @Id} field).
 * A name the type does not have, or a value that cannot be the field's type, is an {@link IllegalArgumentException} naming both.
 */
final class RowFields {

    private static final Map<Class<?>, Map<String, Field>> FIELDS = new ConcurrentHashMap<>();

    private RowFields() {}

    /** A copy of the row: a new object of its class, every field the same (the values are immutable: numbers, text, dates). */
    @SuppressWarnings("unchecked")
    static <T> T copyOf(T row) {
        T copy = (T) newInstance(row.getClass());
        try {
            for (Field f : fieldsOf(row.getClass()).values()) f.set(copy, f.get(row));
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
        return copy;
    }

    /** C2b · The row as an instance of {@code type} — a class that extends the row's (a {@code PackageAccount} as the cached {@code LedgerAccount}). */
    static <T> T copyAs(Class<T> type, Object row) {
        if (type.isInstance(row)) return type.cast(copyOf(row));
        if (!row.getClass().isAssignableFrom(type)) throw new IllegalArgumentException("a " + row.getClass().getSimpleName() + " is no " + type.getSimpleName());
        T copy = newInstance(type);
        try {
            for (Field f : fieldsOf(row.getClass()).values()) f.set(copy, f.get(row));
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
        return copy;
    }

    /** C2 · A new row of {@code type}, the named fields set (each value as its field's type); the rest as the type's constructor leaves them. */
    static <T> T newRow(Class<T> type, Map<String, ?> fields) {
        T row = newInstance(type);
        fields.forEach((name, value) -> set(row, name, value));
        return row;
    }

    /** C2 · Every field of the row by name, in the class's order. */
    static Map<String, Object> valuesOf(Object row) {
        Map<String, Object> values = new java.util.LinkedHashMap<>();
        try {
            for (Field f : fieldsOf(row.getClass()).values()) values.put(f.getName(), f.get(row));
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
        return values;
    }

    /** C2 · An id as the type's key (a number by its value: 30, 30.0 and "30" are one key — one row, one lock). */
    static Object keyAs(Class<?> type, Object id) {
        if (id == null) throw new IllegalArgumentException("a " + type.getSimpleName() + " is named by its key");
        String key = keyField(type);
        return as(field(type, key).getType(), id instanceof BigDecimal d ? d.stripTrailingZeros() : id, type.getSimpleName() + "." + key);
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

    static Object get(Object row, String name) {
        try {
            return field(row.getClass(), name).get(row);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    static void set(Object row, String name, Object value) {
        Field f = field(row.getClass(), name);
        try {
            f.set(row, as(f.getType(), value, row.getClass().getSimpleName() + "." + name));
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The field, or an {@link IllegalArgumentException} naming the type's fields. */
    static Field field(Class<?> type, String name) {
        Field f = name == null ? null : fieldsOf(type).get(name);
        if (f == null) throw new IllegalArgumentException(type.getSimpleName() + " has no field '" + name + "' (its fields: " + fieldsOf(type).keySet() + ")");
        return f;
    }

    /** Is the field a number the ledger can add to? */
    static boolean isNumber(Class<?> type, String name) { return Number.class.isAssignableFrom(boxed(field(type, name).getType())); }

    /** The name of the {@code @Id} field. */
    static String keyField(Class<?> type) {
        for (Field f : fieldsOf(type).values()) if (f.isAnnotationPresent(jakarta.persistence.Id.class)) return f.getName();
        throw new IllegalArgumentException(type.getSimpleName() + " has no @Id field");
    }

    static Object keyOf(Object row) { return get(row, keyField(row.getClass())); }

    /** Does the row's field equal {@code wanted} (numbers by value; text, dates and the rest as the field's type)? */
    static boolean holds(Object row, String name, Object wanted) {
        Object value = get(row, name);
        Object want = as(field(row.getClass(), name).getType(), wanted, row.getClass().getSimpleName() + "." + name);
        if (value instanceof BigDecimal a && want instanceof BigDecimal b) return a.compareTo(b) == 0;
        return Objects.equals(value, want);
    }

    /** Does the row match every named field? */
    static boolean matches(Object row, Map<String, ?> equals) {
        for (Map.Entry<String, ?> e : equals.entrySet()) if (!holds(row, e.getKey(), e.getValue())) return false;
        return true;
    }

    /** Every name must be a field of the type (asked before anything runs). */
    static void checkNames(Class<?> type, Iterable<String> names) { for (String name : names) field(type, name); }

    /** The value as the field's type, or an {@link IllegalArgumentException} naming the field. */
    static Object as(Class<?> fieldType, Object value, String what) {
        if (value == null) {
            if (fieldType.isPrimitive()) throw new IllegalArgumentException(what + " cannot be empty");
            return null;
        }
        Class<?> type = boxed(fieldType);
        if (type.isInstance(value)) return value;
        try {
            if (value instanceof Number n) return number(type, new BigDecimal(n.toString()), value, what);
            if (value instanceof String text) return fromText(type, text.trim(), what);
        } catch (ArithmeticException | NumberFormatException | java.time.format.DateTimeParseException e) {
            throw new IllegalArgumentException(what + " is a " + type.getSimpleName() + ": '" + value + "' is not one", e);
        }
        throw new IllegalArgumentException(what + " is a " + type.getSimpleName() + ": a " + value.getClass().getSimpleName() + " is not one");
    }

    private static Object number(Class<?> type, BigDecimal n, Object value, String what) {
        if (type == BigDecimal.class) return n;
        if (type == Long.class) return n.longValueExact();
        if (type == Integer.class) return n.intValueExact();
        if (type == Double.class) return n.doubleValue();
        throw new IllegalArgumentException(what + " is a " + type.getSimpleName() + ": the number " + value + " is not one");
    }

    private static Object fromText(Class<?> type, String text, String what) {
        if (type == String.class) return text;
        if (type == BigDecimal.class) return new BigDecimal(text);
        if (type == Long.class) return Long.valueOf(text);
        if (type == Integer.class) return Integer.valueOf(text);
        if (type == Boolean.class) return Boolean.valueOf(text);
        if (type == LocalDateTime.class) return LocalDateTime.parse(text.replace(' ', 'T'));
        if (type == LocalDate.class) return LocalDate.parse(text);
        throw new IllegalArgumentException(what + " is a " + type.getSimpleName() + ": the text '" + text + "' is not one");
    }

    private static Class<?> boxed(Class<?> type) {
        if (!type.isPrimitive()) return type;
        if (type == long.class) return Long.class;
        if (type == int.class) return Integer.class;
        if (type == boolean.class) return Boolean.class;
        if (type == double.class) return Double.class;
        return type;
    }

    /** The instance fields of a type and its parents, by name, made accessible once. */
    private static Map<String, Field> fieldsOf(Class<?> type) {
        return FIELDS.computeIfAbsent(type, t -> {
            Map<String, Field> fields = new java.util.LinkedHashMap<>();
            List<Class<?>> chain = new ArrayList<>();
            for (Class<?> c = t; c != null && c != Object.class; c = c.getSuperclass()) chain.add(0, c);
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
