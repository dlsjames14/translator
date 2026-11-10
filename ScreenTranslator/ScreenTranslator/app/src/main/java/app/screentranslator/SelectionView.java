package app.screentranslator;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

/** Full-screen layer where the user drags a box over the text to translate. */
@SuppressLint("ViewConstructor")
final class SelectionView extends View {

    interface Listener {
        /** @param area the chosen box in screen pixels, or null for the whole screen */
        void onChosen(Rect area);
    }

    private static final String HINT_TOP = "Drag a box over the text";
    private static final String HINT_BOTTOM = "or tap anywhere for the whole screen";
    private static final String LIVE_HINT_TOP = "Drag a box over the area to watch";
    private static final String LIVE_HINT_BOTTOM = "or tap anywhere to watch the whole screen";

    private String hintTop = HINT_TOP;
    private String hintBottom = HINT_BOTTOM;

    private final Listener listener;
    private final Paint shade = new Paint();
    private final Paint frame = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hintBold = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF pillBox = new RectF();
    private final float density;
    private final float baseTextSize;
    private final int touchSlop;

    // View coordinates (for drawing) and screen coordinates (for the screenshot).
    private float startX, startY, endX, endY;
    private float startRawX, startRawY, endRawX, endRawY;
    private boolean dragging;

    SelectionView(Context context, Listener listener) {
        super(context);
        this.listener = listener;
        DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        density = metrics.density;
        baseTextSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 15, metrics);
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();

        shade.setColor(0x73000000);
        frame.setStyle(Paint.Style.STROKE);
        frame.setStrokeWidth(2.5f * density);
        frame.setColor(0xFFFFFFFF);
        pill.setColor(0xF0283593);
        hintBold.setColor(0xFFFFFFFF);
        hintBold.setTextAlign(Paint.Align.CENTER);
        hintBold.setTypeface(Typeface.DEFAULT_BOLD);
        hint.setColor(0xE6FFFFFF);
        hint.setTextAlign(Paint.Align.CENTER);
        setContentDescription("Drag a box over the text to translate, or tap for the whole screen");
    }

    /** Switches the instructions between "translate once" and "live" wording. */
    void setLive(boolean live) {
        hintTop = live ? LIVE_HINT_TOP : HINT_TOP;
        hintBottom = live ? LIVE_HINT_BOTTOM : HINT_BOTTOM;
        invalidate();
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                startX = endX = event.getX();
                startY = endY = event.getY();
                startRawX = endRawX = event.getRawX();
                startRawY = endRawY = event.getRawY();
                dragging = false;
                return true;

            case MotionEvent.ACTION_MOVE:
                track(event);
                if (!dragging && (Math.abs(endX - startX) > touchSlop || Math.abs(endY - startY) > touchSlop)) {
                    dragging = true;
                }
                if (dragging) {
                    invalidate();
                }
                return true;

            case MotionEvent.ACTION_UP:
                track(event);
                if (!dragging) {
                    listener.onChosen(null);   // a tap: translate the whole screen
                    return true;
                }
                Rect area = new Rect(
                        Math.round(Math.min(startRawX, endRawX)),
                        Math.round(Math.min(startRawY, endRawY)),
                        Math.round(Math.max(startRawX, endRawX)),
                        Math.round(Math.max(startRawY, endRawY)));
                if (area.width() < 12 * density || area.height() < 12 * density) {
                    dragging = false;          // too small to be useful: let them try again
                    invalidate();
                    return true;
                }
                listener.onChosen(area);
                return true;

            case MotionEvent.ACTION_CANCEL:
                dragging = false;
                invalidate();
                return true;

            default:
                return true;
        }
    }

    private void track(MotionEvent event) {
        endX = event.getX();
        endY = event.getY();
        endRawX = event.getRawX();
        endRawY = event.getRawY();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();

        if (dragging) {
            float left = Math.min(startX, endX);
            float top = Math.min(startY, endY);
            float right = Math.max(startX, endX);
            float bottom = Math.max(startY, endY);
            canvas.drawRect(0, 0, w, top, shade);
            canvas.drawRect(0, bottom, w, h, shade);
            canvas.drawRect(0, top, left, bottom, shade);
            canvas.drawRect(right, top, w, bottom, shade);
            canvas.drawRect(left, top, right, bottom, frame);
            return;
        }

        canvas.drawRect(0, 0, w, h, shade);

        // Instruction "pill" near the top, shrunk to fit narrow screens.
        float textSize = baseTextSize;
        hintBold.setTextSize(textSize);
        hint.setTextSize(textSize);
        float textWidth = Math.max(hintBold.measureText(hintTop), hint.measureText(hintBottom));
        float maxWidth = w - 64 * density;
        if (textWidth > maxWidth && textWidth > 0) {
            textSize = textSize * maxWidth / textWidth;
            hintBold.setTextSize(textSize);
            hint.setTextSize(textSize);
            textWidth = maxWidth;
        }
        float lineHeight = textSize * 1.4f;
        float padH = 20 * density;
        float padV = 12 * density;
        float centerX = w / 2f;
        float top = h * 0.12f;
        pillBox.set(centerX - textWidth / 2 - padH, top,
                centerX + textWidth / 2 + padH, top + padV * 2 + lineHeight + textSize * 1.2f);
        float radius = 20 * density;
        canvas.drawRoundRect(pillBox, radius, radius, pill);
        float firstBaseline = top + padV - hintBold.ascent();
        canvas.drawText(hintTop, centerX, firstBaseline, hintBold);
        canvas.drawText(hintBottom, centerX, firstBaseline + lineHeight, hint);
    }
}
