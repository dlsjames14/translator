package app.screentranslator;

import android.graphics.Bitmap;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/** ChatGPT (OpenAI API): reads the screenshot and translates it in one request. */
final class OpenAiEngine implements TranslationEngine {

    private static final String ENDPOINT = "https://api.openai.com/v1/chat/completions";

    private final String apiKey;
    private final String model;

    OpenAiEngine(String apiKey, String model) {
        this.apiKey = apiKey;
        this.model = model;
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
            throw new TranslateException("Add your OpenAI API key in the Screen Translator app first.", true);
        }
        String imageUrl = "data:image/jpeg;base64," + jpegBase64;
        boolean fastReasoning = true;
        boolean jsonFormat = jsonReply;
        String[] headers = {"Authorization", "Bearer " + apiKey};
        for (int attempt = 0; attempt < 3; attempt++) {
            Http.Reply reply = Http.postJson(ENDPOINT, headers,
                    requestBody(systemPrompt, userText, imageUrl, fastReasoning, jsonFormat));
            if (reply.code == 400) {
                // Some models don't accept every option. Drop the one they object to and ask again.
                String body = reply.body.toLowerCase(Locale.ROOT);
                if (fastReasoning && body.contains("reasoning")) {
                    fastReasoning = false;
                    continue;
                }
                if (jsonFormat && body.contains("response_format")) {
                    jsonFormat = false;
                    continue;
                }
            }
            if (!reply.ok()) {
                throw describeError(reply);
            }
            return readContent(reply.body);
        }
        throw new TranslateException("OpenAI couldn't handle the request. Try a different AI model.", true);
    }

    private String requestBody(String systemPrompt, String userText, String imageUrl, boolean fastReasoning,
                               boolean jsonFormat) throws TranslateException {
        try {
            JSONArray userContent = new JSONArray()
                    .put(new JSONObject().put("type", "text").put("text", userText))
                    .put(new JSONObject()
                            .put("type", "image_url")
                            .put("image_url", new JSONObject().put("url", imageUrl).put("detail", "high")));
            JSONArray messages = new JSONArray()
                    .put(new JSONObject().put("role", "system").put("content", systemPrompt))
                    .put(new JSONObject().put("role", "user").put("content", userContent));
            JSONObject body = new JSONObject()
                    .put("model", model)
                    .put("messages", messages)
                    .put("max_completion_tokens", 4000);
            if (fastReasoning) {
                body.put("reasoning_effort", "none");   // translation doesn't need long "thinking"
            }
            if (jsonFormat) {
                body.put("response_format", new JSONObject().put("type", "json_object"));
            }
            return body.toString();
        } catch (JSONException e) {
            throw new TranslateException("Couldn't prepare the request.", false);
        }
    }

    private static String readContent(String body) throws TranslateException {
        try {
            JSONObject message = new JSONObject(body)
                    .getJSONArray("choices")
                    .getJSONObject(0)
                    .getJSONObject("message");
            String text = Prompts.string(message, "content");
            if (text.trim().isEmpty()) {
                text = Prompts.string(message, "refusal");
            }
            return text;
        } catch (JSONException e) {
            throw new TranslateException("OpenAI sent a reply this app couldn't read.", false);
        }
    }

    private TranslateException describeError(Http.Reply reply) {
        String message = "";
        String code = "";
        try {
            JSONObject error = new JSONObject(reply.body).getJSONObject("error");
            message = Prompts.string(error, "message");
            code = Prompts.string(error, "code") + " " + Prompts.string(error, "type");
        } catch (JSONException ignored) {
            // Not JSON; fall back to the status code alone.
        }
        String detail = message.isEmpty() ? "" : "\n\nOpenAI says: " + message;
        switch (reply.code) {
            case 401:
                return new TranslateException("OpenAI didn't accept your API key. Open the Screen Translator app "
                        + "and paste the key again.", true);
            case 403:
                return new TranslateException("Your OpenAI account isn't allowed to use this model." + detail, true);
            case 404:
                return new TranslateException("The AI model \"" + model + "\" wasn't found. In the Screen "
                        + "Translator app, check the ChatGPT model name." + detail, true);
            case 429:
                if (code.contains("insufficient_quota")) {
                    return new TranslateException("Your OpenAI account is out of credit. Add credit at "
                            + "platform.openai.com (Settings → Billing), or switch to Gemini or the on-device AI.",
                            true);
                }
                return new TranslateException("Too many requests right now. Wait a few seconds and try again.",
                        false);
            default:
                if (reply.code >= 500) {
                    return new TranslateException("OpenAI's servers had a problem (" + reply.code
                            + "). Try again in a moment.", false);
                }
                return new TranslateException("OpenAI returned an error (" + reply.code + ")." + detail, false);
        }
    }
}
