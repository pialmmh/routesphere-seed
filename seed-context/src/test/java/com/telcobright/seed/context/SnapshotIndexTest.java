package com.telcobright.seed.context;

import com.telcobright.seed.context.api.Snapshot;
import com.telcobright.seed.context.api.TenantContexts;
import com.telcobright.seed.context.dependencies.EnvSecrets;
import com.telcobright.seed.context.testkit.ScriptedLoader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P1-O (the architect's A1) · A snapshot carries its index, built ONCE in the step that installs it — the first load and every reload —
 * and never by a reader: a thousand reads of one snapshot see one build and the same object. An index that cannot be built fails that load
 * like the loader's own throw: the last good snapshot stays, with its index, and the version is not taken.
 */
class SnapshotIndexTest {

    private final ScriptedLoader loader = new ScriptedLoader();
    private final List<String> builds = new CopyOnWriteArrayList<>();
    private final AtomicBoolean refuseTheNextIndex = new AtomicBoolean();
    private TenantContexts<Map<String, Object>> cache;

    @AfterEach
    void tearDown() { if (cache != null) cache.close(); }

    private TenantContexts<Map<String, Object>> cache() {
        return TenantContexts.<Map<String, Object>>builder()
            .directory(() -> Set.of("btcl"))
            .loader(loader)
            .secrets(EnvSecrets.of(Map.of()))
            .indexer((tenant, version, context) -> {
                if (refuseTheNextIndex.getAndSet(false)) throw new IllegalStateException("the index of " + tenant + " v" + version + " cannot be built");
                builds.add(tenant + " v" + version);
                return "index of " + tenant + " v" + version;
            })
            .debounceMs(10_000).backstop(Duration.ZERO).loadTimeout(Duration.ofSeconds(5)).startTimeout(Duration.ofSeconds(5))
            .build().start();
    }

    @Test
    void the_index_is_built_once_at_the_install_and_every_read_of_the_snapshot_gets_the_same_object() throws Exception {
        cache = cache();
        Object first = cache.require("btcl").index();
        assertEquals("index of btcl v1", first);
        for (int read = 0; read < 1_000; read++) assertSame(first, cache.require("btcl").index(), "read " + read + ": the same index");
        assertEquals(List.of("btcl v1"), builds, "one build for the load, none for the thousand reads");

        Snapshot<Map<String, Object>> v2 = cache.reload("btcl", "test").get(5, TimeUnit.SECONDS);
        assertEquals("index of btcl v2", v2.index(), "the reload installs a new index with its snapshot");
        assertEquals(String.class, v2.index(String.class).getClass());
        assertNull(v2.index(Integer.class), "an index of another type is none");
        assertEquals(List.of("btcl v1", "btcl v2"), builds);
    }

    @Test
    void an_index_that_cannot_be_built_fails_the_load_and_the_last_snapshot_stays_with_its_index_and_its_version() throws Exception {
        cache = cache();
        refuseTheNextIndex.set(true);
        Exception refused = assertThrows(Exception.class, () -> cache.reload("btcl", "test").get(5, TimeUnit.SECONDS));
        assertTrue(String.valueOf(refused.getMessage()).contains("cannot be built"), refused.toString());
        assertEquals(1, cache.require("btcl").version(), "the last good snapshot stays");
        assertEquals("index of btcl v1", cache.require("btcl").index(), "with its own index");
        assertEquals(1, cache.stats("btcl").failures(), "counted as a failed load");

        assertEquals(2, cache.reload("btcl", "test").get(5, TimeUnit.SECONDS).version(), "the failed install took no version");
        assertEquals("index of btcl v2", cache.require("btcl").index());
    }
}
