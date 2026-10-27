package com.telcobright.seed.ledgerclient.api;

/**
 * C2 · The application that keeps the cached ledger did not answer, answered 5xx, or said its ledger did not answer (503). Nothing is
 * known to be written: send the same unit again under the SAME reference — a unit that was written answers its first commit then.
 */
public final class RemoteLedgerFault extends RuntimeException {

    public RemoteLedgerFault(String message) { super(message); }

    public RemoteLedgerFault(String message, Throwable cause) { super(message, cause); }
}
