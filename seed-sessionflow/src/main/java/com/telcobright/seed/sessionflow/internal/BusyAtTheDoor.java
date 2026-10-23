package com.telcobright.seed.sessionflow.internal;

import com.telcobright.statewalk.registry.RejectCause;

import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.RejectedExecutionException;

/**
 * P1-F · A NEW session refused at the registry's door because every machine is live. statewalk 4.1 answers that first frame's stage with
 * {@code RejectedExecutionException("[<lane>] new session id=<id> refused: CAPACITY_EXCEEDED")} (its kit road: the supervisor and the
 * signaling machine leave their pools together, or neither does). That refusal — and no other: a shutdown, a backlog shed, an unknown id —
 * is {@code busy}. The words are statewalk's: {@code PoolCountersTest} proves them on the real registry, so a statewalk that words it
 * otherwise is seen red there.
 */
public final class BusyAtTheDoor {

    static final String REFUSED_BUSY = "refused: " + RejectCause.CAPACITY_EXCEEDED;

    private BusyAtTheDoor() {}

    /** The stage itself; a refusal because every machine is live is counted {@code busy} when it completes. */
    public static <S extends CompletionStage<?>> S counted(S stage, FlowCounters counters) {
        stage.whenComplete((done, failure) -> { if (isBusy(failure)) counters.busy.incrementAndGet(); });
        return stage;
    }

    /** True for statewalk's refusal of a new session because every machine is live (inside a {@code CompletionException} too). */
    public static boolean isBusy(Throwable failure) {
        Throwable cause = failure instanceof CompletionException && failure.getCause() != null ? failure.getCause() : failure;
        return cause instanceof RejectedExecutionException && cause.getMessage() != null && cause.getMessage().endsWith(REFUSED_BUSY);
    }
}
