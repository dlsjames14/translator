package app.screentranslator;

import android.graphics.Bitmap;
import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;

/** Network and image helpers for the online engines (ChatGPT and Gemini). */
final class Http {

    static final class Reply {
        final int code;
        final String body;

        Reply(int code, String body) {
            this.code = code;
            this.body = body;
        }

        boolean ok() {
            return code >= 200 && code < 300;
        }
    }

    private Http() {
    }

    /** POSTs JSON. {@code headers} is name, value, name, value… */
    static Reply postJson(String url, String[] headers, String json) throws TranslateException {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(20_000);
            connection.setReadTimeout(90_000);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            for (int i = 0; i + 1 < headers.length; i += 2) {
                connection.setRequestProperty(headers[i], headers[i + 1]);
            }
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream out = connection.getOutputStream()) {
                out.write(bytes);
            }
            int code = connection.getResponseCode();
            InputStream in = code >= 400 ? connection.getErrorStream() : connection.getInputStream();
            return new Reply(code, in == null ? "" : readAll(in));
        } catch (UnknownHostException e) {
            throw new TranslateException("No internet connection. Check Wi-Fi or mobile data.", false);
        } catch (SocketTimeoutException e) {
            throw new TranslateException("The AI took too long to answer. Try again, or select a smaller area.",
                    false);
        } catch (IOException e) {
            throw new TranslateException("Network problem: " + e.getMessage(), false);
        } catch (IllegalArgumentException e) {
            // e.g. a pasted key containing a line break
            throw new TranslateException("The API key contains characters that aren't allowed. Paste it again.",
                    true);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static String readAll(InputStream in) throws IOException {
        try (InputStream stream = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = stream.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    /** JPEG as base64, resized so small selections are easier to read and huge ones aren't wasteful. */
    static String jpegBase64(Bitmap image) {
        int longest = Math.max(image.getWidth(), image.getHeight());
        float scale = 1f;
        if (longest < 800) {
            scale = Math.min(3f, 800f / longest);
        } else if (longest > 2048) {
            scale = 2048f / longest;
        }
        Bitmap scaled = image;
        if (scale != 1f) {
            int w = Math.max(1, Math.round(image.getWidth() * scale));
            int h = Math.max(1, Math.round(image.getHeight() * scale));
            scaled = Bitmap.createScaledBitmap(image, w, h, true);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        scaled.compress(Bitmap.CompressFormat.JPEG, 90, out);
        if (scaled != image) {
            scaled.recycle();
        }
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
    }
}
