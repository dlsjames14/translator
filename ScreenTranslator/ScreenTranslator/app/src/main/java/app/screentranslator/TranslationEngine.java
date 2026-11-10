package app.screentranslator;

import android.graphics.Bitmap;

import java.util.List;
import java.util.Set;

/**
 * A way of turning a screenshot into a translation: ChatGPT, Gemini, or the on-device AI.
 * All methods are called on the same background thread.
 */
interface TranslationEngine {

    /** "Translate once": the whole translation as readable text. */
    String translateOnce(Bitmap image, Translation.Request request) throws TranslateException;

    /**
     * Live mode: only the text that hasn't been translated yet.
     *
     * @param recentOriginals the last few texts already translated, oldest first
     * @param seenKeys        every text already translated, as {@link Translation#normalize} keys
     */
    List<Translation.Line> translateLive(Bitmap image, Translation.Request request, List<String> recentOriginals,
                                         Set<String> seenKeys) throws TranslateException;

    /** Frees whatever the engine holds (the on-device AI uses a lot of memory). */
    void close();
}
