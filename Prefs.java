package app.screentranslator;

import android.content.Context;
import android.content.SharedPreferences;

/** The app's settings. Stored privately on the phone only. */
final class Prefs {

    static final String ENGINE_ON_DEVICE = "ondevice";
    static final String ENGINE_GEMINI = "gemini";
    static final String ENGINE_OPENAI = "openai";

    static final String VOICE_PHONE = "phone";
    static final String VOICE_AI = "gemini";

    static final String DEFAULT_OPENAI_MODEL = "gpt-6-luna";
    static final String DEFAULT_GEMINI_MODEL = "gemini-3.1-flash-lite";
    static final String DEFAULT_LANGUAGE = "English";

    private static final String FILE = "settings";
    private static final String KEY_ENGINE = "engine";
    private static final String KEY_OPENAI_KEY = "api_key";          // (name kept from version 1)
    private static final String KEY_OPENAI_MODEL = "model";
    private static final String KEY_GEMINI_KEY = "gemini_key";
    private static final String KEY_GEMINI_MODEL = "gemini_model";
    private static final String KEY_ON_DEVICE_MODEL = "on_device_model";
    private static final String KEY_MOBILE_DATA = "download_mobile_data";
    private static final String KEY_LANGUAGE = "language";
    private static final String KEY_SHOW_ORIGINAL = "show_original";
    private static final String KEY_LIVE_MODE = "live_mode";
    private static final String KEY_DOWNLOAD_PREFIX = "download_id_";
    private static final String KEY_READ_ALOUD = "read_aloud";
    private static final String KEY_SPEECH_RATE = "speech_rate";
    private static final String KEY_VOICE_MODE = "voice_mode";
    private static final String KEY_AI_VOICE_NAME = "ai_voice_name";
    private static final String KEY_AI_VOICE_MODEL = "ai_voice_model";
    private static final String KEY_AI_VOICE_MOOD = "ai_voice_mood";
    private static final String KEY_AI_PAUSED_UNTIL = "ai_voice_paused_until";
    private static final String KEY_AI_PAUSED_FOR = "ai_voice_paused_for";

    private final SharedPreferences store;

    Prefs(Context context) {
        store = context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    /** Which engine translates. Existing ChatGPT users keep ChatGPT; new users start on-device. */
    String engine() {
        String fallback = openAiKey().isEmpty() ? ENGINE_ON_DEVICE : ENGINE_OPENAI;
        String value = store.getString(KEY_ENGINE, fallback);
        if (ENGINE_GEMINI.equals(value) || ENGINE_OPENAI.equals(value) || ENGINE_ON_DEVICE.equals(value)) {
            return value;
        }
        return ENGINE_ON_DEVICE;
    }

    String openAiKey() {
        return store.getString(KEY_OPENAI_KEY, "").trim();
    }

    String openAiModel() {
        return orDefault(store.getString(KEY_OPENAI_MODEL, ""), DEFAULT_OPENAI_MODEL);
    }

    String geminiKey() {
        return store.getString(KEY_GEMINI_KEY, "").trim();
    }

    String geminiModel() {
        return orDefault(store.getString(KEY_GEMINI_MODEL, ""), DEFAULT_GEMINI_MODEL);
    }

    String onDeviceModel() {
        return ModelStore.byId(store.getString(KEY_ON_DEVICE_MODEL, ModelStore.E2B.id)).id;
    }

    boolean downloadOverMobileData() {
        return store.getBoolean(KEY_MOBILE_DATA, false);
    }

    String language() {
        return orDefault(store.getString(KEY_LANGUAGE, ""), DEFAULT_LANGUAGE);
    }

    boolean showOriginal() {
        return store.getBoolean(KEY_SHOW_ORIGINAL, false);
    }

    /** Which tab was last picked on the selection screen: "Translate once" or "Live". */
    boolean liveMode() {
        return store.getBoolean(KEY_LIVE_MODE, false);
    }

    void setLiveMode(boolean live) {
        store.edit().putBoolean(KEY_LIVE_MODE, live).apply();
    }

    /** Read every new translation aloud automatically. */
    boolean readAloud() {
        return store.getBoolean(KEY_READ_ALOUD, false);
    }

    void setReadAloud(boolean on) {
        store.edit().putBoolean(KEY_READ_ALOUD, on).apply();
    }

    /** Speaker.RATE_SLOW, RATE_NORMAL or RATE_FAST. */
    String speechRate() {
        String value = store.getString(KEY_SPEECH_RATE, Speaker.RATE_NORMAL);
        if (Speaker.RATE_SLOW.equals(value) || Speaker.RATE_FAST.equals(value)) {
            return value;
        }
        return Speaker.RATE_NORMAL;
    }

    void setSpeechRate(String rate) {
        store.edit().putString(KEY_SPEECH_RATE, rate).apply();
    }

    /** VOICE_PHONE (built-in voice) or VOICE_AI (natural Gemini voice). */
    String voiceMode() {
        return VOICE_AI.equals(store.getString(KEY_VOICE_MODE, VOICE_PHONE)) ? VOICE_AI : VOICE_PHONE;
    }

    void setVoiceMode(String mode) {
        store.edit().putString(KEY_VOICE_MODE, mode).apply();
    }

    String aiVoiceName() {
        String value = store.getString(KEY_AI_VOICE_NAME, "");
        for (String[] voice : Speaker.AI_VOICES) {
            if (voice[0].equals(value)) {
                return value;
            }
        }
        return Speaker.AI_VOICES[0][0];
    }

    void setAiVoiceName(String name) {
        store.edit().putString(KEY_AI_VOICE_NAME, name).apply();
    }

    String aiVoiceModel() {
        String value = store.getString(KEY_AI_VOICE_MODEL, Speaker.AI_MODEL_BEST);
        return Speaker.AI_MODEL_FAST.equals(value) ? Speaker.AI_MODEL_FAST : Speaker.AI_MODEL_BEST;
    }

    void setAiVoiceModel(String model) {
        store.edit().putString(KEY_AI_VOICE_MODEL, model).apply();
    }

    String aiVoiceMood() {
        String value = store.getString(KEY_AI_VOICE_MOOD, "");
        for (String[] mood : Speaker.AI_MOODS) {
            if (mood[0].equals(value)) {
                return value;
            }
        }
        return Speaker.AI_MOODS[0][0];
    }

    void setAiVoiceMood(String mood) {
        store.edit().putString(KEY_AI_VOICE_MOOD, mood).apply();
    }

    /**
     * Until when (wall-clock ms) the AI voice rests because this key used up Google's daily limit;
     * 0 if it doesn't.
     */
    long aiVoicePausedUntil(String key) {
        if (!keyTag(key).equals(store.getString(KEY_AI_PAUSED_FOR, ""))) {
            return 0;
        }
        return store.getLong(KEY_AI_PAUSED_UNTIL, 0);
    }

    void setAiVoicePaused(String key, long untilMs) {
        store.edit().putString(KEY_AI_PAUSED_FOR, keyTag(key)).putLong(KEY_AI_PAUSED_UNTIL, untilMs).apply();
    }

    void clearAiVoicePause() {
        store.edit().remove(KEY_AI_PAUSED_FOR).remove(KEY_AI_PAUSED_UNTIL).apply();
    }

    /** Tells keys apart without storing the key a second time. */
    private static String keyTag(String key) {
        return key == null || key.isEmpty() ? "" : Integer.toHexString(key.trim().hashCode());
    }

    long downloadId(String modelId) {
        return store.getLong(KEY_DOWNLOAD_PREFIX + modelId, -1);
    }

    void setDownloadId(String modelId, long id) {
        store.edit().putLong(KEY_DOWNLOAD_PREFIX + modelId, id).apply();
    }

    void setEngine(String engine) {
        store.edit().putString(KEY_ENGINE, engine).apply();
    }

    void setOnDeviceModel(String modelId) {
        store.edit().putString(KEY_ON_DEVICE_MODEL, modelId).apply();
    }

    /** Saves everything typed on the settings screen. */
    void saveFields(String openAiKey, String openAiModel, String geminiKey, String geminiModel, String language,
                    boolean showOriginal, boolean mobileData) {
        store.edit()
                .putString(KEY_OPENAI_KEY, openAiKey.trim())
                .putString(KEY_OPENAI_MODEL, openAiModel.trim())
                .putString(KEY_GEMINI_KEY, geminiKey.trim())
                .putString(KEY_GEMINI_MODEL, geminiModel.trim())
                .putString(KEY_LANGUAGE, language.trim())
                .putBoolean(KEY_SHOW_ORIGINAL, showOriginal)
                .putBoolean(KEY_MOBILE_DATA, mobileData)
                .apply();
    }

    private static String orDefault(String value, String fallback) {
        String trimmed = value == null ? "" : value.trim();
        return trimmed.isEmpty() ? fallback : trimmed;
    }
}
