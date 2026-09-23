package com.telcobright.seed.context;

import com.telcobright.seed.context.api.Snapshot;
import com.telcobright.seed.context.publishes.ContextEvent;
import com.telcobright.seed.context.testkit.ScriptedLoader;
import com.telcobright.seed.context.testkit.TwoTenantsHarness;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static com.telcobright.seed.context.testkit.TwoTenantsHarness.A;
import static com.telcobright.seed.context.testkit.TwoTenantsHarness.B;
import static com.telcobright.seed.context.testkit.TwoTenantsHarness.awaitUntil;
import static org.junit.jupiter.api.Assertions.*;

/** The isolation story the harness exists for: whatever happens to a, b keeps serving b. */
class TwoTenantsHarnessTest {

    @Test
    void whatever_happens_to_a_b_keeps_serving_its_own_context() throws Exception {
        ScriptedLoader loader = new ScriptedLoader()
            .extra(t -> Map.of("odoo", "odoo-" + t));
        try (TwoTenantsHarness<Map<String, Object>> bed = new TwoTenantsHarness<>(loader, 50).start()) {
            Snapshot<Map<String, Object>> b1 = bed.b().orElseThrow();
            assertEquals("odoo-b", b1.context().get("odoo"));
            assertEquals("odoo-a", bed.a().orElseThrow().context().get("odoo"));

            // a storm of rings on a, then a's source breaks
            for (int i = 0; i < 20; i++) bed.contexts.ring(A, "kafka");
            assertTrue(bed.awaitVersion(A, 2, 3_000));
            loader.failing(A, "odoo unreachable");
            assertThrows(Exception.class, () -> bed.contexts.reload(A, "admin").get());
            assertTrue(bed.count(ContextEvent.LoadFailed.class, A) >= 1);

            assertSame(b1, bed.b().orElseThrow(), "b's snapshot never moved");
            assertEquals(1, loader.calls(B), "b was loaded exactly once, whatever a did");
            assertEquals(0, bed.count(ContextEvent.LoadFailed.class, B));

            // a leaves the platform; b is untouched
            bed.active.remove(A);
            bed.contexts.refreshDirectory();
            assertTrue(awaitUntil(() -> bed.a().isEmpty(), 1_000));
            assertSame(b1, bed.b().orElseThrow());
            assertEquals(1, bed.count(ContextEvent.TenantDropped.class, A));
        }
    }

    @Test
    void the_environment_of_the_bed_feeds_the_env_pointers() {
        ScriptedLoader loader = new ScriptedLoader();
        try (TwoTenantsHarness<Map<String, Object>> bed = new TwoTenantsHarness<>(loader, 50)) {
            bed.environment.put("TENANT_A_ODOO_PASSWORD", "pw-a");
            bed.start();
            assertTrue(bed.a().isPresent());
        }
    }
}
