package com.hedge257;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * ACT/365F day count used throughout hedge257.
 */
public final class DayCount {
    public static final double DAYS_PER_YEAR = 365.0;

    private DayCount() {
    }

    /**
     * Year fraction between two dates: actual calendar days divided by 365.
     *
     * @param start start date, must not be after the end date
     * @param end   end date
     * @return non-negative year fraction
     */
    public static double act365f(LocalDate start, LocalDate end) {
        Validate.requireDate(start, "start date");
        Validate.requireDate(end, "end date");
        if (end.isBefore(start)) {
            throw new IllegalArgumentException(
                    "end date " + end + " is before start date " + start);
        }
        return ChronoUnit.DAYS.between(start, end) / DAYS_PER_YEAR;
    }
}
