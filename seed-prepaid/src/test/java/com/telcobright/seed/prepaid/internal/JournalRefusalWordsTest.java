package com.telcobright.seed.prepaid.internal;

import org.junit.jupiter.api.Test;

import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A reserve the journal of the sessions in the air refused is said in the log (§14.13's open item: it was a counter and a history
 * note only), flood-safe: at the 1st, 10th, 100th … refusal, so a journal that refuses every session says it a handful of times.
 */
class JournalRefusalWordsTest {

    @Test
    void a_refused_reserve_is_said_at_the_first_tenth_hundredth_refusal_and_not_in_between() {
        assertThat(LongStream.rangeClosed(1, 10_000).filter(TierReserve::worthSaying).boxed())
            .containsExactly(1L, 10L, 100L, 1_000L, 10_000L);
    }

    @Test
    void no_refusal_is_never_said() {
        assertThat(TierReserve.worthSaying(0)).isFalse();
        assertThat(TierReserve.worthSaying(-10)).isFalse();
    }
}
