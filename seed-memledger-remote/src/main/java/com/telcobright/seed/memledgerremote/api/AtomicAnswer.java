package com.telcobright.seed.memledgerremote.api;

import com.telcobright.seed.memledgerremote.wire.ApplyReply;
import com.telcobright.seed.memledgerremote.wire.RowResult;

import java.util.List;

/**
 * ARCH-0119 · The server's answer to a batch of road 1 or road 2, TYPED: an application tells "busy, try again" from "a guard did not
 * hold" from "read again" by {@link Outcome}, never by reading words. Whatever is not {@link Outcome#APPLIED} changed nothing.
 */
public record AtomicAnswer(Outcome outcome, int applied, boolean alreadyApplied, String words, int failedEntry, String failedRowJson, List<RowResult> results) {

    public enum Outcome {
        /** Written, all of it (or it was written before: {@link #alreadyApplied}). */
        APPLIED,
        /** Road 1: a guard did not hold, or a row to change is not there ({@link #failedEntry}, {@link #failedRow}). A decision, not a fault. */
        GUARD,
        /**
         * A row's queue is full, its rows were not free in time, or a row is in doubt ({@link WireBatch#IN_DOUBT}: its last write is not
         * settled yet): nothing wrong with the batch — try again, or give the house answer. Never a money refusal.
         */
        BUSY,
        /** A row the batch read is not what it read: read again and decide again. */
        STALE,
        /** Road 2: the lock does not hold its rows any more (its lease ran out, the server started again): read again. */
        LOCK_LOST,
        /** Road 2: the batch changes a row its lock does not hold and it did not read: a fault of the caller. */
        NOT_COVERED,
        /** Refused in words for another reason (a batch that cannot be read, a row that exists already, …). */
        REFUSED
    }

    public boolean isApplied() { return outcome == Outcome.APPLIED; }

    /** A row the batch left (road 1: every PATCHed row), decoded; null = the answer carries no such row. */
    public <T> T row(Class<T> type, String entity, Object id) {
        String key = RowJson.keyText(id);
        for (RowResult row : results) if (row.getEntityName().equals(entity) && row.getEntityId().equals(key)) return RowJson.rowOf(type, row.getEntityJson());
        return null;
    }

    /** {@link Outcome#GUARD}: the failing entry's row as the server holds it (unchanged); null = it holds none. */
    public <T> T failedRow(Class<T> type) { return failedRowJson == null || failedRowJson.isEmpty() ? null : RowJson.rowOf(type, failedRowJson); }

    public static AtomicAnswer of(ApplyReply reply) {
        Outcome outcome = reply.getApplied() > 0 ? Outcome.APPLIED : switch (reply.getRefusalCode()) {
            case "GUARD" -> Outcome.GUARD;
            case "QUEUE_FULL", "NOT_FREE_IN_TIME", WireBatch.IN_DOUBT -> Outcome.BUSY;
            case "STALE" -> Outcome.STALE;
            case "LOCK_LOST" -> Outcome.LOCK_LOST;
            case "NOT_COVERED" -> Outcome.NOT_COVERED;
            default -> reply.getMessage().contains("were not free within") ? Outcome.BUSY : Outcome.REFUSED;
        };
        return new AtomicAnswer(outcome, reply.getApplied(), reply.getAlreadyApplied(), reply.getMessage(), reply.getFailedEntry(), reply.getFailedRowJson(), reply.getResultsList());
    }
}
