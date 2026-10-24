package com.telcobright.seed.tenant.dependencies;

import com.telcobright.seed.sessionflow.api.Deployment;
import com.telcobright.seed.sessionflow.spi.BootstrapSource;
import com.telcobright.seed.sessionflow.spi.LiveTrees;
import com.telcobright.seed.sessionflow.spi.TreesMaker;
import com.telcobright.seed.tenant.api.TenantTrees;
import com.telcobright.seed.tenant.internal.LiveTenantTrees;
import com.telcobright.seed.tenant.internal.TreeGateHolder;
import com.telcobright.seed.tenant.internal.PrimeContextTreeSource;
import com.telcobright.seed.tenant.spi.TreeSource;

import java.time.Duration;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The roots' trees of a kit made from the bootstrap (R8): seed-tenant's {@link TenantTrees} — one snapshot per served root, from
 * prime-context's tree road ({@code POST <origin>/get-specific-tenant-root?name=<root>}, the origin of the bootstrap url), reloaded per root
 * by the doorbell ({@code config_event_loader_<root>} on the platform's Kafka). Found on the classpath by {@code RoutesphereKit.fromBootstrap}
 * ({@code META-INF/services}). P1-O: the tree road is asked with the bootstrap's own token ({@code Authorization: Bearer …} — the SAME one the
 * envelope is asked with, read at every ask), so prime-context may close its tree roads to callers without it (PC-0025 item 3).
 */
public final class PrimeContextTrees implements TreesMaker {

    /** The tree road's connect and read timeout. */
    public static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final BiFunction<String, Supplier<String>, TreeSource> sourceOf;
    private final boolean doorbell;

    /** The deployed trees: prime-context's road at the bootstrap's origin, asked with the bootstrap's own token; the doorbell on. */
    public PrimeContextTrees() { this(PrimeContextTrees::theTreeRoad, true); }

    /** @param sourceOf the tree source of prime-context's origin (a test's scripted trees); {@code doorbell} false = no Kafka consumer */
    public PrimeContextTrees(Function<String, TreeSource> sourceOf, boolean doorbell) { this((origin, bearer) -> sourceOf.apply(origin), doorbell); }

    private PrimeContextTrees(BiFunction<String, Supplier<String>, TreeSource> sourceOf, boolean doorbell) {
        this.sourceOf = sourceOf;
        this.doorbell = doorbell;
    }

    /** prime-context's tree road at its origin, with the bootstrap's token (null = none: no header). */
    private static TreeSource theTreeRoad(String origin, Supplier<String> bearer) { return new PrimeContextTreeSource(origin, TIMEOUT, bearer); }

    @Override
    public LiveTrees make(Deployment deployment, BootstrapSource source) {
        LiveTenantTrees.Served served = new LiveTenantTrees.Served(deployment.roots());
        TreeGateHolder gate = new TreeGateHolder();                       // P1-G: the road's gate, given after the trees are made
        TenantTreesBuilder b = TenantTrees.builder().directory(served::active).source(sourceOf.apply(source.origin(), source.bearer())).gate(gate);
        if (doorbell) b.doorbell(deployment.platformKafka(), deployment.app() + "-doorbell-" + UUID.randomUUID());
        return LiveTenantTrees.started(b.build(), served, gate);
    }
}
