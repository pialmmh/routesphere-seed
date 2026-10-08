package com.telcobright.seed.sessionflow.dependencies;

/**
 * How the ledger's return road is asked again when it does not take a return at once (it is still answering the charge, it
 * is down, it did not answer).
 *
 * @param tries       how many times one return is asked in all
 * @param firstWaitMs the wait before the second ask
 * @param laterWaitMs the wait before each later ask
 * @param recheckMs   a reserve that got NO ANSWER may still land after the road said "nothing was charged": that answer is
 *                    asked once more after this wait before it is believed
 * @param mostWaiting how many returns may wait at once; one more is written down as owed instead of waiting
 */
public record ReturnPace(int tries, long firstWaitMs, long laterWaitMs, long recheckMs, int mostWaiting) {

    public ReturnPace {
        if (tries < 1) throw new IllegalArgumentException("a return is asked at least once (tries " + tries + ")");
        if (firstWaitMs < 0 || laterWaitMs < 0 || recheckMs < 0) throw new IllegalArgumentException("a wait cannot be negative");
        if (mostWaiting < 1) throw new IllegalArgumentException("at least one return may wait (mostWaiting " + mostWaiting + ")");
    }

    /** Three asks (at once, after 1 s, after 5 s more); an unsure reserve's "nothing charged" asked again after 30 s. */
    public static ReturnPace standard() { return new ReturnPace(3, 1_000, 5_000, 30_000, 10_000); }
}
