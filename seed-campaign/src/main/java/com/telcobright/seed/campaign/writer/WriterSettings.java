package com.telcobright.seed.campaign.writer;

/**
 * How a store's writer works ({@link QueuedCampaignStore}).
 *
 * @param queue                how many changes may wait in memory; one more is written as a line on disk instead of waiting (20,000)
 * @param batch                the most changes of one transaction (500)
 * @param retryFirstMs         the wait before a batch that failed is written again (200 ms); it doubles
 * @param retryCapMs           … up to this (5,000 ms), for as long as the process lives
 * @param stopWaitMs           the longest a clean stop waits for the store to take what is still queued (5,000 ms); the rest
 *                             goes to the journal
 * @param startWaitMs          the longest a start waits for the store to take what a stopped process left in the journal
 *                             (30,000 ms); then the start of this store fails by name
 * @param triesBeforeOneByOne  a batch the store refuses this many times for a reason that is not the store being away is
 *                             written change by change, so that one change the store will never take does not hold the others (3)
 * @param triesBeforeRejected  a change the store refuses this many times alone, for such a reason, is put aside (3)
 */
public record WriterSettings(int queue, int batch, long retryFirstMs, long retryCapMs, long stopWaitMs, long startWaitMs,
                             int triesBeforeOneByOne, int triesBeforeRejected) {

    public static final int DEFAULT_QUEUE = 20_000;
    public static final int DEFAULT_BATCH = 500;
    public static final long DEFAULT_RETRY_FIRST_MS = 200;
    public static final long DEFAULT_RETRY_CAP_MS = 5_000;
    public static final long DEFAULT_STOP_WAIT_MS = 5_000;
    public static final long DEFAULT_START_WAIT_MS = 30_000;

    public WriterSettings {
        if (queue < 1) throw new IllegalArgumentException("the writer's queue holds at least one change (queue " + queue + ")");
        if (batch < 1) throw new IllegalArgumentException("a batch is at least one change (batch " + batch + ")");
        if (retryFirstMs < 1 || retryCapMs < retryFirstMs) {
            throw new IllegalArgumentException("the retry waits: first " + retryFirstMs + " ms, cap " + retryCapMs + " ms — the first is at least 1 and the cap is not below it");
        }
        if (stopWaitMs < 0 || startWaitMs < 0) throw new IllegalArgumentException("a wait cannot be negative");
        if (triesBeforeOneByOne < 1 || triesBeforeRejected < 1) throw new IllegalArgumentException("a change is tried at least once");
    }

    public static WriterSettings standard() {
        return new WriterSettings(DEFAULT_QUEUE, DEFAULT_BATCH, DEFAULT_RETRY_FIRST_MS, DEFAULT_RETRY_CAP_MS, DEFAULT_STOP_WAIT_MS, DEFAULT_START_WAIT_MS, 3, 3);
    }

    public WriterSettings withQueue(int bound) {
        return new WriterSettings(bound, batch, retryFirstMs, retryCapMs, stopWaitMs, startWaitMs, triesBeforeOneByOne, triesBeforeRejected);
    }

    public WriterSettings withBatch(int most) {
        return new WriterSettings(queue, most, retryFirstMs, retryCapMs, stopWaitMs, startWaitMs, triesBeforeOneByOne, triesBeforeRejected);
    }

    public WriterSettings withRetry(long firstMs, long capMs) {
        return new WriterSettings(queue, batch, firstMs, capMs, stopWaitMs, startWaitMs, triesBeforeOneByOne, triesBeforeRejected);
    }

    public WriterSettings withStopWait(long ms) {
        return new WriterSettings(queue, batch, retryFirstMs, retryCapMs, ms, startWaitMs, triesBeforeOneByOne, triesBeforeRejected);
    }

    public WriterSettings withStartWait(long ms) {
        return new WriterSettings(queue, batch, retryFirstMs, retryCapMs, stopWaitMs, ms, triesBeforeOneByOne, triesBeforeRejected);
    }
}
