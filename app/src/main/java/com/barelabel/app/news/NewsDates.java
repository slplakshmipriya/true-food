package com.barelabel.app.news;

/**
 * Date handling on YYYYMMDD ints (Contract §4, §6.4). Pure JVM,
 * using only APIs available on minSdk 23: epoch days come from the
 * days-from-civil algorithm, and every "today" enters as a
 * caller-supplied YYYYMMDD parameter, never a hidden clock.
 */
public final class NewsDates {

    /** Sentinel epoch day for invalid dates: far in the past. */
    static final int INVALID_EPOCH_DAY = -1000000;

    private NewsDates() {
    }

    public static int yearOf(int yyyymmdd) {
        return yyyymmdd / 10000;
    }

    public static int monthOf(int yyyymmdd) {
        return (yyyymmdd / 100) % 100;
    }

    public static int dayOf(int yyyymmdd) {
        return yyyymmdd % 100;
    }

    public static boolean isValid(int yyyymmdd) {
        if (yyyymmdd <= 0) {
            return false;
        }
        int month = monthOf(yyyymmdd);
        int day = dayOf(yyyymmdd);
        return month >= 1 && month <= 12 && day >= 1 && day <= 31;
    }

    /**
     * Converts a YYYYMMDD int to days since 1970-01-01 (days-from-civil).
     * Invalid dates yield {@link #INVALID_EPOCH_DAY} so age arithmetic
     * against them always reads as "very old".
     */
    public static int epochDayFromYyyymmdd(int yyyymmdd) {
        if (!isValid(yyyymmdd)) {
            return INVALID_EPOCH_DAY;
        }
        long y = yearOf(yyyymmdd);
        long m = monthOf(yyyymmdd);
        long d = dayOf(yyyymmdd);
        y -= m <= 2 ? 1 : 0;
        long era = floorDiv(y, 400L);
        long yoe = y - era * 400L;                       // [0, 399]
        long mp = m > 2 ? m - 3 : m + 9;                 // [0, 11]
        long doy = (153L * mp + 2L) / 5L + d - 1L;       // [0, 365]
        long doe = yoe * 365L + yoe / 4L - yoe / 100L + doy;
        return (int) (era * 146097L + doe - 719468L);
    }

    /** Floor division without Math.floorDiv (not on minSdk 23). */
    private static long floorDiv(long a, long b) {
        long r = a / b;
        if ((a ^ b) < 0 && r * b != a) {
            r--;
        }
        return r;
    }
}
