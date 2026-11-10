package app.screentranslator;

import android.graphics.Bitmap;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Google Gemini (free tier available): reads the screenshot and translates it in one request. */
final class GeminiEngine implements TranslationEngine {

    private static final String BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models/";

    private final String apiKey;
    private final String model;

    GeminiEngine(String apiKey, String model) {
        this.apiKey = apiKey;
        String name = model.trim();
        this.model = name.startsWith("models/") ? name.substring("models/".length()) : name;
    }

    @Override
    public String translateOnce(Bitmap image, Translation.Request request) throws TranslateException {
        String content = complete(Prompts.once(request.language, request.showOriginal),
                "Translate the text in this screenshot.", Http.jpegBase64(image), false).trim();
        return content.isEmpty() ? Translation.NO_TEXT : content;
    }

    @Override
    public List<Translation.Line> translateLive(Bitmap image, Translation.Request request,
                                                List<String> recentOriginals, Set<String> seenKeys)
            throws TranslateException {
        String content = complete(Prompts.live(request.language, recentOriginals),
                "List the text in this screenshot that still needs translating, and translate it.",
                Http.jpegBase64(image), true);
        return Prompts.parseLines(content);
    }

    @Override
    public void close() {
        // Nothing to free.
    }

    private String complete(String systemPrompt, String userText, String jpegBase64, boolean jsonReply)
            throws TranslateException {
        if (apiKey == null || apiKey.isEmpty()) {
            throw new TranslateException("Add your Gemini API key in the Screen Translator app first.", true);
        }
        // Optional extras; if this model rejects one, it's dropped and the request is sent again.
        boolean quickThinking = true;
        boolean sharpImage = true;
        boolean jsonFormat = jsonReply;
        String url = BASE_URL + model + ":generateContent";
        String[] headers = {"x-goog-api-key", apiKey};
        for (int attempt = 0; attempt < 4; attempt++) {
            Http.Reply reply = Http.postJson(url, headers,
                    requestBody(systemPrompt, userText, jpegBase64, quickThinking, sharpImage, jsonFormat));
            if (reply.code == 400) {
                String body = reply.body.toLowerCase(Locale.ROOT);
                if (quickThinking && body.contains("thinking")) {
                    quickThinking = false;
                    continue;
                }
                if (sharpImage && (body.contains("media_resolution") || body.contains("mediaresolution"))) {
                    sharpImage = false;
                    continue;
                }
                if (jsonFormat && (body.contains("response_mime") || body.contains("responsemimetype"))) {
                    jsonFormat = false;
                    continue;
                }
            }
            if (!reply.ok()) {
                throw describeError(reply);
            }
            return readText(reply.body);
        }
        throw new TranslateException("Gemini couldn't handle the request. Try a different Gemini model.", true);
    }

    private static String requestBody(String systemPrompt, String userText, String jpegBase64,
                                      boolean quickThinking, boolean sharpImage, boolean jsonFormat)
            throws TranslateException {
        try {
            JSONObject imagePart = new JSONObject()
                    .put("inlineData", new JSONObject().put("mimeType", "image/jpeg").put("data", jpegBase64));
            if (sharpImage) {
                imagePart.put("mediaResolution", new JSONObject().put("level", "media_resolution_high"));
            }
            JSONObject content = new JSONObject()
                    .put("role", "user")
                    .put("parts", new JSONArray()
                            .put(new JSONObject().put("text", userText))
                            .put(imagePart));

            JSONObject generation = new JSONObject().put("maxOutputTokens", 4000);
            if (quickThinking) {
                // Translation doesn't need long "thinking": answer straight away.
                generation.put("thinkingConfig", new JSONObject().put("thinkingLevel", "minimal"));
            }
            if (jsonFormat) {
                generation.put("responseMimeType", "application/json");
            }

            return new JSONObject()
                    .put("systemInstruction", new JSONObject()
                            .put("parts", new JSONArray().put(new JSONObject().put("text", systemPrompt))))
                    .put("contents", new JSONArray().put(content))
                    .put("generationConfig", generation)
                    .toString();
        } catch (JSONException e) {
            throw new TranslateException("Couldn't prepare the request.", false);
        }
    }

    private static String readText(String body) throws TranslateException {
        try {
            JSONObject root = new JSONObject(body);
            JSONArray candidates = root.optJSONArray("candidates");
            if (candidates == null || candidates.length() == 0) {
                JSONObject feedback = root.optJSONObject("promptFeedback");
                String reason = feedback == null ? "" : Prompts.string(feedback, "blockReason");
                throw new TranslateException("Gemini declined to read this screen"
                        + (reason.isEmpty() ? "." : " (" + reason + ")."), false);
            }
            JSONObject candidate = candidates.getJSONObject(0);
            StringBuilder text = new StringBuilder();
            JSONObject content = candidate.optJSONObject("content");
            JSONArray parts = content == null ? null : content.optJSONArray("parts");
            if (parts != null) {
                for (int i = 0; i < parts.length(); i++) {
                    JSONObject part = parts.optJSONObject(i);
                    if (part != null && !part.optBoolean("thought", false)) {
                        text.append(Prompts.string(part, "text"));
                    }
                }
            }
            if (text.length() == 0) {
                String finish = Prompts.string(candidate, "finishReason");
                if (!finish.isEmpty() && !"STOP".equals(finish)) {
                    throw new TranslateException("Gemini stopped without a translation (" + finish + ").", false);
                }
            }
            return text.toString();
        } catch (JSONException e) {
            throw new TranslateException("Gemini sent a reply this app couldn't read.", false);
        }
    }

    private TranslateException describeError(Http.Reply reply) {
        String message = "";
        try {
            message = Prompts.string(new JSONObject(reply.body).getJSONObject("error"), "message");
        } catch (JSONException ignored) {
            // Not JSON; fall back to the status code alone.
        }
        String lower = message.toLowerCase(Locale.ROOT);
        String detail = message.isEmpty() ? "" : "\n\nGemini says: " + message;

        if (lower.contains("api key") && (reply.code == 400 || reply.code == 401 || reply.code == 403)) {
            return new TranslateException("Google didn't accept your Gemini API key. Create a free key at "
                    + "aistudio.google.com/apikey and paste it into the Screen Translator app.", true);
        }
        switch (reply.code) {
            case 403:
                return new TranslateException("Google refused this request (403)." + detail, true);
            case 404:
                return new TranslateException("The Gemini model \"" + model + "\" wasn't found. In the Screen "
                        + "Translator app, check the Gemini model name." + detail, true);
            case 429:
                if (lower.contains("per day") || lower.contains("perday") || lower.contains("daily")) {
                    return new TranslateException("You've used today's free Gemini allowance. It resets every "
                            + "day; until then, switch to the on-device AI or ChatGPT.", true);
                }
                return new TranslateException("Gemini's free per-minute limit was reached. Continuing in a "
                        + "moment…", false, 30_000);
            default:
                if (reply.code >= 500) {
                    return new TranslateException("Google's servers had a problem (" + reply.code
                            + "). Try again in a moment.", false);
                }
                return new TranslateException("Gemini returned an error (" + reply.code + ")." + detail, false);
        }
    }
}
