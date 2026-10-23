package com.telcobright.seed.sessionflow.internal;

import com.telcobright.seed.sessionflow.api.CdrEvent;
import com.telcobright.seed.sessionflow.api.RecordStatement;

import java.util.List;

/**
 * One session's records as the base hands them to its root's writer (P1-R §B): per tier record the application's statements and summary
 * entry, then the session's SDR statements — made on the session's thread, written on the writer's.
 *
 * @param sessionId the session (its line in the journal of the sessions in the air is done after the commit)
 * @param root      the root whose store takes them
 */
public record SessionRecords(String sessionId, String root, List<TierRecord> tiers, List<RecordStatement> sdr) {

    /** One tier's record: its statements, and what it puts into its tier's summary input (null = nothing). */
    public record TierRecord(CdrEvent record, List<RecordStatement> statements, Object summaryEntry) {}

    public SessionRecords {
        tiers = List.copyOf(tiers);
        sdr = sdr == null ? List.of() : List.copyOf(sdr);
    }

    /** The tier records: what the batch's size counts. */
    public int size() { return tiers.size(); }
}
