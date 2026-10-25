package bot.tvhome;

/**
 * Conway's Game of Life with Bill Gosper's HashLife (1984): the universe is a quadtree of
 * hash-consed nodes, and each node memoizes its own future, so large or repetitive patterns
 * advance by huge steps cheaply. No Android dependencies, so it can be tested on a desktop JVM.
 *
 * The root is always centred on the origin: a node of level L covers [-2^(L-1), 2^(L-1)).
 *
 * Nodes live in parallel primitive arrays, a node being an index (0 the dead cell, 1 the live
 * one), not in objects: a big pattern holds a million or more nodes, and as objects they kept
 * the garbage collector copying a hundred megabytes over and over, slowing every thread. Arrays
 * of ints are never traced. Nodes are only ever appended, and a collection builds fresh arrays,
 * so a Tree (the arrays and a root, as of one moment) stays readable from another thread while
 * this Life moves on.
 */
final class Life {

    /** The quadtree as of one moment: read-only, safe to walk on any thread. */
    static final class Tree {
        final int[] nw, ne, sw, se;
        final byte[] level;
        final long[] pop;
        final int root;

        Tree(int[] nw, int[] ne, int[] sw, int[] se, byte[] level, long[] pop, int root) {
            this.nw = nw;
            this.ne = ne;
            this.sw = sw;
            this.se = se;
            this.level = level;
            this.pop = pop;
            this.root = root;
        }

        int level(int n) {
            return level[n];
        }
    }

    static final int DEAD = 0, ALIVE = 1;
    private static final int MAX_NODES = 2_000_000;

    // Node store. Leaves (0, 1) have no children and are never in the hash table.
    private int[] nw, ne, sw, se, next, result;
    private byte[] level, resultStep;
    private long[] pop;
    private int count;
    private int[] table;   // bucket heads (0: empty; leaves are never hashed)
    private final int[] empties = new int[64];

    int root;
    long generation;
    /**
     * Bumped by every collect() and clear(): node indices are reassigned then, so callers comparing
     * them can tell. (A new pattern's node 764 is not the last one's.)
     */
    int collections;

    Life() {
        alloc(1 << 16);
        clear();
    }

    private void alloc(int capacity) {
        nw = new int[capacity];
        ne = new int[capacity];
        sw = new int[capacity];
        se = new int[capacity];
        next = new int[capacity];
        result = new int[capacity];
        level = new byte[capacity];
        resultStep = new byte[capacity];
        pop = new long[capacity];
        table = new int[Math.max(1 << 12, Integer.highestOneBit(capacity) * 2)];
        java.util.Arrays.fill(empties, -1);
        // The two leaves.
        pop[DEAD] = 0;
        pop[ALIVE] = 1;
        resultStep[DEAD] = resultStep[ALIVE] = -1;
        count = 2;
    }

    /** The tree as it is now (for another thread). */
    Tree tree() {
        return new Tree(nw, ne, sw, se, level, pop, root);
    }

    int level(int n) {
        return level[n];
    }

    long pop(int n) {
        return pop[n];
    }

    // ---- hash-consing ----

    private static int mix(int a, int b, int c, int d) {
        int h = a * 0x9E3779B1;
        h = (h ^ (h >>> 15) ^ b) * 0x85EBCA77;
        h = (h ^ (h >>> 13) ^ c) * 0xC2B2AE3D;
        h = (h ^ (h >>> 16) ^ d) * 0x27D4EB2F;
        return h ^ (h >>> 15);
    }

    int node(int a, int b, int c, int d) {
        int i = mix(a, b, c, d) & (table.length - 1);
        for (int n = table[i]; n != 0; n = next[n]) {
            if (nw[n] == a && ne[n] == b && sw[n] == c && se[n] == d) return n;
        }
        if (count == nw.length) grow();
        int n = count++;
        nw[n] = a;
        ne[n] = b;
        sw[n] = c;
        se[n] = d;
        level[n] = (byte) (level[a] + 1);
        pop[n] = pop[a] + pop[b] + pop[c] + pop[d];
        resultStep[n] = -1;
        next[n] = table[i];
        table[i] = n;
        if (count > table.length * 3 / 4 && table.length < (1 << 24)) rehash(table.length * 2);
        return n;
    }

    /** More room: fresh, larger arrays (the old ones stay valid for any Tree still using them). */
    private void grow() {
        int cap = nw.length * 2;
        nw = java.util.Arrays.copyOf(nw, cap);
        ne = java.util.Arrays.copyOf(ne, cap);
        sw = java.util.Arrays.copyOf(sw, cap);
        se = java.util.Arrays.copyOf(se, cap);
        next = java.util.Arrays.copyOf(next, cap);
        result = java.util.Arrays.copyOf(result, cap);
        level = java.util.Arrays.copyOf(level, cap);
        resultStep = java.util.Arrays.copyOf(resultStep, cap);
        pop = java.util.Arrays.copyOf(pop, cap);
    }

    private void rehash(int size) {
        table = new int[size];
        for (int n = 2; n < count; n++) {
            int i = mix(nw[n], ne[n], sw[n], se[n]) & (size - 1);
            next[n] = table[i];
            table[i] = n;
        }
    }

    int empty(int lv) {
        if (lv == 0) return DEAD;
        int e = empties[lv];
        if (e < 0) {
            int c = empty(lv - 1);
            e = empties[lv] = node(c, c, c, c);
        }
        return e;
    }

    /** Drop every node not reachable from the root (and all memos), to bound memory. */
    private void collect() {
        collections++;
        int[] onw = nw, one = ne, osw = sw, ose = se;
        byte[] olevel = level;
        long[] opop = pop;
        int[] map = new int[count];
        java.util.Arrays.fill(map, -1);
        map[DEAD] = DEAD;
        map[ALIVE] = ALIVE;
        // Fresh arrays: a Tree on another thread may still be reading the old ones.
        alloc(Math.max(1 << 16, Integer.highestOneBit(Math.max(1, count / 2)) * 2));
        root = copy(root, onw, one, osw, ose, olevel, opop, map);
    }

    private int copy(int n, int[] onw, int[] one, int[] osw, int[] ose, byte[] olevel, long[] opop, int[] map) {
        int c = map[n];
        if (c >= 0) return c;
        int a = copy(onw[n], onw, one, osw, ose, olevel, opop, map);
        int b = copy(one[n], onw, one, osw, ose, olevel, opop, map);
        int d = copy(osw[n], onw, one, osw, ose, olevel, opop, map);
        int e = copy(ose[n], onw, one, osw, ose, olevel, opop, map);
        c = node(a, b, d, e);
        map[n] = c;
        return c;
    }

    // ---- editing ----

    void clear() {
        collections++;
        alloc(1 << 16);
        root = empty(3);
        generation = 0;
    }

    void set(long x, long y) {
        while (!inside(x, y)) root = expand(root);
        root = set(root, x, y);
    }

    private boolean inside(long x, long y) {
        long half = 1L << (level[root] - 1);
        return x >= -half && x < half && y >= -half && y < half;
    }

    /** x, y relative to this node's centre. */
    private int set(int n, long x, long y) {
        int a = nw[n], b = ne[n], c = sw[n], d = se[n];
        if (level[n] == 1) {
            if (y < 0) { if (x < 0) a = ALIVE; else b = ALIVE; }
            else { if (x < 0) c = ALIVE; else d = ALIVE; }
            return node(a, b, c, d);
        }
        long q = 1L << (level[n] - 2);
        if (y < 0) {
            if (x < 0) return node(set(a, x + q, y + q), b, c, d);
            return node(a, set(b, x - q, y + q), c, d);
        }
        if (x < 0) return node(a, b, set(c, x + q, y - q), d);
        return node(a, b, c, set(d, x - q, y - q));
    }

    /** Grow the root (with empty space) to at least this level. */
    void ensureLevel(int lv) {
        while (level[root] < lv) root = expand(root);
    }

    private int expand(int n) {
        int e = empty(level[n] - 1);
        int a = nw[n], b = ne[n], c = sw[n], d = se[n];
        int p = node(e, e, e, a), q = node(e, e, b, e), r = node(e, c, e, e), t = node(d, e, e, e);
        return node(p, q, r, t);
    }

    // ---- evolution ----

    private int center(int n) {
        int a = se[nw[n]], b = sw[ne[n]], c = ne[sw[n]], d = nw[se[n]];
        return node(a, b, c, d);
    }

    /** Level-2 node (4x4) to its level-1 centre after one generation, by brute force. */
    private int slow(int n) {
        int bits = 0;
        for (int y = 0; y < 4; y++) {
            for (int x = 0; x < 4; x++) {
                int q = y < 2 ? (x < 2 ? nw[n] : ne[n]) : (x < 2 ? sw[n] : se[n]);
                int c = (y & 1) == 0 ? ((x & 1) == 0 ? nw[q] : ne[q]) : ((x & 1) == 0 ? sw[q] : se[q]);
                if (c == ALIVE) bits |= 1 << (y * 4 + x);
            }
        }
        return node(successor(bits, 1, 1), successor(bits, 2, 1), successor(bits, 1, 2), successor(bits, 2, 2));
    }

    /** Cell (x, y) of a 4x4 bit board after one generation. */
    private static int successor(int bits, int x, int y) {
        int neighbours = 0;
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                if ((dx != 0 || dy != 0) && (bits & (1 << ((y + dy) * 4 + x + dx))) != 0) neighbours++;
            }
        }
        boolean on = (bits & (1 << (y * 4 + x))) != 0;
        return neighbours == 3 || on && neighbours == 2 ? ALIVE : DEAD;
    }

    /** The centre of n (one level down), advanced 2^s generations; requires s <= level - 2. */
    private int result(int n, int s) {
        if (resultStep[n] == s) return result[n];
        int r;
        int lv = level[n];
        if (pop[n] == 0) {
            r = empty(lv - 1);
        } else if (lv == 2) {
            r = slow(n);
        } else {
            // Read everything needed before node() can reallocate the arrays.
            int n00 = nw[n], n02 = ne[n], n20 = sw[n], n22 = se[n];
            int nwne = ne[n00], nwsw = sw[n00], nwse = se[n00];
            int nenw = nw[n02], nesw = sw[n02], nese = se[n02];
            int swnw = nw[n20], swne = ne[n20], swse = se[n20];
            int senw = nw[n22], sene = ne[n22], sesw = sw[n22];
            int n01 = node(nwne, nenw, nwse, nesw);
            int n10 = node(nwsw, nwse, swnw, swne);
            int n11 = node(nwse, nesw, swne, senw);
            int n12 = node(nesw, nese, senw, sene);
            int n21 = node(swne, senw, swse, sesw);
            int a, b, c, d, e, f, g, h, i;
            int inner;
            if (s == lv - 2) {
                // Full speed: two half-steps of 2^(s-1).
                inner = s - 1;
                a = result(n00, inner); b = result(n01, inner); c = result(n02, inner);
                d = result(n10, inner); e = result(n11, inner); f = result(n12, inner);
                g = result(n20, inner); h = result(n21, inner); i = result(n22, inner);
            } else {
                // Slower: no time in the first half, the whole step in the second.
                inner = s;
                a = center(n00); b = center(n01); c = center(n02);
                d = center(n10); e = center(n11); f = center(n12);
                g = center(n20); h = center(n21); i = center(n22);
            }
            int p = result(node(a, b, d, e), inner), q = result(node(b, c, e, f), inner);
            int t = result(node(d, e, g, h), inner), u = result(node(e, f, h, i), inner);
            r = node(p, q, t, u);
        }
        result[n] = r;
        resultStep[n] = (byte) s;
        return r;
    }

    private boolean borderEmpty(int n) {
        int a = nw[n], b = ne[n], c = sw[n], d = se[n];
        return pop[nw[a]] + pop[ne[a]] + pop[sw[a]]
             + pop[nw[b]] + pop[ne[b]] + pop[se[b]]
             + pop[nw[c]] + pop[sw[c]] + pop[se[c]]
             + pop[ne[d]] + pop[sw[d]] + pop[se[d]] == 0;
    }

    /** Advance by 2^s generations. */
    void step(int s) {
        while (level[root] < s + 3 || !borderEmpty(root)) root = expand(root);
        root = expand(root);
        root = result(root, s);
        generation += 1L << s;
        if (count > MAX_NODES) collect();
    }

    long population() {
        return pop[root];
    }

    // ---- reading ----

    /**
     * Population of each 2^level block of a w x h grid of blocks whose top-left block starts at
     * cell (x0, y0) (a multiple of 2^level), written to out[j * w + i]; out must be zeroed.
     */
    void raster(int lv, long x0, long y0, int w, int h, int[] out) {
        long half = 1L << (level[root] - 1);
        raster(tree(), root, -half, -half, lv, x0, y0, x0 + ((long) w << lv), y0 + ((long) h << lv), w, out);
    }

    private static void raster(Tree t, int n, long ox, long oy, int lv, long x0, long y0, long x1, long y1, int w, int[] out) {
        if (t.pop[n] == 0) return;
        long size = 1L << t.level[n];
        if (ox >= x1 || oy >= y1 || ox + size <= x0 || oy + size <= y0) return;
        if (t.level[n] <= lv) {
            int i = (int) ((ox - x0) >> lv), j = (int) ((oy - y0) >> lv);
            out[j * w + i] = (int) Math.min(Integer.MAX_VALUE, out[j * w + i] + t.pop[n]);
            return;
        }
        long q = size >> 1;
        raster(t, t.nw[n], ox, oy, lv, x0, y0, x1, y1, w, out);
        raster(t, t.ne[n], ox + q, oy, lv, x0, y0, x1, y1, w, out);
        raster(t, t.sw[n], ox, oy + q, lv, x0, y0, x1, y1, w, out);
        raster(t, t.se[n], ox + q, oy + q, lv, x0, y0, x1, y1, w, out);
    }

    /**
     * Population of each 2^level block of node n (whose level is at most level + 14) into the
     * w-wide grid out, n's top-left block at (i0, j0); out must be zeroed.
     */
    static void rasterTile(Tree t, int n, int lv, int[] out, int w, int i0, int j0) {
        if (t.pop[n] == 0) return;
        int nl = t.level[n];
        if (nl <= lv) {
            out[j0 * w + i0] = (int) Math.min(Integer.MAX_VALUE, out[j0 * w + i0] + t.pop[n]);
            return;
        }
        int q = 1 << (nl - 1 - lv);
        rasterTile(t, t.nw[n], lv, out, w, i0, j0);
        rasterTile(t, t.ne[n], lv, out, w, i0 + q, j0);
        rasterTile(t, t.sw[n], lv, out, w, i0, j0 + q);
        rasterTile(t, t.se[n], lv, out, w, i0 + q, j0 + q);
    }

    interface BlockVisitor {
        void block(long x, long y, long pop);
    }

    /** Visit non-empty 2^level-sized blocks inside the rectangle, with their population. */
    void blocks(int lv, long x0, long y0, long x1, long y1, BlockVisitor v) {
        long half = 1L << (level[root] - 1);
        blocks(root, -half, -half, lv, x0, y0, x1, y1, v);
    }

    private void blocks(int n, long ox, long oy, int lv, long x0, long y0, long x1, long y1, BlockVisitor v) {
        if (pop[n] == 0) return;
        long size = 1L << level[n];
        if (ox >= x1 || oy >= y1 || ox + size <= x0 || oy + size <= y0) return;
        if (level[n] <= lv) {
            v.block(ox, oy, pop[n]);
            return;
        }
        long q = size >> 1;
        blocks(nw[n], ox, oy, lv, x0, y0, x1, y1, v);
        blocks(ne[n], ox + q, oy, lv, x0, y0, x1, y1, v);
        blocks(sw[n], ox, oy + q, lv, x0, y0, x1, y1, v);
        blocks(se[n], ox + q, oy + q, lv, x0, y0, x1, y1, v);
    }

    /**
     * Where the bulk of the population is: the box holding all but `trim` of it on each side,
     * measured on a coarse grid. Escaping gliders don't drag the camera away with them.
     * Returns {x0, y0, x1, y1}, or null when the universe is empty.
     */
    long[] massBox(double trim, int grain) {
        if (pop[root] == 0) return null;
        // Blocks of a size chosen by the caller (relative to what is on screen), never by the
        // universe's size: tying it to the root level made the box snap to a coarser grid
        // whenever a stray glider made the universe double.
        java.util.TreeMap<Long, Long> xs = new java.util.TreeMap<>(), ys = new java.util.TreeMap<>();
        long half = 1L << (level[root] - 1);
        int g = Math.max(0, Math.min(grain, level[root] - 1));
        blocks(g, -half, -half, half, half, (x, y, p) -> {
            xs.merge(x, p, Long::sum);
            ys.merge(y, p, Long::sum);
        });
        long size = 1L << g;
        long[] xr = quantiles(xs, trim, size), yr = quantiles(ys, trim, size);
        return new long[]{xr[0], yr[0], xr[1], yr[1]};
    }

    /**
     * The bounding box of the most populous cluster: occupied 2^grain blocks, linked when within
     * `link` blocks of each other. A soup and its debris form one cluster; a glider that has flown
     * a few blocks clear is its own tiny one, so it stops counting. Returns {x0, y0, x1, y1}.
     */
    long[] coreBox(int grain, int link) {
        if (pop[root] == 0) return null;
        java.util.HashMap<Long, Long> blocks = new java.util.HashMap<>();
        long half = 1L << (level[root] - 1);
        final int g = Math.max(0, Math.min(grain, level[root] - 1));
        blocks(g, -half, -half, half, half, (x, y, p) -> blocks.merge(key(x >> g, y >> g), p, Long::sum));
        java.util.HashSet<Long> seen = new java.util.HashSet<>();
        java.util.ArrayDeque<Long> queue = new java.util.ArrayDeque<>();
        long bestPop = -1;
        long[] best = null;
        for (long start : blocks.keySet()) {
            if (!seen.add(start)) continue;
            queue.add(start);
            long p = 0, x0 = Long.MAX_VALUE, y0 = Long.MAX_VALUE, x1 = Long.MIN_VALUE, y1 = Long.MIN_VALUE;
            while (!queue.isEmpty()) {
                long k = queue.poll();
                long bx = k >> 32, by = (int) k;
                p += blocks.get(k);
                x0 = Math.min(x0, bx);
                y0 = Math.min(y0, by);
                x1 = Math.max(x1, bx);
                y1 = Math.max(y1, by);
                for (int dy = -link; dy <= link; dy++) {
                    for (int dx = -link; dx <= link; dx++) {
                        long n = key(bx + dx, by + dy);
                        if (blocks.containsKey(n) && seen.add(n)) queue.add(n);
                    }
                }
            }
            if (p > bestPop) {
                bestPop = p;
                best = new long[]{x0 << g, y0 << g, (x1 + 1) << g, (y1 + 1) << g};
            }
        }
        return best;
    }

    private static long key(long bx, long by) {
        return (bx << 32) | (by & 0xFFFFFFFFL);
    }

    private static long[] quantiles(java.util.TreeMap<Long, Long> hist, double trim, long size) {
        long total = 0;
        for (long p : hist.values()) total += p;
        long lowCut = (long) (total * trim), highCut = (long) (total * (1 - trim));
        long run = 0, lo = hist.firstKey(), hi = hist.lastKey() + size;
        boolean loSet = false;
        for (java.util.Map.Entry<Long, Long> e : hist.entrySet()) {
            run += e.getValue();
            if (!loSet && run > lowCut) {
                lo = e.getKey();
                loSet = true;
            }
            if (run >= highCut) {
                hi = e.getKey() + size;
                break;
            }
        }
        return new long[]{lo, hi};
    }
}
