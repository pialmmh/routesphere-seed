package com.telcobright.seed.switchledger.spi;

/**
 * ARCH-0118 S1 · A MemLedger whose own {@code reloadNewRows()} takes out the ghost reserves it brings back, under a gate no write passes
 * — so another application's new reserve is never taken for one (a recall-db whose Health says {@code ghosts}). Over such a ledger the
 * switch ledger's merge of new rows takes no ghost out itself; over one that does not (the embedded MemLedger, an older server) it keeps
 * the client-side rule, which is right only while this application is the ONE writer of its root's reserves.
 */
public interface GhostFreeReloads {

    /** Does this ledger's reload take out the ghosts it brings back (its {@code added} already less them)? */
    boolean takesOutGhosts();
}
