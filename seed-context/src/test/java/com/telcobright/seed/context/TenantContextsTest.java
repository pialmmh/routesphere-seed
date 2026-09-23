package com.telcobright.seed.context;

import com.telcobright.seed.context.api.ContextStats;
import com.telcobright.seed.context.api.Snapshot;
import com.telcobright.seed.context.api.TenantContextUnavailable;
import com.telcobright.seed.context.api.TenantContexts;
import com.telcobright.seed.context.dependencies.EnvSecrets;
import com.telcobright.seed.context.publishes.ContextEvent;
import com.telcobright.seed.context.testkit.ScriptedLoader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static com.telcobright.seed.context.testkit.TwoTenantsHarness.awaitUntil;
import static org.junit.jupiter.api.Assertions.*;

/** The cache's promises, one test each: isolation, coalescing, the whole swap, the version, the timeout, the directory. */
class TenantContextsTest {

    private final Set<String> active = ConcurrentHashMap.newKeySet();
    private final ScriptedLoader loader = new ScriptedLoader();
    private final List<ContextEvent> events = new CopyOnWriteArrayList<>();
    private final List<TenantContexts<?>> open = new ArrayList<>();

    private TenantContexts<Map<String, Object>> cache(long debounceMs, Duration backstop, Duration loadTimeout) {
        TenantContexts<Map<String, Object>> c = TenantContexts.<Map<String, Object>>builder()
            .directory(() -> Set.copyOf(active))
            .loader(loader)
            .secrets(EnvSecrets.of(Map.of()))
            .listener(events::add)
            .debounceMs(debounceMs).backstop(backstop).loadTimeout(loadTimeout).startTimeout(Duration.ofSeconds(5))
            .build();
        open.add(c);
        return c;
    }

    @AfterEach
    void tearDown() { open.forEach(TenantContexts::close); }

    private long count(Class<? extends ContextEvent> kind, String tenant) {
        return events.stream().filter(kind::isInstance).filter(e -> e.tenantId().equals(tenant)).count();
    }

    @Test
    void start_loads_every_active_tenant_and_each_answers_with_its_own_context() {
        active.addAll(List.of("a", "b"));
        var cache = cache(3_000, Duration.ZERO, Duration.ofSeconds(5)).start();
        assertEquals(Set.of("a", "b"), cache.tenants());
        Snapshot<Map<String, Object>> a = cache.require("a");
        assertEquals("a", a.context().get("tenantId"));
        assertEquals(1, a.version());
        assertEquals("b", cache.require("b").context().get("tenantId"));
        assertEquals(1, loader.calls("a"));
        TenantContextUnavailable e = assertThrows(TenantContextUnavailable.class, () -> cache.require("c"));
        assertTrue(e.getMessage().contains("not a tenant"), e.getMessage());
        assertEquals(2, events.stream().filter(ContextEvent.Reloaded.class::isInstance).count());
    }

    @Test
    void a_tenant_whose_load_fails_is_absent_and_its_sibling_serves() {
        active.addAll(List.of("a", "b"));
        loader.failing("a", "odoo down");
        var cache = cache(3_000, Duration.ZERO, Duration.ofSeconds(5)).start();
        assertTrue(cache.find("a").isEmpty());
        TenantContextUnavailable e = assertThrows(TenantContextUnavailable.class, () -> cache.require("a"));
        assertTrue(e.getMessage().contains("odoo down"), "the reason is named: " + e.getMessage());
        assertEquals("b", cache.require("b").context().get("tenantId"), "b never noticed");
        ContextEvent.LoadFailed failed = events.stream().filter(ContextEvent.LoadFailed.class::isInstance)
            .map(ContextEvent.LoadFailed.class::cast).findFirst().orElseThrow();
        assertEquals("a", failed.tenantId());
        assertEquals(0, failed.servingVersion(), "nothing was serving");
        assertFalse(cache.stats("a").serving());
        assertEquals(1, cache.stats("a").failures());
    }

    @Test
    void a_failed_reload_keeps_the_last_good_snapshot_until_the_next_good_one() throws Exception {
        active.add("a");
        var cache = cache(3_000, Duration.ZERO, Duration.ofSeconds(5)).start();
        Snapshot<Map<String, Object>> v1 = cache.require("a");

        loader.failing("a", "table locked");
        CompletableFuture<Snapshot<Map<String, Object>>> failed = cache.reload("a", "admin");
        ExecutionException ex = assertThrows(ExecutionException.class, () -> failed.get(3, TimeUnit.SECONDS));
        assertTrue(ex.getCause().getMessage().contains("table locked"));
        assertSame(v1, cache.require("a"), "the old snapshot serves, untouched");
        assertEquals(1, count(ContextEvent.LoadFailed.class, "a"));
        assertEquals(1, ((ContextEvent.LoadFailed) events.get(events.size() - 1)).servingVersion());

        loader.recover("a");
        Snapshot<Map<String, Object>> v2 = cache.reload("a", "admin").get(3, TimeUnit.SECONDS);
        assertEquals(2, v2.version());
        assertSame(v2, cache.require("a"));
        assertEquals(3, loader.calls("a"));
    }

    @Test
    void rings_coalesce_into_one_reload_after_the_quiet_period() throws Exception {
        active.add("a");
        var cache = cache(200, Duration.ZERO, Duration.ofSeconds(5)).start();
        for (int i = 0; i < 5; i++) { cache.ring("a", "kafka"); Thread.sleep(10); }
        assertTrue(awaitUntil(() -> cache.require("a").version() == 2, 3_000), "one reload after the quiet period");
        Thread.sleep(500);
        assertEquals(2, loader.calls("a"), "five rings, one load");
        assertEquals(5, cache.stats("a").rings());
    }

    @Test
    void a_reload_during_a_load_runs_exactly_once_more_and_every_caller_gets_that_run() throws Exception {
        active.add("a");
        var cache = cache(3_000, Duration.ZERO, Duration.ofSeconds(5)).start();
        loader.hold("a");
        CompletableFuture<Snapshot<Map<String, Object>>> first = cache.reload("a", "first");
        Thread.sleep(50);                                                       // the first load is blocked inside the loader
        CompletableFuture<Snapshot<Map<String, Object>>> second = cache.reload("a", "second");
        CompletableFuture<Snapshot<Map<String, Object>>> third = cache.reload("a", "third");
        assertSame(second, third, "everyone waiting behind an in-flight load shares the one queued run");
        assertFalse(first.isDone());

        loader.release("a");
        assertEquals(2, first.get(3, TimeUnit.SECONDS).version());
        loader.release("a");                                                    // the queued run also hits the hold
        assertEquals(3, second.get(3, TimeUnit.SECONDS).version());
        Thread.sleep(200);
        assertEquals(3, loader.calls("a"), "initial + the in-flight + exactly one more");
    }

    @Test
    void the_snapshot_is_swapped_whole_and_the_old_one_never_changes() throws Exception {
        active.add("a");
        var cache = cache(3_000, Duration.ZERO, Duration.ofSeconds(5)).start();
        Snapshot<Map<String, Object>> s1 = cache.require("a");
        Snapshot<Map<String, Object>> s2 = cache.reload("a", "admin").get(3, TimeUnit.SECONDS);
        assertNotSame(s1, s2);
        assertEquals(1, s1.version());
        assertEquals(1, s1.context().get("load"), "the first snapshot still says what it said");
        assertEquals(2, s2.context().get("load"));
        assertSame(s2, cache.require("a"));
        assertThrows(UnsupportedOperationException.class, () -> s2.context().put("x", "y"), "the context is immutable");
    }

    @Test
    void a_load_past_the_timeout_fails_and_its_late_result_never_swaps() throws Exception {
        active.add("a");
        var cache = cache(3_000, Duration.ZERO, Duration.ofMillis(150)).start();
        loader.hold("a");
        CompletableFuture<Snapshot<Map<String, Object>>> slow = cache.reload("a", "slow");
        ExecutionException ex = assertThrows(ExecutionException.class, () -> slow.get(3, TimeUnit.SECONDS));
        assertTrue(ex.getCause() instanceof TimeoutException, String.valueOf(ex.getCause()));
        assertEquals(1, cache.require("a").version());
        assertTrue(cache.stats("a").lastFailure().contains("exceeded"));

        loader.release("a");                                                    // the late completion arrives now
        Thread.sleep(200);
        assertEquals(1, cache.require("a").version(), "the late result was dropped");
        assertEquals(1, cache.stats("a").loads());
        assertEquals(2, cache.reload("a", "again").get(3, TimeUnit.SECONDS).version(), "the cell is not stuck");
    }

    @Test
    void the_backstop_rings_every_tenant() {
        active.addAll(List.of("a", "b"));
        var cache = cache(10, Duration.ofMillis(150), Duration.ofSeconds(5)).start();
        assertTrue(awaitUntil(() -> loader.calls("a") >= 3 && loader.calls("b") >= 3, 3_000), "loads by the backstop");
        assertTrue(cache.stats("a").rings() >= 2);
    }

    @Test
    void the_directory_refresh_adds_and_drops_tenants() {
        active.addAll(List.of("a", "b"));
        var cache = cache(3_000, Duration.ZERO, Duration.ofSeconds(5)).start();

        active.add("c");
        cache.refreshDirectory();
        assertTrue(awaitUntil(() -> cache.find("c").isPresent(), 3_000));
        assertEquals(1, count(ContextEvent.TenantAdded.class, "c"));
        assertEquals(Set.of("a", "b", "c"), cache.tenants());

        active.remove("b");
        cache.refreshDirectory();
        assertTrue(cache.find("b").isEmpty());
        assertEquals(1, count(ContextEvent.TenantDropped.class, "b"));
        assertEquals(Set.of("a", "c"), cache.tenants());
        cache.ring("b", "kafka");                                               // ignored, never throws
        assertThrows(TenantContextUnavailable.class, () -> cache.require("b"));
        assertTrue(cache.reload("b", "x").isCompletedExceptionally());
    }

    @Test
    void stats_and_close() {
        active.add("a");
        var cache = cache(3_000, Duration.ZERO, Duration.ofSeconds(5)).start();
        ContextStats s = cache.stats("a");
        assertTrue(s.serving());
        assertEquals(1, s.version());
        assertEquals(1, s.loads());
        assertEquals(0, s.failures());
        assertNotNull(s.loadedAt());
        assertEquals(Set.of("a"), cache.stats().keySet());

        cache.close();
        assertTrue(cache.find("a").isEmpty());
        assertTrue(cache.reload("a", "x").isCompletedExceptionally());
    }
}
