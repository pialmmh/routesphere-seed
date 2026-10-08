package com.telcobright.seed.tenant.dependencies;

import com.telcobright.rtc.domainmodel.nonentity.Tenant;
import com.telcobright.seed.context.api.TenantContexts;
import com.telcobright.seed.context.dependencies.TenantContextsBuilder;
import com.telcobright.seed.context.publishes.ContextListener;
import com.telcobright.seed.context.spi.ContextLoader;
import com.telcobright.seed.context.spi.TenantDirectory;
import com.telcobright.seed.context.spi.TopicNaming;
import com.telcobright.seed.tenant.api.TenantTrees;
import com.telcobright.seed.tenant.internal.PrimeContextTreeSource;
import com.telcobright.seed.tenant.internal.Trees;
import com.telcobright.seed.tenant.spi.TreeSource;

import java.time.Duration;
import java.util.Objects;
import java.util.function.Supplier;

/** Everything the trees are handed: the directory, the source, the doorbell, the pace. Nothing is located. */
public final class TenantTreesBuilder {

    /** The doorbell's topic base; the topic of a tenant is {@code config_event_loader_<tenant>} (the seed's underscore naming). */
    public static final String DOORBELL_BASE = "config_event_loader";

    private TenantDirectory directory;
    private TreeSource source;
    private ContextListener listener = ContextListener.NONE;
    private long debounceMs = 3000;
    private Duration backstop = Duration.ofMinutes(5);
    private Duration loadTimeout;
    private String bootstrap, group;
    private boolean kafkaFailFast;

    /** The tenants this process serves — never a guess (a cache never invents its tenants). */
    public TenantTreesBuilder directory(TenantDirectory v) { directory = v; return this; }

    /** Where a tree comes from: a test's trees, or {@link #primeContext}. */
    public TenantTreesBuilder source(TreeSource v) { source = v; return this; }

    /** The real road: prime-context's {@code get-specific-tenant-root}; {@code bearer} null when the road is open. */
    public TenantTreesBuilder primeContext(String baseUrl, Duration timeout, Supplier<String> bearer) {
        return source(new PrimeContextTreeSource(baseUrl, timeout, bearer));
    }

    public TenantTreesBuilder listener(ContextListener v) { listener = v; return this; }
    public TenantTreesBuilder debounceMs(long v) { debounceMs = v; return this; }
    public TenantTreesBuilder backstop(Duration v) { backstop = v; return this; }
    public TenantTreesBuilder loadTimeout(Duration v) { loadTimeout = v; return this; }

    /** The Kafka doorbell: one consumer on {@code config_event_loader_<tenant>} for every served tenant; a blank bootstrap = no doorbell. */
    public TenantTreesBuilder doorbell(String bootstrap, String group) { this.bootstrap = bootstrap; this.group = group; return this; }

    /** Abort the start when Kafka is unreachable, instead of retrying in the background. */
    public TenantTreesBuilder kafkaFailFast(boolean v) { kafkaFailFast = v; return this; }

    public TenantTrees build() {
        Objects.requireNonNull(directory, "directory(...) is required — a cache never invents its tenants");
        Objects.requireNonNull(source, "source(...) or primeContext(...) is required");
        ContextLoader<Tenant> loader = (tenantId, secrets) -> Trees.ready(source.fetch(tenantId));
        TenantContextsBuilder<Tenant> b = TenantContexts.<Tenant>builder()
            .directory(directory).loader(loader).listener(listener).debounceMs(debounceMs).backstop(backstop).kafkaFailFast(kafkaFailFast);
        if (loadTimeout != null) b.loadTimeout(loadTimeout);
        if (bootstrap != null && !bootstrap.isBlank()) b.doorbell(bootstrap, DOORBELL_BASE, TopicNaming.underscore(), group);
        return new TenantTrees(b.build());
    }
}
