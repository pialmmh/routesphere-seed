package com.telcobright.seed.switchledgerremote.api;

import com.telcobright.seed.sessionflow.spi.LedgerPort;
import com.telcobright.seed.sessionflow.spi.LedgerPort.LedgerRefusal;
import com.telcobright.seed.switchledger.api.LiveBalance;
import com.telcobright.seed.switchledgerremote.internal.Statuses;
import com.telcobright.seed.switchledgerremote.internal.UnitStream;
import com.telcobright.seed.switchledgerremote.internal.Wire;
import com.telcobright.seed.switchledgerremote.internal.WireRows;
import com.telcobright.seed.switchledgerremote.wire.Account;
import com.telcobright.seed.switchledgerremote.wire.AccountIds;
import com.telcobright.seed.switchledgerremote.wire.AccountRef;
import com.telcobright.seed.switchledgerremote.wire.Accounts;
import com.telcobright.seed.switchledgerremote.wire.Ack;
import com.telcobright.seed.switchledgerremote.wire.Balance;
import com.telcobright.seed.switchledgerremote.wire.Buyer;
import com.telcobright.seed.switchledgerremote.wire.CapRequest;
import com.telcobright.seed.switchledgerremote.wire.CatalogSnapshot;
import com.telcobright.seed.switchledgerremote.wire.CreditRequest;
import com.telcobright.seed.switchledgerremote.wire.Credited;
import com.telcobright.seed.switchledgerremote.wire.Empty;
import com.telcobright.seed.switchledgerremote.wire.HealthReply;
import com.telcobright.seed.switchledgerremote.wire.Hold;
import com.telcobright.seed.switchledgerremote.wire.Move;
import com.telcobright.seed.switchledgerremote.wire.PurchaseRef;
import com.telcobright.seed.switchledgerremote.wire.PurchaseRequest;
import com.telcobright.seed.switchledgerremote.wire.Purchased;
import com.telcobright.seed.switchledgerremote.wire.Purchases;
import com.telcobright.seed.switchledgerremote.wire.RefundRequest;
import com.telcobright.seed.switchledgerremote.wire.ReleaseRequest;
import com.telcobright.seed.switchledgerremote.wire.ReparentRequest;
import com.telcobright.seed.switchledgerremote.wire.Settled;
import com.telcobright.seed.switchledgerremote.wire.Settlement;
import com.telcobright.seed.switchledgerremote.wire.SwitchLedgerGrpc;
import com.telcobright.seed.switchledgerremote.wire.Tier;
import com.telcobright.seed.switchledgerremote.wire.TierDb;
import com.telcobright.seed.switchledgerremote.wire.UnitReply;
import com.telcobright.seed.switchledgerremote.wire.UnitRequest;
import io.grpc.Context;
import io.grpc.Deadline;
import io.grpc.stub.StreamObserver;

import java.time.Instant;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The engine on the wire: every verb of {@code telcobright.switchledger.v1.SwitchLedger} answered by the embedded port and its doors
 * (design §3.3). A {@code LedgerRefusal} is a reply with status OK carrying the refusal; a {@code LedgerFault} is INTERNAL; the credit and
 * cap doors' {@code IllegalArgumentException} is NOT_FOUND (an unheld account) or INVALID_ARGUMENT. The service extends this class with
 * its {@code @GrpcService}; the twice-run proof serves it on a loopback netty server. Not final for that reason only.
 */
public class SwitchLedgerGrpcService extends SwitchLedgerGrpc.SwitchLedgerImplBase {

    /** What the service hands the wire: the port, the live balances, the two doors, the catalog of a tier, the health, the id source. */
    public record Served(LedgerPort port, LiveBalance balances, CreditDoor credit, AccountReads reads,
                         Function<String, CatalogSnapshot> catalogs, Supplier<LedgerHealth> health, LedgerPort.Ids ids) {}

    public static final long DEFAULT_UNIT_IDLE_MS = 30_000;
    public static final long DEFAULT_STEP_WITHIN_MS = 2_000;

    private final Served served;
    private final long unitIdleMs;
    private final long stepWithinMs;

    public SwitchLedgerGrpcService(Served served) { this(served, DEFAULT_UNIT_IDLE_MS, DEFAULT_STEP_WITHIN_MS); }

    public SwitchLedgerGrpcService(Served served, long unitIdleMs, long stepWithinMs) {
        this.served = served;
        this.unitIdleMs = unitIdleMs;
        this.stepWithinMs = stepWithinMs;
    }

    // ── money moves ─────────────────────────────────────────────────────────

    @Override public void reserve(Move in, StreamObserver<Hold> out) {
        answer(out, () -> Wire.holdOf(served.port().reserve(Wire.levelOf(in.getTier()), Wire.decimal(in.getAmount()), in.getReference(), remainingMs())), Wire::refusedHold);
    }

    @Override public void charge(Move in, StreamObserver<Hold> out) {
        answer(out, () -> Wire.holdOf(served.port().charge(Wire.levelOf(in.getTier()), Wire.decimal(in.getAmount()), in.getReference())), Wire::refusedHold);
    }

    @Override public void settle(Settlement in, StreamObserver<Settled> out) {
        answer(out, () -> Wire.settledOf(served.port().settle(Wire.levelOf(in.getTier(), in.getTotalReserved(), in.getReservationCount()), Wire.decimal(in.getCharged()))), Wire::refusedSettled);
    }

    @Override public void release(ReleaseRequest in, StreamObserver<Ack> out) {
        answer(out, () -> { served.port().release(Wire.levelOf(in.getTier(), in.getTotalReserved(), in.getReservationCount()), in.getWhy()); return Wire.ack(); }, Wire::refusedAck);
    }

    @Override public void refund(RefundRequest in, StreamObserver<Settled> out) {
        answer(out, () -> Wire.settledOf(served.port().refund(Wire.levelOf(in.getTier(), in.getTotalReserved(), in.getReservationCount()), Wire.decimal(in.getAmount()), in.getWhy())), Wire::refusedSettled);
    }

    @Override public StreamObserver<UnitRequest> unit(StreamObserver<UnitReply> out) { return new UnitStream(served.port(), out, unitIdleMs, stepWithinMs); }

    // ── the credit door ─────────────────────────────────────────────────────

    @Override public void credit(CreditRequest in, StreamObserver<Credited> out) {
        answer(out, () -> Wire.creditedOf(served.credit().credit(in.getAccount().getDbName(), in.getAccount().getPackageAccountId(), Wire.decimal(in.getAmount()), in.getReference())), Wire::refusedCredited);
    }

    @Override public void reparent(ReparentRequest in, StreamObserver<Credited> out) {
        answer(out, () -> Wire.creditedOf(served.credit().reparent(in.getAccount().getDbName(), in.getAccount().getPackageAccountId(), in.getPurchaseId())), Wire::refusedCredited);
    }

    @Override public void capAt(CapRequest in, StreamObserver<Credited> out) {
        answer(out, () -> Wire.creditedOf(served.credit().capAt(in.getAccount().getDbName(), in.getAccount().getPackageAccountId(), Wire.decimal(in.getCeiling()), in.getReference())), Wire::refusedCredited);
    }

    // ── reads and purchases ─────────────────────────────────────────────────

    @Override public void balanceOf(Tier in, StreamObserver<Balance> out) {
        answer(out, () -> Wire.balanceOf(served.balances().balanceOf(Wire.levelOf(in))), Wire::refusedBalance);
    }

    @Override public void purchase(PurchaseRequest in, StreamObserver<Purchased> out) {
        answer(out, () -> WireRows.purchasedOf(served.port().purchase(Wire.levelOf(in.getBuyer()), WireRows.offerOf(in.getOffer()), in.getReference(), served.ids())), WireRows::refusedPurchased);
    }

    @Override public void accountsOf(Buyer in, StreamObserver<Accounts> out) {
        answer(out, () -> WireRows.accountsOf(served.port().accountsOf(Wire.levelOf(in))), WireRows::refusedAccounts);
    }

    @Override public void purchasesOf(Buyer in, StreamObserver<Purchases> out) {
        answer(out, () -> WireRows.purchasesOf(served.port().purchasesOf(Wire.levelOf(in))), WireRows::refusedPurchases);
    }

    @Override public void catalog(Tier in, StreamObserver<CatalogSnapshot> out) { answer(out, () -> served.catalogs().apply(in.getDbName()), r -> CatalogSnapshot.newBuilder().setPresent(false).build()); }

    @Override public void accountById(AccountRef in, StreamObserver<Account> out) {
        answer(out, () -> served.reads().accountById(in.getDbName(), in.getAccountId()).map(f -> WireRows.accountOf(f.account(), f.expires())).orElse(WireRows.noAccount()), r -> WireRows.noAccount());
    }

    @Override public void accountOfPurchase(PurchaseRef in, StreamObserver<Account> out) {
        Instant at = in.getAtEpochMs() == 0 ? Instant.EPOCH : Instant.ofEpochMilli(in.getAtEpochMs());
        answer(out, () -> served.reads().accountOf(in.getDbName(), in.getPurchaseId(), at).map(f -> WireRows.accountOf(f.account(), f.expires())).orElse(WireRows.noAccount()), r -> WireRows.noAccount());
    }

    @Override public void heldAccounts(TierDb in, StreamObserver<AccountIds> out) {
        answer(out, () -> AccountIds.newBuilder().addAllIds(served.reads().heldAccounts(in.getDbName())).build(), r -> AccountIds.getDefaultInstance());
    }

    @Override public void health(Empty in, StreamObserver<HealthReply> out) { answer(out, () -> served.health().get().toWire(), r -> LedgerHealth.unknown().toWire()); }

    // ── one answer ──────────────────────────────────────────────────────────

    /** The call answered; a refusal of the ledger's own as a reply (status OK); any other failure as its status. */
    private static <T> void answer(StreamObserver<T> out, Supplier<T> call, Function<LedgerRefusal, T> refused) {
        T reply;
        try {
            reply = call.get();
        } catch (LedgerRefusal refusal) {
            reply = refused.apply(refusal);
        } catch (RuntimeException failure) {
            out.onError(Statuses.of(failure));
            return;
        }
        out.onNext(reply);
        out.onCompleted();
    }

    /** What is left of the caller's deadline — the reserve's {@code withinMs}; the port's own default when the caller set none. */
    private static long remainingMs() {
        Deadline deadline = Context.current().getDeadline();
        return deadline == null ? 0 : Math.max(1, deadline.timeRemaining(TimeUnit.MILLISECONDS));
    }
}
