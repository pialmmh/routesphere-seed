package com.telcobright.seed.switchledgerremote;

import com.telcobright.seed.sessionflow.spi.LedgerPort;
import com.telcobright.seed.switchledger.LedgerLab;
import com.telcobright.seed.switchledger.api.SwitchLedger;
import com.telcobright.seed.switchledger.dependencies.SwitchLedgerSettings;
import com.telcobright.seed.switchledger.dependencies.SwitchLedgers;
import com.telcobright.seed.switchledger.internal.Books;
import com.telcobright.seed.switchledgerremote.api.AccountReads;
import com.telcobright.seed.switchledgerremote.api.BearerGuard;
import com.telcobright.seed.switchledgerremote.api.CreditDoor;
import com.telcobright.seed.switchledgerremote.api.LedgerHealth;
import com.telcobright.seed.switchledgerremote.api.RemoteSwitchLedger;
import com.telcobright.seed.switchledgerremote.api.SwitchLedgerGrpcService;
import com.telcobright.seed.switchledgerremote.dependencies.RemoteLedgerSettings;
import com.telcobright.seed.switchledgerremote.internal.WireCatalog;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.ServerInterceptors;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Clock;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The twice-run's second road in one JVM: the engine over a {@link LedgerLab} (H2, MemLedger STANDALONE), served by
 * {@link SwitchLedgerGrpcService} on a loopback grpc-netty-shaded server (127.0.0.1, an ephemeral port — never 0.0.0.0, never
 * grpc-inprocess), read through a {@link RemoteSwitchLedger} with a bearer minted for this run (no fixture carries a token's value).
 */
final class LoopbackLedger implements AutoCloseable {

    static final long DEADLINE_MS = 2_000;

    final LedgerLab lab;
    final SwitchLedger engine;
    final Server server;
    final ManagedChannel channel;
    final RemoteSwitchLedger client;
    final String token = UUID.randomUUID().toString();
    final AtomicLong minted = new AtomicLong(5_000);
    final LedgerPort.Ids ids = (table, highest) -> Math.max(highest + 1, minted.incrementAndGet());

    private LoopbackLedger(LedgerLab lab, Set<String> held, List<String> tiers) throws IOException {
        this.lab = lab;
        lab.start();
        engine = SwitchLedgers.over(lab.ledger(), new SwitchLedgerSettings(60, 500, DEADLINE_MS), Clock.systemUTC());
        SwitchLedgerGrpcService.Served served = new SwitchLedgerGrpcService.Served(
            engine.port(), engine.balances(), CreditDoor.of(engine.credit()),
            AccountReads.over(lab.ledger(), held, ZoneId.of("Asia/Dhaka")),
            db -> WireCatalog.snapshotOf(lab.ledger(), db, held),
            () -> LedgerHealth.of(lab.ledger(), tiers, db -> true, "loopback"),
            ids);
        server = NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1", 0))
            .addService(ServerInterceptors.intercept(new SwitchLedgerGrpcService(served), new BearerGuard(token)))
            .build().start();
        channel = NettyChannelBuilder.forAddress("127.0.0.1", server.getPort()).usePlaintext().build();
        client = RemoteSwitchLedger.over(channel, new RemoteLedgerSettings(address(), DEADLINE_MS, "LOOPBACK_TOKEN"), token);
    }

    /** The lab's scene served; {@code held} = the entity names the lab's ledger caches (the account and reserve books unless said otherwise). */
    static LoopbackLedger serve(LedgerLab lab, Set<String> held, String... tiers) {
        try {
            return new LoopbackLedger(lab, held, List.of(tiers));
        } catch (IOException e) {
            throw new IllegalStateException("the loopback server did not start", e);
        }
    }

    static LoopbackLedger serve(LedgerLab lab, String... tiers) { return serve(lab, Set.of(Books.ACCOUNT, Books.RESERVE), tiers); }

    String address() { return "127.0.0.1:" + server.getPort(); }

    /** A client of this run's token over a channel of its own (the bearer tests and the second client of a race). */
    RemoteSwitchLedger anotherClient(String bearer) {
        ManagedChannel other = NettyChannelBuilder.forAddress("127.0.0.1", server.getPort()).usePlaintext().build();
        return RemoteSwitchLedger.over(other, new RemoteLedgerSettings(address(), DEADLINE_MS, "LOOPBACK_TOKEN"), bearer);
    }

    @Override
    public void close() {
        client.close();
        channel.shutdownNow();
        server.shutdownNow();
        try {
            server.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        lab.close();
    }
}
