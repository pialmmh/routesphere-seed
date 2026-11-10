package com.telcobright.seed.memledgerremote.api;

/**
 * The mem-ledger server did not answer, or answered that it cannot serve (unreachable, a deadline passed twice, the bearer refused, it
 * is reloading or stopped). Never a refusal of a batch — that is an answer. The switch ledger's engine turns it into its
 * {@code LedgerFault}: the pipeline says BILLING_SYSTEM_ERROR, never a balance cause.
 */
public final class RemoteLedgerFault extends RuntimeException {

    public RemoteLedgerFault(String words, Throwable cause) { super(words, cause); }
}
