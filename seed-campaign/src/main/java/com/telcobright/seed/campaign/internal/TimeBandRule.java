package com.telcobright.seed.campaign.internal;

import com.telcobright.seed.campaign.api.TimeBand;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Locale;

/**
 * routesphere's {@code CampaignRunnerJob.isWithinTimeBand}, ported line for line: OR across the bands; a band with
 * {@code allow} lets the campaign run INSIDE its window, one without lets it run OUTSIDE. No bands = always.
 * Weekdays are Sunday–Thursday, the weekend Friday–Saturday (Bangladesh).
 */
public final class TimeBandRule {

    private TimeBandRule() {}

    public static boolean open(List<TimeBand> bands, LocalDateTime now) {
        if (bands == null || bands.isEmpty()) return true;
        LocalTime time = now.toLocalTime();
        for (TimeBand band : bands) {
            if (!dayMatches(band, now.getDayOfWeek(), now.toLocalDate())) continue;
            boolean inside = !time.isBefore(band.start()) && !time.isAfter(band.end());
            if (band.allow() ? inside : !inside) return true;
        }
        return false;
    }

    static boolean dayMatches(TimeBand band, DayOfWeek today, LocalDate date) {
        String day = band.day() == null ? "ALL" : band.day().trim().toUpperCase(Locale.ROOT);
        if (day.equals("ALL")) return true;
        if (day.equals("SPECIFIC DATE")) return band.specificDate() != null && band.specificDate().equals(date);
        return switch (day) {
            case "WEEKDAYS ONLY", "WEEKDAYS" -> today != DayOfWeek.FRIDAY && today != DayOfWeek.SATURDAY;
            case "WEEKENDS ONLY", "WEEKENDS" -> today == DayOfWeek.FRIDAY || today == DayOfWeek.SATURDAY;
            default -> {
                try { yield DayOfWeek.valueOf(day) == today; }
                catch (IllegalArgumentException e) { yield false; }
            }
        };
    }
}
