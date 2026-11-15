package app.screentranslator;

/** A problem worth showing to the user, already worded for them. */
final class TranslateException extends Exception {

    private static final long serialVersionUID = 1L;

    /** True when retrying won't help until the user fixes something (key, credit, model). */
    final boolean fatal;
    /** How long Live mode should wait before trying again (0 = its normal pause). */
    final long retryAfterMs;

    /** True when a daily limit was reached; it lifts when Google's day starts over. */
    final boolean dailyLimit;

    TranslateException(String message, boolean fatal) {
        this(message, fatal, 0, false);
    }

    TranslateException(String message, boolean fatal, long retryAfterMs) {
        this(message, fatal, retryAfterMs, false);
    }

    TranslateException(String message, boolean fatal, long retryAfterMs, boolean dailyLimit) {
        super(message);
        this.fatal = fatal;
        this.retryAfterMs = retryAfterMs;
        this.dailyLimit = dailyLimit;
    }
}
