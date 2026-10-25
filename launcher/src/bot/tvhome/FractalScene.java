package bot.tvhome;

import static android.opengl.GLES30.*;

import java.util.Random;

/**
 * Escape-time fractals, explored by panning along their edges.
 *
 * Each location is rendered once at twice the screen's resolution (four samples per pixel) into
 * a toroidal buffer slightly larger than the screen. The camera only pans, exactly one buffer
 * texel (half a screen pixel) per frame along one of eight directions, so motion is
 * perfectly even and every screen pixel is always an exact average of four samples: sharp,
 * steady, and only a thin strip of new samples to compute each frame.
 *
 * Variants are named places (Seahorse Valley and friends), explored on a tether around their
 * spot, plus a wanderer that picks random stretches of edge. Where to go is decided on the CPU in double precision: a location is
 * a random point whose neighbourhood straddles the set's boundary, and while panning the camera
 * probes directions ahead and steers toward the one with the most boundary in it. After a while,
 * or if it wanders into empty space, it fades out and finds somewhere new.
 *
 * Scales stay above ~3e-3 of the plane per screen: below that 32-bit floats on the GPU cannot
 * separate neighbouring samples.
 */
final class FractalScene extends Scene {

    private static final int SS = 2;            // samples per screen pixel, per axis (4 per pixel)
    private static final int MARGIN = 64;       // buffer texels beyond the view on each side
    private static final int SHIFT = 8;         // texels the buffer window moves at a time (small: no hitches)
    private static final double LOCATION_SECONDS = 150, FADE_SECONDS = 1.5;

    private static final String STRIP =
            "#version 300 es\n"
            + "precision highp float;\n"
            + "uniform vec2 c0;\n"         // plane coordinate of texel (0, 0) for this strip
            + "uniform float pix;\n"       // plane units per texel
            + "uniform int mode, iterations;\n"
            + "uniform vec2 jc;\n"
            + "uniform vec3 tint;\n"
            + "out vec4 o;\n"
            + "void main() {\n"
            + "  vec2 p = c0 + gl_FragCoord.xy * pix;\n"
            + "  vec2 z, c;\n"
            + "  if (mode == 1) { z = p; c = jc; } else { z = vec2(0.0); c = p; }\n"
            + "  if (mode == 2) c.y = -c.y;\n"  // Burning Ship drawn upright
            + "  if (mode == 0) {\n"  // main cardioid and period-2 bulb: inside, skip the loop
            + "    float q = (c.x - 0.25) * (c.x - 0.25) + c.y * c.y;\n"
            + "    if (q * (q + (c.x - 0.25)) < 0.25 * c.y * c.y || dot(c + vec2(1.0, 0.0), c + vec2(1.0, 0.0)) < 0.0625) {\n"
            + "      o = vec4(0.0, 0.0, 0.0, 1.0); return;\n"
            + "    }\n"
            + "  }\n"
            + "  vec2 dz = mode == 1 ? vec2(1.0, 0.0) : vec2(0.0);\n"
            + "  float r2 = 0.0;\n"
            + "  int n = 0;\n"
            + "  for (int i = 0; i < 4000; i++) {\n"
            + "    if (i >= iterations) break;\n"
            + "    if (mode == 2) z = abs(z);\n"
            + "    dz = 2.0 * vec2(z.x * dz.x - z.y * dz.y, z.x * dz.y + z.y * dz.x) + (mode == 1 ? vec2(0.0) : vec2(1.0, 0.0));\n"
            + "    z = vec2(z.x * z.x - z.y * z.y, 2.0 * z.x * z.y) + c;\n"
            + "    r2 = dot(z, z);\n"
            + "    if (r2 > 65536.0) break;\n"
            + "    n++;\n"
            + "  }\n"
            + "  if (n >= iterations) { o = vec4(0.0, 0.0, 0.0, 1.0); return; }\n"
            + "  float mu = float(n) + 1.0 - log2(log2(r2) * 0.5);\n"
            + "  vec3 col = 0.5 + 0.5 * cos(6.2831853 * (mu * 0.02 + tint));\n"
            + "  col = pow(col, vec3(1.4));\n"
            + "  // Distance to the set in screen pixels: filaments glow, open space stays dark.\n"
            + "  float dist = 0.5 * sqrt(r2) * log(r2) / max(length(dz), 1e-20);\n"
            + "  float px = dist / (pix * " + SS + ".0);\n"
            + "  float glow = exp(-px * 0.12) + 0.06 * exp(-px * 0.01);\n"
            + "  o = vec4(col * min(glow, 1.0) * 0.95, 1.0);\n"
            + "}\n";

    private static final String SHOW =
            "#version 300 es\n"
            + "precision highp float;\n"
            + "uniform sampler2D buf;\n"
            + "uniform vec2 cam, size;\n"  // view origin in buffer texels (wrapped), buffer size
            + "uniform float fade;\n"
            + "out vec4 o;\n"
            + "void main() {\n"
            + "  // The shared corner of this pixel's 2x2 samples: one bilinear read is their exact\n"
            + "  // average. (3x3 needs nine reads a pixel, which this GPU cannot spare: it starves\n"
            + "  // the rest of the UI.)\n"
            + "  vec2 t = cam + gl_FragCoord.xy * " + SS + ".0;\n"
            + "  o = vec4(texture(buf, t / size).rgb * fade, 1.0);\n"
            + "}\n";

    private static final float[][] TINTS = {
        {0.0f, 0.12f, 0.25f}, {0.05f, 0.1f, 0.2f}, {0.55f, 0.65f, 0.75f}, {0.3f, 0.2f, 0.1f}, {0.0f, 0.08f, 0.18f},
    };

    /** A named place: which fractal, where, how wide a screen is, and (for Julia sets) c. */
    private static final class Place {
        final String name, credit, blurb;
        final int mode;  // 0 Mandelbrot, 1 Julia, 2 Burning Ship; -1 wander anywhere
        final double x, y, scale, jx, jy;

        Place(String name, String credit, String blurb, int mode, double x, double y, double scale, double jx, double jy) {
            this.name = name;
            this.credit = credit;
            this.blurb = blurb;
            this.mode = mode;
            this.x = x;
            this.y = y;
            this.scale = scale;
            this.jx = jx;
            this.jy = jy;
        }
    }

    private static final String MANDELBROT = "Benoît Mandelbrot, 1980";
    private static final Place[] PLACES = {
        new Place("Seahorse Valley", MANDELBROT, "The Mandelbrot set, between the main cardioid and its largest bulb", 0, -0.7453, 0.1127, 0.03, 0, 0),
        new Place("Elephant Valley", MANDELBROT, "The Mandelbrot set, at the cardioid's cusp", 0, 0.2750, 0.0070, 0.03, 0, 0),
        new Place("Triple Spiral Valley", MANDELBROT, "The Mandelbrot set, beside the period-3 bulb", 0, -0.0881, 0.6550, 0.015, 0, 0),
        new Place("Scepter Valley", MANDELBROT, "The Mandelbrot set, between two bulbs on the real axis", 0, -1.3710, 0.0120, 0.009, 0, 0),
        new Place("The antenna's mini Mandelbrot", MANDELBROT, "A complete copy of the set at c = −1.7549", 0, -1.7549, 0.0, 0.03, 0, 0),
        new Place("Douady's rabbit", "Adrien Douady", "The Julia set of c = −0.123 + 0.745i, where its three ears meet", 1, -0.2766, 0.4797, 0.3, -0.123, 0.745),
        new Place("San Marco", "Gaston Julia, 1918", "The Julia set of c = −0.75", 1, -0.6, 0.4, 0.45, -0.75, 0.0),
        new Place("Siegel disk", "Carl Ludwig Siegel", "The Julia set of c = −0.391 − 0.587i", 1, 0.0, 0.0, 0.6, -0.390541, -0.586788),
        new Place("Burning Ship", "Michelitsch and Rössler, 1992", "z ↦ (|Re z| + i|Im z|)² + c", 2, -1.7620, 0.0280, 0.08, 0, 0),
        new Place("Wandering", "Mandelbrot, Julia, Burning Ship", "A random stretch of edge, somewhere new every few minutes", -1, 0, 0, 0, 0, 0),
    };

    /** Eight pan directions, in buffer texels per frame. */
    private static final int[][] DIRS = {{1, 0}, {1, 1}, {0, 1}, {-1, 1}, {-1, 0}, {-1, -1}, {0, -1}, {1, -1}};

    private final Random random = new Random();
    private int mode;
    private double homeX, homeY;
    private int dir, targetDir;
    private float lastTurn;
    private boolean tethered;
    private int strip, show;
    private Gl.Target buffer;
    private int bw, bh, vw, vh;

    // Where we are: plane coordinate of world texel (0, 0), plane units per texel, Julia c.
    private double originX, originY, pix, jx, jy;
    private int iterations;
    private float[] tint = TINTS[0];

    // Camera (world texels, bottom-left of the view), the buffer's window, steering.
    private double camX, camY;
    private long wx0, wy0;
    private int fillRow;
    private int phase;  // 0 filling, 1 fading in, 2 exploring, 3 fading out
    private float phaseStart, locationStart, lastSteer, lastT = -1;
    private int lost;

    @Override
    String title() {
        return "Fractals";
    }

    @Override
    Variant[] variants() {
        Variant[] v = new Variant[PLACES.length];
        for (int i = 0; i < v.length; i++) v[i] = new Variant(PLACES[i].name, PLACES[i].credit, PLACES[i].blurb);
        return v;
    }

    @Override
    int fps() {
        return 30;  // one texel (half a pixel) a frame: ~15 px/s
    }

    @Override
    void create() {
        buffer = null;  // any old one belonged to a context that no longer exists
        strip = Gl.program(Gl.FULLSCREEN, STRIP);
        show = Gl.program(Gl.FULLSCREEN, SHOW);
    }

    @Override
    void resize(int w, int h) {
        boolean same = buffer != null && w == width && h == height;
        super.resize(w, h);
        if (same) return;
        if (buffer != null) buffer.release();
        vw = w * SS;
        vh = h * SS;
        bw = vw + 2 * MARGIN;
        bh = vh + 2 * MARGIN;
        buffer = new Gl.Target(GL_RGBA8, bw, bh, GL_RGBA, GL_UNSIGNED_BYTE, GL_LINEAR, GL_REPEAT);
        // A new buffer is blank: if we were somewhere, render it again from scratch (only the
        // strips entering view would otherwise ever be computed, leaving black blocks).
        if (pix > 0) relocate();
    }

    @Override
    void select(int v) {
        super.select(v);
        relocate();
    }

    // ---- choosing where to look (CPU, double precision) ----

    /** Smooth escape count at a plane point, or -1 inside the set. */
    private double escape(double x, double y, int max) {
        double zx, zy, cx, cy;
        if (mode == 0) {
            // Skip the main cardioid and the period-2 bulb: most of the interior, the slow part.
            double q = (x - 0.25) * (x - 0.25) + y * y;
            if (q * (q + (x - 0.25)) < 0.25 * y * y || (x + 1) * (x + 1) + y * y < 0.0625) return -1;
        }
        if (mode == 1) {
            zx = x;
            zy = y;
            cx = jx;
            cy = jy;
        } else {
            zx = zy = 0;
            cx = x;
            cy = mode == 2 ? -y : y;
        }
        for (int i = 0; i < max; i++) {
            if (mode == 2) {
                zx = Math.abs(zx);
                zy = Math.abs(zy);
            }
            double nx = zx * zx - zy * zy + cx;
            zy = 2 * zx * zy + cy;
            zx = nx;
            double r2 = zx * zx + zy * zy;
            if (r2 > 65536) return i + 1 - Math.log(Math.log(r2) * 0.5) / Math.log(2);
        }
        return -1;
    }

    /** How much boundary an n x n grid of samples spanning `size` sees: 0 (none) to 1. */
    private double boundary(double x, double y, double size, int n, int max) {
        double[] v = new double[n * n];
        for (int j = 0; j < n; j++) {
            for (int i = 0; i < n; i++) {
                v[j * n + i] = escape(x + (i / (n - 1.0) - 0.5) * size, y + (j / (n - 1.0) - 0.5) * size, max);
            }
        }
        int edges = 0, total = 0;
        for (int j = 0; j < n; j++) {
            for (int i = 0; i < n; i++) {
                double a = v[j * n + i];
                if (i + 1 < n) {
                    edges += differs(a, v[j * n + i + 1]) ? 1 : 0;
                    total++;
                }
                if (j + 1 < n) {
                    edges += differs(a, v[(j + 1) * n + i]) ? 1 : 0;
                    total++;
                }
            }
        }
        return edges / (double) total;
    }

    private static boolean differs(double a, double b) {
        if ((a < 0) != (b < 0)) return true;
        return a >= 0 && Math.abs(a - b) > 1.5;
    }

    private static double logUniform(Random r, double lo, double hi) {
        return Math.exp(Math.log(lo) + r.nextDouble() * (Math.log(hi) - Math.log(lo)));
    }

    /** Pick a spot on the edge (near this variant's place, or anywhere) and start rendering it. */
    private void relocate() {
        long t0 = android.os.SystemClock.uptimeMillis();
        Place p = PLACES[variant];
        tethered = p.mode >= 0;
        double cx, cy, scale;
        if (tethered) {
            mode = p.mode;
            jx = p.jx;
            jy = p.jy;
            homeX = p.x;
            homeY = p.y;
            scale = p.scale;
            // Start at the place itself, or the nearby spot with the most boundary in view.
            cx = p.x;
            cy = p.y;
            int max = maxIterations(scale);
            double best = boundary(cx, cy, scale * 1.6, 6, max);
            for (int k = 0; k < 40 && best < 0.25; k++) {
                double x = p.x + (random.nextDouble() - 0.5) * scale, y = p.y + (random.nextDouble() - 0.5) * scale;
                double score = boundary(x, y, scale * 1.6, 6, max);
                if (score > best) {
                    best = score;
                    cx = x;
                    cy = y;
                }
            }
        } else {
            mode = random.nextInt(3);
            if (mode == 1) {
                // A Julia parameter from the Mandelbrot set's edge gives a connected, intricate set.
                mode = 0;
                for (int k = 0; k < 400; k++) {
                    double x = -2.0 + random.nextDouble() * 2.5, y = -1.2 + random.nextDouble() * 2.4;
                    if (boundary(x, y, 0.02, 5, 300) > 0.3) {
                        jx = x;
                        jy = y;
                        break;
                    }
                }
                mode = 1;
            }
            double bx0 = mode == 1 ? -1.7 : -2.2, bx1 = mode == 1 ? 1.7 : 1.0;
            double by0 = mode == 1 ? -1.2 : -1.3, by1 = mode == 1 ? 1.2 : mode == 2 ? 2.0 : 1.3;
            cx = -0.75;
            cy = 0.1;
            scale = 0.5;
            for (int k = 0; k < 400; k++) {
                double s = mode == 1 ? logUniform(random, 0.01, 0.4) : logUniform(random, 3e-3, 0.06);
                double x = bx0 + random.nextDouble() * (bx1 - bx0), y = by0 + random.nextDouble() * (by1 - by0);
                double score = boundary(x, y, s * 1.6, 6, maxIterations(s));
                if (score > 0.2 && score < 0.85) {
                    cx = x;
                    cy = y;
                    scale = s;
                    break;
                }
            }
            homeX = cx;
            homeY = cy;
        }
        iterations = maxIterations(scale);
        pix = scale / vh;
        // World texel (0, 0) is the middle of the view.
        originX = cx;
        originY = cy;
        camX = -vw / 2;
        camY = -vh / 2;
        wx0 = (long) camX - MARGIN;
        wy0 = (long) camY - MARGIN;
        dir = targetDir = random.nextInt(DIRS.length);
        tint = TINTS[random.nextInt(TINTS.length)];
        fillRow = 0;
        phase = 0;
        lost = 0;
        fillStarted = android.os.SystemClock.uptimeMillis();
        android.util.Log.d("Fractal", PLACES[variant].name + ": found a spot in " + (fillStarted - t0) + " ms");
    }

    private long fillStarted;

    private static int maxIterations(double screenHeight) {
        return (int) Math.min(1500, 250 + 80 * Math.log(0.5 / screenHeight) / Math.log(2));
    }

    // ---- rendering into the toroidal buffer ----

    /** Compute world texels [x, x + w) x [y, y + h) into wherever they live in the buffer. */
    private void renderWorld(long x, long y, int w, int h) {
        glBindFramebuffer(GL_FRAMEBUFFER, buffer.framebuffer);
        glUseProgram(strip);
        glUniform1f(glGetUniformLocation(strip, "pix"), (float) pix);
        glUniform1i(glGetUniformLocation(strip, "mode"), mode);
        glUniform1i(glGetUniformLocation(strip, "iterations"), iterations);
        glUniform2f(glGetUniformLocation(strip, "jc"), (float) jx, (float) jy);
        glUniform3f(glGetUniformLocation(strip, "tint"), tint[0], tint[1], tint[2]);
        int c0 = glGetUniformLocation(strip, "c0");
        glEnable(GL_SCISSOR_TEST);
        for (long yy = y; yy < y + h; ) {
            int ty = (int) Math.floorMod(yy, (long) bh);
            int sh = (int) Math.min(y + h - yy, bh - ty);
            for (long xx = x; xx < x + w; ) {
                int tx = (int) Math.floorMod(xx, (long) bw);
                int sw = (int) Math.min(x + w - xx, bw - tx);
                glViewport(0, 0, bw, bh);
                glScissor(tx, ty, sw, sh);
                // gl_FragCoord in this piece is buffer texel space; shift it to the world texel.
                glUniform2f(c0, (float) (originX + (xx - tx) * pix), (float) (originY + (yy - ty) * pix));
                Gl.drawFullscreen();
                xx += sw;
            }
            yy += sh;
        }
        glDisable(GL_SCISSOR_TEST);
    }

    /** Move the buffer's window so the view stays inside it, computing what comes into it. */
    private void keepInWindow() {
        for (int guard = 0; guard < 4; guard++) {
            if (camX < wx0 + MARGIN / 2.0) {
                renderWorld(wx0 - SHIFT, wy0, SHIFT, bh);
                wx0 -= SHIFT;
            } else if (camX + vw > wx0 + bw - MARGIN / 2.0) {
                renderWorld(wx0 + bw, wy0, SHIFT, bh);
                wx0 += SHIFT;
            } else if (camY < wy0 + MARGIN / 2.0) {
                renderWorld(wx0, wy0 - SHIFT, bw, SHIFT);
                wy0 -= SHIFT;
            } else if (camY + vh > wy0 + bh - MARGIN / 2.0) {
                renderWorld(wx0, wy0 + bh, bw, SHIFT);
                wy0 += SHIFT;
            } else {
                return;
            }
        }
    }

    /**
     * Probe the eight directions ahead and pick the one with the most boundary; named places
     * keep the camera within a couple of screens of home.
     */
    private void steer() {
        double screen = pix * vh;
        double cx = originX + (camX + vw / 2.0) * pix, cy = originY + (camY + vh / 2.0) * pix;
        double best = -1, bestScore = 0;
        int bestDir = dir;
        for (int k = 0; k < DIRS.length; k++) {
            double len = Math.hypot(DIRS[k][0], DIRS[k][1]);
            double ux = DIRS[k][0] / len, uy = DIRS[k][1] / len;
            double px = cx + ux * 0.6 * screen, py = cy + uy * 0.6 * screen;
            double score = boundary(px, py, 0.35 * screen, 5, iterations);
            // Prefer to keep going the same way; turning around is a last resort.
            double turn = Math.min(Math.abs(k - dir), DIRS.length - Math.abs(k - dir));
            double value = score - 0.05 * turn;
            if (tethered) value -= 0.2 * Math.max(0, Math.hypot(px - homeX, py - homeY) / screen - 1.5);
            if (value > best) {
                best = value;
                bestDir = k;
                bestScore = score;
            }
        }
        targetDir = bestDir;
        lost = bestScore < 0.03 ? lost + 1 : 0;
    }

    @Override
    void draw(float t) {
        float dt = lastT < 0 || t < lastT ? 0 : Math.min(0.1f, t - lastT);
        lastT = t;
        float fade;
        if (phase == 0) {
            // Fill the buffer a band at a time while the screen is dark.
            int band = Math.max(16, 250_000 / bw);
            renderWorld(wx0, wy0 + fillRow, bw, Math.min(band, bh - fillRow));
            fillRow += band;
            if (fillRow >= bh) {
                android.util.Log.d("Fractal", "filled " + bw + "x" + bh + " in "
                        + (android.os.SystemClock.uptimeMillis() - fillStarted) + " ms");
                phase = 1;
                phaseStart = locationStart = lastSteer = t;
            }
            fade = 0;
        } else {
            if (t - lastSteer > 1) {
                lastSteer = t;
                steer();
            }
            // Change course at most every few seconds, so the pan reads as deliberate.
            if (targetDir != dir && t - lastTurn > 4) {
                dir = targetDir;
                lastTurn = t;
            }
            camX += DIRS[dir][0];
            camY += DIRS[dir][1];
            keepInWindow();
            if (phase == 1) {
                fade = Math.min(1f, (t - phaseStart) / (float) FADE_SECONDS);
                if (fade >= 1) phase = 2;
            } else if (phase == 2) {
                fade = 1;
                if (t - locationStart > LOCATION_SECONDS || lost >= 4) {
                    phase = 3;
                    phaseStart = t;
                }
            } else {
                fade = Math.max(0f, 1 - (t - phaseStart) / (float) FADE_SECONDS);
                if (fade <= 0) relocate();
            }
        }

        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        glViewport(0, 0, width, height);
        glUseProgram(show);
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, buffer.texture);
        glUniform1i(glGetUniformLocation(show, "buf"), 0);
        // The camera sits on whole texels, so each pixel is always an exact average of four
        // samples (at a fractional offset bilinear would mix in neighbours and shimmer).
        glUniform2f(glGetUniformLocation(show, "cam"), (float) floorModD(camX, bw), (float) floorModD(camY, bh));
        glUniform2f(glGetUniformLocation(show, "size"), bw, bh);
        glUniform1f(glGetUniformLocation(show, "fade"), fade);
        Gl.drawFullscreen();
    }

    private static double floorModD(double v, int m) {
        double r = v % m;
        return r < 0 ? r + m : r;
    }

    @Override
    void release() {
        if (buffer != null) buffer.release();
        buffer = null;
        glDeleteProgram(strip);
        glDeleteProgram(show);
    }
}
