package bot.tvhome;

import static android.opengl.GLES30.*;

import java.nio.ByteBuffer;

/**
 * Langton's ant and its many-colour generalisation: on each step the ant turns by the rule letter
 * for the colour it stands on, advances that cell to the next colour and moves forward. Simulated
 * on the CPU on a torus (an RL highway wrapping around the screen is the best part), uploaded as
 * an 8-bit texture; steps per frame ramp up over time. The board's size varies a little from run
 * to run, so the wrap-around collisions do not repeat.
 */
final class LangtonScene extends Scene {

    private static final String SHOW =
            "#version 300 es\n"
            + "precision mediump float;\n"
            + "in vec2 uv;\n"
            + "uniform sampler2D grid;\n"
            + "uniform float colours;\n"
            + "uniform vec2 ant;\n"
            + "uniform vec2 size;\n"
            + "out vec4 o;\n"
            + "void main() {\n"
            + "  vec2 q = vec2(uv.x, 1.0 - uv.y);\n"
            + "  float s = texture(grid, q).r * 255.0;\n"
            + "  vec2 cell = fract(q * size);\n"
            + "  float inset = step(0.12, cell.x) * step(0.12, cell.y);\n"
            + "  vec3 warm = vec3(0.94, 0.64, 0.35), cool = vec3(0.50, 0.72, 0.79);\n"
            + "  float k = s / max(1.0, colours - 1.0);\n"
            + "  vec3 col = s < 0.5 ? vec3(0.0) : mix(warm, cool, k) * (0.35 + 0.45 * k);\n"
            + "  col *= mix(0.7, 1.0, inset);\n"
            + "  float d = length((q - ant) * size);\n"
            + "  col = mix(col, vec3(1.0), smoothstep(1.6, 0.6, d));\n"
            + "  o = vec4(col, 1.0);\n"
            + "}\n";

    private static final String[] RULES = {"RL", "LLRR", "RRLLLRLLLRRR", "LRRRRRLLR", "LLRRRLRLRLLR"};

    private final java.util.Random random = new java.util.Random();
    private int W, H;
    private byte[] grid = new byte[0];
    private ByteBuffer upload;
    private int program, texture;
    private int ax, ay, dir;
    private volatile long steps, cells;
    private float debt, start = -1;

    @Override
    String title() {
        return "Langton's ant";
    }

    @Override
    Variant[] variants() {
        return new Variant[]{
            new Variant("RL", "Chris Langton, 1986", "About 10,000 steps of chaos, then it builds a highway"),
            new Variant("LLRR", "Greg Turk and Jim Propp", "Grows symmetrically, forever"),
            new Variant("RRLLLRLLLRRR", "Greg Turk and Jim Propp", "A filled triangle that grows and moves"),
            new Variant("LRRRRRLLR", "Greg Turk and Jim Propp", "Fills a growing square"),
            new Variant("LLRRRLRLRLLR", "Greg Turk and Jim Propp", "A convoluted highway"),
        };
    }

    @Override
    void create() {
        program = Gl.program(Gl.FULLSCREEN, SHOW);
        texture = 0;
    }

    @Override
    String[] counters() {
        return new String[]{"step", "cells"};
    }

    @Override
    long counter(int i) {
        return i == 0 ? steps : cells;
    }

    @Override
    void select(int v) {
        super.select(v);
        // About 360 x 203 cells (16:9), give or take 15%.
        W = 312 + random.nextInt(97);
        H = Math.round(W * 9 / 16f);
        grid = new byte[W * H];
        upload = ByteBuffer.allocateDirect(W * H);
        if (texture != 0) glDeleteTextures(1, new int[]{texture}, 0);
        texture = Gl.texture(GL_R8, W, H, GL_RED, GL_UNSIGNED_BYTE, GL_NEAREST, GL_REPEAT, null);
        steps = cells = 0;
        ax = W / 2;
        ay = H / 2;
        dir = 0;
        debt = 0;
        start = -1;
    }

    private void step(String rule) {
        int i = ay * W + ax;
        int c = grid[i];
        dir = (dir + (rule.charAt(c) == 'R' ? 1 : 3)) & 3;
        int next = (c + 1) % rule.length();
        grid[i] = (byte) next;
        if (c == 0) cells++;
        else if (next == 0) cells--;
        steps++;
        if (dir == 0) ay = (ay + H - 1) % H;
        else if (dir == 1) ax = (ax + 1) % W;
        else if (dir == 2) ay = (ay + 1) % H;
        else ax = (ax + W - 1) % W;
    }

    @Override
    void draw(float t) {
        String rule = RULES[variant];
        if (start < 0 || t < start) start = t;
        t -= start;
        // Slow enough to follow the ant at first, then quick enough to watch structures form.
        float perFrame = (float) Math.min(400, 2 + Math.pow(t, 1.6) * 0.6);
        debt += perFrame;
        while (debt >= 1) {
            step(rule);
            debt -= 1;
        }
        // On a torus the pattern eventually tangles with itself: start over.
        if (t > 300) select(variant);

        upload.position(0);
        upload.put(grid).position(0);
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, texture);
        glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
        glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, W, H, GL_RED, GL_UNSIGNED_BYTE, upload);
        glUseProgram(program);
        glUniform1i(glGetUniformLocation(program, "grid"), 0);
        glUniform1f(glGetUniformLocation(program, "colours"), rule.length());
        glUniform2f(glGetUniformLocation(program, "ant"), (ax + 0.5f) / W, (ay + 0.5f) / H);
        glUniform2f(glGetUniformLocation(program, "size"), W, H);
        Gl.drawFullscreen();
    }

    @Override
    void release() {
        glDeleteTextures(1, new int[]{texture}, 0);
        glDeleteProgram(program);
    }
}
