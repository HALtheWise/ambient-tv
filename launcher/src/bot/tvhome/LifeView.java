package bot.tvhome;

import static android.opengl.GLES30.*;

import android.content.Context;
import android.opengl.GLSurfaceView;
import android.os.SystemClock;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * The Game of Life background: runs the HashLife engine through a shuffled queue of famous
 * patterns, following each with a spring-damped camera.
 *
 * Drawing is on the GPU. Each frame the visible part of the universe is rasterized into a byte
 * texture straight from the quadtree: one texel per cell when cells are at least two pixels (with
 * each cell's age, so newborn cells glow and the recently dead leave a ghost), otherwise one per
 * 2^k block, each block's brightness its population density, so dense patterns are area-averaged
 * instead of aliasing. Blocks are always two or more pixels, which keeps the per-frame raster to
 * about an eighth of the screen's pixel count. The shader draws rounded cells when they are big
 * and blends to the smoothly filtered texture as they shrink.
 */
final class LifeView extends GLSurfaceView implements GLSurfaceView.Renderer {

    interface Listener {
        /** On the UI thread when a pattern starts. */
        void onCaption(Catalog.Entry entry, long generation, long population);
    }

    private static final float FADE_SECONDS = 2.5f;
    /**
     * Camera tracking (an alpha-beta filter): a least-squares line through the last
     * TRACK_WINDOW seconds of aim points gives position and velocity; the camera moves at that
     * velocity and closes the remaining gap over CORRECT_SECONDS. Steady growth or drift is
     * followed with no lag, and the box's frame-to-frame noise averages out.
     */
    private static final double TRACK_WINDOW = 5.0, CORRECT_SECONDS = 1.5;
    private static final int SAMPLES = 128;
    /** A cell's rounded square covers about this much of its area. */
    private static final double COVER = 0.67;

    private static final String SHADER =
            "#version 300 es\n"
            + "precision highp float;\n"
            + "uniform sampler2D cells;\n"
            + "uniform vec2 size;\n"     // texture size (it wraps: the world is toroidal)
            + "uniform vec2 screen;\n"   // px
            + "uniform vec2 origin;\n"   // camera centre, in texels of the texture
            + "uniform float texelPx;\n" // screen px per texel
            + "uniform float shaped;\n"  // 1 when texels are single cells (rounded shapes allowed)
            + "uniform float light;\n"   // texel value -> average light over its area
            + "uniform float gain;\n"    // rises smoothly as cells shrink, so sparse patterns stay visible
            + "uniform float fade;\n"
            + "uniform vec3 ink;\n"
            + "out vec4 o;\n"
            + "void main() {\n"
            + "  vec2 px = vec2(gl_FragCoord.x, screen.y - gl_FragCoord.y);\n"
            + "  vec2 t = origin + (px - screen * 0.5) / texelPx;\n"
            + "  // Everything is in one unit, average light per area, so zoom levels match exactly.\n"
            + "  // Sharp bilinear: crisp texels with a one-pixel antialiased edge, not a smear.\n"
            + "  vec2 ti = floor(t), tf = fract(t);\n"
            + "  tf = clamp((tf - 0.5) * texelPx + 0.5, 0.0, 1.0);\n"
            + "  float soft;\n"
            + "  if (texelPx < 1.0) {\n"
            + "    // Texels smaller than a pixel: cover the pixel's whole footprint (1 to 2 texels) with\n"
            + "    // two bilinear reads a side (each one is an exact 1-texel box), or the fine periodic\n"
            + "    // agars some patterns lay down shimmer as moire.\n"
            + "    float d = 0.5 / texelPx - 0.5;\n"
            + "    vec2 c = t;\n"
            + "    soft = 0.25 * (texture(cells, (c + vec2(-d, -d)) / size).r + texture(cells, (c + vec2(d, -d)) / size).r\n"
            + "                 + texture(cells, (c + vec2(-d, d)) / size).r + texture(cells, (c + vec2(d, d)) / size).r) * light;\n"
            + "  } else {\n"
            + "    soft = texture(cells, (ti + tf) / size).r * light;\n"
            + "  }\n"
            + "  float v = soft;\n"
            + "  if (shaped > 0.5) {\n"
            + "    vec2 c = floor(t);\n"
            + "    float raw = texelFetch(cells, ivec2(mod(c, size)), 0).r;\n"
            + "    // Rounded square, inset from the cell edge, antialiased over one pixel.\n"
            + "    vec2 f = abs(fract(t) - 0.5);\n"
            + "    float r = 0.2;\n"
            + "    float sd = length(max(f - (0.42 - r), 0.0)) - r;\n"
            + "    float cover = clamp(0.5 - sd * texelPx, 0.0, 1.0);\n"
            + "    v = mix(soft, raw * cover, smoothstep(2.5, 5.0, texelPx));\n"
            + "  }\n"
            + "  o = vec4(ink * min(1.0, v * gain) * fade, 1.0);\n"
            + "}\n";

    private final LifeSim sim;
    /** Debug (Config lifeVerify): rows of tiles to read back and check each frame, 0 for none. */
    private final int verifySamples;
    private final int[] verifyStats = new int[3];
    private final Random random = new Random();
    private final float maxCell;
    private Listener listener;

    private final List<Catalog.Entry> queue = new ArrayList<>();
    private Catalog.Entry entry;
    /** The load() the renderer wants, and the snapshot it is drawing (null until that load lands). */
    private int epoch;
    private LifeSim.Snapshot snap;
    private int lastBoxSeq;
    private long startedAt;
    private boolean ending;
    private long endingAt;
    /** Fade lengths for the current pattern: quick after a skip, leisurely otherwise. */
    private float fadeOut = FADE_SECONDS, fadeIn = FADE_SECONDS;
    private long lastFrameAt;
    private volatile boolean skip, back;
    private final List<Catalog.Entry> history = new ArrayList<>();

    // Camera: centre in cells, scale in px per cell.
    private double camX, camY, camScale = 1;
    private final double[] sampleT = new double[SAMPLES];
    private final double[][] sampleV = new double[3][SAMPLES];  // x, y, log scale
    private int sampleCount, sampleHead;
    private final double[] fit = new double[2];
    private double targetX, targetY, targetScale = 1;

    /** Published for the counters, which read them on the UI thread every frame. */
    private volatile long shownGeneration, shownPopulation;

    private final LifeRenderer renderer = new LifeRenderer();
    private boolean rendererReady;

    private int program;
    private int surfaceW = 1, surfaceH = 1;

    LifeView(Context context) {
        super(context);
        maxCell = 8 * getResources().getDisplayMetrics().density;
        sim = new LifeSim(context.getAssets());
        int samples = 0;
        try {
            samples = Integer.parseInt(context.getSharedPreferences("home", Context.MODE_PRIVATE).getString("lifeVerify", "0"));
        } catch (NumberFormatException ignored) {
        }
        verifySamples = samples;
        setEGLContextClientVersion(3);
        setPreserveEGLContextOnPause(true);
        setRenderer(this);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        sim.start();
    }

    @Override
    protected void onDetachedFromWindow() {
        sim.stop();
        super.onDetachedFromWindow();
    }

    @Override
    public void onResume() {
        super.onResume();
        sim.setPaused(false);
    }

    @Override
    public void onPause() {
        sim.setPaused(true);
        // The context is kept across the pause, but not the world textures (about 140 MB):
        // give them back while something else is on screen. (Queued events run before the
        // GL thread pauses.)
        queueEvent(() -> {
            if (rendererReady) renderer.release();
            rendererReady = false;
        });
        super.onPause();
    }

    void setListener(Listener l) {
        listener = l;
    }

    long generation() {
        return shownGeneration;
    }

    long population() {
        return shownPopulation;
    }

    /** Skip to the next pattern (with a quick fade). */
    void next() {
        skip = true;
    }

    /** Back to the previous pattern. */
    void previous() {
        back = true;
        skip = true;
    }

    // ---- GL thread ----

    @Override
    public void onSurfaceCreated(GL10 unused, EGLConfig config) {
        // A new context: the renderer's textures went with the old one.
        program = Gl.program(Gl.FULLSCREEN, SHADER);
        rendererReady = false;
    }

    @Override
    public void onSurfaceChanged(GL10 unused, int w, int h) {
        if (!rendererReady || w != surfaceW || h != surfaceH) {
            if (rendererReady) renderer.release();
            renderer.create(w, h);
            rendererReady = true;
        }
        surfaceW = w;
        surfaceH = h;
        glViewport(0, 0, w, h);
        if (entry == null) request(firstEntry());
    }

    @Override
    public void onDrawFrame(GL10 unused) {
        long t0 = SystemClock.uptimeMillis();
        if (!rendererReady) {
            // Back from a pause (the textures were given up meanwhile): rebuild, settled.
            renderer.create(surfaceW, surfaceH);
            renderer.reset(false);
            rendererReady = true;
        }
        boolean ready = entry != null && advance();
        if (ready) {
            render();
        } else {
            glBindFramebuffer(GL_FRAMEBUFFER, 0);
            glClearColor(0, 0, 0, 1);
            glClear(GL_COLOR_BUFFER_BIT);
        }
        long t1 = SystemClock.uptimeMillis();
        // A line every 10 s (logcat -s LifeFrames), for tuning.
        statFrames++;
        statUpdate += renderer.lastUpdateNs;
        statTiles += renderer.lastDone;
        statWorst = Math.max(statWorst, t1 - t0);
        if (t1 - statSince > 10_000) {
            int n = Math.max(1, statFrames);
            android.util.Log.d("LifeFrames", statFrames / 10 + " fps, update " + statUpdate / 1000 / n + " us, worst frame "
                    + statWorst + " ms, " + statTiles / n + " tiles a frame, level " + renderer.level()
                    + ", gen " + (snap != null ? snap.generation + " gc " + snap.collections : "-") + " " + (entry != null ? entry.file : "-"));
            if (verifySamples > 0) {
                android.util.Log.d("LifeVerify", verifyStats[0] + " tiles checked, " + verifyStats[1] + " stale, "
                        + verifyStats[2] + " pending");
                java.util.Arrays.fill(verifyStats, 0);
            }
            statSince = t1;
            statFrames = 0;
            statUpdate = statTiles = statWorst = 0;
        }
    }

    private long statSince, statUpdate, statTiles, statWorst;
    private int statFrames;

    // ---- patterns ----

    /** Normally the queue's head; adb can name one instead, once (see Config: lifeFirst). */
    private Catalog.Entry firstEntry() {
        android.content.SharedPreferences prefs = getContext().getSharedPreferences("home", Context.MODE_PRIVATE);
        String want = prefs.getString("lifeFirst", null);
        if (want != null) {
            prefs.edit().remove("lifeFirst").apply();
            for (Catalog.Entry e : Catalog.ALL) if (e.file.equals(want)) return e;
        }
        return nextEntry();
    }

    private Catalog.Entry nextEntry() {
        if (queue.isEmpty()) {
            Collections.addAll(queue, Catalog.ALL);
            Collections.shuffle(queue, random);
            if (entry != null && queue.get(0) == entry && queue.size() > 1) Collections.swap(queue, 0, 1);
        }
        return queue.remove(0);
    }

    /** Ask the simulation for a pattern; drawing resumes when its first snapshot arrives. */
    private void request(Catalog.Entry e) {
        entry = e;
        history.add(e);
        if (history.size() > 50) history.remove(0);
        snap = null;
        sim.load(e, ++epoch);
    }

    /** The first snapshot of a new pattern: frame it and announce it. */
    private void adopt(LifeSim.Snapshot s) {
        snap = s;
        startedAt = SystemClock.uptimeMillis();
        lastFrameAt = startedAt;
        ending = false;
        sampleCount = sampleHead = 0;
        renderer.reset(true);
        camScale = maxCell;
        lastBoxSeq = s.boxSeq;
        if (s.box != null) {
            target(s.box);
            sample(s.boxTime);
        }
        camX = targetX;
        camY = targetY;
        camScale = targetScale;
        shownGeneration = s.generation;
        shownPopulation = s.population;
        final Catalog.Entry shown = entry;
        final long g = s.generation, p = s.population;
        post(() -> {
            if (listener != null) listener.onCaption(shown, g, p);
        });
    }

    // ---- simulation ----

    /** Pattern changes and the camera; false while waiting for the simulation. */
    private boolean advance() {
        LifeSim.Snapshot s = sim.latest();
        if (s == null || s.epoch != epoch) return false;
        if (s.failed) {
            history.remove(history.size() - 1);
            request(nextEntry());
            return false;
        }
        if (snap == null) adopt(s);
        snap = s;
        long now = SystemClock.uptimeMillis();
        boolean done = entry.gens > 0 ? s.generation >= entry.gens
                : now - startedAt > entry.seconds * 1000L;
        if (skip && !ending) fadeOut = 0.4f;
        if ((done || skip || s.population == 0) && !ending) {
            skip = false;
            ending = true;
            endingAt = now;
        }
        if (ending && now - endingAt > fadeOut * 1000) {
            boolean skipped = fadeOut < FADE_SECONDS;
            Catalog.Entry next = null;
            if (back) {
                back = false;
                if (history.size() >= 2) {
                    // Put the current one back at the front of the queue, so Right returns to it.
                    queue.add(0, history.remove(history.size() - 1));
                    next = history.remove(history.size() - 1);
                }
            }
            request(next != null ? next : nextEntry());
            fadeIn = skipped ? 0.6f : FADE_SECONDS;
            fadeOut = FADE_SECONDS;
            return false;
        }
        double dt = Math.min(0.25, (now - lastFrameAt) / 1000.0);
        lastFrameAt = now;
        shownGeneration = s.generation;
        shownPopulation = s.population;
        if (s.boxSeq != lastBoxSeq && s.box != null) {
            lastBoxSeq = s.boxSeq;
            target(s.box);
            sample(s.boxTime);
        }
        double t = now / 1000.0;
        camX = track(0, camX, t, dt);
        camY = track(1, camY, t, dt);
        camScale = Math.exp(track(2, Math.log(camScale), t, dt));
        sim.viewCells = Math.max(1, surfaceW) / Math.max(1e-9, camScale);
        return true;
    }

    /** One tracked camera axis: follow the fitted line's velocity and close the gap to it. */
    private double track(int axis, double cam, double now, double dt) {
        if (!fitLine(axis, now)) return cam;
        return cam + (fit[1] + (fit[0] - cam) / CORRECT_SECONDS) * dt;
    }

    /** Least-squares line through recent samples of one axis: fit = {value now, slope}. */
    private boolean fitLine(int axis, double now) {
        int n = 0;
        double st = 0, sv = 0;
        for (int k = 0; k < sampleCount; k++) {
            int i = (sampleHead - 1 - k + SAMPLES) % SAMPLES;
            if (now - sampleT[i] > TRACK_WINDOW) break;
            st += sampleT[i];
            sv += sampleV[axis][i];
            n++;
        }
        if (n == 0) return false;
        double tm = st / n, vm = sv / n, cov = 0, var = 0;
        for (int k = 0; k < n; k++) {
            int i = (sampleHead - 1 - k + SAMPLES) % SAMPLES;
            double dt = sampleT[i] - tm;
            cov += dt * (sampleV[axis][i] - vm);
            var += dt * dt;
        }
        // Too short a history for a trustworthy slope: just aim at the average.
        double slope = n >= 8 && var > 1e-6 ? cov / var : 0;
        fit[0] = vm + slope * (now - tm);
        fit[1] = slope;
        return true;
    }

    /** Where the camera should be to frame this box. */
    private void target(long[] box) {
        int w = Math.max(1, surfaceW), h = Math.max(1, surfaceH);
        double bw = Math.max(8, box[2] - box[0]), bh = Math.max(8, box[3] - box[1]);
        targetX = (box[0] + box[2]) / 2.0;
        targetY = (box[1] + box[3]) / 2.0;
        // Generous margins: the pattern sits well inside the frame, so the camera can trail a little.
        targetScale = Math.min(maxCell, Math.min(w * 0.65 / bw, h * 0.58 / bh));
    }

    private void sample(double time) {
        sampleT[sampleHead] = time;
        sampleV[0][sampleHead] = targetX;
        sampleV[1][sampleHead] = targetY;
        sampleV[2][sampleHead] = Math.log(targetScale);
        sampleHead = (sampleHead + 1) % SAMPLES;
        sampleCount = Math.min(SAMPLES, sampleCount + 1);
    }

    // ---- drawing ----

    private void render() {
        long now = SystemClock.uptimeMillis();
        float fade = Math.min(1f, (now - startedAt) / (fadeIn * 1000));
        if (ending) fade = Math.min(fade, 1f - (now - endingAt) / (fadeOut * 1000));
        fade = Math.max(0f, fade);

        double s = camScale;
        renderer.update(snap, camX, camY, s, surfaceW, surfaceH);
        if (verifySamples > 0) {
            int[] v = renderer.verify(verifySamples);
            for (int k = 0; k < 3; k++) verifyStats[k] += v[k];
        }
        long block = 1L << renderer.level();

        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        glViewport(0, 0, surfaceW, surfaceH);
        glClearColor(0, 0, 0, 1);
        glClear(GL_COLOR_BUFFER_BIT);
        glUseProgram(program);
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, renderer.texture());
        glUniform1i(glGetUniformLocation(program, "cells"), 0);
        glUniform2f(glGetUniformLocation(program, "size"), renderer.texW(), renderer.texH());
        glUniform2f(glGetUniformLocation(program, "screen"), surfaceW, surfaceH);
        glUniform2f(glGetUniformLocation(program, "origin"), (float) renderer.originX, (float) renderer.originY);
        glUniform1f(glGetUniformLocation(program, "texelPx"), (float) (s * block));
        glUniform1f(glGetUniformLocation(program, "shaped"), renderer.level() == 0 ? 1f : 0f);
        // A cell's rounded square covers about 0.67 of its area; blocks already hold their
        // density times their age brightness.
        glUniform1f(glGetUniformLocation(program, "light"), (float) COVER);
        glUniform1f(glGetUniformLocation(program, "gain"), (float) Math.max(1, Math.min(3, Math.sqrt(6 / s))));
        glUniform1f(glGetUniformLocation(program, "fade"), fade);
        glUniform3f(glGetUniformLocation(program, "ink"), 0xF0 / 255f, 0xA4 / 255f, 0x58 / 255f);
        Gl.drawFullscreen();
    }
}
