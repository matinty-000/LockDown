package ir.synix.lockdown.util;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/** Small time-formatting helpers used in logs, the GUI and Discord messages. */
public final class Time {

    private Time() {
    }

    public static String now() {
        return DateTimeFormatter
                .ofPattern("yyyy-MM-dd HH:mm:ss")
                .withZone(ZoneId.systemDefault())
                .format(Instant.now());
    }

    /** Human readable remaining time, e.g. "45s" or "2m 10s". */
    public static String remaining(Instant expiry) {
        Duration d = Duration.between(Instant.now(), expiry);
        if (d.isNegative() || d.isZero()) return "0s";
        long s = d.getSeconds();
        if (s < 60) return s + "s";
        long m = s / 60;
        long rem = s % 60;
        return rem == 0 ? m + "m" : m + "m " + rem + "s";
    }
}
