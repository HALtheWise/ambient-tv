package bot.tvhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.Choreographer;
import android.view.View;

import java.util.function.LongSupplier;

/**
 * Two live counters ("generation N · population M"), drawn like glowing digits filmed through a
 * short exposure. Each counter is treated as a continuous value moving at its (smoothed) rate;
 * per frame, every digit position gets the fraction of the last EXPOSURE seconds each glyph was
 * up. Glyphs then behave like phosphor: whatever is showing pops to that brightness, and glyphs
 * no longer shown fade out over about 0.3 s (GLOW_TAU). A digit turning over faster than
 * the exposure settles into an even blend of all ten; a slow one leaves a short fading trail.
 */
final class Counter extends View implements Choreographer.FrameCallback {

    private static final double EXPOSURE = 1 / 30.0;  // seconds
    private static final double RATE_TAU = 0.25;      // smoothing of the rate estimate
    private static final double TRACK_TAU = 0.08;     // how fast the continuous value meets the real one
    private static final double GLOW_TAU = 0.09;      // afterglow of a glyph no longer shown (~0.3 s)
    private static final int SLOTS = 19;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Number[] numbers = new Number[2];
    private final String[] labels = new String[2];
    private final float digitW, commaW;
    private final float[] weights = new float[10];
    private long lastFrameNanos;
    private boolean running;

    private static final class Number {
        final LongSupplier source;
        long value = -1;
        double smooth, rate, speed;  // continuous value, signed and absolute rate (per second)
        int length = 1;
        final float[][] glow = new float[SLOTS][10];

        Number(LongSupplier source) {
            this.source = source;
        }
    }

    Counter(Context context, Typeface face, float sp, int color, LongSupplier first, LongSupplier second) {
        super(context);
        paint.setTypeface(face);
        paint.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, getResources().getDisplayMetrics()));
        paint.setColor(color);
        paint.setFontFeatureSettings("tnum");
        paint.setShadowLayer(14, 0, 0, 0xF0000000);
        digitW = paint.measureText("0");
        commaW = paint.measureText(",");
        numbers[0] = new Number(first);
        numbers[1] = new Number(second);
        setLabels("generation", "population");
    }

    /** What the two counters are called, e.g. "generation" and "population". */
    void setLabels(String first, String second) {
        String a = first + " ", b = " · " + second + " ";
        if (a.equals(labels[0]) && b.equals(labels[1])) return;
        labels[0] = a;
        labels[1] = b;
        requestLayout();
    }

    /** A new pattern started: show the current values crisply. */
    void reset() {
        for (Number n : numbers) n.value = -1;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        running = true;
        lastFrameNanos = 0;
        Choreographer.getInstance().postFrameCallback(this);
    }

    @Override
    protected void onDetachedFromWindow() {
        running = false;
        Choreographer.getInstance().removeFrameCallback(this);
        super.onDetachedFromWindow();
    }

    @Override
    public void doFrame(long nanos) {
        if (!running) return;
        double dt = lastFrameNanos == 0 ? 0 : Math.min(0.1, (nanos - lastFrameNanos) / 1e9);
        lastFrameNanos = nanos;
        if (getVisibility() == VISIBLE) {
            boolean resize = false;
            for (Number n : numbers) resize |= update(n, n.source.getAsLong(), dt);
            if (resize) requestLayout();
            invalidate();
        }
        Choreographer.getInstance().postFrameCallback(this);
    }

    private static boolean fresh(Number n) {
        return n.rate == 0 && n.speed == 0;
    }

    /** Advance the continuous estimate and the glyph glow; true when the digit count changed. */
    private boolean update(Number n, long v, double dt) {
        if (n.value < 0) {
            n.smooth = v;
            n.rate = n.speed = 0;
        } else if (dt > 0) {
            double inst = (v - n.value) / dt;
            double k = 1 - Math.exp(-dt / RATE_TAU);
            n.rate += (inst - n.rate) * k;
            n.speed += (Math.abs(inst) - n.speed) * k;
            n.smooth += n.rate * dt;
            n.smooth += (v - n.smooth) * (1 - Math.exp(-dt / TRACK_TAU));
        }
        n.value = v;
        int length = Math.max(1, Long.toString(Math.max(0, v)).length());
        // The span the value swept during the exposure, ending where it is now.
        double span = n.speed * EXPOSURE;
        double lo = n.rate >= 0 ? n.smooth - span : n.smooth;
        double hi = n.rate >= 0 ? n.smooth : n.smooth + span;
        float decay = (float) Math.exp(-dt / GLOW_TAU);
        for (int i = 0; i < Math.min(SLOTS, length); i++) {
            coverage(lo, hi, i);
            float[] g = n.glow[i];
            for (int d = 0; d < 10; d++) g[d] = fresh(n) ? weights[d] : Math.max(g[d] * decay, weights[d]);
        }
        boolean changed = length != n.length;
        n.length = length;
        return changed;
    }

    /**
     * Fraction of the interval [a, b] (in the counter's units) during which the digit at `slot`
     * reads each value, into `weights`.
     */
    private void coverage(double a, double b, int slot) {
        java.util.Arrays.fill(weights, 0f);
        double p = Math.pow(10, slot);
        double u0 = Math.max(0, a) / p, u1 = Math.max(0, b) / p;
        double len = u1 - u0;
        if (len < 1e-9) {
            weights[(int) (Math.floor(u1) % 10)] = 1f;
            return;
        }
        double full = Math.floor(len / 10);
        for (int d = 0; d < 10; d++) weights[d] = (float) full;
        double start = u0 + full * 10;
        for (double k = Math.floor(start); k < u1; k++) {
            double lo = Math.max(start, k), hi = Math.min(u1, k + 1);
            if (hi > lo) weights[(int) (k % 10)] += (float) (hi - lo);
        }
        for (int d = 0; d < 10; d++) weights[d] /= (float) len;
    }

    private float numberWidth(Number n) {
        return n.length * digitW + ((n.length - 1) / 3) * commaW;
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        float w = paint.measureText(labels[0]) + paint.measureText(labels[1]);
        for (Number n : numbers) w += numberWidth(n);
        Paint.FontMetrics fm = paint.getFontMetrics();
        setMeasuredDimension((int) Math.ceil(w) + 4, (int) Math.ceil(fm.descent - fm.ascent) + 2);
    }

    @Override
    protected void onDraw(Canvas c) {
        float x = 0, y = -paint.getFontMetrics().ascent;
        int base = paint.getAlpha();
        for (int k = 0; k < 2; k++) {
            paint.setAlpha(base);
            c.drawText(labels[k], x, y, paint);
            x += paint.measureText(labels[k]);
            Number n = numbers[k];
            for (int i = n.length - 1; i >= 0; i--) {
                float[] g = n.glow[Math.min(i, SLOTS - 1)];
                for (int d = 0; d < 10; d++) {
                    if (g[d] < 0.02f) continue;
                    paint.setAlpha(Math.round(base * Math.min(1f, g[d])));
                    c.drawText(DIGITS[d], x, y, paint);
                }
                x += digitW;
                if (i > 0 && i % 3 == 0) {
                    paint.setAlpha(base);
                    c.drawText(",", x, y, paint);
                    x += commaW;
                }
            }
        }
        paint.setAlpha(base);
    }

    private static final String[] DIGITS = {"0", "1", "2", "3", "4", "5", "6", "7", "8", "9"};
}
