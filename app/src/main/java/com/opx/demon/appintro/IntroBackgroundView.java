package com.opx.demon.appintro;

import android.animation.ValueAnimator;
import android.annotation.TargetApi;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RadialGradient;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.os.Build;
import android.util.AttributeSet;
import android.view.View;

public class IntroBackgroundView extends View {

    private static final int[] LIGHT_BG = {0xFFFDFEFF, 0xFFECF5FF};
    private static final int[] DARK_BG = {0xFF060E21, 0xFF02050D};
    private static final int[] LIGHT_BLOBS = {0xB866C2FF, 0x8C3894FF, 0xC794E0FF, 0x8052A8FF};
    private static final int[] DARK_BLOBS = {0x991F6BFF, 0x804DB8FF, 0xA61A42D9, 0x805ABFFF};

    private static final float[][] BASE = {{-0.20f, -0.30f}, {0.24f, -0.05f}, {-0.14f, 0.20f}, {0.16f, 0.34f}};
    private static final float[] RADIUS = {0.22f, 0.19f, 0.21f, 0.16f};
    private static final float[] SPIN = {0.55f, -0.75f, 0.95f, -0.40f};

    private final Paint shaderPaint = new Paint();
    private final Paint bgLight = new Paint(), bgDark = new Paint();
    private final Paint blobPaint = new Paint();
    private final PorterDuffXfermode add = new PorterDuffXfermode(PorterDuff.Mode.ADD);
    private final Matrix matrix = new Matrix();
    private final RadialGradient[] lightGlows = new RadialGradient[4];
    private final RadialGradient[] darkGlows = new RadialGradient[4];
    private final float[] center = new float[2];
    private Object shader;

    private long lastNanos;
    private float t, reveal;
    private float pageTarget, page;
    private Boolean forcedDark;
    private boolean systemDark;
    private float dark = -1f;

    public IntroBackgroundView(Context context) {
        this(context, null);
    }

    public IntroBackgroundView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public IntroBackgroundView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        systemDark = isNight(getResources().getConfiguration());
        for (int i = 0; i < 4; i++) {
            lightGlows[i] = glow(LIGHT_BLOBS[i]);
            darkGlows[i] = glow(DARK_BLOBS[i]);
        }
        if (Build.VERSION.SDK_INT >= 33) shader = new IntroShader();
    }

    public void setPageOffset(float position) {
        pageTarget = position;
    }

    public void setDarkTheme(Boolean dark) {
        forcedDark = dark;
        invalidate();
    }

    public void replayReveal() {
        t = 0f;
        invalidate();
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        systemDark = isNight(newConfig);
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        bgLight.setShader(new LinearGradient(0, 0, 0, h, LIGHT_BG[0], LIGHT_BG[1], Shader.TileMode.CLAMP));
        bgDark.setShader(new LinearGradient(0, 0, 0, h, DARK_BG[0], DARK_BG[1], Shader.TileMode.CLAMP));
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        lastNanos = 0;
        invalidate();
    }

    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (visibility == VISIBLE) {
            lastNanos = 0;
            invalidate();
        }
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (visibility == VISIBLE) {
            lastNanos = 0;
            invalidate();
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        long now = System.nanoTime();
        float dt = lastNanos == 0 ? 1f / 60f : Math.min(0.05f, (now - lastNanos) / 1e9f);
        lastNanos = now;
        float pace = animationsEnabled() ? 1f : 0.3f;
        t += dt * pace;

        float targetDark = (forcedDark != null ? forcedDark : systemDark) ? 1f : 0f;
        if (dark < 0f) dark = targetDark;
        dark += (targetDark - dark) * (1f - (float) Math.exp(-dt * 4f));
        page += (pageTarget - page) * (1f - (float) Math.exp(-dt * 14f));
        reveal = ease(clamp(t / 1.8f, 0f, 1f));

        int w = getWidth(), h = getHeight();
        if (w == 0 || h == 0) return;

        if (Build.VERSION.SDK_INT >= 33 && shader != null) {
            ((IntroShader) shader).draw(canvas, shaderPaint, w, h, t, dark, page, reveal);
        } else {
            drawFallback(canvas, w, h);
        }

        boolean settledTheme = Math.abs(targetDark - dark) < 0.001f;
        if (getWindowVisibility() == VISIBLE && isShown()) scheduleNextFrame(settledTheme);
        else if (!settledTheme) invalidate();
    }

    private static final long IDLE_FRAME_MS = 33;

    private void scheduleNextFrame(boolean settledTheme) {
        boolean moving = !settledTheme
                || reveal < 1f
                || Math.abs(pageTarget - page) > 0.002f;
        if (moving) postInvalidateOnAnimation();
        else postInvalidateDelayed(IDLE_FRAME_MS);
    }

    private void drawFallback(Canvas canvas, int w, int h) {
        int darkAlpha = (int) (dark * 255);
        if (darkAlpha < 255) canvas.drawRect(0, 0, w, h, bgLight);
        if (darkAlpha > 0) {
            bgDark.setAlpha(darkAlpha);
            canvas.drawRect(0, 0, w, h, bgDark);
        }
        float rv = 0.15f + 0.85f * reveal;
        float sx = clamp((float) w / h * 1.2f, 0.55f, 1.8f);
        for (int pass = 0; pass < 2; pass++) {
            float a = pass == 0 ? 1f - dark : dark;
            if (a < 0.001f) continue;
            blobPaint.setXfermode(pass == 0 ? null : add);
            for (int i = 0; i < 4; i++) {
                blobCenter(i, sx);
                float cx = center[0] * h + w * 0.5f, cy = center[1] * h + h * 0.5f;
                float r = RADIUS[i] * rv * h * 1.9f;
                RadialGradient g = pass == 0 ? lightGlows[i] : darkGlows[i];
                matrix.setScale(r, r);
                matrix.postTranslate(cx, cy);
                g.setLocalMatrix(matrix);
                blobPaint.setShader(g);
                blobPaint.setAlpha((int) (a * 255));
                canvas.drawCircle(cx, cy, r, blobPaint);
            }
        }
        blobPaint.setShader(null);
    }

    private void blobCenter(int i, float sx) {
        float ang = page * SPIN[i];
        float c = (float) Math.cos(ang), s = (float) Math.sin(ang);
        float bx = BASE[i][0], by = BASE[i][1];
        float x = c * bx - s * by, y = s * bx + c * by;
        switch (i) {
            case 0: x += 0.10f * sin(t * 0.21f);        y += 0.10f * cos(t * 0.17f); break;
            case 1: x += 0.09f * cos(t * 0.19f + 1f);   y += 0.09f * sin(t * 0.23f); break;
            case 2: x += 0.11f * sin(t * 0.16f + 2f);   y += 0.11f * cos(t * 0.20f + 0.5f); break;
            default: x += 0.08f * cos(t * 0.27f + 3f);  y += 0.08f * sin(t * 0.18f + 1.5f); break;
        }
        center[0] = x * sx;
        center[1] = y;
    }

    private static RadialGradient glow(int color) {
        int a = color >>> 24, rgb = color & 0x00FFFFFF;
        return new RadialGradient(0f, 0f, 1f,
                new int[]{(a << 24) | rgb, ((int) (a * 0.55f) << 24) | rgb, ((int) (a * 0.15f) << 24) | rgb, rgb},
                new float[]{0f, 0.3f, 0.62f, 1f}, Shader.TileMode.CLAMP);
    }

    @TargetApi(33)
    private static final class IntroShader {
        private static final String SRC =
                "uniform float2 uRes;\n" +
                "uniform float uTime;\n" +
                "uniform float uDark;\n" +
                "uniform float uPage;\n" +
                "uniform float uReveal;\n" +
                "\n" +
                "float2 rot(float2 v, float a) { float c = cos(a); float s = sin(a); return float2(c * v.x - s * v.y, s * v.x + c * v.y); }\n" +
                "float gauss(float2 p, float2 c, float r) { float2 d = p - c; return exp(-dot(d, d) / (r * r)); }\n" +
                "\n" +
                "float height(float2 p, float2 c0, float2 c1, float2 c2, float2 c3, float rv, float t) {\n" +
                "  float h = gauss(p, c0, 0.22 * rv) + 0.8 * gauss(p, c1, 0.19 * rv) + 0.9 * gauss(p, c2, 0.21 * rv) + 0.6 * gauss(p, c3, 0.16 * rv);\n" +
                "  h += 0.10 * sin(p.x * 3.1 + t * 0.23 + uPage * 0.8) * cos(p.y * 2.7 - t * 0.19);\n" +
                "  h += 0.05 * sin((p.x + p.y) * 5.3 - t * 0.31);\n" +
                "  return h;\n" +
                "}\n" +
                "\n" +
                "half4 main(float2 fc) {\n" +
                "  float2 p = (fc - 0.5 * uRes) / uRes.y;\n" +
                "  float t = uTime;\n" +
                "  float pg = uPage;\n" +
                "  float asp = uRes.x / uRes.y;\n" +
                "  float sx = clamp(asp * 1.2, 0.55, 1.8);\n" +
                "  float rv = 0.15 + 0.85 * uReveal;\n" +
                "\n" +
                "  float2 c0 = rot(float2(-0.20, -0.30), pg * 0.55) + 0.10 * float2(sin(t * 0.21), cos(t * 0.17));\n" +
                "  float2 c1 = rot(float2(0.24, -0.05), -pg * 0.75) + 0.09 * float2(cos(t * 0.19 + 1.0), sin(t * 0.23));\n" +
                "  float2 c2 = rot(float2(-0.14, 0.20), pg * 0.95) + 0.11 * float2(sin(t * 0.16 + 2.0), cos(t * 0.20 + 0.5));\n" +
                "  float2 c3 = rot(float2(0.16, 0.34), -pg * 0.40) + 0.08 * float2(cos(t * 0.27 + 3.0), sin(t * 0.18 + 1.5));\n" +
                "  c0.x *= sx; c1.x *= sx; c2.x *= sx; c3.x *= sx;\n" +
                "\n" +
                "  float g0 = gauss(p, c0, 0.22 * rv);\n" +
                "  float g1 = gauss(p, c1, 0.19 * rv);\n" +
                "  float g2 = gauss(p, c2, 0.21 * rv);\n" +
                "  float g3 = gauss(p, c3, 0.16 * rv);\n" +
                "  float vy = fc.y / uRes.y;\n" +
                "\n" +
                "  float3 L = mix(float3(0.992, 0.996, 1.0), float3(0.925, 0.962, 1.0), vy);\n" +
                "  L = mix(L, float3(0.40, 0.76, 1.0), g0 * 0.72);\n" +
                "  L = mix(L, float3(0.22, 0.58, 1.0), g1 * 0.55);\n" +
                "  L = mix(L, float3(0.58, 0.88, 1.0), g2 * 0.78);\n" +
                "  L = mix(L, float3(0.32, 0.66, 1.0), g3 * 0.50);\n" +
                "  float pool = clamp(g0 + g1 + g2 + g3, 0.0, 1.0);\n" +
                "\n" +
                "  float3 D = mix(float3(0.024, 0.055, 0.13), float3(0.008, 0.018, 0.05), vy);\n" +
                "  D += float3(0.12, 0.42, 1.0) * g0 * 0.6 + float3(0.30, 0.72, 1.0) * g1 * 0.5;\n" +
                "  D += float3(0.10, 0.26, 0.85) * g2 * 0.65 + float3(0.35, 0.75, 1.0) * g3 * 0.5;\n" +
                "\n" +
                "  float eps = 1.0 / uRes.y;\n" +
                "  float h = height(p, c0, c1, c2, c3, rv, t);\n" +
                "  float hx = height(p + float2(eps, 0.0), c0, c1, c2, c3, rv, t);\n" +
                "  float hy = height(p + float2(0.0, eps), c0, c1, c2, c3, rv, t);\n" +
                "  float grad = length(float2(hx - h, hy - h));\n" +
                "  float K = 9.0;\n" +
                "  float v = h * K - t * 0.08;\n" +
                "  float dist = abs(fract(v + 0.5) - 0.5) / max(grad * K, 1e-5);\n" +
                "  float line = 1.0 - smoothstep(0.35, 1.35, dist);\n" +
                "  float major = 1.0 - step(0.01, fract(floor(v + 0.5) * 0.25));\n" +
                "  float la = line * (0.5 + 0.5 * major) * smoothstep(0.35, 1.0, uReveal);\n" +
                "\n" +
                "  L = mix(L, float3(1.0, 1.0, 1.0), la * 0.55 * pool);\n" +
                "  L = mix(L, float3(0.30, 0.58, 1.0), la * 0.13 * (1.0 - pool));\n" +
                "  D += float3(0.50, 0.82, 1.0) * la * 0.22;\n" +
                "\n" +
                "  float2 q = fc / uRes - 0.5;\n" +
                "  D *= 1.0 - dot(q, q) * 0.7;\n" +
                "  float3 col = mix(L, D, uDark);\n" +
                "  col += (fract(sin(dot(fc + fract(t) * 37.0, float2(12.9898, 78.233))) * 43758.5453) - 0.5) / 255.0;\n" +
                "  return half4(half3(col), 1.0);\n" +
                "}\n";

        private final RuntimeShader shader = new RuntimeShader(SRC);

        void draw(Canvas canvas, Paint paint, int w, int h, float time, float dark, float page, float reveal) {
            shader.setFloatUniform("uRes", w, h);
            shader.setFloatUniform("uTime", time);
            shader.setFloatUniform("uDark", dark);
            shader.setFloatUniform("uPage", page);
            shader.setFloatUniform("uReveal", reveal);
            paint.setShader(shader);
            canvas.drawRect(0, 0, w, h, paint);
        }
    }

    private static boolean isNight(Configuration c) {
        return (c.uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
    }

    private static boolean animationsEnabled() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.O || ValueAnimator.areAnimatorsEnabled();
    }

    private static float sin(float v) {
        return (float) Math.sin(v);
    }

    private static float cos(float v) {
        return (float) Math.cos(v);
    }

    private static float ease(float x) {
        return x < .5f ? 4f * x * x * x : 1f - (float) Math.pow(-2f * x + 2f, 3) / 2f;
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
