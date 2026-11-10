package com.telcobright.seed.memledgerremote.internal;

import com.telcobright.seed.memledgerremote.api.RemoteLedgerFault;
import com.telcobright.seed.memledgerremote.dependencies.RemoteLedgerSettings;
import com.telcobright.seed.memledgerremote.wire.ApplyLockedRequest;
import com.telcobright.seed.memledgerremote.wire.ApplyReply;
import com.telcobright.seed.memledgerremote.wire.ApplyRequest;
import com.telcobright.seed.memledgerremote.wire.MemLedgerGrpc;
import com.telcobright.seed.memledgerremote.wire.MemLedgerGrpc.MemLedgerBlockingStub;
import io.grpc.Channel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * One call of the client: a blocking stub bounded by {@code deadline-ms}, and any status but OK turned into a
 * {@link RemoteLedgerFault} in words that name the server, the verb and what it said. The write alone is sent a second time —
 * ONCE, the SAME batch under the SAME transaction id — when its deadline passes (design §2.3): the first may have been applied, and
 * the server answers a repeat with {@code already_applied}; a second failure is a fault whose words say the outcome is UNKNOWN.
 */
public final class Calls {

    private static final Logger log = LoggerFactory.getLogger(Calls.class);
    /** The greppable words of a batch whose fate the client cannot tell (remote-fixes item (e)). */
    public static final String UNKNOWN_OUTCOME = "UNKNOWN outcome";

    private final MemLedgerBlockingStub blocking;
    private final RemoteLedgerSettings settings;

    public Calls(Channel channel, RemoteLedgerSettings settings) {
        this.blocking = MemLedgerGrpc.newBlockingStub(channel);
        this.settings = settings;
    }

    /** A read, a reload, a tier's load, Health: one try within the deadline. */
    public <T> T call(String what, Function<MemLedgerBlockingStub, T> verb) {
        try {
            return verb.apply(withinTheDeadline());
        } catch (StatusRuntimeException e) {
            throw faultOf(e, what);
        }
    }

    /** THE write: once, and once more under the same transaction id when the first try's deadline passed. */
    public ApplyReply apply(ApplyRequest batch) {
        try {
            return withinTheDeadline().apply(batch);
        } catch (StatusRuntimeException first) {
            if (first.getStatus().getCode() != Status.Code.DEADLINE_EXCEEDED) throw faultOf(first, "Apply");
            log.warn("mem-ledger client: {} did not answer Apply of batch {} in {} ms — sent once more under the same id", settings.address(), batch.getTransactionId(), settings.deadlineMs());
        }
        try {
            return withinTheDeadline().apply(batch);
        } catch (StatusRuntimeException second) {
            throw unknownOutcome(batch.getTransactionId(), second);
        }
    }

    /** ARCH-0119 road 2 · The write under a lock: as {@link #apply}, once more under the same id when the first try's deadline passed. */
    public ApplyReply applyLocked(ApplyLockedRequest locked) {
        String id = locked.getBatch().getTransactionId();
        try {
            return withinTheDeadline().applyLocked(locked);
        } catch (StatusRuntimeException first) {
            if (first.getStatus().getCode() != Status.Code.DEADLINE_EXCEEDED) throw faultOf(first, "ApplyLocked");
            log.warn("mem-ledger client: {} did not answer ApplyLocked of batch {} in {} ms — sent once more under the same id", settings.address(), id, settings.deadlineMs());
        }
        try {
            return withinTheDeadline().applyLocked(locked);
        } catch (StatusRuntimeException second) {
            throw unknownOutcome(id, second);
        }
    }

    /** ARCH-0119 road 2 · A call that may WAIT on the server (a lock's queue): its deadline is the client's own plus that wait. */
    public <T> T callWaiting(String what, long waitMs, Function<MemLedgerBlockingStub, T> verb) {
        try {
            return verb.apply(blocking.withDeadlineAfter(settings.deadlineMs() + Math.max(0, waitMs), TimeUnit.MILLISECONDS));
        } catch (StatusRuntimeException e) {
            throw faultOf(e, what);
        }
    }

    /**
     * Remote-fixes item (e) · The first try's deadline passed and the second try did not tell: the batch may still be applied by the server
     * (a stall inside its append) — said plainly, with the transaction id to reconcile by. The behaviour stays: no third try; the engine
     * takes it as a fault and records nothing.
     */
    private RemoteLedgerFault unknownOutcome(String transactionId, StatusRuntimeException second) {
        String secondSaid = second.getStatus().getCode() == Status.Code.DEADLINE_EXCEEDED ? "" : " (the second try: " + faultOf(second, "Apply").getMessage() + ")";
        return new RemoteLedgerFault("the mem-ledger server at " + settings.address() + ": " + UNKNOWN_OUTCOME + ": batch " + transactionId + " sent twice, no answer within "
            + settings.deadlineMs() + " ms — reconcile by this transaction id" + secondSaid, second);
    }

    private MemLedgerBlockingStub withinTheDeadline() { return blocking.withDeadlineAfter(settings.deadlineMs(), TimeUnit.MILLISECONDS); }

    private RemoteLedgerFault faultOf(StatusRuntimeException failure, String what) {
        Status status = failure.getStatus();
        String said = status.getDescription() == null ? "" : ": " + status.getDescription();
        String server = "the mem-ledger server at " + settings.address();
        String words = switch (status.getCode()) {
            case UNAVAILABLE -> server + " is not serving (" + what + ")" + said;
            case DEADLINE_EXCEEDED -> server + " did not answer " + what + " in " + settings.deadlineMs() + " ms" + said;
            case UNAUTHENTICATED, PERMISSION_DENIED -> server + " refused the bearer" + (settings.tokenVar() == null ? " (no token-var is set)" : " in " + settings.tokenVar()) + " (" + what + ")" + said;
            default -> server + " answered " + status.getCode() + " to " + what + said;
        };
        return new RemoteLedgerFault(words, failure);
    }
}
