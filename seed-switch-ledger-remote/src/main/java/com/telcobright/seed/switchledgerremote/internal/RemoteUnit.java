package com.telcobright.seed.switchledgerremote.internal;

import com.telcobright.rtc.domainmodel.LevelAdmission;
import com.telcobright.seed.sessionflow.api.TierSettlement;
import com.telcobright.seed.sessionflow.spi.LedgerPort.LedgerFault;
import com.telcobright.seed.sessionflow.spi.LedgerPort.Reservation;
import com.telcobright.seed.sessionflow.spi.LedgerUnit;
import com.telcobright.seed.switchledgerremote.wire.Abandon;
import com.telcobright.seed.switchledgerremote.wire.Commit;
import com.telcobright.seed.switchledgerremote.wire.Lock;
import com.telcobright.seed.switchledgerremote.wire.SwitchLedgerGrpc;
import com.telcobright.seed.switchledgerremote.wire.UnitReply;
import com.telcobright.seed.switchledgerremote.wire.UnitReply.AnswerCase;
import com.telcobright.seed.switchledgerremote.wire.UnitRequest;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.ClientCallStreamObserver;
import io.grpc.stub.StreamObserver;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * A {@link LedgerUnit} over ONE bidirectional stream (design §3.3): each step is a request and its reply, in order, on the session's
 * thread; the server runs the unit on one thread of its own so the account locks it takes at {@code lock} stay owned until {@code commit}
 * or {@code close}. A step unanswered within the deadline ({@code min(deadline-ms, withinMs)} for a reserve) cancels the stream — the
 * server's unit closes with nothing written — and is a {@code LedgerFault}. A refusal of the ledger's own travels in the reply and is
 * thrown as {@code LedgerRefusal}, as the embedded unit throws it.
 */
public final class RemoteUnit implements LedgerUnit {

    private static final Object END = new Object();

    private final StreamObserver<UnitRequest> requests;
    private final BlockingQueue<Object> replies = new LinkedBlockingQueue<>();
    private final long deadlineMs;
    private final String address;
    private final String tokenVar;
    private boolean open = true;

    public RemoteUnit(SwitchLedgerGrpc.SwitchLedgerStub stub, long deadlineMs, String address, String tokenVar) {
        this.deadlineMs = deadlineMs;
        this.address = address;
        this.tokenVar = tokenVar;
        this.requests = stub.unit(new StreamObserver<>() {
            @Override public void onNext(UnitReply reply) { replies.add(reply); }
            @Override public void onError(Throwable t) { replies.add(t); }
            @Override public void onCompleted() { replies.add(END); }
        });
    }

    @Override
    public void lock(Collection<LevelAdmission> accounts) {
        Lock.Builder lock = Lock.newBuilder();
        accounts.forEach(a -> lock.addAccounts(Wire.tierOf(a)));
        Wire.acked(expect("lock", AnswerCase.LOCKED, UnitRequest.newBuilder().setLock(lock).build(), deadlineMs).getLocked());
    }

    @Override
    public Optional<BigDecimal> balanceOf(LevelAdmission level) {
        return Wire.balanceOf(expect("balanceOf", AnswerCase.BALANCE, UnitRequest.newBuilder().setBalanceOf(Wire.tierOf(level)).build(), deadlineMs).getBalance());
    }

    @Override
    public Optional<Reservation> reserve(LevelAdmission level, BigDecimal amount, String reference, long withinMs) {
        long wait = withinMs > 0 ? Math.min(deadlineMs, withinMs) : deadlineMs;
        return Wire.reservationOf(expect("reserve", AnswerCase.HOLD, UnitRequest.newBuilder().setReserve(Wire.moveOf(level, amount, reference)).build(), wait).getHold());
    }

    @Override
    public Optional<Reservation> charge(LevelAdmission level, BigDecimal amount, String reference) {
        return Wire.reservationOf(expect("charge", AnswerCase.HOLD, UnitRequest.newBuilder().setCharge(Wire.moveOf(level, amount, reference)).build(), deadlineMs).getHold());
    }

    @Override
    public TierSettlement settle(LevelAdmission level, BigDecimal charged) {
        return Wire.settlementOf(expect("settle", AnswerCase.SETTLED, UnitRequest.newBuilder().setSettle(Wire.settlementOf(level, charged)).build(), deadlineMs).getSettled());
    }

    @Override
    public void release(LevelAdmission level, String why) {
        Wire.acked(expect("release", AnswerCase.RELEASED, UnitRequest.newBuilder().setRelease(Wire.releaseOf(level, why)).build(), deadlineMs).getReleased());
    }

    @Override
    public TierSettlement refund(LevelAdmission level, BigDecimal amount, String why) {
        return Wire.settlementOf(expect("refund", AnswerCase.SETTLED, UnitRequest.newBuilder().setRefund(Wire.refundOf(level, amount, why)).build(), deadlineMs).getSettled());
    }

    @Override
    public void abandon(String why) {
        Wire.acked(expect("abandon", AnswerCase.ABANDONED, UnitRequest.newBuilder().setAbandon(Abandon.newBuilder().setWhy(Wire.orEmpty(why))).build(), deadlineMs).getAbandoned());
    }

    @Override
    public void commit(String what) {
        Wire.acked(expect("commit", AnswerCase.COMMITTED, UnitRequest.newBuilder().setCommit(Commit.newBuilder().setWhat(Wire.orEmpty(what))).build(), deadlineMs).getCommitted());
    }

    /** The stream ends; a server-side unit still open closes with nothing written. */
    @Override
    public void close() {
        if (!open) return;
        open = false;
        try {
            requests.onCompleted();
        } catch (RuntimeException ignored) {
            // the stream already failed or was cancelled: nothing more to say to it
        }
    }

    // ── one step ────────────────────────────────────────────────────────────

    private UnitReply expect(String what, AnswerCase answer, UnitRequest request, long waitMs) {
        UnitReply reply = exchange(what, request, waitMs);
        if (reply.getAnswerCase() != answer) {
            cancel("the unit answered " + reply.getAnswerCase() + " to " + what);
            throw new LedgerFault("the switch-ledger at " + address + " answered " + reply.getAnswerCase() + " to the unit's " + what);
        }
        return reply;
    }

    private UnitReply exchange(String what, UnitRequest request, long waitMs) {
        if (!open) throw new LedgerFault("the unit at " + address + " is closed: " + what + " cannot be sent");
        send(what, request);
        Object reply = await(waitMs);
        if (reply == null) {
            cancel(what + " unanswered after " + waitMs + " ms");
            throw new LedgerFault("the switch-ledger at " + address + " did not answer the unit's " + what + " in " + waitMs + " ms");
        }
        if (reply == END) {
            open = false;
            throw new LedgerFault("the switch-ledger at " + address + " closed the unit before answering " + what);
        }
        if (reply instanceof Throwable t) {
            open = false;
            throw t instanceof StatusRuntimeException s ? Statuses.faultOf(s, "the unit's " + what, address, tokenVar) : new LedgerFault("the unit at " + address + " failed at " + what + ": " + t, t);
        }
        return (UnitReply) reply;
    }

    private void send(String what, UnitRequest request) {
        try {
            requests.onNext(request);
        } catch (RuntimeException e) {
            open = false;
            throw new LedgerFault("the unit at " + address + " could not send " + what + ": " + e.getMessage(), e);
        }
    }

    private Object await(long waitMs) {
        try {
            return replies.poll(waitMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            cancel("interrupted");
            throw new LedgerFault("interrupted while waiting for the switch-ledger at " + address, e);
        }
    }

    private void cancel(String why) {
        open = false;
        try {
            if (requests instanceof ClientCallStreamObserver<UnitRequest> call) call.cancel(why, null);
            else requests.onError(new IllegalStateException(why));
        } catch (RuntimeException ignored) {
            // already gone
        }
    }
}
