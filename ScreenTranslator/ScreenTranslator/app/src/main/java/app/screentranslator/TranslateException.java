package app.screentranslator;

/** A problem worth showing to the user, already worded for them. */
final class TranslateException extends Exception {

    private static final long serialVersionUID = 1L;

    /** True when retrying won't help until the user fixes something (key, credit, model). */
    final boolean fatal;
    /** How long Live mode should wait before trying again (0 = its normal pause). */
    final long retryAfterMs;

    TranslateException(String message, boolean fatal) {
        this(message, fatal, 0);
    }

    TranslateException(String message, boolean fatal, long retryAfterMs) {
        super(message);
        this.fatal = fatal;
        this.retryAfterMs = retryAfterMs;
    }
}
