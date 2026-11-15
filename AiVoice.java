package app.screentranslator;

import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * The natural AI voice: Gemini's speech model reads the text like a person, with feeling.
 * Audio is streamed: playback starts as soon as the first fraction of a second arrives, instead of
 * waiting for the whole clip. The next piece of text is generated while the current one plays.
 * Call speak/stop/close on the main thread.
 */
final class AiVoice {

    interface Callback {
        /** Everything queued has been read. Main thread. */
        void onAllDone();

        /** The AI voice failed; {@code unread} is the text that hasn't been read yet. Main thread. */
        void onFailed(String unread, TranslateException error);
    }

    private static final String BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models/";
    private static final int DEFAULT_RATE = 24000;
    private static final long STALL_MS = 30_000;   // no audio for this long: give up on the AI voice
    private static final Object END = new Object();

    /** A part of the audio: raw 16-bit mono samples and their rate. */
    static final class Chunk {
        final byte[] data;
        final int sampleRate;

        Chunk(byte[] data, int sampleRate) {
            this.data = data;
            this.sampleRate = sampleRate;
        }
    }

    private static final class Job {
        final int generation;
        final String text;
        /** Chunk objects as they arrive, then END, or a Throwable if it failed. */
        final LinkedBlockingQueue<Object> audio = new LinkedBlockingQueue<>();
        Future<?> task;

        Job(int generation, String text) {
            this.generation = generation;
            this.text = text;
        }
    }

    private final Callback callback;
    private final Handler main = new Handler(Looper.getMainLooper());
    // Two at a time: the piece being played and the next one.
    private final ExecutorService synth = Executors.newFixedThreadPool(2);
    private final LinkedBlockingQueue<Job> jobs = new LinkedBlockingQueue<>();
    private final Object lock = new Object();
    private final Thread player;

    // Guarded by lock.
    private int generation;
    private int pending;

    private volatile AudioTrack track;
    private volatile boolean closed;

    AiVoice(Callback callback) {
        this.callback = callback;
        player = new Thread(this::playLoop, "ai-voice");
        player.start();
    }

    /** Queues pieces of text to be read in order. */
    void speak(List<String> pieces, Speaker.Options options) {
        synchronized (lock) {
            for (String piece : pieces) {
                final Job job = new Job(generation, piece);
                job.task = synth.submit(() -> generate(job, options));
                jobs.add(job);
                pending++;
            }
        }
    }

    /** How many pieces are waiting or playing. */
    int backlog() {
        synchronized (lock) {
            return pending;
        }
    }

    /** Stops reading and forgets everything queued. */
    void stop() {
        synchronized (lock) {
            generation++;
            pending = 0;
            for (Job job : jobs) {
                job.task.cancel(true);
            }
            jobs.clear();
        }
        silenceTrack();
    }

    void close() {
        closed = true;
        stop();
        player.interrupt();
        synth.shutdownNow();
    }

    // ----------------------------------------------------------------------------------------
    // Generating the audio (on a synth thread)

    private void generate(Job job, Speaker.Options options) {
        try {
            stream(job.text, options, chunk -> {
                if (job.generation != currentGeneration()) {
                    throw new TranslateException("Stopped.", false);   // no longer needed
                }
                job.audio.add(chunk);
            });
            job.audio.add(END);
        } catch (TranslateException e) {
            job.audio.add(e);
        } catch (RuntimeException e) {
            job.audio.add(new TranslateException("Something went wrong: " + e, false));
        }
    }

    interface ChunkHandler {
        void onChunk(Chunk chunk) throws TranslateException;
    }

    /** Streams the speech for one piece of text, handing over audio as it arrives. */
    static void stream(String text, Speaker.Options options, ChunkHandler handler) throws TranslateException {
        if (options.geminiKey.isEmpty()) {
            throw new TranslateException("No Gemini key.", true);
        }
        // Optional parts of the request; if the model rejects one, it's dropped and the request sent again.
        boolean rawAudio = true;      // ask for headerless audio
        boolean style = true;         // how to read (emotion, pace)
        boolean newVoiceField = true; // "voice" (newer) vs "prebuiltVoiceConfig" (older)
        String url = BASE_URL + options.aiModel + ":streamGenerateContent?alt=sse";
        String[] headers = {"x-goog-api-key", options.geminiKey};
        for (int attempt = 0; attempt < 5; attempt++) {
            final int[] rate = {0};
            Http.Reply reply = Http.postJsonStream(url, headers,
                    requestBody(text, options, rawAudio, style, newVoiceField), line -> {
                        Chunk chunk = parseEvent(line, rate);
                        if (chunk != null) {
                            handler.onChunk(chunk);
                        }
                    });
            if (reply.code == 400) {
                String body = reply.body.toLowerCase(Locale.ROOT);
                if (rawAudio && (body.contains("response_format") || body.contains("responseformat"))) {
                    rawAudio = false;
                    continue;
                }
                if (style && (body.contains("speech_metadata") || body.contains("speechmetadata"))) {
                    style = false;
                    continue;
                }
                if (newVoiceField && body.contains("voice")) {
                    newVoiceField = false;
                    continue;
                }
            }
            if (!reply.ok()) {
                throw describeError(reply, options.aiModel);
            }
            if (rate[0] == 0) {
                throw new TranslateException("Gemini sent no audio.", false);
            }
            return;
        }
        throw new TranslateException("Gemini couldn't create the voice for this text.", false);
    }

    /**
     * Reads one line of the streamed reply ("data: {…}"). Returns its audio, or null if it has none.
     * {@code rate[0]} remembers the sample rate (set by the first audio, 0 before).
     */
    static Chunk parseEvent(String line, int[] rate) throws TranslateException {
        if (!line.startsWith("data:")) {
            return null;   // blank lines and comments between events
        }
        String json = line.substring(5).trim();
        if (json.isEmpty() || json.equals("[DONE]")) {
            return null;
        }
        try {
            JSONObject event = new JSONObject(json);
            JSONObject error = event.optJSONObject("error");
            if (error != null) {
                throw new TranslateException("Google's voice service reported a problem: "
                        + Prompts.string(error, "message"), false);
            }
            JSONArray candidates = event.optJSONArray("candidates");
            if (candidates == null || candidates.length() == 0) {
                return null;
            }
            JSONObject content = candidates.getJSONObject(0).optJSONObject("content");
            JSONArray parts = content == null ? null : content.optJSONArray("parts");
            if (parts == null) {
                return null;
            }
            for (int i = 0; i < parts.length(); i++) {
                JSONObject inline = parts.getJSONObject(i).optJSONObject("inlineData");
                if (inline == null || inline.isNull("data")) {
                    continue;
                }
                byte[] bytes = Base64.decode(inline.getString("data"), Base64.DEFAULT);
                Chunk chunk = toPcm(bytes, inline.optString("mimeType", ""), rate[0]);
                rate[0] = chunk.sampleRate;
                return chunk;
            }
            return null;
        } catch (JSONException | IllegalArgumentException e) {
            return null;   // a piece of the stream this app doesn't understand; skip it
        }
    }

    static String requestBody(String text, Speaker.Options options, boolean rawAudio, boolean style,
                              boolean newVoiceField) throws TranslateException {
        try {
            JSONObject part = new JSONObject().put("text", text);
            if (style) {
                part.put("speech_metadata", new JSONObject().put("style", options.style()));
            }
            JSONObject voiceConfig = newVoiceField
                    ? new JSONObject().put("voice", options.aiVoiceName)
                    : new JSONObject().put("prebuiltVoiceConfig",
                    new JSONObject().put("voiceName", options.aiVoiceName));
            JSONObject generation = new JSONObject()
                    .put("responseModalities", new JSONArray().put("AUDIO"))
                    .put("speechConfig", new JSONObject().put("voiceConfig", voiceConfig));
            if (rawAudio) {
                generation.put("responseFormat", new JSONObject().put("audio", new JSONObject()
                        .put("mimeType", "AUDIO_L16")
                        .put("sampleRate", DEFAULT_RATE)));
            }
            return new JSONObject()
                    .put("contents", new JSONArray().put(new JSONObject()
                            .put("role", "user")
                            .put("parts", new JSONArray().put(part))))
                    .put("generationConfig", generation)
                    .toString();
        } catch (JSONException e) {
            throw new TranslateException("Couldn't prepare the voice request.", false);
        }
    }

    /**
     * Accepts raw 16-bit audio, or a WAV file (which is unwrapped).
     *
     * @param knownRate the rate from earlier chunks of the same stream, or 0
     */
    static Chunk toPcm(byte[] bytes, String mimeType, int knownRate) {
        if (bytes.length >= 12 && "RIFF".equals(ascii(bytes, 0)) && "WAVE".equals(ascii(bytes, 8))) {
            int rate = knownRate > 0 ? knownRate : DEFAULT_RATE;
            int offset = 12;
            while (offset + 8 <= bytes.length) {
                String id = ascii(bytes, offset);
                long size = littleEndianInt(bytes, offset + 4) & 0xFFFFFFFFL;
                int start = offset + 8;
                if ("fmt ".equals(id) && start + 8 <= bytes.length) {
                    int fromHeader = (int) littleEndianInt(bytes, start + 4);
                    if (fromHeader > 0) {
                        rate = fromHeader;
                    }
                } else if ("data".equals(id)) {
                    int end = (int) Math.min(bytes.length, start + size);
                    byte[] data = new byte[Math.max(0, end - start)];
                    System.arraycopy(bytes, start, data, 0, data.length);
                    return new Chunk(data, rate);
                }
                offset = (int) Math.min(Integer.MAX_VALUE, start + size + (size & 1));
            }
            byte[] data = new byte[Math.max(0, bytes.length - 44)];   // unusual header: assume 44 bytes
            System.arraycopy(bytes, bytes.length - data.length, data, 0, data.length);
            return new Chunk(data, rate);
        }
        int rate = knownRate > 0 ? knownRate : DEFAULT_RATE;
        int at = mimeType.toLowerCase(Locale.ROOT).indexOf("rate=");
        if (at >= 0) {
            StringBuilder digits = new StringBuilder();
            for (int i = at + 5; i < mimeType.length() && Character.isDigit(mimeType.charAt(i)); i++) {
                digits.append(mimeType.charAt(i));
            }
            if (digits.length() > 0 && digits.length() < 7) {
                rate = Integer.parseInt(digits.toString());
            }
        }
        return new Chunk(bytes, rate);
    }

    /** Keeps 16-bit samples whole when a chunk ends in the middle of one. */
    static final class SampleAligner {
        private int leftover = -1;

        byte[] align(byte[] data) {
            int extra = leftover >= 0 ? 1 : 0;
            int total = data.length + extra;
            int even = total & ~1;
            byte[] out = new byte[even];
            int copied = 0;
            if (extra == 1 && even > 0) {
                out[0] = (byte) leftover;
                copied = 1;
            }
            int fromData = even - copied;
            System.arraycopy(data, 0, out, copied, Math.max(0, fromData));
            if (total % 2 == 1) {
                leftover = data.length > 0 ? data[data.length - 1] & 0xFF : leftover;
            } else {
                leftover = -1;
            }
            return out;
        }
    }

    // ----------------------------------------------------------------------------------------
    // Playback (on the player thread)

    private void playLoop() {
        while (!closed) {
            Job job;
            try {
                job = jobs.take();
            } catch (InterruptedException e) {
                return;
            }
            if (job.generation != currentGeneration()) {
                continue;
            }
            try {
                Throwable problem = play(job);
                if (problem != null) {
                    fail(job, problem);
                    continue;
                }
            } catch (InterruptedException e) {
                return;
            }
            finished(job);
        }
    }

    /** Plays one piece as its audio streams in. Returns the error if generating it failed. */
    private Throwable play(Job job) throws InterruptedException {
        AudioTrack audio = null;
        SampleAligner aligner = new SampleAligner();
        int sampleRate = DEFAULT_RATE;
        long frames = 0;
        long lastData = SystemClock.uptimeMillis();
        try {
            while (job.generation == currentGeneration() && !closed) {
                Object item = job.audio.poll(200, TimeUnit.MILLISECONDS);
                if (item == null) {
                    if (SystemClock.uptimeMillis() - lastData > STALL_MS) {
                        return new TranslateException("Google's voice service stopped responding.", false);
                    }
                    continue;
                }
                lastData = SystemClock.uptimeMillis();
                if (item == END) {
                    break;
                }
                if (item instanceof Throwable) {
                    return (Throwable) item;
                }
                Chunk chunk = (Chunk) item;
                byte[] data = aligner.align(chunk.data);
                if (data.length == 0) {
                    continue;
                }
                if (audio == null) {
                    sampleRate = chunk.sampleRate;
                    audio = openTrack(sampleRate);
                    if (audio == null) {
                        return null;   // the phone couldn't open audio output; skip this piece
                    }
                    track = audio;
                    audio.play();
                }
                int written = 0;
                while (written < data.length && job.generation == currentGeneration() && !closed) {
                    int count = audio.write(data, written, Math.min(4800, data.length - written));
                    if (count <= 0) {
                        break;
                    }
                    written += count;
                }
                frames += written / 2;
            }
            if (audio != null && job.generation == currentGeneration() && !closed) {
                audio.stop();   // plays out what's left in the buffer
                long deadline = SystemClock.uptimeMillis() + 2000 + frames * 1000L / sampleRate;
                while (job.generation == currentGeneration() && !closed
                        && (audio.getPlaybackHeadPosition() & 0xFFFFFFFFL) < frames
                        && SystemClock.uptimeMillis() < deadline) {
                    SystemClock.sleep(30);
                }
            }
            return null;
        } catch (IllegalStateException ignored) {
            return null;   // stopped from another thread
        } finally {
            if (audio != null) {
                track = null;
                audio.release();
            }
        }
    }

    private static AudioTrack openTrack(int sampleRate) {
        int minBuffer = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
        try {
            return new AudioTrack.Builder()
                    .setAudioAttributes(Speaker.SPEECH)
                    .setAudioFormat(new AudioFormat.Builder()
                            .setSampleRate(sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .build())
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setBufferSizeInBytes(Math.max(minBuffer * 2, sampleRate / 2))   // ~¼ second
                    .build();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private int currentGeneration() {
        synchronized (lock) {
            return generation;
        }
    }

    private void finished(Job job) {
        boolean allDone;
        int gen;
        synchronized (lock) {
            gen = generation;
            allDone = job.generation == generation && pending > 0 && --pending == 0;
        }
        if (allDone) {
            main.post(() -> {
                if (gen == currentGeneration() && !closed) {
                    callback.onAllDone();
                }
            });
        }
    }

    /** Stops the AI voice and hands the unread text back, so the phone voice can read it instead. */
    private void fail(Job failed, Throwable cause) {
        StringBuilder unread = new StringBuilder();
        int gen;
        synchronized (lock) {
            if (failed.generation != generation) {
                return;
            }
            unread.append(failed.text);
            for (Job job : jobs) {
                if (job.generation == generation) {
                    unread.append('\n').append(job.text);
                }
                job.task.cancel(true);
            }
            jobs.clear();
            pending = 0;
            gen = ++generation;
        }
        final TranslateException error = cause instanceof TranslateException
                ? (TranslateException) cause
                : new TranslateException("Something went wrong: " + cause, false);
        final String text = unread.toString();
        main.post(() -> {
            if (gen == currentGeneration() && !closed) {
                callback.onFailed(text, error);
            }
        });
    }

    private void silenceTrack() {
        AudioTrack current = track;
        if (current != null) {
            try {
                current.pause();
                current.flush();   // also frees a write that is waiting for room
            } catch (IllegalStateException ignored) {
                // Already released.
            }
        }
    }

    // ----------------------------------------------------------------------------------------
    // Helpers

    private static String ascii(byte[] bytes, int offset) {
        return new String(bytes, offset, 4, StandardCharsets.US_ASCII);
    }

    private static long littleEndianInt(byte[] bytes, int offset) {
        if (offset + 4 > bytes.length) {
            return 0;
        }
        return (bytes[offset] & 0xFF) | (bytes[offset + 1] & 0xFF) << 8 | (bytes[offset + 2] & 0xFF) << 16
                | (long) (bytes[offset + 3] & 0xFF) << 24;
    }

    private static TranslateException describeError(Http.Reply reply, String model) {
        String message = "";
        try {
            message = Prompts.string(new JSONObject(reply.body).getJSONObject("error"), "message");
        } catch (JSONException ignored) {
            // Not JSON.
        }
        String lower = message.toLowerCase(Locale.ROOT);
        if (lower.contains("api key") && (reply.code == 400 || reply.code == 401 || reply.code == 403)) {
            return new TranslateException("Google didn't accept your Gemini key.", true);
        }
        switch (reply.code) {
            case 403:
                return new TranslateException("Google refused the voice request.", true);
            case 404:
                return new TranslateException("The voice model \"" + model + "\" wasn't found.", true);
            case 429:
                if (GeminiQuota.isDaily(reply.body)) {
                    return new TranslateException("Today's free AI voice limit is used up.", true, 0, true);
                }
                return new TranslateException("The free per-minute AI voice limit was reached.", false,
                        Math.max(60_000L, GeminiQuota.retryDelayMs(reply.body)));
            default:
                if (reply.code >= 500) {
                    return new TranslateException("Google's voice service had a problem.", false);
                }
                return new TranslateException("The voice request failed (" + reply.code + ")"
                        + (message.isEmpty() ? "." : ": " + message), false);
        }
    }
}
