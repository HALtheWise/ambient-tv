package bot.tvhome;

import static android.opengl.GLES30.*;

import java.nio.ByteBuffer;
import java.util.Random;

/**
 * The PSX Doom fire (1995), as described by Fabien Sanglard: a heat buffer seeded hot along the
 * bottom row; every step each pixel takes the heat of the one below it, shifted sideways by a
 * random wind and cooled a little. Uploaded as an 8-bit texture; the shader maps heat to colour.
 */
final class FireScene extends Scene {

    private static final int[] PALETTE = {
        0x070707, 0x1F0707, 0x2F0F07, 0x470F07, 0x571707, 0x671F07, 0x771F07, 0x8F2707, 0x9F2F07, 0xAF3F07,
        0xBF4707, 0xC74707, 0xDF4F07, 0xDF5707, 0xDF5707, 0xD75F07, 0xD75F07, 0xD7670F, 0xCF6F0F, 0xCF770F,
        0xCF7F0F, 0xCF8717, 0xC78717, 0xC78F17, 0xC7971F, 0xBF9F1F, 0xBF9F1F, 0xBFA727, 0xBFA727, 0xBFAF2F,
        0xB7AF2F, 0xB7B72F, 0xB7B737, 0xCFCF6F, 0xDFDF9F, 0xEFEFC7, 0xFFFFFF,
    };
    private static final int TOP = PALETTE.length - 1;

    private static final String FRAGMENT =
            "#version 300 es\n"
            + "precision mediump float;\n"
            + "in vec2 uv;\n"
            + "uniform sampler2D heat;\n"
            + "uniform sampler2D palette;\n"
            + "out vec4 o;\n"
            + "void main() {\n"
            + "  float h = texture(heat, vec2(uv.x, 1.0 - uv.y)).r * 255.0;\n"
            + "  vec3 c = texture(palette, vec2((h + 0.5) / 37.0, 0.5)).rgb;\n"
            + "  o = vec4(c, 1.0);\n"
            + "}\n";

    private static final int W = 320, H = 180;
    private final byte[] fire = new byte[W * H];
    private final ByteBuffer upload = ByteBuffer.allocateDirect(W * H);
    private final Random random = new Random();
    private int program, heatTex, paletteTex;
    private float lastStep;

    @Override
    String title() {
        return "Fireplace";
    }

    @Override
    Variant[] variants() {
        return new Variant[]{
            new Variant("Hearth", "PSX Doom, 1995", "The PlayStation port's fire effect, as a hearth"),
            new Variant("Doom", "PSX Doom, 1995", "Unfiltered pixels, as Fabien Sanglard took it apart"),
            new Variant("Embers", "PSX Doom, 1995", "Burning low"),
        };
    }

    @Override
    void create() {
        program = Gl.program(Gl.FULLSCREEN, FRAGMENT);
        heatTex = Gl.texture(GL_R8, W, H, GL_RED, GL_UNSIGNED_BYTE, GL_LINEAR, GL_CLAMP_TO_EDGE, null);
        ByteBuffer pal = ByteBuffer.allocateDirect(PALETTE.length * 4);
        for (int c : PALETTE) {
            pal.put((byte) (c >> 16)).put((byte) (c >> 8)).put((byte) c).put((byte) 255);
        }
        pal.position(0);
        paletteTex = Gl.texture(GL_RGBA8, PALETTE.length, 1, GL_RGBA, GL_UNSIGNED_BYTE, GL_LINEAR, GL_CLAMP_TO_EDGE, pal);
    }

    @Override
    void select(int v) {
        super.select(v);
        java.util.Arrays.fill(fire, (byte) 0);
        seed();
        glBindTexture(GL_TEXTURE_2D, heatTex);
        int filter = v == 1 ? GL_NEAREST : GL_LINEAR;
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, filter);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, filter);
        lastStep = 0;
        // Let the flames reach their height before the first frame is shown.
        for (int i = 0; i < 120; i++) spread();
    }

    private void seed() {
        for (int x = 0; x < W; x++) {
            int heat = TOP;
            if (variant == 0) {
                // A bed of coals: hottest in the middle, tapering to nothing at the sides.
                double d = Math.abs(x - W / 2.0) / (W * 0.34);
                double k = Math.max(0, 1 - d * d);
                heat = (int) Math.round(TOP * Math.sqrt(k) * (0.85 + 0.15 * random.nextDouble()));
            } else if (variant == 2) {
                heat = 14 + random.nextInt(10);
            }
            fire[(H - 1) * W + x] = (byte) heat;
        }
    }

    private void spread() {
        float cooling = variant == 2 ? 1.6f : variant == 0 ? 1.05f : 0.85f;
        for (int x = 0; x < W; x++) {
            for (int y = 1; y < H; y++) {
                int src = y * W + x;
                int heat = fire[src] & 0xFF;
                if (heat == 0) {
                    fire[src - W] = 0;
                    continue;
                }
                int r = random.nextInt(4);
                int dst = Math.min(Math.max(src - r + 1, W), W * H - 1);
                int cool = (r & 1) + (random.nextFloat() < cooling - 1 ? 1 : 0);
                fire[dst - W] = (byte) Math.max(0, heat - cool);
            }
        }
        if (variant != 1 && random.nextInt(variant == 2 ? 6 : 10) == 0) seed();
    }

    @Override
    void draw(float t) {
        // The original ran its simulation at about 27 steps a second.
        if (t - lastStep > 1 / 27f || t < lastStep) {
            lastStep = t;
            spread();
        }
        upload.position(0);
        upload.put(fire).position(0);
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, heatTex);
        glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
        glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, W, H, GL_RED, GL_UNSIGNED_BYTE, upload);
        glActiveTexture(GL_TEXTURE1);
        glBindTexture(GL_TEXTURE_2D, paletteTex);
        glUseProgram(program);
        glUniform1i(glGetUniformLocation(program, "heat"), 0);
        glUniform1i(glGetUniformLocation(program, "palette"), 1);
        Gl.drawFullscreen();
        glActiveTexture(GL_TEXTURE0);
    }

    @Override
    void release() {
        glDeleteTextures(2, new int[]{heatTex, paletteTex}, 0);
        glDeleteProgram(program);
    }
}
