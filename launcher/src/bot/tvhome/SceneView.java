package bot.tvhome;

import android.content.Context;
import android.opengl.GLES30;
import android.opengl.GLSurfaceView;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * Runs one Scene as the home screen's background on its own GL thread, moving to another
 * variant every few minutes (or on next()), with a fade through black at each change. Variants
 * come from a shuffled queue, each once before any repeats; previous() walks back through those
 * shown.
 */
final class SceneView extends GLSurfaceView implements GLSurfaceView.Renderer {

    interface Listener {
        /** On the UI thread whenever a variant starts. */
        void onVariant(Scene scene, Scene.Variant variant);
    }

    private static final long VARIANT_MS = 4 * 60 * 1000;

    private final Scene scene;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private Listener listener;
    private volatile int variant;
    private volatile boolean pendingSwitch;
    private final java.util.Random random = new java.util.Random();
    private final java.util.ArrayList<Integer> queue = new java.util.ArrayList<>(), history = new java.util.ArrayList<>();
    private boolean created, needSelect;
    private long variantStart, lastFrame;
    private float fade;
    private int dimProgram;

    SceneView(Context context, Scene scene, int variant) {
        super(context);
        this.scene = scene;
        this.variant = variant;
        setEGLContextClientVersion(3);
        setPreserveEGLContextOnPause(true);
        float k = scene.surfaceScale();
        if (k < 1f) {
            android.util.DisplayMetrics m = context.getResources().getDisplayMetrics();
            getHolder().setFixedSize(Math.round(m.widthPixels * k), Math.round(m.heightPixels * k));
        }
        setRenderer(this);
    }

    void setListener(Listener l) {
        listener = l;
    }

    Scene scene() {
        return scene;
    }

    int variant() {
        return variant;
    }

    synchronized void next() {
        history.add(variant);
        if (history.size() > 50) history.remove(0);
        if (queue.isEmpty()) {
            for (int k = 0; k < scene.variants().length; k++) queue.add(k);
            java.util.Collections.shuffle(queue, random);
            queue.remove(Integer.valueOf(variant));
        }
        if (!queue.isEmpty()) variant = queue.remove(0);
        pendingSwitch = true;
    }

    synchronized void previous() {
        if (history.isEmpty()) return;
        queue.add(0, variant);  // so Right comes back to it
        variant = history.remove(history.size() - 1);
        pendingSwitch = true;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        announce();
    }

    @Override
    protected void onDetachedFromWindow() {
        // The GL thread exits here and its context goes with it; nothing to release by hand.
        super.onDetachedFromWindow();
    }

    private void announce() {
        final Scene.Variant v = scene.variants()[variant];
        ui.post(() -> {
            if (listener != null) listener.onVariant(scene, v);
        });
    }

    // ---- GL thread ----

    @Override
    public void onSurfaceCreated(GL10 unused, EGLConfig config) {
        // A new context: everything the scene made in the old one is gone.
        scene.create();
        created = true;
        needSelect = true;
        dimProgram = 0;
        fade = 0f;
    }

    @Override
    public void onSurfaceChanged(GL10 unused, int w, int h) {
        GLES30.glViewport(0, 0, w, h);
        scene.resize(w, h);
        if (needSelect) {
            needSelect = false;
            start(SystemClock.uptimeMillis());
        }
    }

    private void start(long now) {
        scene.select(variant);
        variantStart = now;
    }

    @Override
    public void onDrawFrame(GL10 unused) {
        long now = SystemClock.uptimeMillis();
        long wait = 1000 / scene.fps() - (now - lastFrame);
        if (wait > 0) {
            try {
                Thread.sleep(wait);
            } catch (InterruptedException ignored) {
            }
            now = SystemClock.uptimeMillis();
        }
        lastFrame = now;
        if (!created || needSelect) return;
        if (!pendingSwitch && now - variantStart > VARIANT_MS) next();
        if (pendingSwitch) {
            fade = Math.max(0f, fade - 0.08f);
            if (fade == 0f) {
                pendingSwitch = false;
                start(now);
                announce();
            }
        } else if (fade < 1f) {
            fade = Math.min(1f, fade + 0.03f);
        }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0);
        GLES30.glViewport(0, 0, scene.width, scene.height);
        scene.draw((now - variantStart) / 1000f);
        if (fade < 1f) dim(1f - fade);
    }

    /** Black overlay for the fades. */
    private void dim(float amount) {
        if (dimProgram == 0) {
            dimProgram = Gl.program(Gl.FULLSCREEN, "#version 300 es\nprecision mediump float;\nuniform float a;\n"
                    + "out vec4 o;\nvoid main() { o = vec4(0.0, 0.0, 0.0, a); }\n");
        }
        GLES30.glEnable(GLES30.GL_BLEND);
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA);
        GLES30.glUseProgram(dimProgram);
        GLES30.glUniform1f(GLES30.glGetUniformLocation(dimProgram, "a"), amount);
        Gl.drawFullscreen();
        GLES30.glDisable(GLES30.GL_BLEND);
    }
}
