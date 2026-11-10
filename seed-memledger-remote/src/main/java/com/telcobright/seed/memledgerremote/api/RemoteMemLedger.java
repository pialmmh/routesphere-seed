package com.telcobright.seed.memledgerremote.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.telcobright.core.cache.CacheableEntity;
import com.telcobright.core.wal.WALEntryBatch;
import com.telcobright.memledger.api.MemLedger;
import com.telcobright.memledger.api.MemLedgerStats;
import com.telcobright.memledger.api.ReloadResult;
import com.telcobright.memledger.api.request.CrudRequest;
import com.telcobright.memledger.api.request.GetEntityRequest;
import com.telcobright.memledger.api.response.EntityResponse;
import com.telcobright.seed.memledgerremote.dependencies.RemoteLedgerSettings;
import com.telcobright.seed.memledgerremote.internal.BearerHeader;
import com.telcobright.seed.memledgerremote.internal.Calls;
import com.telcobright.seed.memledgerremote.internal.ServerCapabilities;
import com.telcobright.seed.memledgerremote.wire.ApplyReply;
import com.telcobright.seed.memledgerremote.wire.Database;
import com.telcobright.seed.memledgerremote.wire.DatabaseName;
import com.telcobright.seed.memledgerremote.wire.EntityKey;
import com.telcobright.seed.memledgerremote.wire.EntityReply;
import com.telcobright.seed.memledgerremote.wire.EntityRow;
import com.telcobright.seed.memledgerremote.wire.EntityTable;
import com.telcobright.seed.memledgerremote.wire.HealthReply;
import com.telcobright.seed.memledgerremote.wire.Nothing;
import com.telcobright.seed.memledgerremote.wire.ReloadFailure;
import com.telcobright.seed.memledgerremote.wire.ReloadReply;
import com.telcobright.seed.memledgerremote.wire.ReloadRequest;
import com.telcobright.seed.switchledger.spi.AnswersWithin;
import com.telcobright.seed.switchledger.spi.ExpectingLedger;
import com.telcobright.seed.switchledger.spi.GhostFreeReloads;
import io.grpc.Channel;
import io.grpc.ClientInterceptors;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * The application's MemLedger when its ledger is a server (ARCH-0117; design §3): {@code implements MemLedger} over the wire of
 * mem-ledger's own surface, so the seed's engine ({@code SwitchUnit}, {@code Books}, {@code AccountLocks}, the reaper, the cached
 * ledger's units) runs over it unchanged. The eight methods the engine calls and {@code health()} are served; every other method of
 * the interface says in words that it is not. A row comes back as a NEW object of the class its entity is registered with here — the
 * engine never changes a cached row in place. A batch is {@code Apply}: refused = an answer ({@code EntityResponse.error} with the
 * ledger's words, nothing changed); a server that does not answer = a {@link RemoteLedgerFault}. {@link #shutdown()} ends this
 * client's use of the server, never the server.
 *
 * <p>ARCH-0118 · Two applications on one instance: what the server does is asked when the client connects (Health's capabilities),
 * and asked again after the channel's connection changed — a server restarted into another version on the same address is followed
 * ({@link ServerCapabilities}). A server that takes out the ghosts of its own reload ({@code ghosts}) is a {@link GhostFreeReloads}: the engine's merge
 * then takes none out itself, and a reload answers {@code added} less the ghosts the server took out. A server that checks the rows a
 * batch read ({@code expect}) is an {@link ExpectingLedger}: a unit's batch carries each row it read as {@code RowJson.textOf} writes it,
 * and a row the other application moved meanwhile is a STALE refusal (the engine's LedgerFault in the server's words). An older server
 * (no capabilities) keeps today's client-side ghost rule and is sent no rows as read, said once in the log.
 */
public final class RemoteMemLedger implements MemLedger, AnswersWithin, GhostFreeReloads, ExpectingLedger {

    /** A whole table may come back in one stream's messages; a batch of many rows in one: room for both. */
    public static final int MAX_INBOUND_MESSAGE_BYTES = 32 * 1024 * 1024;

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final ManagedChannel channel;
    private final Calls calls;
    private final RemoteLedgerSettings settings;
    private final Map<String, Class<?>> entities;
    private final ServerCapabilities capabilities;
    private volatile boolean shutdown;

    private RemoteMemLedger(ManagedChannel channel, Calls calls, RemoteLedgerSettings settings, Map<String, ? extends Class<?>> entities, ServerCapabilities capabilities) {
        this.channel = channel;
        this.calls = calls;
        this.settings = settings;
        this.entities = Map.copyOf(entities);
        this.capabilities = capabilities;
    }

    /** A client of the server at {@code settings.address()}, plaintext on the private mesh; {@code entities} = the classes rows decode into, by name. */
    public static RemoteMemLedger connect(RemoteLedgerSettings settings, String bearer, Map<String, ? extends Class<?>> entities) {
        ManagedChannel channel = ManagedChannelBuilder.forTarget(settings.address()).usePlaintext().maxInboundMessageSize(MAX_INBOUND_MESSAGE_BYTES).build();
        try {
            return over(channel, settings, bearer, entities);
        } catch (RuntimeException e) {
            channel.shutdownNow();
            throw e;
        }
    }

    /** A client over a channel made elsewhere (a lab's loopback); {@code bearer} null = no header. The server's capabilities are asked here, and again after the channel changed. */
    public static RemoteMemLedger over(ManagedChannel channel, RemoteLedgerSettings settings, String bearer, Map<String, ? extends Class<?>> entities) {
        Channel withBearer = bearer == null ? channel : ClientInterceptors.intercept(channel, new BearerHeader(bearer));
        Calls calls = new Calls(withBearer, settings);
        return new RemoteMemLedger(channel, calls, settings, entities, ServerCapabilities.askedOf(channel, calls, settings.address()));
    }

    public RemoteLedgerSettings settings() { return settings; }

    @Override public long slowestAnswerMs() { return settings.deadlineMs(); }

    /** S1 · The server's reload takes out its own ghosts, under a gate no write passes: the engine must not take any out. */
    @Override public boolean takesOutGhosts() { return capabilities.now().contains(Capabilities.GHOSTS); }

    /** S2 · The server checks the rows a batch read, under the batch's row locks. */
    @Override public boolean checksExpectations() { return capabilities.now().contains(Capabilities.EXPECT); }

    // ── the eight methods the engine calls ──────────────────────────────────

    @Override
    @SuppressWarnings("unchecked")
    public <K, E extends CacheableEntity<K, ?>> E getEntity(String dbName, String entityName, K entityId) {
        EntityKey key = EntityKey.newBuilder().setDbName(orEmpty(dbName)).setEntityName(orEmpty(entityName)).setEntityId(RowJson.keyText(entityId)).build();
        EntityReply reply = calls.call("GetEntity", s -> s.getEntity(key));
        return reply.getFound() ? (E) RowJson.rowOf(classOf(entityName), reply.getEntityJson()) : null;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <K, E extends CacheableEntity<K, ?>> Map<K, E> getAllEntities(String dbName, String entityName) {
        EntityTable table = EntityTable.newBuilder().setDbName(orEmpty(dbName)).setEntityName(orEmpty(entityName)).build();
        return calls.call("GetAllEntities", s -> {
            Map<K, E> rows = new LinkedHashMap<>();
            for (Iterator<EntityRow> stream = s.getAllEntities(table); stream.hasNext(); ) {
                EntityRow row = stream.next();
                Class<?> type = classOf(entityName);
                rows.put((K) RowJson.keyOf(type, row.getEntityId()), (E) RowJson.rowOf(type, row.getEntityJson()));
            }
            return rows;
        });
    }

    /** THE write: the batch whole or not at all; a refusal is the answer the embedded ledger gives (not success, its words). */
    @Override
    public EntityResponse execCrud(WALEntryBatch batch) { return answerOf(calls.apply(WireBatch.requestOf(batch))); }

    /** S2 · THE write with the rows its unit read: one not as read = STALE, an error answer in the server's words, nothing changed. */
    @Override
    public EntityResponse execCrud(WALEntryBatch batch, List<AsRead> asRead) { return answerOf(calls.apply(WireExpectations.requestOf(batch, asRead))); }

    private static EntityResponse answerOf(ApplyReply reply) {
        return reply.getApplied() > 0 ? EntityResponse.success(null, reply.getMessage()) : EntityResponse.error(reply.getMessage());
    }

    @Override
    public MemLedgerStats getStats() {
        String json = calls.call("CacheStatistics", s -> s.cacheStatistics(Nothing.getDefaultInstance())).getStatisticsJson();
        MemLedgerStats stats = new MemLedgerStats();
        stats.setInitialized(true);
        stats.setCacheStatistics(mapOf(json));
        return stats;
    }

    @Override
    public List<String> getRegisteredDatabases() {
        return List.copyOf(calls.call("RegisteredDatabases", s -> s.registeredDatabases(Nothing.getDefaultInstance())).getNamesList());
    }

    /** The rows merged, less the ghosts the server took out again at once (0 from an older server). */
    @Override
    public ReloadResult reloadNewRows() {
        ReloadReply reply = calls.call("ReloadNewRows", s -> s.reloadNewRows(ReloadRequest.getDefaultInstance()));
        List<ReloadResult.Failure> failures = new ArrayList<>();
        for (ReloadFailure f : reply.getFailuresList()) failures.add(new ReloadResult.Failure(f.getSchema(), f.getEntity(), new IllegalStateException(f.getReason())));
        return new ReloadResult(reply.getAdded() - reply.getGhostsTakenOut(), reply.getScanned(), failures, reply.getKeptNotReadList());
    }

    @Override
    public boolean addDatabase(String schema) {
        return calls.call("AddDatabase", s -> s.addDatabase(DatabaseName.newBuilder().setName(orEmpty(schema)).build())).getAdded();
    }

    /** This client's channel is closed; the server goes on. */
    @Override
    public void shutdown() {
        shutdown = true;
        channel.shutdownNow();
        try {
            channel.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ── health, for the watch ───────────────────────────────────────────────

    /** The server's Health in the shape of mem-ledger's own map: status UP, DEGRADED or DOWN, the write-behind's state, the WAL lag, the tiers. */
    @Override
    public Map<String, Object> health() {
        HealthReply reply = calls.call("Health", s -> s.health(Nothing.getDefaultInstance()));
        Map<String, Boolean> tiers = new LinkedHashMap<>();
        for (Database d : reply.getDatabasesList()) tiers.put(d.getName(), d.getLoaded());
        Map<String, Object> health = new LinkedHashMap<>();
        health.put("status", !reply.getReady() ? "DOWN" : "OK".equals(reply.getPersistence()) ? "UP" : "DEGRADED");
        health.put("initialized", reply.getReady());
        health.put("shutdown", shutdown);
        health.put("persistence", Map.of("status", reply.getPersistence()));
        health.put("wal_lag", reply.getWalLag());
        health.put("databases", tiers);
        health.put("version", reply.getVersion());
        health.put("address", settings.address());
        return health;
    }

    @Override public boolean isInitialized() { return !shutdown; }

    // ── what the server does not serve ──────────────────────────────────────

    @Override public EntityResponse getEntity(GetEntityRequest request) { throw notServed("getEntity(GetEntityRequest)"); }
    @Override public EntityResponse execCrud(CrudRequest request) { throw notServed("execCrud(CrudRequest): the one write is execCrud(WALEntryBatch)"); }
    @Override public <K, E extends CacheableEntity<K, ?>> boolean insert(String dbName, String entityName, E entity) { throw notServed("insert: the one write is execCrud(WALEntryBatch)"); }
    @Override public <K, E extends CacheableEntity<K, ?>> boolean update(String dbName, String entityName, E entity) { throw notServed("update: the one write is execCrud(WALEntryBatch)"); }
    @Override public <K, D, E extends CacheableEntity<K, D>> E updateWithDelta(String dbName, String entityName, K entityId, D delta) { throw notServed("updateWithDelta: no delta crosses the wire, only post-images"); }
    @Override public <K> boolean delete(String dbName, String entityName, K entityId) { throw notServed("delete: the one write is execCrud(WALEntryBatch)"); }
    @Override public void reload() { throw notServed("reload: ask reloadNewRows()"); }
    @Override public List<String> reloadDatabases() { throw notServed("reloadDatabases: a tier is added by addDatabase(schema)"); }
    @Override public int reloadNewEntities() { throw notServed("reloadNewEntities: ask reloadNewRows()"); }

    private UnsupportedOperationException notServed(String what) {
        return new UnsupportedOperationException("the mem-ledger server at " + settings.address() + " does not serve " + what);
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private Class<?> classOf(String entityName) {
        Class<?> type = entities.get(entityName);
        if (type == null) throw new IllegalStateException("the mem-ledger server at " + settings.address() + " answered a row of '" + entityName + "', an entity this ledger does not register (" + entities.keySet() + ")");
        return type;
    }

    private static Map<String, Object> mapOf(String json) {
        try {
            return json == null || json.isBlank() ? Map.of() : JSON.readValue(json, new TypeReference<LinkedHashMap<String, Object>>() {});
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("the cache's statistics could not be read: " + e.getOriginalMessage(), e);
        }
    }

    private static String orEmpty(String text) { return text == null ? "" : text; }
}
