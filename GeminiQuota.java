package app.screentranslator;

import java.text.DateFormat;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Date;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads Google's "too many requests" (429) errors: which limit was hit, and when it lifts. */
final class GeminiQuota {

    private static final ZoneId GOOGLE_RESET_ZONE = ZoneId.of("America/Los_Angeles");
    private static final Pattern RETRY_DELAY = Pattern.compile("\"retryDelay\"\\s*:\\s*\"(\\d+(?:\\.\\d+)?)s\"");
    private static final Pattern RETRY_IN = Pattern.compile("retry in (\\d+(?:\\.\\d+)?)\\s*s");

    private GeminiQuota() {
    }

    /**
     * True if the error is about the daily limit. Google names the limit in the error details
     * (e.g. "GenerateRequestsPerDayPerProjectPerModel-FreeTier"), not always in the message.
     */
    static boolean isDaily(String body) {
        String text = body == null ? "" : body.toLowerCase(Locale.ROOT);
        return text.contains("perday") || text.contains("per_day") || text.contains("per day")
                || text.contains("daily");
    }

    /** How long Google says to wait, in milliseconds (0 if it doesn't say). */
    static long retryDelayMs(String body) {
        if (body == null) {
            return 0;
        }
        Matcher match = RETRY_DELAY.matcher(body);
        if (!match.find()) {
            match = RETRY_IN.matcher(body.toLowerCase(Locale.ROOT));
            if (!match.find()) {
                return 0;
            }
        }
        try {
            return (long) Math.ceil(Double.parseDouble(match.group(1)) * 1000);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** When Google's daily limits start over: the next midnight, Pacific time (wall-clock milliseconds). */
    static long nextReset(long nowMs) {
        return Instant.ofEpochMilli(nowMs).atZone(GOOGLE_RESET_ZONE).toLocalDate().plusDays(1)
                .atStartOfDay(GOOGLE_RESET_ZONE).toInstant().toEpochMilli();
    }

    /** A time in the phone's own time zone, e.g. "5:00 PM". */
    static String timeText(long whenMs) {
        return DateFormat.getTimeInstance(DateFormat.SHORT).format(new Date(whenMs));
    }
}
