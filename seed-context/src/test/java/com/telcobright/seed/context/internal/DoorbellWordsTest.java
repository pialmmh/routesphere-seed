package com.telcobright.seed.context.internal;

import com.telcobright.seed.context.dependencies.EnvSecrets;
import com.telcobright.seed.context.api.TenantContexts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * L2b item 3 · a doorbell message's WORDS reach whoever listens ({@code onDoorbellWords}), by the tenant its topic names, as they arrive
 * — the kit's ledger reads which tables changed; a topic of no tenant is nothing.
 */
class DoorbellWordsTest {

    private TenantContexts<Map<String, Object>> cache;

    @AfterEach
    void close() { if (cache != null) cache.close(); }

    @Test
    void theWordsOfATenantsTopic_reachEveryListener_byTenant() {
        cache = TenantContexts.<Map<String, Object>>builder().directory(() -> Set.of("btcl"))
            .loader((tenant, secrets) -> Map.of())
            .secrets(EnvSecrets.of(Map.of())).debounceMs(1_000).backstop(Duration.ZERO).loadTimeout(Duration.ofSeconds(5)).startTimeout(Duration.ofSeconds(5))
            .build().start();
        List<String> heard = new CopyOnWriteArrayList<>();
        cache.onDoorbellWords((tenant, message) -> heard.add(tenant + " " + message));
        TenantContextsImpl<Map<String, Object>> impl = (TenantContextsImpl<Map<String, Object>>) cache;
        String topic = impl.subscriptions().keySet().iterator().next();

        impl.wordsByTopic(topic, "{\"what\":\"change\",\"changes\":[\"res_9.packageaccount:INSERT\"]}");
        impl.wordsByTopic("config_event_loader_nobody", "{}");

        assertEquals(List.of("btcl {\"what\":\"change\",\"changes\":[\"res_9.packageaccount:INSERT\"]}"), heard);
    }
}
