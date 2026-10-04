package com.opx.demon.about;

import android.animation.ValueAnimator;
import android.annotation.TargetApi;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.os.Build;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import java.util.Random;

public class ThanksBackgroundView extends View {

    private static final int NIGHT_TOP = 0xFF06101E;
    private static final int NIGHT_BOTTOM = 0xFF01040A;
    private static final int NIGHT_ICE = 0xFFA6E3FF;
    private static final int NIGHT_SKY = 0xFF62C6FF;

    private static final int DAY_TOP = 0xFFD8EAFB;
    private static final int DAY_BOTTOM = 0xFFFFFFFF;
    private static final int DAY_ICE = 0xFF2E7BB8;
    private static final int DAY_SKY = 0xFF1565C0;

    private boolean dark;
    private int skyTop, skyBottom, ice, sky;

    private static final int CAP = 320;
    private static final int AMBIENT = 0, BURST = 1, SPARK = 2;
    private final float[] px = new float[CAP], py = new float[CAP], vx = new float[CAP], vy = new float[CAP];
    private final float[] size = new float[CAP], phase = new float[CAP], age = new float[CAP], life = new float[CAP];
    private final int[] kind = new int[CAP];
    private final boolean[] alive = new boolean[CAP];
    private int ambientTarget;

    private final Paint spritePaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint skyPaint = new Paint();
    private final Paint auroraPaint = new Paint();
    private final RectF dst = new RectF();
    private final Matrix matrix = new Matrix();
    private final Random rnd = new Random();
    private Bitmap sprite;
    private RadialGradient softGlow;
    private Object aurora;

    private final float density;
    private long lastNanos;
    private float t, introAmt;
    private boolean introPlayed;
    private float touchX, touchY;
    private boolean touching;
    private float nextStar = 3f;
    private float starT = -1f, starX, starY, starDX, starDY;

    public ThanksBackgroundView(Context context) {
        this(context, null);
    }

    public ThanksBackgroundView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public ThanksBackgroundView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        density = getResources().getDisplayMetrics().density;

        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeCap(Paint.Cap.ROUND);

        applyTheme(isNightMode(context));

        if (Build.VERSION.SDK_INT >= 33) aurora = new AuroraShader();
    }

    public void replayIntro() {
        introPlayed = false;
        t = 0f;
        for (int i = 0; i < CAP; i++) alive[i] = false;
        invalidate();
    }

    private static boolean isNightMode(Context context) {
        int mode = context.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK;
        return mode == Configuration.UI_MODE_NIGHT_YES;
    }

    private void applyTheme(boolean night) {
        dark = night;
        skyTop = night ? NIGHT_TOP : DAY_TOP;
        skyBottom = night ? NIGHT_BOTTOM : DAY_BOTTOM;
        ice = night ? NIGHT_ICE : DAY_ICE;
        sky = night ? NIGHT_SKY : DAY_SKY;

        PorterDuffXfermode blend = night ? new PorterDuffXfermode(PorterDuff.Mode.ADD) : null;
        spritePaint.setXfermode(blend);
        strokePaint.setXfermode(blend);
        fillPaint.setXfermode(blend);

        if (sprite != null) sprite.recycle();
        sprite = makeSprite((int) (48 * density), night, ice, sky);

        softGlow = new RadialGradient(0f, 0f, 1f,
                new int[]{withAlpha(sky, night ? 0.30f : 0.22f),
                        withAlpha(sky, night ? 0.10f : 0.08f),
                        withAlpha(sky, 0f)},
                new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP);

        if (getHeight() > 0) rebuildSkyGradient(getHeight());
        invalidate();
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        boolean night = (newConfig.uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        if (night != dark) applyTheme(night);
    }

    private void rebuildSkyGradient(int h) {
        skyPaint.setShader(new LinearGradient(0, 0, 0, h, skyTop, skyBottom, Shader.TileMode.CLAMP));
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        rebuildSkyGradient(h);
        ambientTarget = Math.min(110, Math.max(40, (int) (w * h / (density * density) / 3200f)));
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
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        lastNanos = 0;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                touching = true;
                touchX = e.getX();
                touchY = e.getY();
                sparks(touchX, touchY, 14);
                return true;
            case MotionEvent.ACTION_MOVE:
                float mx = e.getX(), my = e.getY();
                if (Math.hypot(mx - touchX, my - touchY) > 18 * density) sparks(mx, my, 3);
                touchX = mx;
                touchY = my;
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                touching = false;
                if (e.getActionMasked() == MotionEvent.ACTION_UP) performClick();
                return true;
            default:
                return super.onTouchEvent(e);
        }
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        long now = System.nanoTime();
        float dt = lastNanos == 0 ? 1f / 60f : Math.min(0.05f, (now - lastNanos) / 1e9f);
        lastNanos = now;
        float pace = animationsEnabled() ? 1f : 0.35f;
        t += dt * pace;

        final int w = getWidth(), h = getHeight();
        if (w == 0 || h == 0) return;
        final float cx = w / 2f, cy = h * 0.42f, minDim = Math.min(w, h);

        if (!introPlayed) {
            introPlayed = true;
            burst(cx, cy, minDim * 0.16f);
        }
        float intro = ease(clamp((t - 0.5f) / 2.8f, 0f, 1f));
        introAmt = intro;

        if (Build.VERSION.SDK_INT >= 33 && aurora != null) {
            ((AuroraShader) aurora).draw(canvas, auroraPaint, w, h, t, intro, dark);
        } else {
            canvas.drawRect(0, 0, w, h, skyPaint);
            drawFallbackAurora(canvas, w, h, intro);
        }

        if (t < 1.8f) {
            float e = t / 1.8f, inv = 1f - e;
            float r = minDim * (0.16f + 0.55f * ease(e));
            strokePaint.setStrokeWidth((2f + 14f * inv) * density);
            strokePaint.setColor(withAlpha(ice, (dark ? 0.22f : 0.32f) * inv * inv));
            canvas.drawCircle(cx, cy, r, strokePaint);
            strokePaint.setStrokeWidth((1f + 2.5f * inv) * density);
            strokePaint.setColor(withAlpha(dark ? 0xFFFFFFFF : sky, 0.5f * inv * inv));
            canvas.drawCircle(cx, cy, r, strokePaint);
            drawRadial(canvas, cx, cy, minDim * 0.5f * inv, 1f, 1f, inv);
        }

        updateParticles(dt * pace, w, h);
        drawParticles(canvas);
        updateAndDrawShootingStar(canvas, dt * pace, w, h, intro);

        if (getWindowVisibility() == VISIBLE && isShown()) postInvalidateOnAnimation();
    }

    private int spawn(int k) {
        for (int i = 0; i < CAP; i++) {
            if (!alive[i]) {
                alive[i] = true;
                kind[i] = k;
                age[i] = 0f;
                phase[i] = rnd.nextFloat() * 6.2832f;
                return i;
            }
        }
        return -1;
    }

    private void burst(float cx, float cy, float radius) {
        for (int n = 0; n < 150; n++) {
            int i = spawn(BURST);
            if (i < 0) return;
            float a = rnd.nextFloat() * 6.2832f;
            float sp = (60f + rnd.nextFloat() * 260f) * density;
            px[i] = cx + (float) Math.cos(a) * radius;
            py[i] = cy + (float) Math.sin(a) * radius;
            vx[i] = (float) Math.cos(a) * sp;
            vy[i] = (float) Math.sin(a) * sp;
            size[i] = (3f + rnd.nextFloat() * 7f) * density;
            life[i] = 4f + rnd.nextFloat() * 6f;
        }
    }

    private void sparks(float x, float y, int count) {
        for (int n = 0; n < count; n++) {
            int i = spawn(SPARK);
            if (i < 0) return;
            float a = rnd.nextFloat() * 6.2832f;
            float sp = (40f + rnd.nextFloat() * 160f) * density;
            px[i] = x;
            py[i] = y;
            vx[i] = (float) Math.cos(a) * sp;
            vy[i] = (float) Math.sin(a) * sp;
            size[i] = (2.5f + rnd.nextFloat() * 5f) * density;
            life[i] = 1.2f + rnd.nextFloat() * 1.6f;
        }
    }

    private void spawnAmbient(int w, int h, boolean anywhere) {
        int i = spawn(AMBIENT);
        if (i < 0) return;
        px[i] = rnd.nextFloat() * w;
        py[i] = anywhere ? rnd.nextFloat() * h : h + 20 * density;
        vx[i] = 0f;
        vy[i] = 0f;
        size[i] = (2f + (float) Math.pow(rnd.nextFloat(), 2.5) * 10f) * density;
        life[i] = 14f + rnd.nextFloat() * 16f;
        if (anywhere) age[i] = rnd.nextFloat() * life[i] * 0.5f;
    }

    private void updateParticles(float dt, int w, int h) {
        int ambient = 0;
        float repelR = 110 * density, repelR2 = repelR * repelR;
        float relax = 1f - (float) Math.exp(-dt * 1.3f);

        for (int i = 0; i < CAP; i++) {
            if (!alive[i]) continue;
            age[i] += dt;

            float rise = (10f + size[i] / density * 2.2f) * density;
            float tx = (float) Math.sin(t * 0.5f + phase[i]) * 7f * density;
            float ty = -rise;
            if (kind[i] == BURST || kind[i] == SPARK) {
                float drag = (float) Math.exp(-dt * (kind[i] == SPARK ? 3.2f : 2.0f));
                vx[i] = vx[i] * drag + tx * (1f - drag);
                vy[i] = vy[i] * drag + ty * (1f - drag);
            } else {
                vx[i] += (tx - vx[i]) * relax;
                vy[i] += (ty - vy[i]) * relax;
            }

            if (touching) {
                float dx = px[i] - touchX, dy = py[i] - touchY, d2 = dx * dx + dy * dy;
                if (d2 < repelR2 && d2 > 1f) {
                    float d = (float) Math.sqrt(d2);
                    float f = (1f - d / repelR) * 1400f * density * dt / d;
                    vx[i] += dx * f;
                    vy[i] += dy * f;
                }
            }

            px[i] += vx[i] * dt;
            py[i] += vy[i] * dt;

            if (age[i] > life[i] || py[i] < -40 * density
                    || px[i] < -60 * density || px[i] > w + 60 * density) {
                alive[i] = false;
                continue;
            }
            if (kind[i] == AMBIENT) ambient++;
        }

        int missing = ambientTarget - ambient;
        boolean anywhere = t < 0.2f;
        for (int n = 0; n < missing && (anywhere || n < 2); n++) spawnAmbient(w, h, anywhere);
    }

    private void drawParticles(Canvas canvas) {
        float twinkle = dark ? 0.4f : 0.18f;
        float weight = dark ? 0.85f : 0.55f;

        for (int i = 0; i < CAP; i++) {
            if (!alive[i]) continue;
            float lf = age[i] / life[i];
            float fade = Math.min(1f, age[i] / 0.6f) * (1f - lf * lf * lf);
            if (kind[i] == AMBIENT) fade *= Math.min(1f, age[i] / 2.5f) * introAmt;
            float tw = (1f - twinkle)
                    + twinkle * (float) Math.sin(t * (1.6f + (phase[i] % 1f) * 2f) + phase[i] * 5f);
            float a = clamp(fade * tw * (kind[i] == SPARK ? 1f : weight), 0f, 1f);
            if (a < 0.01f) continue;
            float s = size[i] * (0.85f + 0.3f * tw) * (kind[i] == SPARK ? 1.2f : 1f);
            dst.set(px[i] - s, py[i] - s, px[i] + s, py[i] + s);
            spritePaint.setAlpha((int) (a * 255f));
            canvas.drawBitmap(sprite, null, dst, spritePaint);
        }
    }

    private void updateAndDrawShootingStar(Canvas canvas, float dt, int w, int h, float intro) {
        if (intro < 1f) return;
        if (starT < 0f) {
            nextStar -= dt;
            if (nextStar <= 0f) {
                starT = 0f;
                boolean ltr = rnd.nextBoolean();
                starX = w * (ltr ? 0.1f + rnd.nextFloat() * 0.4f : 0.5f + rnd.nextFloat() * 0.4f);
                starY = h * (0.04f + rnd.nextFloat() * 0.22f);
                float speed = Math.max(w, h) * (0.55f + rnd.nextFloat() * 0.3f);
                float ang = (float) Math.toRadians(18 + rnd.nextFloat() * 22);
                starDX = (ltr ? 1 : -1) * (float) Math.cos(ang) * speed;
                starDY = (float) Math.sin(ang) * speed;
            }
            return;
        }
        starT += dt;
        float dur = 0.9f;
        if (starT > dur) {
            starT = -1f;
            nextStar = 5f + rnd.nextFloat() * 5f;
            return;
        }
        float e = starT / dur;
        float env = (float) Math.sin(Math.PI * e);
        float hx = starX + starDX * starT, hy = starY + starDY * starT;
        float tail = 0.16f;
        int seg = 10;
        for (int k = 0; k < seg; k++) {
            float f0 = (float) k / seg, f1 = (float) (k + 1) / seg;
            float x0 = hx - starDX * tail * f0, y0 = hy - starDY * tail * f0;
            float x1 = hx - starDX * tail * f1, y1 = hy - starDY * tail * f1;
            float fade = 1f - f0;
            strokePaint.setStrokeWidth((0.6f + 2.2f * fade) * density);
            int head = dark ? 0xFFFFFFFF : sky;
            strokePaint.setColor(withAlpha(k < 2 ? head : ice,
                    (dark ? 0.75f : 0.55f) * env * fade * fade));
            canvas.drawLine(x0, y0, x1, y1, strokePaint);
        }
        float s = 7f * density * (0.6f + 0.4f * env);
        dst.set(hx - s, hy - s, hx + s, hy + s);
        spritePaint.setAlpha((int) (255 * env * (dark ? 1f : 0.7f)));
        canvas.drawBitmap(sprite, null, dst, spritePaint);
    }

    private void drawFallbackAurora(Canvas canvas, int w, int h, float intro) {
        if (intro <= 0f) return;
        for (int i = 0; i < 3; i++) {
            float s = t * (0.05f + 0.02f * i);
            float x = w * (0.5f + 0.32f * (float) Math.sin(s * 2.1f + i * 2.1f));
            float y = h * (0.18f + 0.1f * i + 0.03f * (float) Math.sin(s * 3.3f + i));
            float rx = w * (0.7f + 0.15f * (float) Math.sin(s * 1.7f + i));
            float ry = h * (0.09f + 0.02f * i);
            float a = intro * (0.9f - 0.2f * i)
                    * (0.7f + 0.3f * (float) Math.sin(s * 5f + i * 1.3f));
            drawRadial(canvas, x, y, 1f, rx, ry, dark ? a : a * 0.55f);
        }
    }

    private void drawRadial(Canvas canvas, float x, float y, float r, float rx, float ry, float alpha) {
        if (r <= 0f || alpha <= 0f) return;
        float sx = r * rx, sy = r * ry;
        matrix.setScale(sx, sy);
        matrix.postTranslate(x, y);
        softGlow.setLocalMatrix(matrix);
        fillPaint.setShader(softGlow);
        fillPaint.setAlpha((int) (clamp(alpha, 0f, 1f) * 255f));
        dst.set(x - sx, y - sy, x + sx, y + sy);
        canvas.drawOval(dst, fillPaint);
        fillPaint.setShader(null);
        fillPaint.setAlpha(255);
    }

    @TargetApi(33)
    private static final class AuroraShader {
        private static final String SRC =
                "uniform float2 uRes;\n" +
                "uniform float uTime;\n" +
                "uniform float uIntro;\n" +
                "uniform float uDark;\n" +
                "float h1(float n) { return fract(sin(n) * 43758.5453); }\n" +
                "float h2(float2 p) { return fract(sin(dot(p, float2(127.1, 311.7))) * 43758.5453); }\n" +
                "float n1(float x) { float i = floor(x); float f = fract(x); f = f * f * (3.0 - 2.0 * f);" +
                "  return mix(h1(i), h1(i + 1.0), f); }\n" +
                "half4 main(float2 fc) {\n" +
                "  float2 uv = fc / uRes;\n" +
                "  float x = uv.x * (uRes.x / uRes.y);\n" +
                "  float t = uTime;\n" +
                "  float g = smoothstep(0.0, 1.0, uv.y);\n" +
                "  float3 night = mix(float3(0.024, 0.063, 0.118), float3(0.004, 0.016, 0.04), g);\n" +
                "  float3 day   = mix(float3(0.847, 0.918, 0.984), float3(1.0, 1.0, 1.0), g);\n" +
                "  float3 col = mix(day, night, uDark);\n" +
                "  float2 sg = float2(x, uv.y) * 70.0;\n" +
                "  float2 cell = floor(sg);\n" +
                "  float sh = h2(cell);\n" +
                "  if (sh > 0.972 && uDark > 0.5) {\n" +
                "    float2 f = fract(sg) - 0.5 - (float2(h2(cell + 3.1), h2(cell + 7.7)) - 0.5) * 0.6;\n" +
                "    float tw = 0.55 + 0.45 * sin(t * (1.0 + sh * 3.0) + sh * 60.0);\n" +
                "    col += float3(0.75, 0.88, 1.0) * exp(-dot(f, f) * 240.0) * tw * (0.3 + (sh - 0.972) * 25.0) * (1.0 - uv.y * 0.6);\n" +
                "  }\n" +
                "  float3 aur = float3(0.0);\n" +
                "  float ax = uv.x * 1.7;\n" +
                "  for (int i = 0; i < 3; i++) {\n" +
                "    float fi = float(i);\n" +
                "    float s = t * (0.05 + 0.02 * fi);\n" +
                "    float curve = 0.17 + 0.105 * fi\n" +
                "      + 0.16 * (n1(ax * 1.6 + s * 2.0 + fi * 7.3) - 0.5)\n" +
                "      + 0.035 * sin(ax * 4.2 + s * 6.0 + fi * 2.0)\n" +
                "      + 0.012 * sin(ax * 11.0 - s * 9.0 + fi);\n" +
                "    float d = uv.y - curve;\n" +
                "    float body = d < 0.0 ? exp(d * (7.0 + 2.5 * fi)) : exp(-d * 55.0);\n" +
                "    float edge = exp(-abs(d) * 90.0);\n" +
                "    float r1 = n1(ax * 30.0 + s * 18.0 + fi * 13.0);\n" +
                "    float rays = 0.25 + 0.75 * r1 * r1;\n" +
                "    rays *= 0.6 + 0.4 * n1(ax * 80.0 - s * 26.0 + fi * 5.0);\n" +
                "    float flick = 0.45 + 0.55 * n1(ax * 2.3 - s * 7.0 + fi * 3.0);\n" +
                "    float I = (body * rays * 1.6 + edge * 0.7) * flick * (1.0 - 0.22 * fi);\n" +
                "    float3 c = mix(float3(0.55, 0.95, 1.0), float3(0.38, 0.68, 1.0), fi * 0.5);\n" +
                "    c = mix(c, float3(0.22, 0.42, 1.0), clamp(-d * 5.0, 0.0, 1.0));\n" +
                "    aur += c * I;\n" +
                "  }\n" +
                "  aur = aur / (1.0 + aur * 0.35);\n" +
                "  float3 lit = col + aur * uIntro;\n" +
                "  float3 veil = col - min(aur * uIntro, 1.6) * float3(0.34, 0.16, 0.02);\n" +
                "  col = clamp(mix(veil, lit, uDark), 0.0, 1.0);\n" +
                "  float2 q = uv - 0.5;\n" +
                "  col *= 1.0 - dot(q, q) * mix(0.10, 0.9, uDark);\n" +
                "  col += (h2(fc + fract(t) * 91.0) - 0.5) / 255.0;\n" +
                "  return half4(half3(col), 1.0);\n" +
                "}\n";

        private final RuntimeShader shader = new RuntimeShader(SRC);

        void draw(Canvas canvas, Paint paint, int w, int h, float time, float intro, boolean dark) {
            shader.setFloatUniform("uRes", w, h);
            shader.setFloatUniform("uTime", time);
            shader.setFloatUniform("uIntro", intro);
            shader.setFloatUniform("uDark", dark ? 1f : 0f);
            paint.setShader(shader);
            canvas.drawRect(0, 0, w, h, paint);
        }
    }

    private static Bitmap makeSprite(int px, boolean night, int ice, int sky) {
        px = Math.max(16, px);
        Bitmap b = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        float r = px / 2f;
        int[] colors = night
                ? new int[]{0xFFFFFFFF, withAlpha(0xFFDDF4FF, 0.85f), withAlpha(ice, 0.28f),
                        withAlpha(sky, 0.08f), withAlpha(sky, 0f)}
                : new int[]{withAlpha(sky, 0.95f), withAlpha(sky, 0.70f), withAlpha(ice, 0.34f),
                        withAlpha(ice, 0.10f), withAlpha(ice, 0f)};
        p.setShader(new RadialGradient(r, r, r, colors,
                new float[]{0f, 0.08f, 0.22f, 0.5f, 1f}, Shader.TileMode.CLAMP));
        c.drawCircle(r, r, r, p);
        return b;
    }

    private static boolean animationsEnabled() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.O || ValueAnimator.areAnimatorsEnabled();
    }

    private static int withAlpha(int color, float alpha) {
        int a = (int) (clamp(alpha, 0f, 1f) * 255f + 0.5f);
        return (a << 24) | (color & 0x00FFFFFF);
    }

    private static float ease(float x) {
        return x < .5f ? 4f * x * x * x : 1f - (float) Math.pow(-2f * x + 2f, 3) / 2f;
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
