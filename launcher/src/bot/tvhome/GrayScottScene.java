package bot.tvhome;

import static android.opengl.GLES30.*;

import java.util.Random;

/**
 * Gray-Scott reaction-diffusion: two chemicals, A fed in and B eating it (A + 2B -> 3B), the
 * kind of system Alan Turing proposed in 1952 to explain animal markings. Simulated on the GPU
 * in half-float textures that swap roles every step, on a surface the display hardware scales
 * up (this GPU is shared with the UI, and full-HD shading starved it);
 * variants are John Pearson's (1993) classes.
 */
final class GrayScottScene extends Scene {

    // State is (1 - A, B) in half floats: both sit near zero in the background, where half
    // precision is finest, and half floats can be filtered. Four bilinear reads at the corners of
    // this texel sum to (4c + 2 edges + diagonals) / 4: an isotropic 9-point Laplacian from five
    // reads instead of nine.
    private static final String STEP =
            "#version 300 es\n"
            + "precision highp float;\n"
            + "uniform sampler2D state;\n"
            + "uniform float feed, kill;\n"
            + "uniform vec3 drop;\n"  // x, y in texels, radius (0 = none)
            + "out vec4 o;\n"
            + "void main() {\n"
            + "  vec2 p = gl_FragCoord.xy;\n"
            + "  vec2 inv = 1.0 / vec2(textureSize(state, 0));\n"
            + "  vec2 c = texture(state, p * inv).rg;\n"
            + "  vec2 s = texture(state, (p + vec2(0.5, 0.5)) * inv).rg + texture(state, (p + vec2(-0.5, 0.5)) * inv).rg\n"
            + "         + texture(state, (p + vec2(0.5, -0.5)) * inv).rg + texture(state, (p + vec2(-0.5, -0.5)) * inv).rg;\n"
            + "  // Scaled to match the classic (0.2 edges, 0.05 diagonals, -1 centre) stencil.\n"
            + "  vec2 lap = 0.3 * s - 1.2 * c;\n"
            + "  float a = 1.0 - c.r, b = c.g, abb = a * b * b;\n"
            + "  float da = -1.0 * lap.r - abb + feed * (1.0 - a);\n"
            + "  b += 0.5 * lap.g + abb - (kill + feed) * b;\n"
            + "  if (drop.z > 0.0 && distance(p, drop.xy) < drop.z) b = 0.6;\n"
            + "  o = vec4(clamp(c.r - da, 0.0, 1.0), clamp(b, 0.0, 1.0), 0.0, 1.0);\n"
            + "}\n";

    private static final String SEED =
            "#version 300 es\n"
            + "precision highp float;\n"
            + "uniform float seed;\n"
            + "out vec4 o;\n"
            + "float hash(vec2 q) { return fract(sin(dot(q, vec2(12.9898, 78.233)) + seed) * 43758.5453); }\n"
            + "void main() {\n"
            + "  vec2 cell = floor(gl_FragCoord.xy / 12.0);\n"
            + "  float b = hash(cell) > 0.965 ? 0.6 : 0.0;\n"
            + "  o = vec4(0.0, b, 0.0, 1.0);\n"
            + "}\n";

    private static final String SHOW =
            "#version 300 es\n"
            + "precision mediump float;\n"
            + "in vec2 uv;\n"
            + "uniform sampler2D view;\n"
            + "uniform vec2 px;\n"
            + "uniform vec3 ink, deep;\n"
            + "out vec4 o;\n"
            + "void main() {\n"
            + "  float b = texture(view, uv).g;\n"
            + "  float gx = texture(view, uv + vec2(px.x, 0.0)).g - texture(view, uv - vec2(px.x, 0.0)).g;\n"
            + "  float gy = texture(view, uv + vec2(0.0, px.y)).g - texture(view, uv - vec2(0.0, px.y)).g;\n"
            + "  vec3 n = normalize(vec3(-gx, -gy, 0.06));\n"
            + "  float light = clamp(dot(n, normalize(vec3(-0.4, 0.6, 0.7))), 0.0, 1.0);\n"
            + "  float v = smoothstep(0.08, 0.35, b);\n"
            + "  vec3 col = mix(deep, ink, v) * (0.55 + 0.6 * light);\n"
            + "  o = vec4(col, 1.0);\n"
            + "}\n";

    private static final float[][] PARAMS = {
        // feed, kill
        {0.0545f, 0.062f},
        {0.0367f, 0.0649f},
        {0.029f, 0.057f},
        {0.030f, 0.062f},
        {0.014f, 0.045f},
    };
    private static final float[][] COLOURS = {
        {0.94f, 0.64f, 0.35f}, {0.06f, 0.03f, 0.02f},
        {0.50f, 0.72f, 0.79f}, {0.02f, 0.04f, 0.06f},
        {0.85f, 0.82f, 0.76f}, {0.03f, 0.03f, 0.04f},
        {0.72f, 0.64f, 0.91f}, {0.03f, 0.02f, 0.05f},
        {0.94f, 0.55f, 0.30f}, {0.05f, 0.02f, 0.02f},
    };

    private static final int W = 384, H = 216, STEPS = 12;
    private final Random random = new Random();
    private int step, seedProgram, show;
    private Gl.Target a, b;
    private float lastDrop;

    @Override
    String title() {
        return "Reaction–diffusion";
    }

    @Override
    Variant[] variants() {
        String who = "John Pearson, 1993", what = "The Gray-Scott model, after Alan Turing's 1952 theory of animal markings";
        return new Variant[]{
            new Variant("Coral", who, what),
            new Variant("Mitosis", who, what),
            new Variant("Maze", who, what),
            new Variant("Solitons", who, what),
            new Variant("Waves", who, what),
        };
    }

    @Override
    void create() {
        step = Gl.program(Gl.FULLSCREEN, STEP);
        seedProgram = Gl.program(Gl.FULLSCREEN, SEED);
        show = Gl.program(Gl.FULLSCREEN, SHOW);
        a = new Gl.Target(GL_RG16F, W, H, GL_RG, GL_HALF_FLOAT, GL_LINEAR, GL_REPEAT);
        b = new Gl.Target(GL_RG16F, W, H, GL_RG, GL_HALF_FLOAT, GL_LINEAR, GL_REPEAT);
    }

    @Override
    float surfaceScale() {
        return 0.5f;  // a soft field magnified 5x: half resolution loses nothing
    }

    @Override
    void select(int v) {
        super.select(v);
        a.bind();
        glUseProgram(seedProgram);
        glUniform1f(glGetUniformLocation(seedProgram, "seed"), random.nextFloat() * 100);
        Gl.drawFullscreen();
        lastDrop = 0;
    }

    @Override
    void draw(float t) {
        float[] p = PARAMS[variant];
        glUseProgram(step);
        glUniform1f(glGetUniformLocation(step, "feed"), p[0]);
        glUniform1f(glGetUniformLocation(step, "kill"), p[1]);
        int dropLoc = glGetUniformLocation(step, "drop");
        // A new drop now and then keeps sparse patterns (solitons, waves) from dying out.
        boolean dropNow = t - lastDrop > 25 || t < lastDrop;
        for (int i = 0; i < STEPS; i++) {
            b.bind();
            glActiveTexture(GL_TEXTURE0);
            glBindTexture(GL_TEXTURE_2D, a.texture);
            glUniform1i(glGetUniformLocation(step, "state"), 0);
            if (dropNow && i == 0) {
                glUniform3f(dropLoc, random.nextFloat() * W, random.nextFloat() * H, 3 + random.nextFloat() * 4);
                lastDrop = t;
            } else {
                glUniform3f(dropLoc, 0, 0, 0);
            }
            Gl.drawFullscreen();
            Gl.Target swap = a;
            a = b;
            b = swap;
        }
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        glViewport(0, 0, width, height);
        glUseProgram(show);
        glBindTexture(GL_TEXTURE_2D, a.texture);
        glUniform1i(glGetUniformLocation(show, "view"), 0);
        glUniform2f(glGetUniformLocation(show, "px"), 1f / W, 1f / H);
        float[] ink = COLOURS[variant * 2], deep = COLOURS[variant * 2 + 1];
        glUniform3f(glGetUniformLocation(show, "ink"), ink[0], ink[1], ink[2]);
        glUniform3f(glGetUniformLocation(show, "deep"), deep[0], deep[1], deep[2]);
        Gl.drawFullscreen();
    }

    @Override
    void release() {
        a.release();
        b.release();
        glDeleteProgram(step);
        glDeleteProgram(seedProgram);
        glDeleteProgram(show);
    }
}
