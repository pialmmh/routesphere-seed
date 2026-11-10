package com.telcobright.seed.memledgerremote;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.telcobright.core.cache.CacheableEntity;
import com.telcobright.memledger.api.MemLedger;
import com.telcobright.memledger.api.response.EntityResponse;
import com.telcobright.seed.memledgerremote.api.BearerGuard;
import com.telcobright.seed.memledgerremote.api.RowJson;
import com.telcobright.seed.memledgerremote.api.WireBatch;
import com.telcobright.seed.memledgerremote.wire.AddDatabaseReply;
import com.telcobright.seed.memledgerremote.wire.ApplyReply;
import com.telcobright.seed.memledgerremote.wire.ApplyRequest;
import com.telcobright.seed.memledgerremote.wire.Database;
import com.telcobright.seed.memledgerremote.wire.DatabaseList;
import com.telcobright.seed.memledgerremote.wire.DatabaseName;
import com.telcobright.seed.memledgerremote.wire.EntityKey;
import com.telcobright.seed.memledgerremote.wire.EntityReply;
import com.telcobright.seed.memledgerremote.wire.EntityRow;
import com.telcobright.seed.memledgerremote.wire.EntityTable;
import com.telcobright.seed.memledgerremote.wire.HealthReply;
import com.telcobright.seed.memledgerremote.wire.MemLedgerGrpc;
import com.telcobright.seed.memledgerremote.wire.Nothing;
import com.telcobright.seed.memledgerremote.wire.StatisticsReply;
import com.telcobright.seed.switchledger.internal.LedgerEntities;
import io.grpc.Server;
import io.grpc.ServerInterceptors;
import io.grpc.Status;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.StreamObserver;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

/**
 * A stand-in for the mem-ledger server in this module's tests (the real one is its own repository, proven there by the twice-run): the
 * wire in front of a REAL MemLedger (the switch ledger's lab) on a loopback grpc-netty-shaded server (127.0.0.1, an ephemeral port),
 * behind the bearer guard with a token minted for this run. It can be told to behave badly: {@link #faultEveryWrite}, or to answer a
 * write only after a wait ({@link #stallTheFirstTryOfEachBatch}, {@link #stallEveryWrite}) — what a client must survive.
 */
final class StubServer extends MemLedgerGrpc.MemLedgerImplBase implements AutoCloseable {

    final String token = UUID.randomUUID().toString();
    final List<String> writesSeen = new CopyOnWriteArrayList<>();
    private final MemLedger ledger;
    private final Server server;
    private final Set<String> applied = ConcurrentHashMap.newKeySet();
    private final Set<String> triedOnce = ConcurrentHashMap.newKeySet();
    private volatile boolean faultWrites;
    private volatile long stallFirstMs, stallEveryMs;

    StubServer(MemLedger ledger) {
        this.ledger = ledger;
        try {
            server = NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1", 0)).addService(ServerInterceptors.intercept(this, new BearerGuard(token))).build().start();
        } catch (IOException e) {
            throw new IllegalStateException("the stub server did not start", e);
        }
    }

    String address() { return "127.0.0.1:" + server.getPort(); }

    /** Every write answers UNAVAILABLE and changes nothing (a WAL that would not append). */
    void faultEveryWrite() { faultWrites = true; }

    /** The first try of each batch is answered only after {@code ms} (it IS applied); a repeat under the same id is answered at once. */
    void stallTheFirstTryOfEachBatch(long ms) { stallFirstMs = ms; }

    /** Every write waits {@code ms} and then fails: nothing is ever applied. */
    void stallEveryWrite(long ms) { stallEveryMs = ms; }

    @Override
    public void apply(ApplyRequest request, StreamObserver<ApplyReply> out) {
        writesSeen.add(request.getTransactionId());
        if (faultWrites) { out.onError(Status.UNAVAILABLE.withDescription("Batch failed, nothing changed: the stub's WAL would not append").asRuntimeException()); return; }
        if (stallEveryMs > 0) { pause(stallEveryMs); out.onError(Status.UNAVAILABLE.withDescription("stalled").asRuntimeException()); return; }
        boolean firstTry = triedOnce.add(request.getTransactionId());
        ApplyReply reply = applied.contains(request.getTransactionId()) ? ApplyReply.newBuilder().setApplied(request.getEntriesCount()).setAlreadyApplied(true).build() : applyNow(request);
        if (firstTry && stallFirstMs > 0) pause(stallFirstMs);
        out.onNext(reply);
        out.onCompleted();
    }

    private ApplyReply applyNow(ApplyRequest request) {
        EntityResponse answer = ledger.execCrud(WireBatch.batchOf(request, StubServer::classOf));
        if (!answer.isSuccess()) return ApplyReply.newBuilder().setMessage(answer.getMessage()).setRefusalCode("REFUSED").build();
        applied.add(request.getTransactionId());
        return ApplyReply.newBuilder().setApplied(request.getEntriesCount()).setMessage(answer.getMessage()).build();
    }

    @Override
    public void getEntity(EntityKey key, StreamObserver<EntityReply> out) {
        Class<?> type = classOf(key.getEntityName());
        CacheableEntity<Object, ?> row = type == null ? null : ledger.getEntity(key.getDbName(), key.getEntityName(), RowJson.keyOf(type, key.getEntityId()));
        out.onNext(row == null ? EntityReply.getDefaultInstance() : EntityReply.newBuilder().setFound(true).setEntityJson(RowJson.textOf(row)).build());
        out.onCompleted();
    }

    @Override
    public void getAllEntities(EntityTable table, StreamObserver<EntityRow> out) {
        Map<Object, CacheableEntity<Object, ?>> rows = ledger.getAllEntities(table.getDbName(), table.getEntityName());
        if (rows != null) rows.forEach((key, row) -> out.onNext(EntityRow.newBuilder().setEntityId(RowJson.keyText(key)).setEntityJson(RowJson.textOf(row)).build()));
        out.onCompleted();
    }

    @Override
    public void cacheStatistics(Nothing request, StreamObserver<StatisticsReply> out) {
        try {
            out.onNext(StatisticsReply.newBuilder().setStatisticsJson(JsonMapper.builder().build().writeValueAsString(ledger.getStats().getCacheStatistics())).build());
            out.onCompleted();
        } catch (Exception e) {
            out.onError(Status.INTERNAL.withDescription(e.toString()).asRuntimeException());
        }
    }

    @Override
    public void registeredDatabases(Nothing request, StreamObserver<DatabaseList> out) {
        out.onNext(DatabaseList.newBuilder().addAllNames(ledger.getRegisteredDatabases()).build());
        out.onCompleted();
    }

    @Override
    public void addDatabase(DatabaseName request, StreamObserver<AddDatabaseReply> out) {
        try {
            out.onNext(AddDatabaseReply.newBuilder().setAdded(ledger.addDatabase(request.getName())).build());
            out.onCompleted();
        } catch (RuntimeException e) {
            out.onError(Status.FAILED_PRECONDITION.withDescription(String.valueOf(e.getMessage())).asRuntimeException());
        }
    }

    @Override
    public void health(Nothing request, StreamObserver<HealthReply> out) {
        HealthReply.Builder reply = HealthReply.newBuilder().setReady(true).setWalLag(0).setPersistence("OK").setVersion("stub");
        for (String tier : ledger.getRegisteredDatabases()) reply.addDatabases(Database.newBuilder().setName(tier).setLoaded(true));
        out.onNext(reply.build());
        out.onCompleted();
    }

    private static Class<?> classOf(String entityName) {
        return LedgerEntities.known().stream().filter(k -> k.name().equals(entityName)).findFirst().<Class<?>>map(LedgerEntities.Known::type).orElse(null);
    }

    private static void pause(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void close() {
        server.shutdownNow();
        try {
            server.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
