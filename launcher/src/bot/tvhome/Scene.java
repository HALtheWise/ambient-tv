package bot.tvhome;

import android.content.Context;

/** One ambient scene with named variants. All methods except the getters run on the GL thread. */
abstract class Scene {

    static final class Variant {
        final String name, credit, blurb;

        Variant(String name, String credit) {
            this(name, credit, null);
        }

        /** credit: who and when; blurb: what to notice (shown only on request). */
        Variant(String name, String credit, String blurb) {
            this.name = name;
            this.credit = credit;
            this.blurb = blurb;
        }
    }

    protected Context context;
    protected int width, height;
    protected int variant;

    abstract String title();

    /** Names of two live counters shown under the caption, or null for none. */
    String[] counters() {
        return null;
    }

    /** Current value of counter i (read from the UI thread every frame). */
    long counter(int i) {
        return 0;
    }

    /** Frame rate to run at; simulations that advance per frame keep the default. */
    int fps() {
        return 30;
    }

    /**
     * Resolution of the drawing surface relative to the screen; below 1 the display hardware
     * scales it up, which costs the (weak, shared) GPU nothing.
     */
    float surfaceScale() {
        return 1f;
    }

    abstract Variant[] variants();

    /** Create GL resources. */
    abstract void create();

    void resize(int w, int h) {
        width = w;
        height = h;
    }

    /** Switch variant; also called once after create(). */
    void select(int v) {
        variant = v;
    }

    /** Draw one frame to the default framebuffer; t is seconds since the variant started. */
    abstract void draw(float t);

    abstract void release();

}
