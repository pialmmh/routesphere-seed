package com.telcobright.seed.context;

import com.telcobright.seed.context.testkit.ContextShape;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ContextShapeTest {

    record GoodRecord(String tenantId, List<String> zones) {}

    static final class GoodFinal { private final int a = 1; int a() { return a; } }

    static final class Mutable { private final int a = 1; private String name = "x"; }

    static class NotFinal { private final int a = 1; }

    @Test
    void a_record_and_a_final_class_with_final_fields_pass() {
        assertDoesNotThrow(() -> ContextShape.assertImmutable(GoodRecord.class));
        assertDoesNotThrow(() -> ContextShape.assertImmutable(GoodFinal.class));
    }

    @Test
    void a_mutable_field_is_named() {
        AssertionError e = assertThrows(AssertionError.class, () -> ContextShape.assertImmutable(Mutable.class));
        assertTrue(e.getMessage().contains("Mutable.name"), e.getMessage());
    }

    @Test
    void a_class_that_is_not_final_is_flagged() {
        assertEquals(List.of("the class is not final"), ContextShape.problems(NotFinal.class));
    }
}
