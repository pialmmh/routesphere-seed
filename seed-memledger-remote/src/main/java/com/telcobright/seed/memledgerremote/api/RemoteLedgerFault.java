package com.telcobright.seed.memledgerremote.api;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;

/**
 * The mem-ledger server did not answer, or answered that it cannot serve (unreachable, a deadline passed twice, the bearer refused, it
 * is reloading or stopped). Never a refusal of a batch — that is an answer. The switch ledger's engine turns it into its
 * {@code LedgerFault}: the pipeline says BILLING_SYSTEM_ERROR, never a balance cause.
 */
public final class RemoteLedgerFault extends RuntimeException {

    public RemoteLedgerFault(String words, Throwable cause) { super(words, cause); }

    /**
     * The server is not there yet or did not answer in time (UNAVAILABLE, a deadline) — not a refusal of the bearer, not any other answer:
     * a start may wait for it (remote-fixes item (d)).
     */
    public boolean notServingYet() {
        if (!(getCause() instanceof StatusRuntimeException failure)) return false;
        Status.Code code = failure.getStatus().getCode();
        return code == Status.Code.UNAVAILABLE || code == Status.Code.DEADLINE_EXCEEDED;
    }
}
