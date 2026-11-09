package com.telcobright.seed.switchledgerremote.internal;

import com.telcobright.rtc.domainmodel.LevelAdmission;
import com.telcobright.seed.sessionflow.api.LogText;
import com.telcobright.seed.sessionflow.spi.LedgerPort;
import com.telcobright.seed.sessionflow.spi.LedgerPort.LedgerRefusal;
import com.telcobright.seed.sessionflow.spi.LedgerUnit;
import com.telcobright.seed.switchledgerremote.wire.Lock;
import com.telcobright.seed.switchledgerremote.wire.Tier;
import com.telcobright.seed.switchledgerremote.wire.UnitReply;
import com.telcobright.seed.switchledgerremote.wire.UnitRequest;
import com.telcobright.seed.switchledgerremote.wire.UnitRequest.StepCase;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * The server's side of a unit: the steps of one stream run in order on ONE thread of their own (the engine's account locks are
 * thread-owned: what {@code lock} takes, {@code commit} or {@code close} must give back from the same thread). A refusal of the
 * ledger's own goes back in the reply; any other failure ends the stream with its status and the unit closes with nothing written; a
 * stream idle longer than {@code idleMs} is ended the same way (DEADLINE_EXCEEDED) — a client that went away holds no lock.
 */
public final class UnitStream implements StreamObserver<UnitRequest> {

    private static final Logger log = LoggerFactory.getLogger(UnitStream.class);
    private static final Object END = new Object();
    private static final Object CANCELLED = new Object();

    private final LedgerPort port;
    private final StreamObserver<UnitReply> replies;
    private final long idleMs;
    private final long stepWithinMs;
    private final BlockingQueue<Object> steps = new LinkedBlockingQueue<>();

    public UnitStream(LedgerPort port, StreamObserver<UnitReply> replies, long idleMs, long stepWithinMs) {
        this.port = port;
        this.replies = replies;
        this.idleMs = idleMs;
        this.stepWithinMs = stepWithinMs;
        Thread.ofVirtual().name("switch-ledger-unit").start(this::run);
    }

    @Override public void onNext(UnitRequest step) { steps.add(step); }
    @Override public void onError(Throwable t) { steps.add(CANCELLED); }
    @Override public void onCompleted() { steps.add(END); }

    // ── the unit's thread ───────────────────────────────────────────────────

    private void run() {
        LedgerUnit unit = null;
        try {
            while (true) {
                Object step = steps.poll(idleMs, TimeUnit.MILLISECONDS);
                if (step == null) { end(Status.DEADLINE_EXCEEDED.withDescription("the unit was idle for " + idleMs + " ms")); return; }
                if (step == END) { replies.onCompleted(); return; }
                if (step == CANCELLED) return;
                if (unit == null) unit = port.unit();
                replies.onNext(answer(unit, (UnitRequest) step));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            end(Status.CANCELLED.withDescription("the unit's thread was interrupted"));
        } catch (RuntimeException e) {
            log.warn("switch-ledger unit: a step failed — the unit closes with nothing written ({})", LogText.failureText(e));
            end(Statuses.of(e).getStatus());
        } finally {
            if (unit != null) unit.close();
        }
    }

    private void end(Status status) {
        try {
            replies.onError(status.asRuntimeException());
        } catch (RuntimeException ignored) {
            // the client is already gone
        }
    }

    private UnitReply answer(LedgerUnit unit, UnitRequest step) {
        try {
            return switch (step.getStepCase()) {
                case LOCK -> { unit.lock(levelsOf(step.getLock())); yield UnitReply.newBuilder().setLocked(Wire.ack()).build(); }
                case RESERVE -> UnitReply.newBuilder().setHold(Wire.holdOf(unit.reserve(Wire.levelOf(step.getReserve().getTier()), Wire.decimal(step.getReserve().getAmount()), step.getReserve().getReference(), stepWithinMs))).build();
                case CHARGE -> UnitReply.newBuilder().setHold(Wire.holdOf(unit.charge(Wire.levelOf(step.getCharge().getTier()), Wire.decimal(step.getCharge().getAmount()), step.getCharge().getReference()))).build();
                case SETTLE -> UnitReply.newBuilder().setSettled(Wire.settledOf(unit.settle(Wire.levelOf(step.getSettle().getTier(), step.getSettle().getTotalReserved(), step.getSettle().getReservationCount()), Wire.decimal(step.getSettle().getCharged())))).build();
                case RELEASE -> { unit.release(Wire.levelOf(step.getRelease().getTier(), step.getRelease().getTotalReserved(), step.getRelease().getReservationCount()), step.getRelease().getWhy()); yield UnitReply.newBuilder().setReleased(Wire.ack()).build(); }
                case REFUND -> UnitReply.newBuilder().setSettled(Wire.settledOf(unit.refund(Wire.levelOf(step.getRefund().getTier(), step.getRefund().getTotalReserved(), step.getRefund().getReservationCount()), Wire.decimal(step.getRefund().getAmount()), step.getRefund().getWhy()))).build();
                case BALANCE_OF -> UnitReply.newBuilder().setBalance(Wire.balanceOf(unit.balanceOf(Wire.levelOf(step.getBalanceOf())))).build();
                case ABANDON -> { unit.abandon(step.getAbandon().getWhy()); yield UnitReply.newBuilder().setAbandoned(Wire.ack()).build(); }
                case COMMIT -> { unit.commit(step.getCommit().getWhat()); yield UnitReply.newBuilder().setCommitted(Wire.ack()).build(); }
                case STEP_NOT_SET -> throw new IllegalArgumentException("an empty step of the unit");
            };
        } catch (LedgerRefusal refusal) {
            return refused(step.getStepCase(), refusal);
        }
    }

    /** The refusal in the reply the step expects, so the client throws it as the embedded unit would. */
    private static UnitReply refused(StepCase step, LedgerRefusal refusal) {
        UnitReply.Builder out = UnitReply.newBuilder();
        return switch (step) {
            case LOCK -> out.setLocked(Wire.refusedAck(refusal)).build();
            case RESERVE, CHARGE -> out.setHold(Wire.refusedHold(refusal)).build();
            case SETTLE, REFUND -> out.setSettled(Wire.refusedSettled(refusal)).build();
            case RELEASE -> out.setReleased(Wire.refusedAck(refusal)).build();
            case BALANCE_OF -> out.setBalance(Wire.refusedBalance(refusal)).build();
            case ABANDON -> out.setAbandoned(Wire.refusedAck(refusal)).build();
            case COMMIT, STEP_NOT_SET -> out.setCommitted(Wire.refusedAck(refusal)).build();
        };
    }

    private static List<LevelAdmission> levelsOf(Lock lock) {
        List<LevelAdmission> levels = new ArrayList<>(lock.getAccountsCount());
        for (Tier tier : lock.getAccountsList()) levels.add(Wire.levelOf(tier));
        return levels;
    }
}
