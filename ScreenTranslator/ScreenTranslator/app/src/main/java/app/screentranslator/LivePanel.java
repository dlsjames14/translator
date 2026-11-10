package app.screentranslator;

import android.content.Context;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;
import java.util.Locale;

/**
 * The floating panel used in Live mode. New translations are added at the bottom,
 * so it reads like a running transcript of everything new that appeared on screen.
 */
final class LivePanel {

    interface Actions {
        void onPauseToggle();

        void onClear();

        void onMove();

        void onClose();

        void onCopy(CharSequence text);
    }

    private static final int MAX_ITEMS = 150;

    final LinearLayout root;

    private final Context context;
    private final Actions actions;
    private final boolean dark;
    private final TextView dot;
    private final TextView status;
    private final TextView pauseChip;
    private final TextView empty;
    private final LinearLayout items;
    private final ScrollView scroll;

    LivePanel(Context context, String language, Actions actions) {
        this.context = context;
        this.actions = actions;
        this.dark = Ui.isDark(context);

        root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(10), dp(10), dp(10));
        root.setBackground(Ui.rounded(dark ? 0xF5202124 : 0xFAFFFFFF, dp(18),
                dark ? 0x33FFFFFF : 0x22000000, dp(1)));

        // Header: ● LIVE · ENGLISH / status ............ Pause Clear ⇅ ✕
        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        dot = new TextView(context);
        dot.setText("●");
        dot.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        LinearLayout.LayoutParams dotParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dotParams.rightMargin = dp(8);
        header.addView(dot, dotParams);

        LinearLayout titles = new LinearLayout(context);
        titles.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(context);
        title.setText(("Live · " + language).toUpperCase(Locale.ROOT));
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setLetterSpacing(0.08f);
        title.setSingleLine(true);
        title.setTextColor(dark ? Ui.ACCENT_ON_DARK : Ui.ACCENT);
        titles.addView(title);
        status = new TextView(context);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        status.setSingleLine(true);
        status.setTextColor(secondaryColor());
        titles.addView(status);
        header.addView(titles, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        pauseChip = chip("Pause");
        pauseChip.setOnClickListener(v -> actions.onPauseToggle());
        header.addView(pauseChip);
        TextView clear = chip("Clear");
        clear.setOnClickListener(v -> actions.onClear());
        header.addView(clear, chipParams());
        TextView move = chip("⇅");
        move.setContentDescription("Move panel to the other end of the screen");
        move.setOnClickListener(v -> actions.onMove());
        header.addView(move, chipParams());
        TextView close = chip("✕");
        close.setContentDescription("Stop live translation");
        close.setOnClickListener(v -> actions.onClose());
        header.addView(close, chipParams());

        root.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Running list of translations.
        scroll = new ScrollView(context);
        items = new LinearLayout(context);
        items.setOrientation(LinearLayout.VERTICAL);
        items.setPadding(0, 0, dp(6), dp(4));
        scroll.addView(items, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        scrollParams.topMargin = dp(6);
        root.addView(scroll, scrollParams);

        empty = new TextView(context);
        empty.setText("Watching for new text. Anything that isn't in " + language
                + " will appear here as soon as it shows up. Long-press a line to copy it.");
        empty.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        empty.setLineSpacing(0, 1.15f);
        empty.setTextColor(secondaryColor());
        empty.setPadding(0, dp(6), 0, 0);
        items.addView(empty);
    }

    void setStatus(String text, int dotColor) {
        status.setText(text);
        dot.setTextColor(dotColor);
    }

    void setPaused(boolean paused) {
        pauseChip.setText(paused ? "Resume" : "Pause");
    }

    /** Adds one batch of new translations at the bottom and scrolls to it. */
    void addLines(List<Translation.Line> lines, boolean showOriginal) {
        startBatch();
        for (Translation.Line line : lines) {
            LinearLayout entry = new LinearLayout(context);
            entry.setOrientation(LinearLayout.VERTICAL);
            entry.setPadding(0, dp(6), 0, dp(2));
            if (showOriginal && !line.original.isEmpty()) {
                TextView original = new TextView(context);
                original.setText(line.original);
                original.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
                original.setTextColor(secondaryColor());
                entry.addView(original);
            }
            TextView translation = new TextView(context);
            translation.setText(line.translation);
            translation.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            translation.setLineSpacing(0, 1.12f);
            translation.setTextColor(dark ? 0xFFECEDEF : 0xFF1B1C1F);
            entry.addView(translation);
            final CharSequence copyText = line.translation;
            entry.setOnLongClickListener(v -> {
                actions.onCopy(copyText);
                return true;
            });
            items.addView(entry);
        }
        trimAndScroll();
    }

    /** A short message (an error or a pause notice), shown in the list in a different colour. */
    void addNote(String text) {
        items.removeView(empty);
        TextView note = new TextView(context);
        note.setText(text);
        note.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        note.setTypeface(Typeface.DEFAULT, Typeface.ITALIC);
        note.setTextColor(dark ? 0xFFFFB74D : 0xFFB45309);
        note.setPadding(0, dp(8), 0, dp(2));
        items.addView(note);
        trimAndScroll();
    }

    void clear() {
        items.removeAllViews();
        items.addView(empty);
        scroll.scrollTo(0, 0);
    }

    private void startBatch() {
        items.removeView(empty);
        if (items.getChildCount() > 0) {
            View divider = new View(context);
            divider.setBackgroundColor(dark ? 0x26FFFFFF : 0x1A000000);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1)));
            params.topMargin = dp(8);
            params.bottomMargin = dp(2);
            items.addView(divider, params);
        }
    }

    private void trimAndScroll() {
        while (items.getChildCount() > MAX_ITEMS) {
            items.removeViewAt(0);
        }
        scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
    }

    private TextView chip(String label) {
        TextView chip = new TextView(context);
        chip.setText(label);
        chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        chip.setTypeface(Typeface.DEFAULT_BOLD);
        chip.setTextColor(dark ? 0xFFECEDEF : 0xFF1B1C1F);
        chip.setGravity(Gravity.CENTER);
        chip.setMinWidth(dp(36));
        chip.setPadding(dp(10), dp(7), dp(10), dp(7));
        chip.setBackground(Ui.rounded(dark ? 0x26FFFFFF : 0x12000000, dp(15)));
        return chip;
    }

    private LinearLayout.LayoutParams chipParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.leftMargin = dp(6);
        return params;
    }

    private int secondaryColor() {
        return dark ? 0xB3FFFFFF : 0xA6000000;
    }

    private int dp(float value) {
        return Ui.dp(context, value);
    }
}
