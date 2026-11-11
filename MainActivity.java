package app.screentranslator;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.media.projection.MediaProjectionConfig;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.speech.tts.TextToSpeech;
import android.text.InputType;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;

/** Settings screen: engine choice, keys or model download, language, and the Start / Stop buttons. */
public class MainActivity extends Activity {

    private static final int REQUEST_CAPTURE = 1001;
    private static final int REQUEST_NOTIFICATIONS = 1002;
    private static final int REQUEST_IMPORT = 1003;
    private static final String OPENAI_KEYS_URL = "https://platform.openai.com/api-keys";
    private static final String GEMINI_KEYS_URL = "https://aistudio.google.com/apikey";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable modelTicker = new Runnable() {
        @Override
        public void run() {
            refreshModelStatus();
            handler.postDelayed(this, 1000);
        }
    };

    private Prefs prefs;

    // Engine choice
    private RadioButton onDeviceOption;
    private RadioButton geminiOption;
    private RadioButton openAiOption;
    private LinearLayout onDevicePanel;
    private LinearLayout geminiPanel;
    private LinearLayout openAiPanel;

    // On-device AI
    private RadioButton smallModelOption;
    private RadioButton largeModelOption;
    private TextView memoryNote;
    private TextView modelStatus;
    private Button downloadButton;
    private Button cancelButton;
    private Button deleteButton;
    private Button importButton;
    private CheckBox mobileDataBox;
    private volatile String importMessage;   // set while a model file is being copied

    // Keys and models
    private EditText geminiKeyField;
    private EditText geminiModelField;
    private EditText openAiKeyField;
    private EditText openAiModelField;

    // Common
    private EditText languageField;
    private CheckBox originalBox;

    // Read aloud
    private CheckBox readAloudBox;
    private RadioButton slowOption;
    private RadioButton normalOption;
    private RadioButton fastOption;
    private Speaker testSpeaker;
    private TextView statusView;
    private Button stopButton;
    private boolean waitingForOverlayPermission;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = new Prefs(this);
        drawBehindSystemBars();
        setContentView(buildScreen());

        geminiKeyField.setText(prefs.geminiKey());
        geminiModelField.setText(prefs.geminiModel());
        openAiKeyField.setText(prefs.openAiKey());
        openAiModelField.setText(prefs.openAiModel());
        languageField.setText(prefs.language());
        originalBox.setChecked(prefs.showOriginal());
        switch (prefs.speechRate()) {
            case Speaker.RATE_SLOW:
                slowOption.setChecked(true);
                break;
            case Speaker.RATE_FAST:
                fastOption.setChecked(true);
                break;
            default:
                normalOption.setChecked(true);
        }
        mobileDataBox.setChecked(prefs.downloadOverMobileData());
        (ModelStore.E4B.id.equals(prefs.onDeviceModel()) ? largeModelOption : smallModelOption).setChecked(true);
        switch (prefs.engine()) {
            case Prefs.ENGINE_GEMINI:
                geminiOption.setChecked(true);
                break;
            case Prefs.ENGINE_OPENAI:
                openAiOption.setChecked(true);
                break;
            default:
                onDeviceOption.setChecked(true);
        }
        showEnginePanel();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
        handler.post(modelTicker);
        readAloudBox.setChecked(prefs.readAloud());   // may have been switched from the Live panel
        if (waitingForOverlayPermission) {
            waitingForOverlayPermission = false;
            if (Settings.canDrawOverlays(this)) {
                continueStart();
            }
        }
    }

    @Override
    protected void onDestroy() {
        if (testSpeaker != null) {
            testSpeaker.shutdown();
            testSpeaker = null;
        }
        super.onDestroy();
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(modelTicker);
        saveFields();
    }

    // ----------------------------------------------------------------------------------------
    // Starting and stopping

    private void onStartTapped() {
        saveFields();
        if (!engineReady()) {
            return;
        }
        if (TranslatorService.isRunning()) {
            Toast.makeText(this, "Already running. Tap the bubble in any app.", Toast.LENGTH_SHORT).show();
            moveTaskToBack(true);
            return;
        }
        if (!Settings.canDrawOverlays(this)) {
            new AlertDialog.Builder(this)
                    .setTitle("Allow the floating bubble")
                    .setMessage("Screen Translator needs permission to appear on top of other apps, "
                            + "so the bubble and translations can show over any app.\n\n"
                            + "On the next screen, turn on \"Allow display over other apps\", then come back.")
                    .setPositiveButton("Open settings", (dialog, which) -> openOverlaySettings())
                    .setNegativeButton("Cancel", null)
                    .show();
            return;
        }
        continueStart();
    }

    /** Checks the selected engine has what it needs, and explains what's missing if not. */
    private boolean engineReady() {
        switch (prefs.engine()) {
            case Prefs.ENGINE_GEMINI:
                if (prefs.geminiKey().isEmpty()) {
                    geminiKeyField.setError("Paste your free Gemini API key first");
                    geminiKeyField.requestFocus();
                    return false;
                }
                return true;
            case Prefs.ENGINE_OPENAI:
                if (prefs.openAiKey().isEmpty()) {
                    openAiKeyField.setError("Paste your OpenAI API key first");
                    openAiKeyField.requestFocus();
                    return false;
                }
                return true;
            default:
                ModelStore.Spec spec = selectedModel();
                if (ModelStore.isReady(this, spec)) {
                    return true;
                }
                if (ModelStore.progress(this, spec) != null) {
                    Toast.makeText(this, "The AI model is still downloading. Start again when it's ready.",
                            Toast.LENGTH_LONG).show();
                    return false;
                }
                new AlertDialog.Builder(this)
                        .setTitle("Download the on-device AI first")
                        .setMessage("The on-device AI needs a one-time download of " + spec.sizeText()
                                + ". After that it works without internet.")
                        .setPositiveButton("Download", (dialog, which) -> onDownloadTapped())
                        .setNegativeButton("Cancel", null)
                        .show();
                return false;
        }
    }

    private void continueStart() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            // Lets the "Stop" notification show. The app works even if they say no.
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_NOTIFICATIONS);
            return;
        }
        askForScreenCapture();
    }

    private void openOverlaySettings() {
        waitingForOverlayPermission = true;
        Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()));
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException e) {
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION));
        }
    }

    private void askForScreenCapture() {
        MediaProjectionManager manager = getSystemService(MediaProjectionManager.class);
        Intent intent;
        if (Build.VERSION.SDK_INT >= 34) {
            // Always share the entire screen, so the bubble works in every app.
            intent = manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay());
        } else {
            intent = manager.createScreenCaptureIntent();
        }
        startActivityForResult(intent, REQUEST_CAPTURE);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_NOTIFICATIONS) {
            askForScreenCapture();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_IMPORT) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                importModel(data.getData());
            }
            return;
        }
        if (requestCode != REQUEST_CAPTURE) {
            return;
        }
        if (resultCode == RESULT_OK && data != null) {
            Intent service = new Intent(this, TranslatorService.class)
                    .setAction(TranslatorService.ACTION_START)
                    .putExtra(TranslatorService.EXTRA_RESULT_CODE, resultCode)
                    .putExtra(TranslatorService.EXTRA_RESULT_DATA, data);
            startForegroundService(service);
            Toast.makeText(this, "Bubble is on. Open any app and tap it to translate.", Toast.LENGTH_LONG).show();
            moveTaskToBack(true);
        } else {
            Toast.makeText(this, "Screen capture wasn't allowed, so the translator can't read your screen.",
                    Toast.LENGTH_LONG).show();
        }
    }

    private void onStopTapped() {
        startService(new Intent(this, TranslatorService.class).setAction(TranslatorService.ACTION_STOP));
        statusView.postDelayed(this::refreshStatus, 300);
    }

    private void saveFields() {
        if (languageField == null) {
            return;
        }
        prefs.saveFields(openAiKeyField.getText().toString(), openAiModelField.getText().toString(),
                geminiKeyField.getText().toString(), geminiModelField.getText().toString(),
                languageField.getText().toString(), originalBox.isChecked(), mobileDataBox.isChecked());
    }

    private void refreshStatus() {
        boolean running = TranslatorService.isRunning();
        if (running) {
            statusView.setText("● Running with " + engineName() + ". Tap the bubble on your screen to translate. "
                    + "(Changing the engine here takes effect on the next translation.)");
            statusView.setTextColor(Ui.isDark(this) ? Ui.ACCENT_ON_DARK : Ui.ACCENT);
        } else if (!Settings.canDrawOverlays(this)) {
            statusView.setText("Tap Start. Android will ask you to let Screen Translator appear on top of "
                    + "other apps, then to allow screen capture.");
            statusView.setTextColor(secondaryTextColor());
        } else {
            statusView.setText("Ready. Tap Start and allow screen capture.");
            statusView.setTextColor(secondaryTextColor());
        }
        stopButton.setEnabled(running);
        stopButton.setAlpha(running ? 1f : 0.45f);
    }

    private String engineName() {
        switch (prefs.engine()) {
            case Prefs.ENGINE_GEMINI:
                return "Gemini";
            case Prefs.ENGINE_OPENAI:
                return "ChatGPT";
            default:
                return "the on-device AI";
        }
    }

    // ----------------------------------------------------------------------------------------
    // Engine choice

    private void onEngineChosen(String engine) {
        prefs.setEngine(engine);
        showEnginePanel();
        refreshStatus();
    }

    private void showEnginePanel() {
        String engine = prefs.engine();
        onDevicePanel.setVisibility(Prefs.ENGINE_ON_DEVICE.equals(engine) ? View.VISIBLE : View.GONE);
        geminiPanel.setVisibility(Prefs.ENGINE_GEMINI.equals(engine) ? View.VISIBLE : View.GONE);
        openAiPanel.setVisibility(Prefs.ENGINE_OPENAI.equals(engine) ? View.VISIBLE : View.GONE);
        refreshModelStatus();
    }

    // ----------------------------------------------------------------------------------------
    // On-device model download

    private ModelStore.Spec selectedModel() {
        return ModelStore.byId(prefs.onDeviceModel());
    }

    private void onModelChosen(ModelStore.Spec spec) {
        prefs.setOnDeviceModel(spec.id);
        refreshModelStatus();
    }

    private void onDownloadTapped() {
        saveFields();
        final ModelStore.Spec spec = selectedModel();
        String network = mobileDataBox.isChecked()
                ? "It will use Wi-Fi or mobile data, whichever is available."
                : "It will wait for Wi-Fi.";
        new AlertDialog.Builder(this)
                .setTitle("Download " + spec.name + "?")
                .setMessage("This is a one-time download of " + spec.sizeText() + ". " + network
                        + " You can leave this screen; Android keeps downloading and shows its progress in the "
                        + "notifications.")
                .setPositiveButton("Download", (dialog, which) -> {
                    try {
                        ModelStore.startDownload(this, spec, mobileDataBox.isChecked());
                    } catch (IllegalStateException e) {
                        new AlertDialog.Builder(this)
                                .setTitle("Can't download yet")
                                .setMessage(e.getMessage())
                                .setPositiveButton("OK", null)
                                .show();
                    }
                    refreshModelStatus();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void onCancelTapped() {
        ModelStore.cancelDownload(this, selectedModel());
        refreshModelStatus();
    }

    private void onDeleteTapped() {
        final ModelStore.Spec spec = selectedModel();
        new AlertDialog.Builder(this)
                .setTitle("Delete " + spec.name + "?")
                .setMessage("This frees " + spec.sizeText() + ". You can download it again later.")
                .setPositiveButton("Delete", (dialog, which) -> {
                    if (!ModelStore.delete(this, spec)) {
                        Toast.makeText(this, "Couldn't delete the model file.", Toast.LENGTH_LONG).show();
                    }
                    refreshModelStatus();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void onImportTapped() {
        Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*");
        try {
            startActivityForResult(pick, REQUEST_IMPORT);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "No file picker is available on this phone.", Toast.LENGTH_LONG).show();
        }
    }

    private void importModel(final Uri source) {
        final ModelStore.Spec spec = selectedModel();
        importMessage = "Copying the model file…";
        refreshModelStatus();
        new Thread(() -> {
            String result;
            try {
                ModelStore.importFrom(getApplicationContext(), source, spec,
                        copied -> importMessage = "Copying the model file… " + ModelStore.gb(copied));
                result = spec.name + " is ready.";
            } catch (IOException | SecurityException e) {
                result = "Couldn't use that file: " + e.getMessage();
            }
            final String message = result;
            handler.post(() -> {
                importMessage = null;
                Toast.makeText(this, message, Toast.LENGTH_LONG).show();
                refreshModelStatus();
            });
        }, "model-import").start();
    }

    private void refreshModelStatus() {
        if (modelStatus == null || onDevicePanel.getVisibility() != View.VISIBLE) {
            return;
        }
        ModelStore.Spec spec = selectedModel();

        int ram = ModelStore.phoneRamGb(this);
        if (ram < spec.minRamGb) {
            memoryNote.setText("This phone has about " + ram + " GB of memory; " + spec.name + " works best with "
                    + spec.minRamGb + " GB or more. If translation is slow or the app closes, choose "
                    + (spec == ModelStore.E4B ? "E2B" : "Gemini") + " instead.");
            memoryNote.setVisibility(View.VISIBLE);
        } else {
            memoryNote.setVisibility(View.GONE);
        }

        String copying = importMessage;
        if (copying != null) {
            modelStatus.setText(copying);
            showButtons(false, false, false, false);
            return;
        }
        ModelStore.Progress progress = ModelStore.progress(this, spec);
        if (progress != null && !progress.failed()) {
            modelStatus.setText(ModelStore.describe(progress, spec));
            showButtons(false, true, false, false);
        } else if (progress != null) {
            modelStatus.setText(ModelStore.describe(progress, spec));
            showButtons(true, false, false, true);
        } else if (ModelStore.isReady(this, spec)) {
            modelStatus.setText("✓ " + spec.name + " is on this phone and ready. It works without internet.");
            showButtons(false, false, true, false);
        } else {
            modelStatus.setText("Not downloaded yet. One-time download of " + spec.sizeText()
                    + " (Wi-Fi recommended).");
            showButtons(true, false, false, true);
        }
    }

    private void showButtons(boolean download, boolean cancel, boolean delete, boolean importFile) {
        downloadButton.setVisibility(download ? View.VISIBLE : View.GONE);
        cancelButton.setVisibility(cancel ? View.VISIBLE : View.GONE);
        deleteButton.setVisibility(delete ? View.VISIBLE : View.GONE);
        importButton.setVisibility(importFile ? View.VISIBLE : View.GONE);
        mobileDataBox.setVisibility(download ? View.VISIBLE : View.GONE);
        boolean idle = importMessage == null;
        smallModelOption.setEnabled(idle);
        largeModelOption.setEnabled(idle);
    }

    // ----------------------------------------------------------------------------------------
    // Read aloud

    private void onTestVoice() {
        saveFields();
        if (testSpeaker != null && testSpeaker.unavailable()) {
            testSpeaker.shutdown();   // try again from scratch, e.g. after installing a speech app
            testSpeaker = null;
        }
        if (testSpeaker == null) {
            testSpeaker = new Speaker(this, new Speaker.Listener() {
                @Override
                public void onSpeakingChanged(boolean speaking) {
                    // Nothing to show.
                }

                @Override
                public void onProblem(String message, boolean needsVoice) {
                    showVoiceProblem(message, needsVoice);
                }
            });
        }
        testSpeaker.forgetProblems();
        String language = prefs.language();
        testSpeaker.speak(Speaker.sample(language), language, prefs.speechRate(), true);
    }

    private void showVoiceProblem(String message, boolean needsVoice) {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        if (!needsVoice) {
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Voice not installed")
                .setMessage("Your phone doesn't have a voice for " + prefs.language() + " yet.\n\n"
                        + "Tap Install voices, download the language in the list that opens (it's free and "
                        + "works offline), then come back and tap Test voice again.")
                .setPositiveButton("Install voices", (dialog, which) -> openVoiceSettings())
                .setNegativeButton("Close", null)
                .show();
    }

    private void openVoiceSettings() {
        Intent[] choices = {
                new Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA),
                new Intent("com.android.settings.TTS_SETTINGS"),
                new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.google.android.tts")),
        };
        for (Intent intent : choices) {
            try {
                startActivity(intent);
                return;
            } catch (ActivityNotFoundException ignored) {
                // Try the next way.
            }
        }
        Toast.makeText(this, "Open the phone's Settings and search for \"Text-to-speech\".", Toast.LENGTH_LONG)
                .show();
    }

    // ----------------------------------------------------------------------------------------
    // Layout (built in code so the whole app has no layout files to keep in sync)

    private void drawBehindSystemBars() {
        Window window = getWindow();
        window.setStatusBarColor(Color.TRANSPARENT);
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false);
        } else {
            int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN;
            if (!Ui.isDark(this)) {
                flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            }
            window.getDecorView().setSystemUiVisibility(flags);
        }
    }

    private View buildScreen() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                Insets bars = insets.getInsets(WindowInsets.Type.systemBars()
                        | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            } else {
                view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                        insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            }
            return insets;
        });

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(this, 22);
        column.setPadding(pad, pad, pad, pad);
        scroll.addView(column, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = text("Screen Translator", 28);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        column.addView(title);
        add(column, secondary("Translate any text on your screen. Tap the floating bubble, then drag a box "
                + "over the text, or tap once to translate the whole screen. Switch to Live on that screen to "
                + "keep translating new text as it appears."), 6, false);

        // Engine choice
        add(column, label("Translation engine"), 28, false);
        RadioGroup engines = new RadioGroup(this);
        onDeviceOption = option("On-device AI (Gemma 4)");
        geminiOption = option("Gemini (Google)");
        openAiOption = option("ChatGPT (OpenAI)");
        engines.addView(onDeviceOption);
        engines.addView(optionNote("Free and private. Works without internet after a one-time download."));
        engines.addView(geminiOption);
        engines.addView(optionNote("Free tier with daily limits. Needs internet and a free Google key."));
        engines.addView(openAiOption);
        engines.addView(optionNote("Paid per use. Needs internet and an OpenAI key."));
        onDeviceOption.setOnClickListener(v -> onEngineChosen(Prefs.ENGINE_ON_DEVICE));
        geminiOption.setOnClickListener(v -> onEngineChosen(Prefs.ENGINE_GEMINI));
        openAiOption.setOnClickListener(v -> onEngineChosen(Prefs.ENGINE_OPENAI));
        add(column, engines, 4, true);

        onDevicePanel = panel();
        buildOnDevicePanel(onDevicePanel);
        add(column, onDevicePanel, 12, true);

        geminiPanel = panel();
        buildGeminiPanel(geminiPanel);
        add(column, geminiPanel, 12, true);

        openAiPanel = panel();
        buildOpenAiPanel(openAiPanel);
        add(column, openAiPanel, 12, true);

        // Common settings
        add(column, label("Translate into"), 24, false);
        languageField = field(Prefs.DEFAULT_LANGUAGE, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        add(column, languageField, 0, true);

        originalBox = new CheckBox(this);
        originalBox.setText("Also show the original text above each translation");
        originalBox.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        add(column, originalBox, 12, false);

        // Read aloud
        add(column, label("Read aloud"), 24, false);
        readAloudBox = new CheckBox(this);
        readAloudBox.setText("Read every translation aloud automatically");
        readAloudBox.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        readAloudBox.setOnCheckedChangeListener((box, checked) -> prefs.setReadAloud(checked));
        add(column, readAloudBox, 2, false);
        add(column, secondary("You can also tap 🔊 on any translation to hear it, or tap a line in the Live "
                + "panel. It uses your phone's built-in voices, which work offline once installed."), 2, false);

        add(column, label("Voice speed"), 12, false);
        RadioGroup speeds = new RadioGroup(this);
        speeds.setOrientation(LinearLayout.HORIZONTAL);
        slowOption = option("Slow");
        normalOption = option("Normal");
        fastOption = option("Fast");
        slowOption.setOnClickListener(v -> prefs.setSpeechRate(Speaker.RATE_SLOW));
        normalOption.setOnClickListener(v -> prefs.setSpeechRate(Speaker.RATE_NORMAL));
        fastOption.setOnClickListener(v -> prefs.setSpeechRate(Speaker.RATE_FAST));
        speeds.addView(slowOption);
        speeds.addView(normalOption);
        speeds.addView(fastOption);
        add(column, speeds, 0, false);

        Button testVoice = smallButton("Test voice", false);
        testVoice.setOnClickListener(v -> onTestVoice());
        add(column, testVoice, 6, false);

        statusView = text("", 15);
        add(column, statusView, 24, false);

        Button start = new Button(this);
        start.setText("Start translator bubble");
        start.setAllCaps(false);
        start.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        start.setTextColor(Color.WHITE);
        start.setBackgroundTintList(ColorStateList.valueOf(Ui.ACCENT));
        start.setMinHeight(Ui.dp(this, 56));
        start.setOnClickListener(v -> onStartTapped());
        add(column, start, 12, true);

        stopButton = new Button(this);
        stopButton.setText("Stop translator");
        stopButton.setAllCaps(false);
        stopButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        stopButton.setOnClickListener(v -> onStopTapped());
        add(column, stopButton, 4, true);

        add(column, secondary("Tips: you can drag the bubble anywhere. To turn it off, use Stop here or in the "
                + "notification. Live mode checks the screen whenever it changes and pauses itself after 20 "
                + "minutes. Apps that block screenshots (banking, some video apps) show up black and can't be "
                + "translated."), 20, false);
        return scroll;
    }

    private void buildOnDevicePanel(LinearLayout panel) {
        panel.addView(secondary("The phone reads the text on screen (Korean, English and other Latin-alphabet "
                + "languages), then Google's Gemma 4 AI translates it with the whole screen as context, so "
                + "it follows meaning and tone instead of word by word. The first translation after starting "
                + "takes a few seconds while the AI loads."));

        TextView modelLabel = label("AI model");
        add(panel, modelLabel, 14, false);
        RadioGroup models = new RadioGroup(this);
        smallModelOption = option(ModelStore.E2B.name + " · " + ModelStore.E2B.sizeText() + " · faster");
        largeModelOption = option(ModelStore.E4B.name + " · " + ModelStore.E4B.sizeText() + " · better quality");
        models.addView(smallModelOption);
        models.addView(optionNote("Recommended. For phones with 8 GB of memory or more."));
        models.addView(largeModelOption);
        models.addView(optionNote("For phones with 12 GB of memory or more."));
        smallModelOption.setOnClickListener(v -> onModelChosen(ModelStore.E2B));
        largeModelOption.setOnClickListener(v -> onModelChosen(ModelStore.E4B));
        add(panel, models, 0, true);

        memoryNote = text("", 13);
        memoryNote.setTextColor(Ui.isDark(this) ? 0xFFFFB74D : 0xFFB45309);
        add(panel, memoryNote, 6, false);

        modelStatus = text("", 15);
        modelStatus.setTypeface(Typeface.DEFAULT_BOLD);
        add(panel, modelStatus, 12, false);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        downloadButton = smallButton("Download", true);
        downloadButton.setOnClickListener(v -> onDownloadTapped());
        cancelButton = smallButton("Cancel download", false);
        cancelButton.setOnClickListener(v -> onCancelTapped());
        deleteButton = smallButton("Delete model", false);
        deleteButton.setOnClickListener(v -> onDeleteTapped());
        buttons.addView(downloadButton);
        buttons.addView(cancelButton);
        buttons.addView(deleteButton);
        add(panel, buttons, 6, false);

        mobileDataBox = new CheckBox(this);
        mobileDataBox.setText("Allow download over mobile data");
        mobileDataBox.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        add(panel, mobileDataBox, 2, false);

        importButton = new Button(this, null, android.R.attr.borderlessButtonStyle);
        importButton.setText("Use a model file from this phone (.litertlm)");
        importButton.setAllCaps(false);
        importButton.setTextColor(Ui.isDark(this) ? Ui.ACCENT_ON_DARK : Ui.ACCENT);
        importButton.setOnClickListener(v -> onImportTapped());
        LinearLayout.LayoutParams importParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        importParams.leftMargin = -Ui.dp(this, 12);
        panel.addView(importButton, importParams);
    }

    private void buildGeminiPanel(LinearLayout panel) {
        panel.addView(label("Gemini API key"));
        geminiKeyField = field("Paste your key", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        add(panel, geminiKeyField, 0, true);
        add(panel, secondary("Create a free key at aistudio.google.com. The free tier allows a limited number "
                + "of translations per minute and per day, and Google may use free-tier requests to improve "
                + "its products. The key is saved only on this phone."), 4, false);
        panel.addView(link("Get a free Gemini key ↗", GEMINI_KEYS_URL), linkParams());
        add(panel, label("Gemini model"), 10, false);
        geminiModelField = field(Prefs.DEFAULT_GEMINI_MODEL, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        add(panel, geminiModelField, 0, true);
        add(panel, secondary("Default: " + Prefs.DEFAULT_GEMINI_MODEL + " (fast, on the free tier)."), 4, false);
    }

    private void buildOpenAiPanel(LinearLayout panel) {
        panel.addView(label("OpenAI API key"));
        openAiKeyField = field("sk-…", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        add(panel, openAiKeyField, 0, true);
        add(panel, secondary("Create one at platform.openai.com. It's billed separately from a ChatGPT "
                + "subscription; each translation costs a fraction of a cent. The key is saved only on this "
                + "phone."), 4, false);
        panel.addView(link("Get an API key ↗", OPENAI_KEYS_URL), linkParams());
        add(panel, label("ChatGPT model"), 10, false);
        openAiModelField = field(Prefs.DEFAULT_OPENAI_MODEL, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        add(panel, openAiModelField, 0, true);
        add(panel, secondary("Default: " + Prefs.DEFAULT_OPENAI_MODEL + " (fast and low-cost)."), 4, false);
    }

    // ----------------------------------------------------------------------------------------
    // Small view helpers

    private LinearLayout panel() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(this, 14);
        panel.setPadding(pad, pad, pad, pad);
        boolean dark = Ui.isDark(this);
        panel.setBackground(Ui.rounded(dark ? 0x14FFFFFF : 0x0A000000, Ui.dp(this, 14),
                dark ? 0x22FFFFFF : 0x14000000, Ui.dp(this, 1)));
        return panel;
    }

    private RadioButton option(String label) {
        RadioButton button = new RadioButton(this);
        button.setId(View.generateViewId());
        button.setText(label);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        return button;
    }

    private TextView optionNote(String value) {
        TextView note = secondary(value);
        note.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        note.setPadding(Ui.dp(this, 32), 0, 0, Ui.dp(this, 6));
        return note;
    }

    private Button smallButton(String label, boolean primary) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        if (primary) {
            button.setTextColor(Color.WHITE);
            button.setBackgroundTintList(ColorStateList.valueOf(Ui.ACCENT));
        }
        return button;
    }

    private Button link(String label, String url) {
        Button button = new Button(this, null, android.R.attr.borderlessButtonStyle);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextColor(Ui.isDark(this) ? Ui.ACCENT_ON_DARK : Ui.ACCENT);
        button.setOnClickListener(v -> openLink(url));
        return button;
    }

    private LinearLayout.LayoutParams linkParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.leftMargin = -Ui.dp(this, 12);
        return params;
    }

    private void add(LinearLayout column, View view, int topDp, boolean fullWidth) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                fullWidth ? ViewGroup.LayoutParams.MATCH_PARENT : ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = Ui.dp(this, topDp);
        column.addView(view, params);
    }

    private TextView text(String value, float sizeSp) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp);
        return view;
    }

    private TextView secondary(String value) {
        TextView view = text(value, 14);
        view.setTextColor(secondaryTextColor());
        view.setLineSpacing(0, 1.15f);
        return view;
    }

    private TextView label(String value) {
        TextView view = text(value, 14);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        return view;
    }

    private EditText field(String hint, int inputType) {
        EditText field = new EditText(this);
        field.setHint(hint);
        field.setSingleLine(true);
        field.setInputType(inputType);
        field.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        return field;
    }

    private int secondaryTextColor() {
        return Ui.isDark(this) ? 0xB3FFFFFF : 0xB3000000;
    }

    private void openLink(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, url, Toast.LENGTH_LONG).show();
        }
    }
}
