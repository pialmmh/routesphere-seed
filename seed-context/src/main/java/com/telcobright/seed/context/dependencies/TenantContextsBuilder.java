package com.telcobright.seed.context.dependencies;

import com.telcobright.seed.context.api.TenantContexts;
import com.telcobright.seed.context.internal.TenantContextsImpl;
import com.telcobright.seed.context.publishes.ContextListener;
import com.telcobright.seed.context.spi.ContextLoader;
import com.telcobright.seed.context.spi.SecretResolver;
import com.telcobright.seed.context.spi.TenantDirectory;
import com.telcobright.seed.context.spi.TopicNaming;

import java.time.Duration;
import java.util.Objects;

/** Everything injected, nothing located. Only {@code directory} and {@code loader} are required. */
public final class TenantContextsBuilder<T> {

    private TenantDirectory directory;
    private ContextLoader<T> loader;
    private SecretResolver secrets = EnvSecrets.fromProcess();
    private ContextListener listener = ContextListener.NONE;
    private long debounceMs = 3_000;
    private Duration backstop = Duration.ofHours(6);
    private Duration loadTimeout = Duration.ofSeconds(60);
    private Duration startTimeout = Duration.ofSeconds(60);
    private String kafkaBootstrap, topicBase, kafkaGroupId;
    private TopicNaming naming = TopicNaming.underscore();
    private boolean kafkaFailFast;

    public TenantContextsBuilder<T> directory(TenantDirectory directory) { this.directory = directory; return this; }

    public TenantContextsBuilder<T> loader(ContextLoader<T> loader) { this.loader = loader; return this; }

    public TenantContextsBuilder<T> secrets(SecretResolver secrets) { this.secrets = secrets; return this; }

    public TenantContextsBuilder<T> listener(ContextListener listener) { this.listener = listener; return this; }

    /** The quiet period after the last ring before a tenant reloads (routesphere's 3 s). */
    public TenantContextsBuilder<T> debounceMs(long ms) { this.debounceMs = ms; return this; }

    /** Every tenant reloads at least this often, doorbell or not — the floor under a missed event. Zero = off. */
    public TenantContextsBuilder<T> backstop(Duration every) { this.backstop = every; return this; }

    /** A loader that takes longer fails the load; its late result is dropped, never swapped in. */
    public TenantContextsBuilder<T> loadTimeout(Duration timeout) { this.loadTimeout = timeout; return this; }

    /** How long {@code start()} waits for the initial loads before it returns (they keep running after). */
    public TenantContextsBuilder<T> startTimeout(Duration timeout) { this.startTimeout = timeout; return this; }

    /**
     * The Kafka doorbell: one consumer on the exact list {@code naming.topic(base, tenant)} of the active
     * tenants. A record's content is ignored; its topic names the tenant to reload.
     */
    public TenantContextsBuilder<T> doorbell(String bootstrap, String base, TopicNaming naming, String groupId) {
        this.kafkaBootstrap = bootstrap; this.topicBase = base; this.naming = naming; this.kafkaGroupId = groupId;
        return this;
    }

    /** Abort start when Kafka is unreachable, instead of retrying forever in the background. */
    public TenantContextsBuilder<T> kafkaFailFast(boolean failFast) { this.kafkaFailFast = failFast; return this; }

    public TenantContexts<T> build() {
        Objects.requireNonNull(directory, "directory(...) is required — a cache never invents its tenants");
        Objects.requireNonNull(loader, "loader(...) is required");
        return new TenantContextsImpl<>(directory, loader, secrets, listener, debounceMs, backstop, loadTimeout,
            startTimeout, kafkaBootstrap, topicBase, naming, kafkaGroupId, kafkaFailFast);
    }
}
