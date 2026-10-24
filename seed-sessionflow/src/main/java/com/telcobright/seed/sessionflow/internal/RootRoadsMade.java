package com.telcobright.seed.sessionflow.internal;

import com.telcobright.seed.sessionflow.spi.LedgerPort;
import com.telcobright.seed.sessionflow.spi.SessionJournal;

/**
 * P1-H · What ONE try made for a root it serves: its ledger, its CDR road — and its producer, when the try made one for a Kafka no other
 * root shares —, its journal. A try that fails half-way closes exactly this again, its ledger first; what another root shares is never
 * closed by it.
 */
final class RootRoadsMade {

    final String rootDb;
    LedgerPort ledger;
    boolean cdrRoad;
    /** The Kafka whose producer this try made; null = none, or one another root shares. */
    String producerOf;
    SessionJournal journal;

    RootRoadsMade(String rootDb) { this.rootDb = rootDb; }
}
