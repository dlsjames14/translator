package app.screentranslator;

import android.content.Context;

/**
 * A snapshot of the settings one translation needs, taken on the main thread and handed to the
 * background thread. Also builds the matching engine.
 */
final class EngineSettings {

    final String engine;
    final String openAiKey;
    final String openAiModel;
    final String geminiKey;
    final String geminiModel;
    final String onDeviceModel;
    final String language;
    final boolean showOriginal;

    EngineSettings(Prefs prefs) {
        engine = prefs.engine();
        openAiKey = prefs.openAiKey();
        openAiModel = prefs.openAiModel();
        geminiKey = prefs.geminiKey();
        geminiModel = prefs.geminiModel();
        onDeviceModel = prefs.onDeviceModel();
        language = prefs.language();
        showOriginal = prefs.showOriginal();
    }

    /** Changes whenever a different engine object is needed. */
    String identity() {
        switch (engine) {
            case Prefs.ENGINE_GEMINI:
                return engine + "|" + geminiKey + "|" + geminiModel;
            case Prefs.ENGINE_OPENAI:
                return engine + "|" + openAiKey + "|" + openAiModel;
            default:
                return engine + "|" + onDeviceModel;
        }
    }

    TranslationEngine create(Context context) {
        switch (engine) {
            case Prefs.ENGINE_GEMINI:
                return new GeminiEngine(geminiKey, geminiModel);
            case Prefs.ENGINE_OPENAI:
                return new OpenAiEngine(openAiKey, openAiModel);
            default:
                return new OnDeviceEngine(context, ModelStore.byId(onDeviceModel));
        }
    }

    /** Live mode's fastest pace. Gemini's free tier only allows a few requests per minute. */
    long liveMinIntervalMs() {
        return Prefs.ENGINE_GEMINI.equals(engine) ? 5000 : 1500;
    }

    /** Live mode's slowest pace, used while changes on screen keep containing no new text. */
    long liveMaxIntervalMs() {
        return Prefs.ENGINE_GEMINI.equals(engine) ? 20000 : 8000;
    }
}
