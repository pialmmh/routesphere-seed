package com.telcobright.seed.campaign.api;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * One row of routesphere's {@code time_band}: WHEN a campaign may run. {@code day} is one of
 * {@code ALL}, {@code MONDAY..SUNDAY}, {@code WEEKDAYS ONLY}, {@code WEEKENDS ONLY}, {@code SPECIFIC DATE}
 * (then {@code specificDate} says which). {@code allow} true = allowed INSIDE the window, false = allowed OUTSIDE it.
 * Bangladesh weekend: Friday and Saturday.
 */
public record TimeBand(String day, LocalDate specificDate, LocalTime start, LocalTime end, boolean allow) {

    public static TimeBand allowDaily(LocalTime start, LocalTime end) {
        return new TimeBand("ALL", null, start, end, true);
    }

    public static TimeBand restrictDaily(LocalTime start, LocalTime end) {
        return new TimeBand("ALL", null, start, end, false);
    }
}
