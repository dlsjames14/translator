package app.screentranslator;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Reads translations aloud with the phone's built-in text-to-speech voices (offline once a voice is
 * installed). Other sound, like a video, is turned down while it speaks. Use on the main thread.
 */
final class Speaker {

    interface Listener {
        void onSpeakingChanged(boolean speaking);

        /**
         * A problem worth telling the user about, already worded for them. Reported once per problem.
         *
         * @param needsVoice true when installing a voice (or a speech app) would fix it
         */
        void onProblem(String message, boolean needsVoice);
    }

    static final String RATE_SLOW = "slow";
    static final String RATE_NORMAL = "normal";
    static final String RATE_FAST = "fast";

    /** When this many pieces are still waiting to be spoken, Live mode skips ahead to the newest text. */
    private static final int MAX_BACKLOG = 8;

    private static final AudioAttributes SPEECH = new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build();

    // Language names people might type → voice. More specific names come first.
    private static final String[][] LANGUAGES = {
            {"en-US", "english", "ingles", "영어"},
            {"ko-KR", "korean", "한국어", "한국말", "korea"},
            {"fil-PH", "filipino", "tagalog", "pilipino"},
            {"ja-JP", "japanese", "日本語", "nihongo"},
            {"zh-TW", "traditional chinese", "taiwanese", "繁體"},
            {"yue-HK", "cantonese", "粵語"},
            {"zh-CN", "chinese", "mandarin", "中文", "简体"},
            {"es-ES", "spanish", "español", "espanol"},
            {"vi-VN", "vietnamese", "tiếng việt", "tieng viet"},
            {"th-TH", "thai", "ภาษาไทย"},
            {"id-ID", "indonesian", "bahasa indonesia"},
            {"ms-MY", "malay", "bahasa melayu"},
            {"fr-FR", "french", "français", "francais"},
            {"de-DE", "german", "deutsch"},
            {"it-IT", "italian", "italiano"},
            {"pt-BR", "portuguese", "português", "portugues"},
            {"ru-RU", "russian", "русский"},
            {"ar", "arabic", "العربية"},
            {"hi-IN", "hindi", "हिन्दी"},
            {"ne-NP", "nepali", "नेपाली"},
            {"bn-BD", "bengali", "bangla"},
            {"ur-PK", "urdu"},
            {"si-LK", "sinhala", "sinhalese"},
            {"km-KH", "khmer", "cambodian"},
            {"my-MM", "burmese", "myanmar"},
            {"mn-MN", "mongolian"},
            {"uz-UZ", "uzbek"},
            {"kk-KZ", "kazakh"},
            {"tr-TR", "turkish", "türkçe"},
    };

    private static final String[][] SAMPLES = {
            {"en", "This is how your translations will sound."},
            {"ko", "번역은 이렇게 들립니다."},
            {"fil", "Ganito ang tunog ng iyong mga pagsasalin."},
            {"ja", "翻訳はこのように聞こえます。"},
            {"zh", "您的翻译听起来会是这样。"},
            {"es", "Así sonarán tus traducciones."},
            {"vi", "Bản dịch của bạn sẽ nghe như thế này."},
            {"th", "คำแปลของคุณจะฟังดูแบบนี้"},
            {"id", "Beginilah terjemahan Anda akan terdengar."},
    };

    private final Context context;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<String[]> waiting = new ArrayList<>();   // {text, language, rate} before the voice is ready
    private final AudioManager audio;
    private final AudioFocusRequest focus;

    private TextToSpeech tts;
    private boolean ready;
    private boolean failed;
    private boolean speaking;
    private boolean hasFocus;
    private int counter;
    private int backlog;
    private String lastId;
    private String appliedLanguage;
    private float appliedRate = -1f;
    private String lastProblem;

    Speaker(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        audio = this.context.getSystemService(AudioManager.class);
        focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(SPEECH)
                .build();
    }

    boolean isSpeaking() {
        return speaking;
    }

    /** True when the phone has no working text-to-speech engine. */
    boolean unavailable() {
        return failed;
    }

    /**
     * Speaks the text in the given language.
     *
     * @param replace true to stop whatever is being said first; false to say it afterwards
     */
    void speak(String text, String language, String rate, boolean replace) {
        if (text == null || text.trim().isEmpty()) {
            return;
        }
        if (failed) {
            problem(noEngineMessage(), true);
            return;
        }
        if (tts == null) {
            start();
        }
        if (!ready) {
            if (replace) {
                waiting.clear();
            }
            waiting.add(new String[]{text, language, rate});
            return;
        }
        if (!useLanguage(language)) {
            return;
        }
        float speed = rateValue(rate);
        if (speed != appliedRate) {
            tts.setSpeechRate(speed);
            appliedRate = speed;
        }
        boolean flush = replace || backlog > MAX_BACKLOG;
        if (flush) {
            backlog = 0;
        }
        int mode = flush ? TextToSpeech.QUEUE_FLUSH : TextToSpeech.QUEUE_ADD;
        for (String piece : pieces(text, Math.min(TextToSpeech.getMaxSpeechInputLength(), 2000))) {
            String id = "st" + (++counter);
            if (tts.speak(piece, mode, new Bundle(), id) == TextToSpeech.SUCCESS) {
                lastId = id;
                backlog++;
                mode = TextToSpeech.QUEUE_ADD;
            }
        }
        if (backlog > 0) {
            takeFocus();
            setSpeaking(true);
        }
    }

    void stop() {
        waiting.clear();
        if (tts != null && ready) {
            tts.stop();
        }
        finished();
    }

    void shutdown() {
        waiting.clear();
        if (tts != null) {
            try {
                tts.stop();
                tts.shutdown();
            } catch (RuntimeException ignored) {
                // Already shut down.
            }
            tts = null;
        }
        ready = false;
        finished();
    }

    // ----------------------------------------------------------------------------------------

    private void start() {
        tts = new TextToSpeech(context, status -> main.post(() -> onReady(status)));
    }

    private void onReady(int status) {
        if (tts == null) {
            return;   // shut down meanwhile
        }
        if (status != TextToSpeech.SUCCESS) {
            failed = true;
            waiting.clear();
            problem(noEngineMessage(), true);
            return;
        }
        ready = true;
        tts.setAudioAttributes(SPEECH);
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override
            public void onStart(String id) {
                // Nothing to do: already marked as speaking.
            }

            @Override
            public void onDone(String id) {
                main.post(() -> pieceEnded(id));
            }

            @Override
            public void onStop(String id, boolean interrupted) {
                main.post(() -> pieceEnded(id));
            }

            @SuppressWarnings("deprecation")
            @Override
            public void onError(String id) {
                main.post(() -> pieceEnded(id));
            }

            @Override
            public void onError(String id, int errorCode) {
                main.post(() -> {
                    if (errorCode == TextToSpeech.ERROR_NOT_INSTALLED_YET) {
                        problem("The voice is still being installed. Try again in a moment.");
                    }
                    pieceEnded(id);
                });
            }
        });
        List<String[]> queued = new ArrayList<>(waiting);
        waiting.clear();
        for (String[] item : queued) {
            speak(item[0], item[1], item[2], false);
        }
    }

    private void pieceEnded(String id) {
        if (backlog > 0) {
            backlog--;
        }
        if (id != null && id.equals(lastId)) {
            finished();
        }
    }

    private boolean useLanguage(String language) {
        String name = language == null ? "" : language.trim();
        if (name.equals(appliedLanguage)) {
            return true;
        }
        Locale locale = localeFor(name);
        if (locale == null) {
            problem("No voice is set up for \"" + name + "\", so the phone's default voice is used.");
            appliedLanguage = name;
            return true;
        }
        int result = tts.setLanguage(locale);
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            problem("This phone has no " + name + " voice installed yet. In the Screen Translator app, tap "
                    + "\"Test voice\" to install one.", true);
            return false;
        }
        appliedLanguage = name;
        return true;
    }

    private void takeFocus() {
        if (!hasFocus) {
            hasFocus = audio.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        }
    }

    private void finished() {
        backlog = 0;
        lastId = null;
        if (hasFocus) {
            audio.abandonAudioFocusRequest(focus);
            hasFocus = false;
        }
        setSpeaking(false);
    }

    private void setSpeaking(boolean value) {
        if (speaking != value) {
            speaking = value;
            listener.onSpeakingChanged(value);
        }
    }

    private void problem(String message) {
        problem(message, false);
    }

    private void problem(String message, boolean needsVoice) {
        if (!message.equals(lastProblem)) {
            lastProblem = message;
            listener.onProblem(message, needsVoice);
        }
    }

    /** Lets the same problem be reported again (e.g. each time "Test voice" is tapped). */
    void forgetProblems() {
        lastProblem = null;
    }

    private static String noEngineMessage() {
        return "This phone has no text-to-speech voice. Install \"Speech Services by Google\" from the Play "
                + "Store, then try again.";
    }

    // ----------------------------------------------------------------------------------------
    // Helpers (also used by the settings screen)

    static float rateValue(String rate) {
        if (RATE_SLOW.equals(rate)) {
            return 0.8f;
        }
        if (RATE_FAST.equals(rate)) {
            return 1.3f;
        }
        return 1f;
    }

    /** The voice for a language name like "English", "Tagalog" or "한국어", or null if unknown. */
    static Locale localeFor(String language) {
        String name = language == null ? "" : language.trim().toLowerCase(Locale.ROOT);
        if (name.isEmpty()) {
            return Locale.US;
        }
        // 1. An exact name from the list ("Tagalog" → the Filipino voice).
        for (String[] entry : LANGUAGES) {
            for (int i = 1; i < entry.length; i++) {
                if (name.equals(entry[i])) {
                    return Locale.forLanguageTag(entry[0]);
                }
            }
        }
        // 2. Any language's own name, in English or in that language ("Malayalam", "Nederlands").
        for (Locale locale : Locale.getAvailableLocales()) {
            if (!locale.getLanguage().isEmpty()
                    && (name.equals(locale.getDisplayLanguage(Locale.ENGLISH).toLowerCase(Locale.ROOT))
                    || name.equals(locale.getDisplayLanguage(locale).toLowerCase(Locale.ROOT)))) {
                return Locale.forLanguageTag(locale.getLanguage());
            }
        }
        // 3. A name from the list inside longer text ("English (US)", "simple Korean").
        for (String[] entry : LANGUAGES) {
            for (int i = 1; i < entry.length; i++) {
                if (name.contains(entry[i])) {
                    return Locale.forLanguageTag(entry[0]);
                }
            }
        }
        if (name.matches("[a-z]{2,3}([-_][a-z0-9]{2,8})*")) {
            return Locale.forLanguageTag(name.replace('_', '-'));   // a code like "en" or "pt-br"
        }
        return null;
    }

    /** A short sentence in the language, for "Test voice". */
    static String sample(String language) {
        Locale locale = localeFor(language);
        String code = locale == null ? "en" : locale.getLanguage();
        for (String[] entry : SAMPLES) {
            if (entry[0].equals(code)) {
                return entry[1];
            }
        }
        return SAMPLES[0][1];
    }

    /** With "show original" on, the card holds "original / → translation" pairs: read only the translations. */
    static String translationsOnly(String text, boolean showOriginal) {
        if (!showOriginal || text == null) {
            return text;
        }
        StringBuilder out = new StringBuilder();
        for (String line : text.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("→")) {
                out.append(trimmed.substring(1).trim()).append('\n');
            }
        }
        return out.length() > 0 ? out.toString() : text;
    }

    /** Splits long text into pieces the voice engine accepts, at line and sentence breaks where possible. */
    static List<String> pieces(String text, int maxLength) {
        int limit = Math.max(50, maxLength);
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String line : text.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            for (String part : splitLong(trimmed, limit)) {
                if (current.length() > 0 && current.length() + 1 + part.length() > limit) {
                    result.add(current.toString());
                    current.setLength(0);
                }
                if (current.length() > 0) {
                    current.append('\n');
                }
                current.append(part);
            }
        }
        if (current.length() > 0) {
            result.add(current.toString());
        }
        return result;
    }

    private static List<String> splitLong(String line, int limit) {
        List<String> parts = new ArrayList<>();
        String rest = line;
        while (rest.length() > limit) {
            int cut = -1;
            for (int i = limit - 1; i > limit / 2; i--) {
                char c = rest.charAt(i);
                if (c == '.' || c == '!' || c == '?' || c == '。' || c == '！' || c == '？') {
                    cut = i + 1;
                    break;
                }
            }
            if (cut < 0) {
                cut = rest.lastIndexOf(' ', limit - 1);
            }
            if (cut <= 0) {
                cut = limit;
            }
            parts.add(rest.substring(0, cut).trim());
            rest = rest.substring(cut).trim();
        }
        if (!rest.isEmpty()) {
            parts.add(rest);
        }
        return parts;
    }
}
