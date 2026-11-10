package app.screentranslator;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Icon;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Display;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Runs while the translator is on. It owns:
 * - the screen capture (Android's MediaProjection, mirrored into an ImageReader),
 * - the floating bubble,
 * - the "drag a box" selection layer with its Translate once / Live switch,
 * - the result card (once) and the live panel (Live mode).
 * Everything that touches the capture or the overlays runs on the main thread.
 */
public class TranslatorService extends Service {

    static final String ACTION_START = "app.screentranslator.action.START";
    static final String ACTION_STOP = "app.screentranslator.action.STOP";
    static final String EXTRA_RESULT_CODE = "result_code";
    static final String EXTRA_RESULT_DATA = "result_data";

    private static final String CHANNEL_ID = "translator";
    private static final int NOTIFICATION_ID = 42;
    /** Time for the screen to redraw after our own overlays disappear, so they aren't in the screenshot. */
    private static final long SETTLE_MS = 180;
    private static final long MAX_WAIT_MS = 1200;

    // Live mode tuning
    private static final long LIVE_TICK_MS = 300;             // how often the screen is checked for changes
    private static final long LIVE_SETTLE_MS = 700;           // wait until the screen stops changing...
    private static final long LIVE_MAX_DELAY_MS = 3000;       // ...but never longer than this (e.g. videos)
    private static final long LIVE_MIN_INTERVAL_MS = 1500;    // at most one request this often
    private static final long LIVE_MAX_INTERVAL_MS = 8000;    // slowest pace when nothing new turns up
    private static final long LIVE_ERROR_BACKOFF_MS = 6000;
    private static final long LIVE_AUTO_PAUSE_MS = 20 * 60 * 1000L;
    private static final int LIVE_SAMPLES = 40000;            // pixels compared per check
    private static final int LIVE_PIXEL_DIFF = 28;            // brightness change that counts as "different"
    private static final float LIVE_BIG_CHANGE = 0.05f;       // 5% of the area: e.g. scrolled to new content
    private static final int LIVE_RECENT_LIMIT = 40;          // already-translated items sent as a reminder
    private static final int LIVE_SEEN_LIMIT = 3000;

    private static final int COLOR_WATCHING = 0xFF43A047;
    private static final int COLOR_BUSY = 0xFFFFB300;
    private static final int COLOR_PAUSED = 0xFF9E9E9E;
    private static final int COLOR_LIVE_BUBBLE = 0xFFE53935;
    private static final int MASK_COLOR = 0xFF808080;

    private static volatile boolean running;

    static boolean isRunning() {
        return running;
    }

    /** State of one live translation session. */
    private static final class LiveSession {
        final boolean wholeScreen;
        Rect region;          // area being watched, in screen pixels
        Point screen;         // screen size when the area was chosen
        final LinkedHashSet<String> seen = new LinkedHashSet<>();
        final ArrayDeque<String> recent = new ArrayDeque<>();
        int[] currentSig;     // what the area looks like now
        int[] sentSig;        // what it looked like when last translated
        int lastFrameChecked = -1;
        long startedAt;
        long lastChangeAt;
        long pendingSince;
        long sentAt;
        long nextAllowedAt;
        long errorUntil;
        long minInterval = LIVE_MIN_INTERVAL_MS;   // depends on the engine
        long maxInterval = LIVE_MAX_INTERVAL_MS;
        long interval = LIVE_MIN_INTERVAL_MS;
        boolean inFlight;
        boolean paused;
        boolean panelAtBottom;
        int checks;
        String lastError;

        LiveSession(boolean wholeScreen, Rect region, Point screen) {
            this.wholeScreen = wholeScreen;
            this.region = region;
            this.screen = screen;
        }
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    // The translation engine. Used only on the worker thread.
    private TranslationEngine engine;
    private String engineIdentity;

    private Context ui;
    private WindowManager windowManager;

    // Screen capture
    private MediaProjection projection;
    private VirtualDisplay virtualDisplay;
    private ImageReader reader;
    private Image latestFrame;
    private int frameCount;
    private int captureWidth;
    private int captureHeight;
    private int captureDpi;

    // Overlays
    private View bubble;
    private GradientDrawable bubbleBackground;
    private WindowManager.LayoutParams bubbleParams;
    private View selectionLayer;
    private View card;
    private TextView cardTitle;
    private TextView cardBody;
    private TextView cardCopy;
    private String cardLanguage = Prefs.DEFAULT_LANGUAGE;
    private int requestId;

    // Live mode
    private LiveSession live;
    private LivePanel livePanel;
    private final Runnable liveTick = new Runnable() {
        @Override
        public void run() {
            if (live == null) {
                return;
            }
            tickLive();
            main.postDelayed(this, LIVE_TICK_MS);
        }
    };

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_START.equals(action)) {
            start(intent);
        } else if (ACTION_STOP.equals(action)) {
            shutdown();
        } else if (!running) {
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        removeOverlays();
        releaseCapture();
        worker.execute(this::closeEngine);   // frees the on-device AI's memory
        worker.shutdown();
        super.onDestroy();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // Screen rotated: let the window context catch up, then keep everything on screen.
        main.postDelayed(this::fitOverlaysToScreen, 200);
    }

    // ----------------------------------------------------------------------------------------
    // Start / stop

    private void start(Intent intent) {
        goForeground();   // Android requires this before the screen capture can begin.

        int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0);
        Intent data = Build.VERSION.SDK_INT >= 33
                ? intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent.class)
                : legacyResultData(intent);

        removeOverlays();
        releaseCapture();
        if (data == null) {
            toast("Screen capture permission was missing. Open Screen Translator and tap Start again.");
            shutdown();
            return;
        }
        try {
            prepareWindows();
            startCapture(resultCode, data);
            showBubble();
            running = true;
        } catch (RuntimeException e) {
            toast("Couldn't start screen capture: " + e.getMessage());
            shutdown();
        }
    }

    @SuppressWarnings("deprecation")
    private static Intent legacyResultData(Intent intent) {
        return intent.getParcelableExtra(EXTRA_RESULT_DATA);
    }

    private void goForeground() {
        NotificationManager notifications = getSystemService(NotificationManager.class);
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "Screen Translator", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Shown while the translator bubble is on");
        notifications.createNotificationChannel(channel);

        PendingIntent openApp = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, TranslatorService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Notification notification = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_translate)
                .setContentTitle("Screen Translator is on")
                .setContentText("Tap the bubble in any app to translate.")
                .setContentIntent(openApp)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(
                        Icon.createWithResource(this, R.drawable.ic_translate), "Stop", stop).build())
                .build();

        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void shutdown() {
        running = false;
        requestId++;
        removeOverlays();
        releaseCapture();
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    // ----------------------------------------------------------------------------------------
    // Screen capture

    private void prepareWindows() {
        if (windowManager != null) {
            return;
        }
        if (Build.VERSION.SDK_INT >= 30) {
            Display display = getSystemService(DisplayManager.class).getDisplay(Display.DEFAULT_DISPLAY);
            ui = createDisplayContext(display)
                    .createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null);
        } else {
            ui = this;
        }
        windowManager = ui.getSystemService(WindowManager.class);
    }

    private void startCapture(int resultCode, Intent data) {
        MediaProjectionManager manager = getSystemService(MediaProjectionManager.class);
        final MediaProjection session = manager.getMediaProjection(resultCode, data);
        if (session == null) {
            throw new IllegalStateException("permission was not granted");
        }
        projection = session;
        session.registerCallback(new MediaProjection.Callback() {
            @Override
            public void onStop() {
                // The user tapped "Stop sharing" in the status bar, or Android ended it.
                if (projection == session) {
                    toast("Screen sharing ended, so Screen Translator turned off.");
                    shutdown();
                }
            }
        }, main);

        Point size = screenSize();
        captureWidth = size.x;
        captureHeight = size.y;
        captureDpi = ui.getResources().getDisplayMetrics().densityDpi;
        reader = newReader(captureWidth, captureHeight);
        virtualDisplay = session.createVirtualDisplay("ScreenTranslator", captureWidth, captureHeight, captureDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.getSurface(), null, main);
    }

    @SuppressLint("WrongConstant")
    private ImageReader newReader(int width, int height) {
        ImageReader imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3);
        imageReader.setOnImageAvailableListener(this::onFrame, main);
        return imageReader;
    }

    /**
     * Keeps only the newest frame, so a capture is instant when needed. Android only sends a new
     * frame when something on screen changed, which is also what Live mode relies on.
     */
    private void onFrame(ImageReader source) {
        if (source != reader) {
            return;
        }
        Image image;
        try {
            image = source.acquireLatestImage();
        } catch (IllegalStateException e) {
            return;
        }
        if (image == null) {
            return;
        }
        if (latestFrame != null) {
            latestFrame.close();
        }
        latestFrame = image;
        frameCount++;
    }

    /** If the phone was rotated, resize the capture to match the screen. */
    private void matchCaptureToScreen() {
        if (virtualDisplay == null) {
            return;
        }
        Point size = screenSize();
        if (size.x == captureWidth && size.y == captureHeight) {
            return;
        }
        captureWidth = size.x;
        captureHeight = size.y;
        if (latestFrame != null) {
            latestFrame.close();
            latestFrame = null;
        }
        ImageReader old = reader;
        reader = newReader(captureWidth, captureHeight);
        virtualDisplay.resize(captureWidth, captureHeight, captureDpi);
        virtualDisplay.setSurface(reader.getSurface());
        old.setOnImageAvailableListener(null, null);
        old.close();
    }

    private void releaseCapture() {
        if (latestFrame != null) {
            latestFrame.close();
            latestFrame = null;
        }
        if (virtualDisplay != null) {
            virtualDisplay.release();
            virtualDisplay = null;
        }
        if (reader != null) {
            reader.setOnImageAvailableListener(null, null);
            reader.close();
            reader = null;
        }
        if (projection != null) {
            MediaProjection old = projection;
            projection = null;
            try {
                old.stop();
            } catch (RuntimeException ignored) {
                // Already stopped.
            }
        }
    }

    @SuppressWarnings("deprecation")
    private Point screenSize() {
        if (Build.VERSION.SDK_INT >= 30) {
            Rect bounds = windowManager.getMaximumWindowMetrics().getBounds();
            return new Point(bounds.width(), bounds.height());
        }
        DisplayMetrics metrics = new DisplayMetrics();
        windowManager.getDefaultDisplay().getRealMetrics(metrics);
        return new Point(metrics.widthPixels, metrics.heightPixels);
    }

    /** Copies a frame into a bitmap (the bitmap may be a little wider than the frame). */
    private static Bitmap copyFrame(Image frame) {
        int height = frame.getHeight();
        Image.Plane plane = frame.getPlanes()[0];
        int paddedWidth = plane.getRowStride() / plane.getPixelStride();
        ByteBuffer pixels = plane.getBuffer();
        pixels.rewind();
        int needed = paddedWidth * height * 4;
        if (pixels.remaining() < needed) {
            // Some phones leave the last row short; pad it so the copy below fits.
            ByteBuffer padded = ByteBuffer.allocate(needed);
            padded.put(pixels);
            padded.rewind();
            pixels = padded;
        }
        Bitmap full = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888);
        full.copyPixelsFromBuffer(pixels);
        return full;
    }

    /** Converts a rectangle in screen pixels to frame pixels, clipped to the frame. */
    private static Rect toFrame(Rect screenRect, Point screen, int frameWidth, int frameHeight) {
        float sx = frameWidth / (float) screen.x;
        float sy = frameHeight / (float) screen.y;
        Rect rect = new Rect(Math.round(screenRect.left * sx), Math.round(screenRect.top * sy),
                Math.round(screenRect.right * sx), Math.round(screenRect.bottom * sy));
        if (!rect.intersect(0, 0, frameWidth, frameHeight)) {
            rect.setEmpty();
        }
        return rect;
    }

    private static Bitmap cut(Bitmap full, Rect box, int frameWidth, int frameHeight) {
        Rect area = box.isEmpty() ? new Rect(0, 0, frameWidth, frameHeight) : box;
        Bitmap result = Bitmap.createBitmap(full, area.left, area.top, area.width(), area.height());
        if (result != full) {
            full.recycle();
        }
        return result;
    }

    /** "Translate once": cuts out the chosen area (or the whole frame). Closes the frame. */
    private static Bitmap crop(Image frame, Rect area, Point screen) {
        try {
            int width = frame.getWidth();
            int height = frame.getHeight();
            Bitmap full = copyFrame(frame);
            Rect box = area == null ? new Rect(0, 0, width, height) : toFrame(area, screen, width, height);
            return cut(full, box, width, height);
        } finally {
            frame.close();
        }
    }

    /** Live mode: cuts out the watched area with our own panel and bubble painted over in grey. */
    private static Bitmap cropMasked(Image frame, Rect region, Point screen, List<Rect> masks) {
        int width = frame.getWidth();
        int height = frame.getHeight();
        Bitmap full = copyFrame(frame);
        Canvas canvas = new Canvas(full);
        Paint grey = new Paint();
        grey.setColor(MASK_COLOR);
        for (Rect mask : masks) {
            Rect inFrame = toFrame(mask, screen, width, height);
            if (!inFrame.isEmpty()) {
                canvas.drawRect(inFrame, grey);
            }
        }
        return cut(full, toFrame(region, screen, width, height), width, height);
    }

    // ----------------------------------------------------------------------------------------
    // Selecting an area

    private void startSelection() {
        if (selectionLayer != null || virtualDisplay == null) {
            return;
        }
        hideCard();
        if (live == null) {
            matchCaptureToScreen();   // (Live mode keeps watching underneath until a new choice is made)
        }
        final Point screen = new Point(captureWidth, captureHeight);
        final Prefs prefs = new Prefs(this);
        final boolean[] liveMode = {prefs.liveMode()};

        FrameLayout layer = new FrameLayout(ui);
        final SelectionView selection = new SelectionView(ui, area -> onAreaChosen(area, screen, liveMode[0]));
        layer.addView(selection, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // "Translate once | ● Live" switch
        LinearLayout modes = new LinearLayout(ui);
        modes.setOrientation(LinearLayout.HORIZONTAL);
        modes.setPadding(dp(4), dp(4), dp(4), dp(4));
        modes.setBackground(Ui.rounded(0xCC000000, dp(26), 0x66FFFFFF, dp(1)));
        final TextView onceTab = modeTab("Translate once");
        final TextView liveTab = modeTab("● Live");
        final Runnable paintTabs = () -> {
            styleTab(onceTab, !liveMode[0]);
            styleTab(liveTab, liveMode[0]);
            selection.setLive(liveMode[0]);
        };
        onceTab.setOnClickListener(v -> {
            liveMode[0] = false;
            prefs.setLiveMode(false);
            paintTabs.run();
        });
        liveTab.setOnClickListener(v -> {
            liveMode[0] = true;
            prefs.setLiveMode(true);
            paintTabs.run();
        });
        paintTabs.run();
        modes.addView(onceTab);
        modes.addView(liveTab);
        FrameLayout.LayoutParams modesParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        modesParams.bottomMargin = dp(150);
        layer.addView(modes, modesParams);

        TextView cancel = new TextView(ui);
        cancel.setText("Cancel");
        cancel.setTextColor(Color.WHITE);
        cancel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        cancel.setTypeface(Typeface.DEFAULT_BOLD);
        cancel.setPadding(dp(28), dp(12), dp(28), dp(12));
        cancel.setBackground(Ui.rounded(0xCC000000, dp(24), 0x66FFFFFF, dp(1)));
        cancel.setOnClickListener(v -> {
            removeSelection();
            if (bubble != null) {
                bubble.setVisibility(View.VISIBLE);
            }
        });
        FrameLayout.LayoutParams cancelParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        cancelParams.bottomMargin = dp(88);
        layer.addView(cancel, cancelParams);

        WindowManager.LayoutParams params = overlayParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
        windowManager.addView(layer, params);
        selectionLayer = layer;
        if (bubble != null) {
            bubble.setVisibility(View.INVISIBLE);
        }
    }

    private TextView modeTab(String label) {
        TextView tab = new TextView(ui);
        tab.setText(label);
        tab.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        tab.setTypeface(Typeface.DEFAULT_BOLD);
        tab.setGravity(Gravity.CENTER);
        tab.setPadding(dp(18), dp(10), dp(18), dp(10));
        return tab;
    }

    private void styleTab(TextView tab, boolean selected) {
        tab.setBackground(selected ? Ui.rounded(0xFFFFFFFF, dp(22)) : null);
        tab.setTextColor(selected ? 0xFF1B1C1F : 0xFFFFFFFF);
    }

    private void onAreaChosen(final Rect area, final Point screen, boolean liveMode) {
        stopLive();
        if (liveMode) {
            removeSelection();
            startLive(area, screen);
            return;
        }
        removeSelection();   // removed right away so it isn't in the screenshot
        if (bubble != null) {
            bubble.setVisibility(View.INVISIBLE);
        }
        final int framesBefore = frameCount;
        final long removedAt = SystemClock.uptimeMillis();
        main.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!running) {
                    return;
                }
                long waited = SystemClock.uptimeMillis() - removedAt;
                boolean fresh = frameCount > framesBefore && latestFrame != null;
                if (!fresh && waited < MAX_WAIT_MS) {
                    main.postDelayed(this, 50);
                    return;
                }
                Image frame = latestFrame;
                latestFrame = null;
                if (bubble != null) {
                    bubble.setVisibility(View.VISIBLE);
                }
                boolean cardAtBottom = area == null || area.centerY() < screen.y / 2;
                Prefs prefs = new Prefs(TranslatorService.this);
                showCard(cardAtBottom, prefs.language());
                if (frame == null) {
                    setCardResult("Couldn't read the screen. Please try again.", false);
                    return;
                }
                Bitmap shot;
                try {
                    shot = crop(frame, area, screen);
                } catch (RuntimeException e) {
                    setCardResult("Couldn't read the screen. Please try again.", false);
                    return;
                }
                translateOnce(shot, prefs);
            }
        }, SETTLE_MS);
    }

    // ----------------------------------------------------------------------------------------
    // Translate once

    private void translateOnce(final Bitmap shot, Prefs prefs) {
        final int id = ++requestId;
        final EngineSettings settings = new EngineSettings(prefs);
        final Translation.Request request = new Translation.Request(settings.language, settings.showOriginal,
                message -> main.post(() -> {
                    if (id == requestId && card != null) {
                        cardBody.setText(message);
                    }
                }));
        worker.execute(() -> {
            String text;
            boolean ok;
            try {
                text = engineFor(settings).translateOnce(shot, request);
                ok = true;
            } catch (TranslateException e) {
                text = e.getMessage();
                ok = false;
            } catch (RuntimeException e) {
                text = "Something went wrong: " + e.getMessage();
                ok = false;
            } finally {
                shot.recycle();
            }
            final String result = text;
            final boolean success = ok;
            main.post(() -> {
                if (id == requestId && card != null) {
                    setCardResult(result, success);
                }
            });
        });
    }

    /**
     * The engine for these settings, created on first use and kept while the settings stay the same
     * (the on-device AI takes a few seconds to load). Called on the background thread only.
     */
    private TranslationEngine engineFor(EngineSettings settings) {
        String identity = settings.identity();
        if (engine == null || !identity.equals(engineIdentity)) {
            closeEngine();
            engine = settings.create(getApplicationContext());
            engineIdentity = identity;
        }
        return engine;
    }

    /** Called on the background thread only. */
    private void closeEngine() {
        if (engine != null) {
            engine.close();
            engine = null;
            engineIdentity = null;
        }
    }

    // ----------------------------------------------------------------------------------------
    // Live mode

    private void startLive(Rect area, Point screen) {
        hideCard();
        boolean whole = area == null;
        LiveSession session = new LiveSession(whole, whole ? wholeScreenRegion() : new Rect(area), new Point(screen));
        long now = SystemClock.uptimeMillis();
        session.startedAt = now;
        session.lastChangeAt = now;   // lets the screen settle after the selection layer is gone
        session.panelAtBottom = whole || area.centerY() < screen.y / 2;
        Prefs prefs = new Prefs(this);
        EngineSettings settings = new EngineSettings(prefs);
        session.minInterval = settings.liveMinIntervalMs();
        session.maxInterval = settings.liveMaxIntervalMs();
        session.interval = session.minInterval;
        live = session;

        livePanel = new LivePanel(ui, prefs.language(), new LivePanel.Actions() {
            @Override
            public void onPauseToggle() {
                if (live == null) {
                    return;
                }
                if (live.paused) {
                    resumeLive();
                } else {
                    pauseLive(null);
                }
            }

            @Override
            public void onClear() {
                if (livePanel != null) {
                    livePanel.clear();
                }
            }

            @Override
            public void onMove() {
                moveLivePanel();
            }

            @Override
            public void onClose() {
                stopLive();
            }

            @Override
            public void onCopy(CharSequence text) {
                copyToClipboard(text);
            }
        });
        WindowManager.LayoutParams params = overlayParams(cardWidth(), livePanelHeight(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
        placeLivePanel(params, session.panelAtBottom);
        windowManager.addView(livePanel.root, params);
        livePanel.setStatus("Watching for new text…", COLOR_WATCHING);

        if (bubble != null) {
            bubble.setVisibility(View.VISIBLE);
        }
        setBubbleLive(true);
        main.removeCallbacks(liveTick);
        main.postDelayed(liveTick, LIVE_TICK_MS);
    }

    private void stopLive() {
        main.removeCallbacks(liveTick);
        live = null;
        if (livePanel != null) {
            removeWindow(livePanel.root);
            livePanel = null;
        }
        setBubbleLive(false);
    }

    private void pauseLive(String note) {
        LiveSession session = live;
        if (session == null || livePanel == null) {
            return;
        }
        session.paused = true;
        livePanel.setPaused(true);
        livePanel.setStatus("Paused", COLOR_PAUSED);
        if (note != null) {
            livePanel.addNote(note);
        }
    }

    private void resumeLive() {
        LiveSession session = live;
        if (session == null || livePanel == null) {
            return;
        }
        long now = SystemClock.uptimeMillis();
        session.paused = false;
        session.startedAt = now;
        session.lastChangeAt = now;
        session.interval = session.minInterval;
        session.nextAllowedAt = 0;
        session.errorUntil = 0;
        session.lastError = null;
        livePanel.setPaused(false);
        livePanel.setStatus(watchingText(session), COLOR_WATCHING);
    }

    /**
     * Runs every LIVE_TICK_MS. Compares the watched area with how it looked at the last
     * translation, and sends it again once something changed and the screen has settled
     * (or has kept changing for a while, as with videos).
     */
    private void tickLive() {
        LiveSession session = live;
        if (session == null || session.paused || selectionLayer != null) {
            return;
        }
        long now = SystemClock.uptimeMillis();
        if (now - session.startedAt > LIVE_AUTO_PAUSE_MS) {
            pauseLive("Paused after 20 minutes to save your OpenAI credit. Tap Resume to keep going.");
            return;
        }

        if (latestFrame != null && frameCount != session.lastFrameChecked) {
            session.lastFrameChecked = frameCount;
            int[] signature = signature(latestFrame, session, overlayRects());
            if (signature != null && (session.currentSig == null || isChange(session.currentSig, signature))) {
                session.currentSig = signature;
                session.lastChangeAt = now;
            }
        }
        if (session.currentSig == null || session.inFlight || now < session.errorUntil) {
            return;
        }

        int changed = session.sentSig == null ? session.currentSig.length
                : countChanged(session.sentSig, session.currentSig);
        if (session.sentSig != null && !isChange(changed, session.currentSig.length)) {
            session.pendingSince = 0;   // nothing new since the last translation
            return;
        }
        if (session.pendingSince == 0) {
            session.pendingSince = now;
        }
        long allowedAt = session.nextAllowedAt;
        if (changed >= session.currentSig.length * LIVE_BIG_CHANGE) {
            // A big change (e.g. scrolled to new content): don't wait for the slow pace.
            allowedAt = Math.min(allowedAt, session.sentAt + session.minInterval);
        }
        if (now < allowedAt) {
            return;
        }
        boolean settled = now - session.lastChangeAt >= LIVE_SETTLE_MS;
        boolean waitedLong = now - session.pendingSince >= LIVE_MAX_DELAY_MS;
        if (settled || waitedLong) {
            sendLive(session, now);
        }
    }

    private void sendLive(final LiveSession session, long now) {
        if (latestFrame == null || livePanel == null) {
            return;
        }
        final Bitmap shot;
        try {
            shot = cropMasked(latestFrame, session.region, session.screen, overlayRects());
        } catch (RuntimeException e) {
            session.errorUntil = now + LIVE_MIN_INTERVAL_MS;
            return;
        }
        session.inFlight = true;
        session.sentSig = session.currentSig;
        session.pendingSince = 0;
        session.sentAt = now;
        session.checks++;
        livePanel.setStatus("Translating…", COLOR_BUSY);

        final EngineSettings settings = new EngineSettings(new Prefs(this));
        session.minInterval = settings.liveMinIntervalMs();
        session.maxInterval = settings.liveMaxIntervalMs();
        final Translation.Request request = new Translation.Request(settings.language, settings.showOriginal,
                message -> main.post(() -> {
                    if (live == session && livePanel != null && !session.paused) {
                        livePanel.setStatus(message, COLOR_BUSY);
                    }
                }));
        final List<String> recent = new ArrayList<>(session.recent);
        final Set<String> seen = new HashSet<>(session.seen);
        worker.execute(() -> {
            List<Translation.Line> lines = null;
            TranslateException problem = null;
            try {
                lines = engineFor(settings).translateLive(shot, request, recent, seen);
            } catch (TranslateException e) {
                problem = e;
            } catch (RuntimeException e) {
                problem = new TranslateException("Something went wrong: " + e.getMessage(), false);
            } finally {
                shot.recycle();
            }
            final List<Translation.Line> result = lines;
            final TranslateException failure = problem;
            main.post(() -> onLiveResult(session, result, failure, settings.showOriginal));
        });
    }

    private void onLiveResult(LiveSession session, List<Translation.Line> lines, TranslateException failure,
                              boolean showOriginal) {
        if (live != session || livePanel == null) {
            return;   // Live was stopped or restarted meanwhile
        }
        session.inFlight = false;
        long now = SystemClock.uptimeMillis();

        if (failure != null) {
            String error = failure.getMessage();
            if (!error.equals(session.lastError)) {
                livePanel.addNote(error);
            }
            session.lastError = error;
            session.sentSig = null;   // this screen still needs translating
            if (failure.fatal) {
                pauseLive(null);
            } else {
                session.errorUntil = now + (failure.retryAfterMs > 0 ? failure.retryAfterMs : LIVE_ERROR_BACKOFF_MS);
                livePanel.setStatus("Trying again shortly…", COLOR_BUSY);
            }
            return;
        }
        session.lastError = null;

        List<Translation.Line> fresh = new ArrayList<>();
        for (Translation.Line line : lines) {
            String original = line.original.isEmpty() ? line.translation : line.original;
            String key = Translation.normalize(original);
            if (key.isEmpty() || session.seen.contains(key)) {
                continue;   // already shown earlier
            }
            remember(session, key, original);
            if (!line.original.isEmpty() && key.equals(Translation.normalize(line.translation))) {
                continue;   // already in the target language
            }
            fresh.add(line);
        }
        if (fresh.isEmpty()) {
            // Nothing new (e.g. an animation changed the screen): check less often for a while.
            session.interval = Math.min(session.interval * 2, session.maxInterval);
        } else {
            session.interval = session.minInterval;
            livePanel.addLines(fresh, showOriginal);
        }
        session.nextAllowedAt = session.sentAt + session.interval;
        if (!session.paused) {
            livePanel.setStatus(watchingText(session), COLOR_WATCHING);
        }
    }

    private static void remember(LiveSession session, String key, String original) {
        session.seen.add(key);
        if (session.seen.size() > LIVE_SEEN_LIMIT) {
            Iterator<String> oldest = session.seen.iterator();
            oldest.next();
            oldest.remove();
        }
        session.recent.addLast(original.length() > 80 ? original.substring(0, 80) : original);
        while (session.recent.size() > LIVE_RECENT_LIMIT) {
            session.recent.removeFirst();
        }
    }

    private static String watchingText(LiveSession session) {
        return "Watching · " + session.checks + (session.checks == 1 ? " check" : " checks");
    }

    /**
     * A small brightness sample of the watched area, used to notice when its content changes.
     * Our own panel and bubble are skipped (-1), so updating them doesn't count as a change.
     */
    private static int[] signature(Image frame, LiveSession session, List<Rect> masks) {
        int width = frame.getWidth();
        int height = frame.getHeight();
        Rect area = toFrame(session.region, session.screen, width, height);
        if (area.isEmpty()) {
            return null;
        }
        List<Rect> hidden = new ArrayList<>(masks.size());
        for (Rect mask : masks) {
            hidden.add(toFrame(mask, session.screen, width, height));
        }
        Image.Plane plane = frame.getPlanes()[0];
        ByteBuffer pixels = plane.getBuffer();
        int rowStride = plane.getRowStride();
        int pixelStride = plane.getPixelStride();
        int limit = pixels.limit();

        int step = Math.max(4, (int) Math.sqrt((double) area.width() * area.height() / LIVE_SAMPLES));
        int cols = Math.max(1, area.width() / step);
        int rows = Math.max(1, area.height() / step);
        int[] signature = new int[cols * rows];
        int i = 0;
        for (int row = 0; row < rows; row++) {
            int y = area.top + row * step + step / 2;
            for (int col = 0; col < cols; col++, i++) {
                int x = area.left + col * step + step / 2;
                if (inside(hidden, x, y)) {
                    signature[i] = -1;
                    continue;
                }
                int offset = y * rowStride + x * pixelStride;
                if (offset + 2 >= limit) {
                    signature[i] = -1;
                    continue;
                }
                int red = pixels.get(offset) & 0xFF;
                int green = pixels.get(offset + 1) & 0xFF;
                int blue = pixels.get(offset + 2) & 0xFF;
                signature[i] = (red * 3 + green * 6 + blue) / 10;
            }
        }
        return signature;
    }

    private static boolean inside(List<Rect> rects, int x, int y) {
        for (Rect rect : rects) {
            if (rect.contains(x, y)) {
                return true;
            }
        }
        return false;
    }

    private static int countChanged(int[] before, int[] after) {
        if (before.length != after.length) {
            return after.length;
        }
        int changed = 0;
        for (int i = 0; i < after.length; i++) {
            int a = before[i];
            int b = after[i];
            if ((a < 0) != (b < 0)) {
                changed++;
            } else if (a >= 0 && Math.abs(a - b) > LIVE_PIXEL_DIFF) {
                changed++;
            }
        }
        return changed;
    }

    /** Small things like a blinking cursor don't count; a new line of text does. */
    private static boolean isChange(int changed, int total) {
        return changed >= Math.max(4, total * 0.0005f);
    }

    private static boolean isChange(int[] before, int[] after) {
        return isChange(countChanged(before, after), after.length);
    }

    /** The whole screen minus the status bar (clock, icons) and the navigation bar. */
    private Rect wholeScreenRegion() {
        int top = systemDimension("status_bar_height");
        int bottom = captureHeight > captureWidth ? systemDimension("navigation_bar_height") : 0;
        return new Rect(0, top, captureWidth, Math.max(top + 1, captureHeight - bottom));
    }

    @SuppressLint({"DiscouragedApi", "InternalInsetResource"})
    private int systemDimension(String name) {
        int id = getResources().getIdentifier(name, "dimen", "android");
        return id > 0 ? getResources().getDimensionPixelSize(id) : 0;
    }

    /** Where our own windows are on screen, so they can be left out of Live captures. */
    private List<Rect> overlayRects() {
        List<Rect> rects = new ArrayList<>(2);
        if (livePanel != null) {
            addScreenRect(rects, livePanel.root);
        }
        if (bubble != null && bubble.getVisibility() == View.VISIBLE) {
            addScreenRect(rects, bubble);
        }
        return rects;
    }

    private void addScreenRect(List<Rect> rects, View view) {
        if (!view.isAttachedToWindow() || view.getWidth() == 0) {
            return;
        }
        int[] location = new int[2];
        view.getLocationOnScreen(location);
        Rect rect = new Rect(location[0], location[1], location[0] + view.getWidth(), location[1] + view.getHeight());
        rect.inset(-dp(3), -dp(3));   // include anti-aliased edges
        rects.add(rect);
    }

    private int livePanelHeight() {
        return Math.max(dp(170), Math.round(screenSize().y * 0.30f));
    }

    private void placeLivePanel(WindowManager.LayoutParams params, boolean atBottom) {
        params.gravity = (atBottom ? Gravity.BOTTOM : Gravity.TOP) | Gravity.CENTER_HORIZONTAL;
        params.x = 0;
        params.y = atBottom ? dp(64) : dp(52);
    }

    private void moveLivePanel() {
        if (live == null || livePanel == null) {
            return;
        }
        live.panelAtBottom = !live.panelAtBottom;
        WindowManager.LayoutParams params = (WindowManager.LayoutParams) livePanel.root.getLayoutParams();
        placeLivePanel(params, live.panelAtBottom);
        windowManager.updateViewLayout(livePanel.root, params);
    }

    private void setBubbleLive(boolean on) {
        if (bubbleBackground != null) {
            bubbleBackground.setColor(on ? COLOR_LIVE_BUBBLE : Ui.ACCENT);
        }
    }

    // ----------------------------------------------------------------------------------------
    // Floating bubble

    private void showBubble() {
        if (bubble != null) {
            return;
        }
        int size = dp(54);
        FrameLayout circle = new FrameLayout(ui);
        bubbleBackground = new GradientDrawable();
        bubbleBackground.setShape(GradientDrawable.OVAL);
        bubbleBackground.setColor(Ui.ACCENT);
        bubbleBackground.setStroke(dp(2), 0xCCFFFFFF);
        circle.setBackground(bubbleBackground);
        circle.setContentDescription("Translate screen");

        ImageView icon = new ImageView(ui);
        icon.setImageResource(R.drawable.ic_translate);
        icon.setImageTintList(ColorStateList.valueOf(Color.WHITE));
        circle.addView(icon, new FrameLayout.LayoutParams(dp(28), dp(28), Gravity.CENTER));

        bubbleParams = overlayParams(size, size,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
        Point screen = screenSize();
        bubbleParams.x = screen.x - size - dp(6);
        bubbleParams.y = Math.round(screen.y * 0.35f);
        circle.setOnTouchListener(new BubbleTouch());
        windowManager.addView(circle, bubbleParams);
        bubble = circle;
    }

    /** Tap = start selecting. Drag = move the bubble; it snaps to the nearest side. */
    private final class BubbleTouch implements View.OnTouchListener {
        private final int slop = ViewConfiguration.get(ui).getScaledTouchSlop();
        private float downRawX;
        private float downRawY;
        private int downX;
        private int downY;
        private boolean moved;

        @SuppressLint("ClickableViewAccessibility")
        @Override
        public boolean onTouch(View view, MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downRawX = event.getRawX();
                    downRawY = event.getRawY();
                    downX = bubbleParams.x;
                    downY = bubbleParams.y;
                    moved = false;
                    view.setAlpha(0.75f);
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float dx = event.getRawX() - downRawX;
                    float dy = event.getRawY() - downRawY;
                    if (!moved && Math.hypot(dx, dy) > slop) {
                        moved = true;
                    }
                    if (moved) {
                        bubbleParams.x = downX + Math.round(dx);
                        bubbleParams.y = downY + Math.round(dy);
                        keepBubbleOnScreen(false);
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    view.setAlpha(1f);
                    if (moved) {
                        keepBubbleOnScreen(true);
                    } else {
                        startSelection();
                    }
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    view.setAlpha(1f);
                    return true;
                default:
                    return false;
            }
        }
    }

    private void keepBubbleOnScreen(boolean snapToSide) {
        if (bubble == null) {
            return;
        }
        Point screen = screenSize();
        int size = bubbleParams.width;
        int edge = dp(6);
        if (snapToSide) {
            bubbleParams.x = bubbleParams.x + size / 2 < screen.x / 2 ? edge : screen.x - size - edge;
        }
        bubbleParams.x = clamp(bubbleParams.x, 0, screen.x - size);
        bubbleParams.y = clamp(bubbleParams.y, dp(24), screen.y - size - dp(48));
        windowManager.updateViewLayout(bubble, bubbleParams);
    }

    private void fitOverlaysToScreen() {
        if (selectionLayer != null) {
            removeSelection();    // rotated mid-selection: start over
            if (bubble != null) {
                bubble.setVisibility(View.VISIBLE);
            }
        }
        if (live != null) {
            if (live.wholeScreen) {
                matchCaptureToScreen();
                live.screen = new Point(captureWidth, captureHeight);
                live.region = wholeScreenRegion();
                live.currentSig = null;
                live.lastChangeAt = SystemClock.uptimeMillis();
            } else {
                stopLive();
                toast("The screen rotated, so Live stopped. Tap the bubble to choose the area again.");
            }
        }
        keepBubbleOnScreen(true);
        if (card != null) {
            WindowManager.LayoutParams params = (WindowManager.LayoutParams) card.getLayoutParams();
            params.width = cardWidth();
            windowManager.updateViewLayout(card, params);
        }
        if (livePanel != null) {
            WindowManager.LayoutParams params = (WindowManager.LayoutParams) livePanel.root.getLayoutParams();
            params.width = cardWidth();
            params.height = livePanelHeight();
            windowManager.updateViewLayout(livePanel.root, params);
        }
    }

    // ----------------------------------------------------------------------------------------
    // Result card (Translate once)

    private void showCard(boolean atBottom, String language) {
        hideCard();
        cardLanguage = language;
        final boolean dark = Ui.isDark(ui);
        final int maxHeight = Math.round(screenSize().y * 0.45f);

        LinearLayout box = new LinearLayout(ui) {
            @Override
            protected void onMeasure(int widthSpec, int heightSpec) {
                super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.AT_MOST));
            }
        };
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(18), dp(12), dp(12), dp(16));
        box.setBackground(Ui.rounded(dark ? 0xF5202124 : 0xFAFFFFFF, dp(18),
                dark ? 0x33FFFFFF : 0x22000000, dp(1)));

        LinearLayout header = new LinearLayout(ui);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        cardTitle = new TextView(ui);
        cardTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        cardTitle.setTypeface(Typeface.DEFAULT_BOLD);
        cardTitle.setLetterSpacing(0.08f);
        cardTitle.setTextColor(dark ? Ui.ACCENT_ON_DARK : Ui.ACCENT);
        header.addView(cardTitle, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        cardCopy = chip("Copy", dark);
        cardCopy.setOnClickListener(v -> copyToClipboard(cardBody.getText()));
        header.addView(cardCopy);

        TextView close = chip("✕", dark);
        close.setContentDescription("Close");
        close.setOnClickListener(v -> hideCard());
        LinearLayout.LayoutParams closeParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        closeParams.leftMargin = dp(8);
        header.addView(close, closeParams);
        box.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        ScrollView scroll = new ScrollView(ui);
        cardBody = new TextView(ui);
        cardBody.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        cardBody.setTextColor(dark ? 0xFFECEDEF : 0xFF1B1C1F);
        cardBody.setLineSpacing(0, 1.18f);
        cardBody.setPadding(0, 0, dp(6), 0);
        scroll.addView(cardBody);
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        scrollParams.topMargin = dp(8);
        box.addView(scroll, scrollParams);

        WindowManager.LayoutParams params = overlayParams(cardWidth(), ViewGroup.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
        params.gravity = (atBottom ? Gravity.BOTTOM : Gravity.TOP) | Gravity.CENTER_HORIZONTAL;
        params.y = atBottom ? dp(64) : dp(52);
        windowManager.addView(box, params);
        card = box;

        cardTitle.setText(("Translating to " + language + "…").toUpperCase(Locale.ROOT));
        cardBody.setText("Reading the text on your screen…");
        cardCopy.setVisibility(View.GONE);
    }

    private void setCardResult(String text, boolean success) {
        if (card == null) {
            return;
        }
        cardTitle.setText(success ? cardLanguage.toUpperCase(Locale.ROOT) : "COULDN'T TRANSLATE");
        cardBody.setText(text);
        cardCopy.setVisibility(success ? View.VISIBLE : View.GONE);
    }

    private int cardWidth() {
        return Math.min(screenSize().x - dp(24), dp(560));
    }

    private TextView chip(String label, boolean dark) {
        TextView chip = new TextView(ui);
        chip.setText(label);
        chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        chip.setTypeface(Typeface.DEFAULT_BOLD);
        chip.setTextColor(dark ? 0xFFECEDEF : 0xFF1B1C1F);
        chip.setGravity(Gravity.CENTER);
        chip.setMinWidth(dp(40));
        chip.setPadding(dp(14), dp(8), dp(14), dp(8));
        chip.setBackground(Ui.rounded(dark ? 0x26FFFFFF : 0x12000000, dp(16)));
        return chip;
    }

    private void copyToClipboard(CharSequence text) {
        ClipboardManager clipboard = getSystemService(ClipboardManager.class);
        clipboard.setPrimaryClip(ClipData.newPlainText("Translation", text));
        if (Build.VERSION.SDK_INT < 33) {
            toast("Copied");   // Android 13+ shows its own confirmation
        }
    }

    // ----------------------------------------------------------------------------------------
    // Overlay helpers

    private WindowManager.LayoutParams overlayParams(int width, int height, int flags) {
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(width, height,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, flags, PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        params.windowAnimations = 0;   // appear/disappear instantly, so they never leak into a screenshot
        if (Build.VERSION.SDK_INT >= 28) {
            params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        if (Build.VERSION.SDK_INT >= 30) {
            params.setFitInsetsTypes(0);   // use whole-screen coordinates, same as the screenshot
        }
        return params;
    }

    private void removeSelection() {
        if (selectionLayer != null) {
            removeWindow(selectionLayer);
            selectionLayer = null;
        }
    }

    private void hideCard() {
        if (card != null) {
            removeWindow(card);
            card = null;
        }
    }

    private void removeOverlays() {
        removeSelection();
        hideCard();
        stopLive();
        if (bubble != null) {
            removeWindow(bubble);
            bubble = null;
            bubbleBackground = null;
        }
    }

    private void removeWindow(View view) {
        if (windowManager == null) {
            return;
        }
        try {
            windowManager.removeViewImmediate(view);
        } catch (IllegalArgumentException ignored) {
            // Already gone.
        }
    }

    private int dp(float value) {
        return Ui.dp(ui != null ? ui : this, value);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(value, Math.max(min, max)));
    }

    private void toast(String message) {
        main.post(() -> Toast.makeText(getApplicationContext(), message, Toast.LENGTH_LONG).show());
    }
}
