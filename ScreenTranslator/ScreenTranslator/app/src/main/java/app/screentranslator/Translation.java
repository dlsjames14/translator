package app.screentranslator;

import java.util.Locale;

/** Small types and helpers shared by all translation engines. */
final class Translation {

    static final String NO_TEXT = "(No text found)";

    private Translation() {
    }

    /** One translated piece of text (used by Live mode). */
    static final class Line {
        final String original;
        final String translation;

        Line(String original, String translation) {
            this.original = original == null ? "" : original;
            this.translation = translation == null ? "" : translation;
        }
    }

    /** Lets an engine report progress, e.g. "Starting the on-device AI…". */
    interface StatusListener {
        void onStatus(String message);
    }

    /** What to translate into, and how to show it. */
    static final class Request {
        final String language;
        final boolean showOriginal;
        final StatusListener status;

        Request(String language, boolean showOriginal, StatusListener status) {
            this.language = language;
            this.showOriginal = showOriginal;
            this.status = status == null ? message -> { } : status;
        }
    }

    /** Lowercase, without spaces or punctuation: used to spot text that was already translated. */
    static String normalize(String text) {
        if (text == null) {
            return "";
        }
        return text.toLowerCase(Locale.ROOT).replaceAll("[\\s\\p{P}\\p{S}]+", "");
    }

    /** True if the text contains at least one letter (in any alphabet). */
    static boolean hasLetters(String text) {
        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);
            if (Character.isLetter(codePoint)) {
                return true;
            }
            i += Character.charCount(codePoint);
        }
        return false;
    }
}
