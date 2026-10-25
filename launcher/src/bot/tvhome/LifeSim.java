package bot.tvhome;

import android.content.res.AssetManager;
import android.os.Process;
import android.os.SystemClock;

import java.io.InputStreamReader;

/**
 * Runs the Life engine on its own thread, so a slow HashLife step (tens of milliseconds for a
 * big soup) delays the pattern, never a frame. Each tick it advances the generations owed by
 * elapsed time, finds the box worth framing, and publishes an immutable Snapshot. Quadtree nodes
 * never change once built, so the renderer can walk a snapshot's tree while the next generation
 * is being computed; even a garbage collection only builds a new tree beside the old one.
 */
final class LifeSim {

    static final class Snapshot {
        final int epoch;            // which load() this belongs to
        final boolean failed;       // that pattern could not be loaded
        final Life.Tree tree;       // the quadtree as of this snapshot (tree.root)
        final long generation, population;
        final int collections;      // node ids are only comparable within one value of this
        final long[] box;           // {x0, y0, x1, y1} worth framing, or null
        final int boxSeq;           // bumped whenever box is recomputed
        final double boxTime;       // when it was, in seconds of uptime

        Snapshot(int epoch, boolean failed, Life.Tree tree, long generation, long population, int collections,
                 long[] box, int boxSeq, double boxTime) {
            this.epoch = epoch;
            this.failed = failed;
            this.tree = tree;
            this.generation = generation;
            this.population = population;
            this.collections = collections;
            this.box = box;
            this.boxSeq = boxSeq;
            this.boxTime = boxTime;
        }
    }

    private static final long TICK_MS = 16;
    private static final long BOX_MS = 50;

    private final Life life = new Life();
    private final AssetManager assets;
    private final Object lock = new Object();
    private Thread thread;
    private boolean running, paused;

    // Requests from the renderer (under lock).
    private Catalog.Entry pendingEntry;
    private int pendingEpoch;

    /** The renderer's view width in cells, which sets how fine the framing box is measured. */
    volatile double viewCells = 1000;
    private volatile Snapshot latest;

    // Sim-thread state.
    private Catalog.Entry entry;
    private int epoch;
    private long startedAt, lastAdvance, lastBox;
    private double genDebt;
    private long[] box;
    private int boxSeq;
    private double boxTime;

    LifeSim(AssetManager assets) {
        this.assets = assets;
    }

    Snapshot latest() {
        return latest;
    }

    /** Start a pattern; its first snapshot carries this epoch. */
    void load(Catalog.Entry e, int epoch) {
        synchronized (lock) {
            pendingEntry = e;
            pendingEpoch = epoch;
            lock.notifyAll();
        }
    }

    void start() {
        synchronized (lock) {
            if (thread != null) return;
            running = true;
            thread = new Thread(this::run, "LifeSim");
            thread.start();
        }
    }

    void stop() {
        Thread t;
        synchronized (lock) {
            running = false;
            t = thread;
            thread = null;
            lock.notifyAll();
        }
        if (t != null) {
            try {
                t.join(500);
            } catch (InterruptedException ignored) {
            }
        }
    }

    void setPaused(boolean p) {
        synchronized (lock) {
            paused = p;
            lock.notifyAll();
        }
    }

    private void run() {
        // Below the UI and render threads: if the CPU is short, the pattern runs late, not the frames.
        Process.setThreadPriority(Process.THREAD_PRIORITY_DEFAULT + 2);
        while (true) {
            Catalog.Entry req;
            int reqEpoch;
            synchronized (lock) {
                while (running && (paused || entry == null) && pendingEntry == null) {
                    try {
                        lock.wait();
                    } catch (InterruptedException ignored) {
                    }
                }
                if (!running) return;
                req = pendingEntry;
                reqEpoch = pendingEpoch;
                pendingEntry = null;
                if (req == null && paused) continue;
            }
            long tick = SystemClock.uptimeMillis();
            if (req != null) {
                open(req, reqEpoch);
                continue;
            }
            advance();
            long wait = TICK_MS - (SystemClock.uptimeMillis() - tick);
            if (wait > 0) {
                synchronized (lock) {
                    if (running && pendingEntry == null) {
                        try {
                            lock.wait(wait);
                        } catch (InterruptedException ignored) {
                        }
                    }
                }
            }
        }
    }

    private void open(Catalog.Entry e, int ep) {
        epoch = ep;
        try (InputStreamReader in = new InputStreamReader(assets.open("life/" + e.file))) {
            Patterns.load(life, in);
        } catch (Exception ex) {
            android.util.Log.w("Home", "cannot load " + e.file, ex);
            entry = null;
            latest = new Snapshot(ep, true, null, 0, 0, 0, null, 0, 0);
            return;
        }
        entry = e;
        startedAt = lastAdvance = SystemClock.uptimeMillis();
        genDebt = 0;
        lastBox = 0;
        box = null;
        aim();
        publish();
    }

    private void advance() {
        long now = SystemClock.uptimeMillis();
        double dt = Math.min(0.25, (now - lastAdvance) / 1000.0);
        lastAdvance = now;
        // Ease in: start slow so the opening is readable, reach full speed after ~8 s.
        // Generations are owed by elapsed time, so a slow step does not slow the pattern.
        double ramp = Math.min(1.0, 0.15 + (now - startedAt) / 8000.0);
        genDebt += entry.speed * ramp * dt;
        if (genDebt >= 1) {
            int s = 63 - Long.numberOfLeadingZeros((long) genDebt);
            life.step(s);
            genDebt -= 1L << s;
        }
        if (now - lastBox >= BOX_MS) aim();
        publish();
    }

    private void aim() {
        lastBox = SystemClock.uptimeMillis();
        // Histogram blocks at most 1/128 of the current view, so the box moves in fine steps.
        int grain = Math.max(0, (int) Math.floor(Math.log(viewCells / 128) / Math.log(2)));
        // Soups: frame the main cluster, so escaping gliders don't drag the camera out.
        long[] b = entry.soup ? life.coreBox(3, 3) : life.massBox(0.02, grain);
        if (b == null) return;
        box = b;
        boxSeq++;
        boxTime = lastBox / 1000.0;
    }

    private void publish() {
        // The renderer cuts the world into tiles of up to 2^(level + 4) cells: keep the root at
        // least that big (more empty space around the pattern changes nothing).
        life.ensureLevel(24);
        latest = new Snapshot(epoch, false, life.tree(), life.generation, life.population(), life.collections,
                box, boxSeq, boxTime);
    }
}
