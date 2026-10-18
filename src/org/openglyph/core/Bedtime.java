package org.openglyph.core;

/**
 * Bedtime schedule: Glyph lights stay off from a start time to an end time on chosen days.
 * Times are minutes of the day (0..1439); days are a bitmask with Monday as bit 0. A schedule
 * that crosses midnight belongs to the day it starts on.
 */
public final class Bedtime {
    public static final int EVERY_DAY = 0x7f;
    public static final int WEEKDAYS = 0x1f;
    public static final int WEEKEND = 0x60;

    private Bedtime() {}

    /** Calendar.DAY_OF_WEEK (Sunday = 1) to our day index (Monday = 0). */
    public static int dayIndex(int calendarDayOfWeek) {
        return (calendarDayOfWeek + 5) % 7;
    }

    public static boolean isActive(int startMin, int endMin, int days, int day, int minute) {
        if (startMin == endMin || days == 0) return false;
        int yesterday = (day + 6) % 7;
        if (startMin < endMin) return has(days, day) && minute >= startMin && minute < endMin;
        return (has(days, day) && minute >= startMin) || (has(days, yesterday) && minute < endMin);
    }

    /** Minutes from {@code minute} until the schedule may next switch on or off (1..1440). */
    public static int minutesToNextChange(int startMin, int endMin, int minute) {
        int toStart = Math.floorMod(startMin - minute, 1440);
        int toEnd = Math.floorMod(endMin - minute, 1440);
        if (toStart == 0) toStart = 1440;
        if (toEnd == 0) toEnd = 1440;
        return Math.min(toStart, toEnd);
    }

    static boolean has(int days, int day) {
        return (days & (1 << day)) != 0;
    }
}
