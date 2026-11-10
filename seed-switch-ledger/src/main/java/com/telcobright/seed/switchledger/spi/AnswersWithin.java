package com.telcobright.seed.switchledger.spi;

/**
 * ARCH-0117 · A MemLedger that is not in the process says how long one answer of it may take (its call deadline): the switch ledger's
 * port answers it as {@code slowestAnswerMs()}, so the engine sizes an admission's wait. An embedded MemLedger is none: its port
 * answers 0, as before.
 */
public interface AnswersWithin {

    /** The longest one call may take before it is given up, in milliseconds. */
    long slowestAnswerMs();
}
