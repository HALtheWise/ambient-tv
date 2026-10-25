package bot.tvhome;

import static android.opengl.GLES31.*;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;

/**
 * Draws a Life snapshot with per-frame work that follows what changed, not what is on screen.
 *
 * The visible part of the universe lives on the GPU in a toroidal "world" image at the current
 * block level (one texel per 2^level cells, half a pixel to a pixel across), each texel holding
 * its block's density and the frame it was born (or died). The world is cut into 16 x 16 texel
 * tiles. Every frame the CPU walks the quadtree only down to tile nodes and compares them with
 * last frame's: nodes are hash-consed, so the same node means the same contents, and only changed
 * tiles are rasterized (within a time budget), packed into a staging texture, and merged into the
 * world by a compute pass that keeps or resets each texel's birth frame. Brightness (newborn life
 * glows, settled life dims, the dead leave a ghost) is a second image, recomputed only for tiles
 * that changed in the last second or so. Panning brings in a few tiles at the edges; a zoom past
 * a power of two resamples the world to the next level on the GPU instead of starting over.
 *
 * Compute, not render passes: this GPU (a Mali, tile-based) loads and stores a render target's
 * whole area on every pass, so drawing a few tiles into a big texture cost as much as all of it.
 */
final class LifeRenderer {

    static final int T = 16;                    // tile side, in texels
    static final double MIN_TEXEL_PX = 0.5;     // the smallest a texel may be on screen
    private static final int STAGE_TILES = 64;  // staging texture is STAGE_TILES x STAGE_TILES tiles
    private static final int WARM_FRAMES = 64;  // a tile is re-shaded this long after it changes
    private static final int SETTLED = 60;      // age (frames) given to life with no history
    private static final long CPU_BUDGET_NS = 4_000_000;
    private static final int SHADE_BUDGET = 3000;  // warm tiles re-shaded per frame, at most

    // Tile modes for the merge pass.
    private static final int NORMAL = 0, FRESH = 1, NEWBORN = 2, KEEP = 3;
    private static final byte[] EMPTY_TILE = new byte[T * T];

    /** State texel: density (8 bits) << 24 | birth or death frame (24 bits). */
    private static final String COMMON =
            "#version 310 es\n"
            + "precision highp float;\n"
            + "precision highp int;\n"
            + "layout(local_size_x = 16, local_size_y = 16) in;\n"
            + "uniform uint now;\n"
            + "uniform float ghostScale;\n"
            + "const uint MASK = 0xFFFFFFu;\n"
            + "uint pack(uint d, uint b) { return (d << 24) | (b & MASK); }\n"
            + "float shade(uint s) {\n"
            + "  float k = float((now - (s & MASK)) & MASK);\n"
            + "  float d = float(s >> 24) / 255.0;\n"
            + "  if (d > 0.0) return d * min(1.0, 0.26 + 0.64 * exp(-k / 14.0));\n"
            + "  return k < 10.0 ? 0.13 * (1.0 - k / 10.0) * ghostScale : 0.0;\n"
            + "}\n";

    /** Merge rasterized tiles into the world, keeping or resetting each texel's birth frame. */
    private static final String MERGE =
            COMMON
            + "layout(std430, binding = 0) readonly buffer Tiles { ivec4 tiles[]; };\n"
            + "layout(r32ui, binding = 0) uniform highp uimage2D world;\n"
            + "layout(rgba8, binding = 1) uniform writeonly highp image2D bright;\n"
            + "uniform highp sampler2D stage;\n"
            + "void main() {\n"
            + "  ivec4 t = tiles[gl_WorkGroupID.x];\n"
            + "  ivec2 l = ivec2(gl_LocalInvocationID.xy);\n"
            + "  ivec2 dst = t.xy + l, src = ivec2(t.z & 0xFFFF, t.w) + l;\n"
            + "  int mode = t.z >> 16;\n"
            + "  uint old = imageLoad(world, dst).r;\n"
            + "  uint d0 = old >> 24, b = old & MASK;\n"
            + "  uint d1 = uint(texelFetch(stage, src, 0).r * 255.0 + 0.5);\n"
            + "  bool was = d0 > 0u, is = d1 > 0u;\n"
            + "  if (mode == " + NORMAL + ") {\n"     // changed density is new life; died: a ghost
            + "    if (is ? (!was || d1 != d0) : was) b = now;\n"
            + "  } else if (mode == " + FRESH + ") {\n"  // no history: settled, no ghosts
            + "    b = is ? now - " + SETTLED + "u : now - 1000u;\n"
            + "  } else if (mode == " + NEWBORN + ") {\n"  // a pattern's first frame
            + "    b = is ? now : now - 1000u;\n"
            + "  } else {\n"                           // re-levelled: refine density, keep ages
            + "    if (is && !was) b = now - " + SETTLED + "u;\n"
            + "    else if (!is) b = now - 1000u;\n"
            + "  }\n"
            + "  uint s = pack(d1, b);\n"
            + "  imageStore(world, dst, uvec4(s));\n"
            + "  imageStore(bright, dst, vec4(shade(s)));\n"
            + "}\n";

    /** Brightness of tiles whose ages are still moving. */
    private static final String SHADE =
            COMMON
            + "layout(std430, binding = 0) readonly buffer Tiles { ivec4 tiles[]; };\n"
            + "layout(r32ui, binding = 0) uniform readonly highp uimage2D world;\n"
            + "layout(rgba8, binding = 1) uniform writeonly highp image2D bright;\n"
            + "void main() {\n"
            + "  ivec2 p = tiles[gl_WorkGroupID.x].xy + ivec2(gl_LocalInvocationID.xy);\n"
            + "  imageStore(bright, p, vec4(shade(imageLoad(world, p).r)));\n"
            + "}\n";

    /**
     * Re-levelling: one band of rows of the next level's covered range (texel r of it is world
     * texel nb + r there), built from the current world into the spare set. Up (blocks double):
     * gathers old texels 2r + k (old slot (a + 2r + k) % size, offset in the old window b + 2r + k).
     * Down: old texel floor((nx + r) / 2) (slot (d + q) % size, offset c + q, q = (r + p) / 2).
     */
    private static final String RELEVEL =
            COMMON
            + "layout(r32ui, binding = 0) uniform readonly highp uimage2D world;\n"
            + "layout(rgba8, binding = 1) uniform writeonly highp image2D nextBright;\n"
            + "layout(r32ui, binding = 2) uniform writeonly highp uimage2D next;\n"
            + "uniform ivec2 size, area;\n"
            + "uniform int up, row0;\n"
            + "uniform ivec2 nb, a, b, d, c, p;\n"
            + "bool inside(ivec2 off) { return all(greaterThanEqual(off, ivec2(0))) && all(lessThan(off, size)); }\n"
            + "void main() {\n"
            + "  ivec2 r = ivec2(gl_GlobalInvocationID.xy) + ivec2(0, row0);\n"
            + "  if (any(greaterThanEqual(r, area))) return;\n"
            + "  uint s = pack(0u, now - 1000u);\n"
            + "  if (up == 1) {\n"
            + "    uint dens = 0u, young = MASK, bAlive = 0u, ghost = MASK, bDead = now - 1000u;\n"
            + "    for (int j = 0; j < 2; j++) for (int i = 0; i < 2; i++) {\n"
            + "      ivec2 k = ivec2(i, j);\n"
            + "      if (!inside(b + 2 * r + k)) continue;\n"
            + "      uint o = imageLoad(world, (a + 2 * r + k) % size).r;\n"
            + "      uint age = (now - (o & MASK)) & MASK;\n"
            + "      dens += o >> 24;\n"
            + "      if ((o >> 24) > 0u && age < young) { young = age; bAlive = o & MASK; }\n"
            + "      if ((o >> 24) == 0u && age < ghost) { ghost = age; bDead = o & MASK; }\n"
            + "    }\n"
            + "    dens = (dens + 2u) / 4u;\n"
            + "    if (young < MASK) dens = max(dens, 1u);\n"
            + "    s = pack(dens, dens > 0u ? bAlive : bDead);\n"
            + "  } else {\n"
            + "    ivec2 q = (r + p) / 2;\n"
            + "    if (inside(c + q)) s = imageLoad(world, (d + q) % size).r;\n"
            + "  }\n"
            + "  ivec2 dst = (nb + r) % size;\n"
            + "  imageStore(next, dst, uvec4(s));\n"
            + "  imageStore(nextBright, dst, vec4(shade(s)));\n"
            + "}\n";

    /** Two sets of world + brightness: the spare one receives the next level while re-levelling. */
    private final int[] worlds = new int[2], brights = new int[2];
    private final int[] stages = new int[3];
    private int stage, clearFbo;
    private int merge, shadeProg, relevelProg;
    private int tilesW, tilesH, texW, texH;
    private final byte[] stageBytes = new byte[STAGE_TILES * T * STAGE_TILES * T];
    private final ByteBuffer stageBuf = ByteBuffer.allocateDirect(stageBytes.length);
    private IntBuffer tileBuf;
    private final int[] ssbos = new int[6];
    private int ssbo;
    private long frameNo = 1000;

    // Per tile slot (toroidal): which world tile it holds, its node, whether its texels are good,
    // and when it last changed. Two copies, for re-levelling.
    /** slotNode values besides node indices: empty, and not yet known (after re-levelling). */
    private static final int NONE = -1, UNKNOWN = -2;
    /** slotX value for a slot known to be empty wherever it is (after a clear). */
    private static final long ANYWHERE = Long.MIN_VALUE + 1;
    private int cur;  // which set (textures and slot arrays) is live
    private final long[][] slotX = new long[2][], slotY = new long[2][];
    private final int[][] slotNode = new int[2][];
    private final boolean[][] slotValid = new boolean[2][];
    private final long[][] slotChanged = new long[2][];
    /** When a slot last took a new tile position (FRESH or NEWBORN raster). */
    private final long[][] slotMoved = new long[2][];
    /**
     * After a swap to a re-levelled set, each slot's bookkeeping is worked out from the old set
     * the first time the slot is visited (slotEpoch != remapEpoch), inside the per-frame tile
     * loop: that loop is hot, so compiled; a one-off loop over every slot ran interpreted (ART
     * here will not compile ahead of time) and cost a frame.
     */
    private final int[][] slotEpoch = new int[2][];
    private int remapEpoch;
    private boolean remapUp;
    private int remapFrom;
    private long remapCovX, remapCovY, remapSince, remapTx0, remapTy0;
    private int remapTw, remapTh;

    // Re-levelling in progress: building `toLevel` into the spare set, `rowsDone` of its covered
    // range (toX, toY, toW x toH texels) so far.
    private static final int RELEVEL_FRAMES = 16;
    private static final double DOWN_AT = 2.5;  // texel size (in MIN_TEXEL_PX) that sends us a level down
    private boolean moving;
    private int toLevel, toW, toH, rowsDone;
    private long toX, toY, movingSince;
    private int level = -1;
    private long covX, covY;     // first covered texel (world), at the current level
    private int covW, covH;      // covered size, in texels

    private int[] grid = new int[0];
    private final int[] counts = new int[T * T];
    private int[] dirty = new int[0], warmList = new int[0];
    private int shadeCursor;
    /**
     * Rasterized tiles by node (same node, same texels) for the current level: a fixed pool,
     * oldest evicted first, found through a small open-addressing table on the node's hash. No
     * allocation per tile (chaotic patterns rarely repeat a tile, and the garbage cost frames).
     */
    private static final int POOL = 8192, TABLE = 16384, PROBES = 8;
    private final byte[] pool = new byte[POOL * T * T];
    private final int[] poolNode = new int[POOL];
    private final int[] tableKey = new int[TABLE];
    private final int[] tableVal = new int[TABLE];
    private int poolNext, cacheLevel = -1, cacheCollections = -1;

    private static int hashOf(int n) {
        int h = n * 0x9E3779B1;
        return h ^ (h >>> 15);
    }

    /** Pool index holding n's texels, or -1. */
    private int cacheFind(int n) {
        for (int k = 0, i = hashOf(n) & (TABLE - 1); k < PROBES; k++, i = (i + 1) & (TABLE - 1)) {
            int key = tableKey[i];
            if (key == NONE) return -1;
            if (key == n) return poolNode[tableVal[i]] == n ? tableVal[i] : -1;
        }
        return -1;
    }

    /** A pool index for n's texels (the caller fills them). */
    private int cacheAdd(int n) {
        int idx = poolNext;
        poolNext = (poolNext + 1) % POOL;
        poolNode[idx] = n;
        int target = hashOf(n) & (TABLE - 1);
        for (int k = 0, i = target; k < PROBES; k++, i = (i + 1) & (TABLE - 1)) {
            int key = tableKey[i];
            // Free, or holding a tile since evicted from the pool.
            if (key == NONE || key == n || poolNode[tableVal[i]] != key) {
                target = i;
                break;
            }
        }
        tableKey[target] = n;
        tableVal[target] = idx;
        return idx;
    }
    /** Slots changed within WARM_FRAMES (each once), so brightness upkeep need not scan everything. */
    private int[] warmSet, warmPos;
    private int nWarm;

    double originX, originY;
    /** Stats for the last update, for the log. */
    int lastDirty, lastDone, lastWarm;

    /** Brightness, for the display shader (rgba8, red channel; wraps). */
    int texture() {
        return brights[cur];
    }

    int texW() {
        return texW;
    }

    int texH() {
        return texH;
    }

    int level() {
        return level;
    }

    /** Allocate for a surface; texels never get smaller than MIN_TEXEL_PX, which bounds the view. */
    void create(int surfaceW, int surfaceH) {
        tilesW = (int) Math.ceil(surfaceW / MIN_TEXEL_PX / T) + 4;
        tilesH = (int) Math.ceil(surfaceH / MIN_TEXEL_PX / T) + 4;
        texW = tilesW * T;
        texH = tilesH * T;
        for (int k = 0; k < 2; k++) {
            worlds[k] = tex(GL_R32UI, texW, texH, GL_NEAREST);
            brights[k] = tex(GL_RGBA8, texW, texH, GL_LINEAR);
        }
        // Staging textures and tile lists rotate, so a frame never overwrites one the GPU may
        // still be reading for the frame before.
        for (int k = 0; k < 3; k++) stages[k] = tex(GL_R8, STAGE_TILES * T, STAGE_TILES * T, GL_NEAREST);
        stage = stages[0];
        int[] f = new int[1];
        glGenFramebuffers(1, f, 0);
        clearFbo = f[0];
        merge = compute(MERGE);
        shadeProg = compute(SHADE);
        relevelProg = compute(RELEVEL);
        glGenBuffers(6, ssbos, 0);
        ssbo = ssbos[0];
        int n = tilesW * tilesH;
        for (int k = 0; k < 2; k++) {
            slotX[k] = new long[n];
            slotY[k] = new long[n];
            slotNode[k] = new int[n];
            slotValid[k] = new boolean[n];
            slotChanged[k] = new long[n];
            slotMoved[k] = new long[n];
            slotEpoch[k] = new int[n];
        }
        cX = new long[n];
        cY = new long[n];
        pendX = new long[n];
        pendY = new long[n];
        cSlot = new int[n];
        seen = new int[n];
        cNode = new int[n];
        diffTree = null;
        warmSet = new int[n];
        warmPos = new int[n];
        java.util.Arrays.fill(warmPos, -1);
        nWarm = 0;
        tileBuf = ByteBuffer.allocateDirect(Math.max(n, STAGE_TILES * STAGE_TILES) * 16)
                .order(ByteOrder.nativeOrder()).asIntBuffer();
        level = -1;
        moving = false;
        warmUp();
    }

    /**
     * Run every program once now, doing nothing: drivers finish compiling a shader on its first
     * use, which would otherwise cost a frame the first time each one is needed.
     */
    private void warmUp() {
        tileBuf.clear();
        tileBuf.put(0).put(0).put(0).put(0);
        glUseProgram(merge);
        glUniform1i(glGetUniformLocation(merge, "stage"), 0);
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, stage);
        run(merge, 1, true);  // one tile of nothing at slot 0 (the world is cleared before use)
        tileBuf.clear();
        tileBuf.put(0).put(0).put(0).put(0);
        glUseProgram(shadeProg);
        run(shadeProg, 1, false);
        glBindImageTexture(0, worlds[0], 0, false, 0, GL_READ_ONLY, GL_R32UI);
        glBindImageTexture(1, brights[1], 0, false, 0, GL_WRITE_ONLY, GL_RGBA8);
        glBindImageTexture(2, worlds[1], 0, false, 0, GL_WRITE_ONLY, GL_R32UI);
        glUseProgram(relevelProg);
        glUniform2i(glGetUniformLocation(relevelProg, "area"), 0, 0);
        glDispatchCompute(1, 1, 1);
        glMemoryBarrier(GL_SHADER_IMAGE_ACCESS_BARRIER_BIT);
        glFinish();
    }

    private static int tex(int format, int w, int h, int filter) {
        int[] t = new int[1];
        glGenTextures(1, t, 0);
        glBindTexture(GL_TEXTURE_2D, t[0]);
        glTexStorage2D(GL_TEXTURE_2D, 1, format, w, h);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, filter);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, filter);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_REPEAT);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_REPEAT);
        return t[0];
    }

    private static int compute(String source) {
        int s = glCreateShader(GL_COMPUTE_SHADER);
        glShaderSource(s, source);
        glCompileShader(s);
        int[] ok = new int[1];
        glGetShaderiv(s, GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == 0) throw new IllegalStateException("compile: " + glGetShaderInfoLog(s) + "\n" + source);
        int p = glCreateProgram();
        glAttachShader(p, s);
        glLinkProgram(p);
        glGetProgramiv(p, GL_LINK_STATUS, ok, 0);
        if (ok[0] == 0) throw new IllegalStateException("link: " + glGetProgramInfoLog(p));
        return p;
    }

    /**
     * Forget everything: a new pattern (its first frame drawn all newborn), or after the
     * textures were rebuilt (everything drawn settled).
     */
    void reset(boolean newborn) {
        level = -1;
        moving = false;
        startNewborn = newborn;
    }

    private boolean startNewborn = true;
    /** Time the last update() took, for the log. */
    long lastUpdateNs;

    /**
     * Bring the world up to date with this snapshot for a view centred on (cx, cy) cells, at
     * `scale` px per cell, on a w x h surface. Afterwards texture() holds brightness, and
     * originX/Y the toroidal texel under the view's centre.
     */
    void update(LifeSim.Snapshot snap, double cx, double cy, double scale, int w, int h) {
        long started = System.nanoTime();
        frameNo++;
        // The level to draw at: texels from MIN_TEXEL_PX to 2.5x that, with hysteresis (going
        // down only at 1.25 px, not 1), so a zoom hovering at a boundary does not flip back and
        // forth; each change means a rebuild.
        int natural = 0;
        while (scale * (1L << natural) < MIN_TEXEL_PX) natural++;
        int want = level;
        if (level < 0 || Math.abs(natural - level) > 1) want = natural;
        else if (scale * (1L << level) < MIN_TEXEL_PX) want = level + 1;
        else if (level > 0 && scale * (1L << level) >= MIN_TEXEL_PX * DOWN_AT) want = level - 1;
        boolean first = level < 0;
        if (first || Math.abs(want - level) > 1) {
            // A new pattern, or a jump of several levels: start from an empty world at once.
            moving = false;
            level = want;
            clear();
        } else if (want != level) {
            // Crossed into the next level: swap to it if it is built (else keep building, and
            // keep showing this level a little past its range meanwhile).
            if (!moving || toLevel != want) startRelevel(want, cx, cy, scale, w, h);
            else if (rowsDone >= toH) finishRelevel();
        } else {
            // Within this level's range: start building a neighbour as the zoom nears its edge.
            double px = scale * (1L << level);
            int next = px < MIN_TEXEL_PX * 1.1 ? level + 1 : px >= MIN_TEXEL_PX * (DOWN_AT - 0.2) && level > 0 ? level - 1 : level;
            if (next == level) {
                moving = false;
            } else if (!moving || toLevel != next || frameNo - movingSince > 120) {
                startRelevel(next, cx, cy, scale, w, h);
            }
        }
        int lv = level;
        double block = 1L << lv;
        double tcx = cx / block, tcy = cy / block, halfW = w / 2.0 / (scale * block), halfH = h / 2.0 / (scale * block);
        long tx0 = (long) Math.floor((tcx - halfW) / T) - 1, ty0 = (long) Math.floor((tcy - halfH) / T) - 1;
        long tx1 = (long) Math.floor((tcx + halfW) / T) + 1, ty1 = (long) Math.floor((tcy + halfH) / T) + 1;
        int gw = (int) Math.min(tilesW, tx1 - tx0 + 1), gh = (int) Math.min(tilesH, ty1 - ty0 + 1);
        // Tiles the view itself touches (the rest is a one-tile margin).
        long vx0 = tx0 + 1, vy0 = ty0 + 1, vx1 = tx1 - 1, vy1 = ty1 - 1;

        covX = tx0 * T;
        covY = ty0 * T;
        covW = gw * T;
        covH = gh * T;
        originX = Math.floorMod((long) Math.floor(tcx), (long) texW) + (tcx - Math.floor(tcx));
        originY = Math.floorMod((long) Math.floor(tcy), (long) texH) + (tcy - Math.floor(tcy));


        // Which tiles need rasterizing, and how; then rasterize them (separate methods, each
        // small enough for the JIT: one huge method stayed interpreted, ten times slower).
        fTx0 = tx0;
        fTy0 = ty0;
        fGw = gw;
        fGh = gh;
        fFirst = first;
        fNewborn = first && startNewborn;
        fTree = snap.tree.level(snap.tree.root) >= lv + 4 + 4 ? snap.tree : null;
        fCollections = snap.collections;
        int nd = classify();
        int done = rasterize(nd, lv);
        int ring = (int) (frameNo % 3);
        stage = stages[ring];
        ssbo = ssbos[2 * ring];
        if (done > 0) {
            int rows = (done + STAGE_TILES - 1) / STAGE_TILES;
            stageBuf.position(0);
            stageBuf.put(stageBytes, 0, rows * T * STAGE_TILES * T).position(0);
            glActiveTexture(GL_TEXTURE0);
            glBindTexture(GL_TEXTURE_2D, stage);
            glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
            glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, STAGE_TILES * T, rows * T, GL_RED, GL_UNSIGNED_BYTE, stageBuf);
            glUseProgram(merge);
            glUniform1i(glGetUniformLocation(merge, "stage"), 0);
            run(merge, done, true);
        }

        // Tiles whose ages are still moving: refresh their brightness (round robin within budget).
        int nw = collectWarm();
        lastWarm = nw;
        if (nw > 0) {
            tileBuf.clear();
            int count = Math.min(nw, SHADE_BUDGET);
            for (int k = 0; k < count; k++) {
                int slot = warmList[(shadeCursor + k) % nw];
                tileBuf.put((slot % tilesW) * T).put((slot / tilesW) * T).put(0).put(0);
            }
            shadeCursor = nw > count ? (shadeCursor + count) % nw : 0;
            ssbo = ssbos[2 * ring + 1];
            glUseProgram(shadeProg);
            run(shadeProg, count, false);
        }
        if (moving && rowsDone < toH) relevelBand();
        // The display samples the brightness next.
        glMemoryBarrier(GL_TEXTURE_FETCH_BARRIER_BIT | GL_SHADER_IMAGE_ACCESS_BARRIER_BIT);
        lastUpdateNs = System.nanoTime() - started;
    }

    // The frame's covered range of tiles and snapshot, for classify() and rasterize().
    private long fTx0, fTy0;
    private int fGw, fGh, fCollections;
    private boolean fFirst, fNewborn;
    private Life.Tree fTree;
    // Candidate tiles this frame, and what last frame's diff was against.
    private long[] cX, cY, pendX, pendY;
    private int[] cSlot, seen;
    private int[] cNode;
    private int nc, np, stamp;
    private Life.Tree diffTree;
    private int diffLevel = -1, diffEpoch, diffCollections, diffGw, diffGh;
    private long diffTx0, diffTy0;
    boolean lastFull;
    private static final int SCAN_TILES = 2500;  // tiles a progressive scan covers per frame
    private int scanRow = -1;

    private boolean scanning() {
        return scanRow >= 0;
    }

    /** Find the tiles that need rasterizing (into dirty, sorted: must-do first), and their modes. */
    private int classify() {
        long tx0 = fTx0, ty0 = fTy0;
        int gw = fGw, gh = fGh;
        int L = level + 4;
        Life.Tree tree = fTree;
        // Candidates: every covered tile when anything structural changed; otherwise only tiles
        // under quadtree nodes that differ from last frame's, newly covered tiles, and tiles a
        // previous frame's budget left undone.
        nc = 0;
        stamp++;
        // A new pattern: everything at once (it starts small). A swap of levels, or a collection
        // (which renumbers every node): there is nothing to diff against, so scan every tile, a
        // few rows a frame; what is on screen meanwhile is already right, just not yet confirmed.
        boolean full = fFirst || tree == null;
        boolean canDiff = !full && diffTree != null && diffLevel == level && diffEpoch == remapEpoch
                && diffCollections == fCollections;
        if (!full && !canDiff) scanRow = 0;
        lastFull = full;
        if (full) {
            scanRow = -1;
            if (grid.length < gw * gh) grid = new int[gw * gh];
            java.util.Arrays.fill(grid, 0, gw * gh, NONE);
            if (tree != null) walk(tree, L, tx0, ty0, gw, gh);
            for (int j = 0; j < gh; j++) {
                for (int i = 0; i < gw; i++) candidate(tx0 + i, ty0 + j, grid[j * gw + i]);
            }
        } else if (canDiff) {
            // Changed subtrees, a block of 8 x 8 tiles at a time.
            int K = L + 3;
            long bx0 = tx0 >> 3, by0 = ty0 >> 3, bx1 = (tx0 + gw - 1) >> 3, by1 = (ty0 + gh - 1) >> 3;
            for (long by = by0; by <= by1; by++) {
                for (long bx = bx0; bx <= bx1; bx++) {
                    long x = bx << K, y = by << K;
                    int c = nodeAt(tree, K, x, y), p = nodeAt(diffTree, K, x, y);
                    if (c != p) diff(tree, c, diffTree, p, x, y, K, L);
                }
            }
            // Newly covered tiles.
            for (int j = 0; j < gh; j++) {
                long ty = ty0 + j;
                boolean rowWasIn = ty >= diffTy0 && ty < diffTy0 + diffGh;
                for (int i = 0; i < gw; i++) {
                    long tx = tx0 + i;
                    if (rowWasIn && tx >= diffTx0 && tx < diffTx0 + diffGw) {
                        i = (int) Math.max(i, diffTx0 + diffGw - tx0 - 1);  // skip the old span
                        continue;
                    }
                    candidate(tx, ty, nodeAt(tree, L, tx << L, ty << L));
                }
            }
        }
        if (!full) {
            // Left over from before.
            for (int k = 0; k < np; k++) {
                long tx = pendX[k], ty = pendY[k];
                if (tx >= tx0 && tx < tx0 + gw && ty >= ty0 && ty < ty0 + gh) candidate(tx, ty, nodeAt(tree, L, tx << L, ty << L));
            }
            if (scanRow >= 0) {
                // The next rows of a progressive scan (overlapping a row, as the view drifts).
                int rows = Math.max(1, SCAN_TILES / Math.max(1, gw));
                int j0 = Math.max(0, scanRow - 1), j1 = Math.min(gh, scanRow + rows);
                for (int j = j0; j < j1; j++) {
                    for (int i = 0; i < gw; i++) candidate(tx0 + i, ty0 + j, nodeAt(tree, L, (tx0 + i) << L, (ty0 + j) << L));
                }
                scanRow = j1 >= gh ? -1 : j1;
            }
        }
        diffTree = tree;
        diffLevel = level;
        diffEpoch = remapEpoch;
        diffCollections = fCollections;
        diffTx0 = tx0;
        diffTy0 = ty0;
        diffGw = gw;
        diffGh = gh;

        boolean first = fFirst;
        long vx0 = tx0 + 1, vy0 = ty0 + 1, vx1 = tx0 + gw - 2, vy1 = ty0 + gh - 2;
        int s = cur, o = remapFrom;
        long[] sX = slotX[s], sY = slotY[s], sM = slotMoved[s], sC = slotChanged[s];
        long[] oX = slotX[o], oY = slotY[o], oM = slotMoved[o], oC = slotChanged[o];
        int[] sN = slotNode[s];
        boolean[] sV = slotValid[s], oV = slotValid[o];
        int[] sE = slotEpoch[s];
        if (dirty.length < nc) dirty = new int[nc];
        int nd = 0;
        for (int k = 0; k < nc; k++) {
            long tx = cX[k], ty = cY[k];
            int slot = cSlot[k];
            if (sE[slot] != remapEpoch) {
                // First visit since the swap: are this slot's texels (built from the old level)
                // good? Only if every old tile they came from was, lay inside the window the
                // build read, and kept its place while the build ran.
                sE[slot] = remapEpoch;
                boolean good = tx >= remapTx0 && tx < remapTx0 + remapTw && ty >= remapTy0 && ty < remapTy0 + remapTh;
                boolean allEmpty = true;
                long changed = 0;
                int span = remapUp ? 2 : 1;
                long ox = remapUp ? 2 * tx : tx >> 1, oy = remapUp ? 2 * ty : ty >> 1;
                for (int b = 0; b < span && good; b++) {
                    for (int a = 0; a < span && good; a++) {
                        long otx = ox + a, oty = oy + b;
                        int os = slotOf(otx, oty);
                        long wx = otx * T - remapCovX, wy = oty * T - remapCovY;
                        boolean inWindow = wx >= 0 && wx + T <= texW && wy >= 0 && wy + T <= texH;
                        boolean wasEmpty = oV[os] && oX[os] == ANYWHERE;
                        boolean wasHere = oV[os] && oX[os] == otx && oY[os] == oty;
                        good = inWindow && (wasHere || wasEmpty) && oM[os] < remapSince;
                        allEmpty &= wasEmpty;
                        changed = Math.max(changed, oC[os]);
                    }
                }
                sX[slot] = good && allEmpty ? ANYWHERE : tx;
                sY[slot] = ty;
                sN[slot] = good && allEmpty ? NONE : UNKNOWN;
                sV[slot] = good;
                sM[slot] = 0;
                // Still settling: keep refreshing its brightness for a while.
                boolean warm = good && !allEmpty && frameNo - changed <= WARM_FRAMES;
                sC[slot] = warm ? frameNo : good ? changed : 0;
                if (warm) {
                    warmAdd(slot);
                }
            }
            int n = cNode[k];
            boolean valid = sV[slot];
            boolean here = valid && sX[slot] == tx && sY[slot] == ty;
            boolean empty = valid && sX[slot] == ANYWHERE;
            int mode;
            if (here) {
                if (sN[slot] == UNKNOWN) mode = KEEP;
                else if (sN[slot] != n) mode = NORMAL;
                else continue;
            } else if (empty && n == NONE) {
                continue;
            } else {
                mode = fNewborn ? NEWBORN : FRESH;
            }
            boolean visible = tx >= vx0 && tx <= vx1 && ty >= vy0 && ty <= vy1;
            // Visible tiles without good texels go first and are never deferred.
            int pri = !here && visible ? 0 : 1;
            dirty[nd++] = (pri << 30) | (mode << 28) | k;
        }
        lastDirty = nd;
        java.util.Arrays.sort(dirty, 0, nd);
        return nd;
    }

    /** Add a candidate tile (once per frame). */
    private void candidate(long tx, long ty, int n) {
        int slot = slotOf(tx, ty);
        if (seen[slot] == stamp) return;
        seen[slot] = stamp;
        if (nc == cX.length) return;  // cannot happen: candidates are distinct covered tiles
        cX[nc] = tx;
        cY[nc] = ty;
        cSlot[nc] = slot;
        cNode[nc] = n;
        nc++;
    }

    /** The node of level K whose square starts at cell (x, y), or NONE if that square is empty. */
    private static int nodeAt(Life.Tree t, int K, long x, long y) {
        int n = t.root;
        long half = 1L << (t.level[n] - 1), ox = -half, oy = -half;
        if (x < ox || y < oy || x >= half || y >= half) return NONE;
        while (t.level[n] > K) {
            if (t.pop[n] == 0) return NONE;
            long q = 1L << (t.level[n] - 1);
            boolean east = x >= ox + q, south = y >= oy + q;
            n = south ? (east ? t.se[n] : t.sw[n]) : (east ? t.ne[n] : t.nw[n]);
            if (east) ox += q;
            if (south) oy += q;
        }
        return t.pop[n] == 0 ? NONE : n;
    }

    /**
     * Candidates for every tile (level L) under the square where node c (of tree tc) and node p
     * (of last frame's tree tp) differ. Within one collection a node index means the same node
     * in both trees.
     */
    private void diff(Life.Tree tc, int c, Life.Tree tp, int p, long x, long y, int lv, int L) {
        if (c >= 0 && tc.pop[c] == 0) c = NONE;
        if (p >= 0 && tp.pop[p] == 0) p = NONE;
        if (c == p) return;
        long size = 1L << lv;
        long cx0 = fTx0 << L, cy0 = fTy0 << L, cx1 = (fTx0 + fGw) << L, cy1 = (fTy0 + fGh) << L;
        if (x >= cx1 || y >= cy1 || x + size <= cx0 || y + size <= cy0) return;
        if (lv == L) {
            candidate(x >> L, y >> L, c);
            return;
        }
        long q = size >> 1;
        diff(tc, c < 0 ? NONE : tc.nw[c], tp, p < 0 ? NONE : tp.nw[p], x, y, lv - 1, L);
        diff(tc, c < 0 ? NONE : tc.ne[c], tp, p < 0 ? NONE : tp.ne[p], x + q, y, lv - 1, L);
        diff(tc, c < 0 ? NONE : tc.sw[c], tp, p < 0 ? NONE : tp.sw[p], x, y + q, lv - 1, L);
        diff(tc, c < 0 ? NONE : tc.se[c], tp, p < 0 ? NONE : tp.se[p], x + q, y + q, lv - 1, L);
    }

    /** Rasterize dirty tiles into the staging buffer within the time budget; returns how many. */
    private int rasterize(int nd, int lv) {
        int s = cur;
        long[] sX = slotX[s], sY = slotY[s], sM = slotMoved[s], sC = slotChanged[s];
        int[] sN = slotNode[s];
        boolean[] sV = slotValid[s];
        Life.Tree tree = fTree;
        // Rasterize within budget (forced tiles regardless).
        long start = System.nanoTime();
        double toByte = 255.0 / (double) (1L << (2 * lv));
        if (cacheLevel != lv || cacheCollections != fCollections) {
            java.util.Arrays.fill(tableKey, NONE);
            java.util.Arrays.fill(poolNode, NONE);
            cacheLevel = lv;
            cacheCollections = fCollections;
        }
        tileBuf.clear();
        int done = 0, k = 0;
        for (; k < nd && done < STAGE_TILES * STAGE_TILES; k++) {
            int e = dirty[k];
            if ((e >>> 30) != 0 && (k & 7) == 0 && System.nanoTime() - start > CPU_BUDGET_NS) break;
            int c = e & 0xFFFFFFF, mode = (e >>> 28) & 3;
            long tx = cX[c], ty = cY[c];
            int slot = cSlot[c];
            int n = cNode[c];
            int sx = (done % STAGE_TILES) * T, sy = (done / STAGE_TILES) * T;
            byte[] texels = EMPTY_TILE;
            int at = 0;
            if (n >= 0) {
                texels = pool;
                int idx = cacheFind(n);
                if (idx < 0) {
                    idx = cacheAdd(n);
                    java.util.Arrays.fill(counts, 0);
                    Life.rasterTile(tree, n, lv, counts, T, 0, 0);
                    int base = idx * T * T;
                    for (int x = 0; x < T * T; x++) {
                        int count = counts[x];
                        pool[base + x] = count == 0 ? 0 : (byte) Math.max(1, Math.min(255, (int) (count * toByte + 0.5)));
                    }
                }
                at = idx * T * T;
            }
            for (int y = 0; y < T; y++) System.arraycopy(texels, at + y * T, stageBytes, (sy + y) * STAGE_TILES * T + sx, T);
            tileBuf.put((slot % tilesW) * T).put((slot / tilesW) * T).put(sx | (mode << 16)).put(sy);
            sX[slot] = tx;
            sY[slot] = ty;
            sN[slot] = n;
            sV[slot] = true;
            sC[slot] = frameNo;
            if (mode == FRESH || mode == NEWBORN) sM[slot] = frameNo;
            warmAdd(slot);
            done++;
        }
        // What the budget left: next frame's candidates too.
        np = 0;
        for (; k < nd; k++) {
            int c = dirty[k] & 0xFFFFFFF;
            pendX[np] = cX[c];
            pendY[np] = cY[c];
            np++;
        }
        lastDone = done;
        return done;
    }

    private IntBuffer verifyBuf;
    private int verifyRow;

    /**
     * Debug (Config lifeVerify): read back `rows` rows of visible tiles of the world (the next
     * ones of a sweep) and compare their densities with what the snapshot says. Stalls the GPU,
     * so never on in normal use. Returns {checked, stale, pending}: pending tiles are known to be
     * waiting (on the budget, or a scan); stale ones are wrong with nothing set to fix them.
     */
    int[] verify(int rows) {
        Life.Tree tree = fTree;
        if (tree == null || level < 0) return new int[3];
        if (verifyBuf == null) verifyBuf = ByteBuffer.allocateDirect(texW * T * 16).order(ByteOrder.nativeOrder()).asIntBuffer();
        int L = level + 4;
        double toByte = 255.0 / (double) (1L << (2 * level));
        glMemoryBarrier(GL_FRAMEBUFFER_BARRIER_BIT);
        glBindFramebuffer(GL_FRAMEBUFFER, clearFbo);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, worlds[cur], 0);
        int vw = Math.max(1, fGw - 2), vh = Math.max(1, fGh - 2);
        int checked = 0, stale = 0, pendingN = 0;
        for (int r = 0; r < rows; r++) {
            verifyRow = (verifyRow + 1) % vh;
            long ty = fTy0 + 1 + verifyRow;
            int sy = Math.floorMod((int) ty, tilesH) * T;
            verifyBuf.clear();
            glReadPixels(0, sy, texW, T, GL_RGBA_INTEGER, GL_UNSIGNED_INT, verifyBuf);
            for (int i = 0; i < vw; i++) {
                long tx = fTx0 + 1 + i;
                int slot = slotOf(tx, ty);
                int n = nodeAt(tree, L, tx << L, ty << L);
                java.util.Arrays.fill(counts, 0);
                if (n >= 0) Life.rasterTile(tree, n, level, counts, T, 0, 0);
                int sx = (slot % tilesW) * T, bad = 0, ghosts = 0;
                for (int y = 0; y < T; y++) {
                    for (int x = 0; x < T; x++) {
                        int count = counts[y * T + x];
                        int want = count == 0 ? 0 : Math.max(1, Math.min(255, (int) (count * toByte + 0.5)));
                        int got = verifyBuf.get(((y * texW) + sx + x) * 4) >>> 24;
                        if (got != want) bad++;
                        if (got != 0 && want == 0) ghosts++;
                    }
                }
                checked++;
                if (bad == 0) continue;
                boolean pending = scanning() || slotEpoch[cur][slot] != remapEpoch;
                for (int q = 0; q < np && !pending; q++) pending = pendX[q] == tx && pendY[q] == ty;
                if (pending) {
                    pendingN++;
                } else {
                    stale++;
                    android.util.Log.w("LifeVerify", "stale tile " + tx + "," + ty + " level " + level + ": " + bad
                            + " texels (" + ghosts + " alive that should be empty), slot node " + slotNode[cur][slot]
                            + " tree node " + n + ", here " + (slotX[cur][slot] == tx && slotY[cur][slot] == ty)
                            + ", collections " + fCollections + ", frame " + frameNo);
                }
            }
        }
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, 0, 0);
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        return new int[]{checked, stale, pendingN};
    }

    private void warmAdd(int slot) {
        if (warmPos[slot] >= 0) return;
        warmPos[slot] = nWarm;
        warmSet[nWarm++] = slot;
    }

    private void warmClear() {
        for (int k = 0; k < nWarm; k++) warmPos[warmSet[k]] = -1;
        nWarm = 0;
    }

    /** Warm slots due a refresh this frame into warmList (dropping those that have settled). */
    private int collectWarm() {
        int s = cur, nw = 0;
        long[] sC = slotChanged[s], sX = slotX[s];
        boolean[] sV = slotValid[s];
        if (warmList.length < warmSet.length) warmList = new int[warmSet.length];
        for (int k = 0; k < nWarm; ) {
            int slot = warmSet[k];
            long age = frameNo - sC[slot];
            if (age > WARM_FRAMES || !sV[slot] || sX[slot] == ANYWHERE) {
                // Settled (or gone): swap-remove.
                int last = warmSet[--nWarm];
                warmSet[k] = last;
                warmPos[last] = k;
                warmPos[slot] = -1;
                continue;
            }
            if (age > 0) warmList[nw++] = slot;  // (merged this frame: already shaded)
            k++;
        }
        return nw;
    }

    /** Dispatch one 16 x 16 workgroup per tile in tileBuf. */
    private void run(int prog, int tiles, boolean writesWorld) {
        tileBuf.flip();
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, ssbo);
        glBufferData(GL_SHADER_STORAGE_BUFFER, tileBuf.limit() * 4, tileBuf, GL_STREAM_DRAW);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 0, ssbo);
        glBindImageTexture(0, worlds[cur], 0, false, 0, writesWorld ? GL_READ_WRITE : GL_READ_ONLY, GL_R32UI);
        glBindImageTexture(1, brights[cur], 0, false, 0, GL_WRITE_ONLY, GL_RGBA8);
        uniforms(prog);
        glDispatchCompute(tiles, 1, 1);
        glMemoryBarrier(GL_SHADER_IMAGE_ACCESS_BARRIER_BIT);
    }

    private void uniforms(int prog) {
        glUniform1ui(glGetUniformLocation(prog, "now"), (int) (frameNo & 0xFFFFFF));
        glUniform1f(glGetUniformLocation(prog, "ghostScale"), level > 0 ? 0.15f : 1f);
    }

    /** Empty the world, and mark every slot as known empty wherever it is. */
    private void clear() {
        glBindFramebuffer(GL_FRAMEBUFFER, clearFbo);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, worlds[cur], 0);
        glClearBufferuiv(GL_COLOR, 0, new int[]{0, 0, 0, 0}, 0);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, brights[cur], 0);
        glClearBufferfv(GL_COLOR, 0, new float[]{0, 0, 0, 0}, 0);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, 0, 0);
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        java.util.Arrays.fill(slotValid[cur], true);
        java.util.Arrays.fill(slotX[cur], ANYWHERE);
        java.util.Arrays.fill(slotNode[cur], NONE);
        java.util.Arrays.fill(slotChanged[cur], 0);
        java.util.Arrays.fill(slotMoved[cur], 0);
        java.util.Arrays.fill(slotEpoch[cur], remapEpoch);
        warmClear();
    }

    /** Begin building level `lv` for the current view (with a margin, as the camera moves on). */
    private void startRelevel(int lv, double cx, double cy, double scale, int w, int h) {
        double block = 1L << lv, px = scale * block;
        double tcx = cx / block, tcy = cy / block, halfW = w / 2.0 / px, halfH = h / 2.0 / px;
        // At the moment of the swap texels will be at the edge of their range: cover that view.
        double edge = lv > level ? MIN_TEXEL_PX * 2 : MIN_TEXEL_PX;
        halfW = Math.max(halfW, w / 2.0 / edge);
        halfH = Math.max(halfH, h / 2.0 / edge);
        long tx0 = (long) Math.floor((tcx - halfW) / T) - 2, ty0 = (long) Math.floor((tcy - halfH) / T) - 2;
        long tx1 = (long) Math.floor((tcx + halfW) / T) + 2, ty1 = (long) Math.floor((tcy + halfH) / T) + 2;
        toLevel = lv;
        toX = tx0 * T;
        toY = ty0 * T;
        toW = (int) Math.min(tilesW, tx1 - tx0 + 1) * T;
        toH = (int) Math.min(tilesH, ty1 - ty0 + 1) * T;
        rowsDone = 0;
        moving = true;
        movingSince = frameNo;
    }

    /** Build the next band of rows of the level being prepared. */
    private void relevelBand() {
        boolean up = toLevel > level;
        int rows = Math.max(T, (toH / RELEVEL_FRAMES + T - 1) / T * T);
        rows = Math.min(rows, toH - rowsDone);
        int nn = 1 - cur, p = relevelProg;
        glBindImageTexture(0, worlds[cur], 0, false, 0, GL_READ_ONLY, GL_R32UI);
        glBindImageTexture(1, brights[nn], 0, false, 0, GL_WRITE_ONLY, GL_RGBA8);
        glBindImageTexture(2, worlds[nn], 0, false, 0, GL_WRITE_ONLY, GL_R32UI);
        glUseProgram(p);
        glUniform1ui(glGetUniformLocation(p, "now"), (int) (frameNo & 0xFFFFFF));
        glUniform1f(glGetUniformLocation(p, "ghostScale"), toLevel > 0 ? 0.15f : 1f);
        glUniform2i(glGetUniformLocation(p, "size"), texW, texH);
        glUniform2i(glGetUniformLocation(p, "area"), toW, toH);
        glUniform1i(glGetUniformLocation(p, "up"), up ? 1 : 0);
        glUniform1i(glGetUniformLocation(p, "row0"), rowsDone);
        long nx = toX, ny = toY;
        glUniform2i(glGetUniformLocation(p, "nb"), (int) Math.floorMod(nx, (long) texW), (int) Math.floorMod(ny, (long) texH));
        glUniform2i(glGetUniformLocation(p, "a"), (int) Math.floorMod(2 * nx, (long) texW), (int) Math.floorMod(2 * ny, (long) texH));
        glUniform2i(glGetUniformLocation(p, "b"), clampInt(2 * nx - covX), clampInt(2 * ny - covY));
        long hx = Math.floorDiv(nx, 2), hy = Math.floorDiv(ny, 2);
        glUniform2i(glGetUniformLocation(p, "d"), (int) Math.floorMod(hx, (long) texW), (int) Math.floorMod(hy, (long) texH));
        glUniform2i(glGetUniformLocation(p, "c"), clampInt(hx - covX), clampInt(hy - covY));
        glUniform2i(glGetUniformLocation(p, "p"), (int) Math.floorMod(nx, 2L), (int) Math.floorMod(ny, 2L));
        glDispatchCompute((toW + T - 1) / T, rows / T, 1);
        glMemoryBarrier(GL_SHADER_IMAGE_ACCESS_BARRIER_BIT);
        rowsDone += rows;
    }

    /** Swap to the level just built; slots are sorted out as they are next visited. */
    private void finishRelevel() {
        remapUp = toLevel > level;
        remapFrom = cur;
        remapCovX = covX;
        remapCovY = covY;
        remapSince = movingSince;
        remapTx0 = Math.floorDiv(toX, T);
        remapTy0 = Math.floorDiv(toY, T);
        remapTw = toW / T;
        remapTh = toH / T;
        remapEpoch++;
        cur = 1 - cur;
        level = toLevel;
        moving = false;
        warmClear();
    }

    private static int clampInt(long v) {
        return (int) Math.max(-(1 << 29), Math.min(1 << 29, v));
    }

    /** The toroidal slot holding world tile (tx, ty). */
    private int slotOf(long tx, long ty) {
        // Ints when they fit (they always do in practice): this is 32-bit ARM, where a long
        // division is a slow library call, and this runs for every tile.
        int x = (int) tx, y = (int) ty;
        if (x == tx && y == ty) return Math.floorMod(y, tilesH) * tilesW + Math.floorMod(x, tilesW);
        return (int) Math.floorMod(ty, (long) tilesH) * tilesW + (int) Math.floorMod(tx, (long) tilesW);
    }

    /** Nodes at level L for tiles [tx0, tx0 + gw) x [ty0, ty0 + gh), into grid (NONE: empty). */
    private void walk(Life.Tree t, int L, long tx0, long ty0, int gw, int gh) {
        long half = 1L << (t.level[t.root] - 1);
        walk(t, t.root, -half, -half, L, tx0 << L, ty0 << L, (tx0 + gw) << L, (ty0 + gh) << L, tx0, ty0, gw);
    }

    private void walk(Life.Tree t, int n, long ox, long oy, int L, long x0, long y0, long x1, long y1, long tx0, long ty0, int gw) {
        if (t.pop[n] == 0) return;
        long size = 1L << t.level[n];
        if (ox >= x1 || oy >= y1 || ox + size <= x0 || oy + size <= y0) return;
        if (t.level[n] == L) {
            int i = (int) ((ox >> L) - tx0), j = (int) ((oy >> L) - ty0);
            grid[j * gw + i] = n;
            return;
        }
        long q = size >> 1;
        walk(t, t.nw[n], ox, oy, L, x0, y0, x1, y1, tx0, ty0, gw);
        walk(t, t.ne[n], ox + q, oy, L, x0, y0, x1, y1, tx0, ty0, gw);
        walk(t, t.sw[n], ox, oy + q, L, x0, y0, x1, y1, tx0, ty0, gw);
        walk(t, t.se[n], ox + q, oy + q, L, x0, y0, x1, y1, tx0, ty0, gw);
    }

    void release() {
        glDeleteTextures(7, new int[]{worlds[0], worlds[1], brights[0], brights[1], stages[0], stages[1], stages[2]}, 0);
        glDeleteFramebuffers(1, new int[]{clearFbo}, 0);
        glDeleteBuffers(6, ssbos, 0);
        glDeleteProgram(merge);
        glDeleteProgram(shadeProg);
        glDeleteProgram(relevelProg);
    }
}
