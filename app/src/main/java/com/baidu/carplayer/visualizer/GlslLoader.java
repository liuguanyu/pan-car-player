package com.baidu.carplayer.visualizer;

import android.content.Context;
import android.opengl.GLES30;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * 从 {@code res/raw} 加载 GLSL 源码并编译成 program。
 *
 * <p>shader 以独立文本文件存放（{@code res/raw/xxx.glsl}），
 * 不再用 Java 字符串拼接：无需转义引号与 {@code \n}，编辑器有语法高亮，
 * 文件名由 {@code R.raw} 常量在编译期检查。
 */
public final class GlslLoader {
    private GlslLoader() {}

    /** 读 raw 资源为字符串。 */
    public static String read(Context context, int rawResId) {
        try (InputStream in = context.getResources().openRawResource(rawResId)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toString(StandardCharsets.UTF_8.name());
        } catch (IOException e) {
            throw new IllegalStateException("read raw shader failed: " + rawResId, e);
        }
    }

    private static int compile(int type, String src, String tag) {
        int s = GLES30.glCreateShader(type);
        GLES30.glShaderSource(s, src);
        GLES30.glCompileShader(s);
        int[] ok = {0};
        GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == 0) {
            String log = GLES30.glGetShaderInfoLog(s);
            GLES30.glDeleteShader(s);
            throw new IllegalStateException(tag + " compile: " + log);
        }
        return s;
    }

    /** 编译+链接，失败抛异常（含 shader 日志），由调用方决定回退。 */
    public static int program(Context context, int vertResId, int fragResId) {
        int v = compile(GLES30.GL_VERTEX_SHADER, read(context, vertResId), "vert#" + vertResId);
        int f = compile(GLES30.GL_FRAGMENT_SHADER, read(context, fragResId), "frag#" + fragResId);
        int p = GLES30.glCreateProgram();
        GLES30.glAttachShader(p, v);
        GLES30.glAttachShader(p, f);
        GLES30.glLinkProgram(p);
        GLES30.glDeleteShader(v);
        GLES30.glDeleteShader(f);
        int[] ok = {0};
        GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, ok, 0);
        if (ok[0] == 0) {
            String log = GLES30.glGetProgramInfoLog(p);
            GLES30.glDeleteProgram(p);
            throw new IllegalStateException("link: " + log);
        }
        return p;
    }
}
