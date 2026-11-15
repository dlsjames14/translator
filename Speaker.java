package app.screentranslator;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Reads translations aloud, either with the phone's built-in text-to-speech voices (offline once a
 * voice is installed) or with the natural AI voice ({@link AiVoice}). If the AI voice can't be used
 * (no key, limit reached, no internet), the phone voice takes over automatically. Other sound, like a
 * video, is turned down while it speaks. Use on the main thread.
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

    /** Which voice to use and how, taken from the settings. */
    static final class Options {
        final String language;
        final String rate;
        final boolean aiVoice;
        final String geminiKey;
        final String aiModel;
        final String aiVoiceName;
        final String aiMood;

        Options(String language, String rate, boolean aiVoice, String geminiKey, String aiModel, String aiVoiceName,
                String aiMood) {
            this.language = language == null ? "" : language;
            this.rate = rate;
            this.aiVoice = aiVoice;
            this.geminiKey = geminiKey == null ? "" : geminiKey.trim();
            this.aiModel = aiModel;
            this.aiVoiceName = aiVoiceName;
            this.aiMood = aiMood;
        }

        static Options from(Prefs prefs, String language) {
            return new Options(language, prefs.speechRate(), Prefs.VOICE_AI.equals(prefs.voiceMode()),
                    prefs.geminiKey(), prefs.aiVoiceModel(), prefs.aiVoiceName(), prefs.aiVoiceMood());
        }

        /** How the AI voice should sound, e.g. "warm, kind and friendly, speaking slowly". */
        String style() {
            String style = AI_MOODS[0][2];
            for (String[] mood : AI_MOODS) {
                if (mood[0].equals(aiMood)) {
                    style = mood[2];
                }
            }
            if (RATE_SLOW.equals(rate)) {
                style += ", speaking slowly";
            } else if (RATE_FAST.equals(rate)) {
                style += ", speaking quickly";
            }
            return style;
        }
    }

    static final String RATE_SLOW = "slow";
    static final String RATE_NORMAL = "normal";
    static final String RATE_FAST = "fast";

    static final String AI_MODEL_BEST = "gemini-3.8-flash-tts";
    static final String AI_MODEL_FAST = "gemini-3.8-flash-lite-tts";

    /** Gemini voices offered in the settings: name, character. */
    static final String[][] AI_VOICES = {
            {"Sulafat", "warm"},
            {"Achird", "friendly"},
            {"Vindemiatrix", "gentle"},
            {"Aoede", "breezy"},
            {"Puck", "upbeat"},
            {"Leda", "youthful"},
            {"Algieba", "smooth"},
            {"Charon", "informative"},
            {"Sadachbia", "lively"},
            {"Kore", "firm"},
    };

    /** Speaking styles: id, label in the settings, instruction for the AI voice. */
    static final String[][] AI_MOODS = {
            {"natural", "Natural (follows the feeling of the words)",
                    "natural and expressive, with emotion that follows the meaning of the words"},
            {"warm", "Warm and friendly", "warm, kind and friendly"},
            {"calm", "Calm and gentle", "calm, gentle and clear"},
            {"lively", "Lively and upbeat", "lively, upbeat and enthusiastic"},
    };

    /** When this many pieces are still waiting to be spoken, Live mode skips ahead to the newest text. */
    private static final int MAX_BACKLOG = 8;
    private static final int MAX_AI_BACKLOG = 3;
    // One request per reading where possible: Google's daily limit counts requests, and streaming
    // already makes long text start quickly.
    private static final int AI_PIECE_LENGTH = 1200;
    private static final long REPEAT_PROBLEM_MS = 5 * 60_000L;

    static final AudioAttributes SPEECH = new AudioAttributes.Builder()
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

    // Phone voice
    private TextToSpeech tts;
    private boolean ready;
    private boolean failed;
    private boolean phoneActive;
    private int counter;
    private int backlog;
    private String lastId;
    private String appliedLanguage;
    private float appliedRate = -1f;

    // AI voice
    private AiVoice ai;
    private boolean aiActive;
    private Options aiOptions;
    private long aiBlockedUntil;
    private String aiBlockedKey = "";

    private boolean speaking;
    private boolean hasFocus;
    private String lastProblem;
    private long lastProblemAt;

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
     * Reads the text aloud.
     *
     * @param replace true to stop whatever is being said first; false to say it afterwards
     */
    void speak(String text, Options options, boolean replace) {
        if (text == null || text.trim().isEmpty()) {
            return;
        }
        if (options.aiVoice && aiAllowed(options) && (replace || !phoneActive)) {
            speakAi(text, options, replace);
        } else {
            if (replace && ai != null) {
                ai.stop();
                aiActive = false;
            }
            speakPhone(text, options.language, options.rate, replace);
        }
    }

    void stop() {
        waiting.clear();
        if (tts != null && ready) {
            tts.stop();
        }
        if (ai != null) {
            ai.stop();
        }
        aiActive = false;
        phoneFinished();
    }

    void shutdown() {
        waiting.clear();
        if (ai != null) {
            ai.close();
            ai = null;
        }
        aiActive = false;
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
        phoneFinished();
    }

    /** Lets the AI voice be tried again right away (e.g. after the key was changed). */
    void retryAiVoice() {
        aiBlockedUntil = 0;
        new Prefs(context).clearAiVoicePause();
    }

    // ----------------------------------------------------------------------------------------
    // AI voice

    private boolean aiAllowed(Options options) {
        if (options.geminiKey.isEmpty()) {
            problem("To use the natural AI voice, paste your free Gemini key under Read aloud in the Screen "
                    + "Translator app. Using the phone voice for now.");
            return false;
        }
        if (!options.geminiKey.equals(aiBlockedKey)) {
            aiBlockedUntil = 0;   // a different key: try again
        }
        if (SystemClock.uptimeMillis() < aiBlockedUntil) {
            return false;
        }
        long pausedUntil = new Prefs(context).aiVoicePausedUntil(options.geminiKey);
        if (System.currentTimeMillis() < pausedUntil) {
            problem(dailyLimitMessage(pausedUntil));
            return false;
        }
        return true;
    }

    static String dailyLimitMessage(long untilMs) {
        return "The natural AI voice has used today's free limit from Google, so the phone voice reads "
                + "instead until about " + GeminiQuota.timeText(untilMs) + ", when the limit starts over.";
    }

    private void speakAi(String text, Options options, boolean replace) {
        if (ai == null) {
            ai = new AiVoice(new AiVoice.Callback() {
                @Override
                public void onAllDone() {
                    aiActive = false;
                    updateSpeaking();
                }

                @Override
                public void onFailed(String unread, TranslateException error) {
                    aiFailed(unread, error);
                }
            });
        }
        if (replace) {
            waiting.clear();
            if (tts != null && ready) {
                tts.stop();
            }
            phoneFinished();
            ai.stop();
        } else if (ai.backlog() > MAX_AI_BACKLOG) {
            ai.stop();   // falling behind: skip ahead to the newest text
        }
        aiOptions = options;
        ai.speak(aiPieces(text), options);
        aiActive = true;
        updateSpeaking();
    }

    private void aiFailed(String unread, TranslateException error) {
        aiActive = false;
        Options options = aiOptions;
        String key = options == null ? "" : options.geminiKey;
        if (error.dailyLimit) {
            long until = GeminiQuota.nextReset(System.currentTimeMillis());
            new Prefs(context).setAiVoicePaused(key, until);   // remembered even if the app restarts
            problem(dailyLimitMessage(until));
        } else {
            aiBlockedKey = key;
            aiBlockedUntil = error.fatal ? Long.MAX_VALUE
                    : SystemClock.uptimeMillis() + Math.max(60_000L, error.retryAfterMs);
            problem("Natural AI voice: " + error.getMessage() + " Using the phone voice "
                    + (error.fatal ? "instead." : "for a minute."));
        }
        if (options != null) {
            speakPhone(unread, options.language, options.rate, false);
        }
        updateSpeaking();
    }

    // ----------------------------------------------------------------------------------------
    // Phone voice

    private void speakPhone(String text, String language, String rate, boolean replace) {
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
            phoneActive = true;
            updateSpeaking();
        }
    }

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
            speakPhone(item[0], item[1], item[2], false);
        }
    }

    private void pieceEnded(String id) {
        if (backlog > 0) {
            backlog--;
        }
        if (id != null && id.equals(lastId)) {
            phoneFinished();
        }
    }

    private void phoneFinished() {
        backlog = 0;
        lastId = null;
        phoneActive = false;
        updateSpeaking();
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

    /** Turns other sound down while speaking, and back up when done. */
    private void updateSpeaking() {
        boolean now = phoneActive || aiActive;
        if (now && !hasFocus) {
            hasFocus = audio.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        } else if (!now && hasFocus) {
            audio.abandonAudioFocusRequest(focus);
            hasFocus = false;
        }
        if (speaking != now) {
            speaking = now;
            listener.onSpeakingChanged(now);
        }
    }

    private void problem(String message) {
        problem(message, false);
    }

    private void problem(String message, boolean needsVoice) {
        long now = SystemClock.uptimeMillis();
        if (!message.equals(lastProblem) || now - lastProblemAt > REPEAT_PROBLEM_MS) {
            lastProblem = message;
            lastProblemAt = now;
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

    /** Pieces for the AI voice: as few as possible, since each one is a request to Google. */
    static List<String> aiPieces(String text) {
        return pieces(text, AI_PIECE_LENGTH);
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
