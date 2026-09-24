package com.telcobright.seed.campaign;

import com.telcobright.seed.campaign.api.TimeBand;
import com.telcobright.seed.campaign.internal.TimeBandRule;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The SMS runner's evaluation, kept exactly: OR across bands, allow inside / restrict outside, the Bangladesh weekend. */
class TimeBandRuleTest {

    // 2026-09-25 is a Friday (the Bangladesh weekend); 2026-09-27 is a Sunday (a weekday)
    private static final LocalDateTime FRIDAY_10 = LocalDateTime.of(2026, 9, 25, 10, 0);
    private static final LocalDateTime SUNDAY_10 = LocalDateTime.of(2026, 9, 27, 10, 0);
    private static final LocalDateTime SUNDAY_23 = LocalDateTime.of(2026, 9, 27, 23, 30);

    @Test
    void no_bands_means_always() {
        assertThat(TimeBandRule.open(List.of(), SUNDAY_23)).isTrue();
    }

    @Test
    void business_hours_on_weekdays_only() {
        List<TimeBand> bands = List.of(new TimeBand("WEEKDAYS ONLY", null, LocalTime.of(9, 0), LocalTime.of(17, 0), true));
        assertThat(TimeBandRule.open(bands, SUNDAY_10)).as("Sunday is a weekday in BD").isTrue();
        assertThat(TimeBandRule.open(bands, FRIDAY_10)).as("Friday is the weekend").isFalse();
        assertThat(TimeBandRule.open(bands, SUNDAY_23)).as("outside the window").isFalse();
    }

    @Test
    void a_restrict_band_allows_everything_outside_its_window() {
        List<TimeBand> bands = List.of(TimeBand.restrictDaily(LocalTime.of(23, 0), LocalTime.of(23, 59)));
        assertThat(TimeBandRule.open(bands, SUNDAY_10)).isTrue();
        assertThat(TimeBandRule.open(bands, SUNDAY_23)).isFalse();
    }

    @Test
    void a_specific_date_and_a_named_day_and_the_weekend() {
        assertThat(TimeBandRule.open(List.of(new TimeBand("SPECIFIC DATE", LocalDate.of(2026, 9, 25), LocalTime.MIN, LocalTime.MAX, true)), FRIDAY_10)).isTrue();
        assertThat(TimeBandRule.open(List.of(new TimeBand("SPECIFIC DATE", LocalDate.of(2026, 9, 26), LocalTime.MIN, LocalTime.MAX, true)), FRIDAY_10)).isFalse();
        assertThat(TimeBandRule.open(List.of(new TimeBand("FRIDAY", null, LocalTime.MIN, LocalTime.MAX, true)), FRIDAY_10)).isTrue();
        assertThat(TimeBandRule.open(List.of(new TimeBand("WEEKENDS ONLY", null, LocalTime.MIN, LocalTime.MAX, true)), FRIDAY_10)).isTrue();
        assertThat(TimeBandRule.open(List.of(new TimeBand("WEEKENDS ONLY", null, LocalTime.MIN, LocalTime.MAX, true)), SUNDAY_10)).isFalse();
        assertThat(TimeBandRule.open(List.of(new TimeBand("NONSENSE", null, LocalTime.MIN, LocalTime.MAX, true)), SUNDAY_10)).as("an unknown day never matches").isFalse();
    }
}
