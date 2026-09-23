package com.telcobright.seed.context.testkit;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

/**
 * The shape rule a product's test asserts on its context type: a record, or a final class whose every
 * instance field is final. A mutable context would be shared by every reader of the snapshot and edited
 * under them; the cache's whole promise is that a snapshot never changes.
 */
public final class ContextShape {

    private ContextShape() {}

    /** Throws {@link AssertionError} naming the first field that breaks the rule. */
    public static void assertImmutable(Class<?> type) {
        List<String> problems = problems(type);
        if (!problems.isEmpty()) throw new AssertionError(type.getName() + " is not an immutable context: " + String.join("; ", problems));
    }

    public static List<String> problems(Class<?> type) {
        List<String> out = new ArrayList<>();
        if (type.isRecord()) return out;
        if (!Modifier.isFinal(type.getModifiers())) out.add("the class is not final");
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                if (!Modifier.isFinal(f.getModifiers())) out.add("field " + c.getSimpleName() + "." + f.getName() + " is not final");
            }
        }
        return out;
    }
}
