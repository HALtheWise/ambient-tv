package bot.tvhome;

import static android.opengl.GLES30.*;

import java.nio.ByteBuffer;

/** Small OpenGL ES 3 helpers: programs, render targets and a full-screen triangle. */
final class Gl {

    /** Vertex shader for a full-screen triangle with no vertex buffers; passes uv in [0, 1]. */
    static final String FULLSCREEN =
            "#version 300 es\n"
            + "out vec2 uv;\n"
            + "void main() {\n"
            + "  vec2 p = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));\n"
            + "  uv = p;\n"
            + "  gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);\n"
            + "}\n";

    private Gl() {
    }

    static int program(String vertex, String fragment) {
        int p = glCreateProgram();
        glAttachShader(p, shader(GL_VERTEX_SHADER, vertex));
        glAttachShader(p, shader(GL_FRAGMENT_SHADER, fragment));
        glLinkProgram(p);
        int[] ok = new int[1];
        glGetProgramiv(p, GL_LINK_STATUS, ok, 0);
        if (ok[0] == 0) throw new IllegalStateException("link: " + glGetProgramInfoLog(p));
        return p;
    }

    private static int shader(int type, String source) {
        int s = glCreateShader(type);
        glShaderSource(s, source);
        glCompileShader(s);
        int[] ok = new int[1];
        glGetShaderiv(s, GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == 0) throw new IllegalStateException("compile: " + glGetShaderInfoLog(s) + "\n" + source);
        return s;
    }

    static void drawFullscreen() {
        glDrawArrays(GL_TRIANGLES, 0, 3);
    }

    static int texture(int internalFormat, int w, int h, int format, int type, int filter, int wrap, ByteBuffer data) {
        int[] t = new int[1];
        glGenTextures(1, t, 0);
        glBindTexture(GL_TEXTURE_2D, t[0]);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, filter);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, filter);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, wrap);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, wrap);
        glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
        glTexImage2D(GL_TEXTURE_2D, 0, internalFormat, w, h, 0, format, type, data);
        return t[0];
    }

    /** A texture plus the framebuffer that renders into it. */
    static final class Target {
        final int texture, framebuffer, width, height;

        Target(int internalFormat, int w, int h, int format, int type, int filter, int wrap) {
            width = w;
            height = h;
            texture = texture(internalFormat, w, h, format, type, filter, wrap, null);
            int[] f = new int[1];
            glGenFramebuffers(1, f, 0);
            framebuffer = f[0];
            glBindFramebuffer(GL_FRAMEBUFFER, framebuffer);
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texture, 0);
            int status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
            glBindFramebuffer(GL_FRAMEBUFFER, 0);
            if (status != GL_FRAMEBUFFER_COMPLETE) throw new IllegalStateException("framebuffer " + status);
        }

        void bind() {
            glBindFramebuffer(GL_FRAMEBUFFER, framebuffer);
            glViewport(0, 0, width, height);
        }

        void release() {
            glDeleteTextures(1, new int[]{texture}, 0);
            glDeleteFramebuffers(1, new int[]{framebuffer}, 0);
        }
    }
}
