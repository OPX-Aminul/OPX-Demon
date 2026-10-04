package com.opx.demon.utils;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RenderNode;
import android.graphics.RuntimeShader;
import android.os.Build;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.Choreographer;
import android.view.View;

public final class AuroraView extends View {

    private static final String AGSL =
            "uniform float2 uResolution;\n" +
            "uniform float uTime;\n" +
            "uniform float3 uTopColor;\n" +
            "uniform float3 uBottomColor;\n" +
            "uniform float3 uBackground;\n" +
            "uniform float uIntensity;\n" +
            "uniform float uGlow;\n" +
            "uniform float uWidth;\n" +
            "uniform float uHeight;\n" +
            "uniform float uNoise;\n" +
            "uniform float uRotCos;\n" +
            "uniform float uRotSin;\n" +
            "uniform float uPillarRotCos;\n" +
            "uniform float uPillarRotSin;\n" +
            "\n" +
            "const int ITER = 32;\n" +
            "const int WAVE = 2;\n" +
            "const float STEP_MULT = 1.2;\n" +
            "\n" +
            "const float WAVE_SIN = 0.38941833;\n" +
            "const float WAVE_COS = 0.92106099;\n" +
            "\n" +
            "float3 softClip(float3 x) {\n" +
            "    float3 e = exp(-2.0 * min(x, float3(20.0)));\n" +
            "    return (1.0 - e) / (1.0 + e);\n" +
            "}\n" +
            "\n" +
            "half4 main(float2 fragCoord) {\n" +
            "    float2 uv = fragCoord / uResolution * 2.0 - 1.0;\n" +
            "    uv.y = -uv.y;\n" +
            "    uv.x *= uResolution.x / uResolution.y;\n" +
            "    uv = float2(\n" +
            "        uPillarRotCos * uv.x - uPillarRotSin * uv.y,\n" +
            "        uPillarRotSin * uv.x + uPillarRotCos * uv.y\n" +
            "    );\n" +
            "\n" +
            "    float3 ro = float3(0.0, 0.0, -10.0);\n" +
            "    float3 rd = normalize(float3(uv, 1.0));\n" +
            "\n" +
            "    float3 col = float3(0.0);\n" +
            "    float t = 0.1;\n" +
            "\n" +
            "    for (int i = 0; i < ITER; i++) {\n" +
            "        float3 p = ro + rd * t;\n" +
            "        p.xz = float2(uRotCos * p.x - uRotSin * p.z, uRotSin * p.x + uRotCos * p.z);\n" +
            "\n" +
            "        float3 q = p;\n" +
            "        q.y = p.y * uHeight + uTime;\n" +
            "\n" +
            "        float freq = 1.0;\n" +
            "        float amp = 1.0;\n" +
            "        for (int j = 0; j < WAVE; j++) {\n" +
            "            q.xz = float2(WAVE_COS * q.x - WAVE_SIN * q.z, WAVE_SIN * q.x + WAVE_COS * q.z);\n" +
            "            q += cos(q.zxy * freq - uTime * float(j) * 2.0) * amp;\n" +
            "            freq *= 2.0;\n" +
            "            amp *= 0.5;\n" +
            "        }\n" +
            "\n" +
            "        float d = length(cos(q.xz)) - 0.2;\n" +
            "        float bound = length(p.xz) - uWidth;\n" +
            "        float k = 4.0;\n" +
            "        float h = max(k - abs(d - bound), 0.0);\n" +
            "        d = max(d, bound) + h * h * 0.0625 / k;\n" +
            "        d = abs(d) * 0.15 + 0.01;\n" +
            "\n" +
            "        float grad = clamp((15.0 - p.y) / 30.0, 0.0, 1.0);\n" +
            "        col += mix(uBottomColor, uTopColor, grad) / d;\n" +
            "\n" +
            "        t += d * STEP_MULT;\n" +
            "        if (t > 50.0) break;\n" +
            "    }\n" +
            "\n" +
            "    col = softClip(col * uGlow / (uWidth / 3.0)) * uIntensity;\n" +
            "\n" +
            "    col -= fract(sin(dot(fragCoord, float2(12.9898, 78.233))) * 43758.5453) / 15.0 * uNoise;\n" +
            "\n" +
            "    return half4(half3(clamp(uBackground + col, 0.0, 1.0)), 1.0);\n" +
            "}\n";

    public static boolean supported() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU;
    }

    private RuntimeShader shader;
    private final Paint paint = new Paint();

    private float topR = 0.063f, topG = 0.725f, topB = 0.506f;
    private float botR = 0.063f, botG = 0.725f, botB = 0.506f;
    private float bgR = 0.024f, bgG = 0.090f, bgB = 0.059f;
    private float intensity = 1.2f;
    private float speed = 0.3f;
    private float glow = 0.005f;
    private float pillarWidth = 10f;
    private float pillarHeight = 0.6f;
    private float noise = 0.4f;
    private float rotationDegrees = 0f;

    private float time;
    private long lastFrameNanos;
    private boolean animating;

    private static final float RENDER_SCALE = 0.5f;

    private static final long FRAME_INTERVAL_MS = 33L;

    private static final long RESIZE_FREEZE_MS = 320L;

    private RenderNode node;
    private int nodeWidth;
    private int nodeHeight;
    private boolean nodeDrawn;
    private long freezeUntil;
    private long lastRenderMs;
    private boolean paramsDirty = true;

    public AuroraView(Context context) {
        this(context, null);
    }

    public AuroraView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public AuroraView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        if (!supported()) setWillNotDraw(true);
    }

    public void setColors(int top, int bottom, int background) {
        paramsDirty = true;
        topR = Color.red(top) / 255f;
        topG = Color.green(top) / 255f;
        topB = Color.blue(top) / 255f;
        botR = Color.red(bottom) / 255f;
        botG = Color.green(bottom) / 255f;
        botB = Color.blue(bottom) / 255f;
        bgR = Color.red(background) / 255f;
        bgG = Color.green(background) / 255f;
        bgB = Color.blue(background) / 255f;
        invalidate();
    }

    public void setIntensity(float value) {
        paramsDirty = true;
        intensity = value;
        invalidate();
    }

    public void setSpeed(float value) {
        speed = value;
    }

    public void setGlow(float value) {
        paramsDirty = true;
        glow = value;
        invalidate();
    }

    public void setPillarWidth(float value) {
        paramsDirty = true;
        pillarWidth = value;
        invalidate();
    }

    public void setPillarHeight(float value) {
        paramsDirty = true;
        pillarHeight = value;
        invalidate();
    }

    public void setNoise(float value) {
        paramsDirty = true;
        noise = value;
        invalidate();
    }

    public void setRotationDegrees(float value) {
        paramsDirty = true;
        rotationDegrees = value;
        invalidate();
    }

    private final Choreographer.FrameCallback frame = new Choreographer.FrameCallback() {
        @Override
        public void doFrame(long frameTimeNanos) {
            if (!animating) return;
            if (lastFrameNanos != 0L) {
                time += (frameTimeNanos - lastFrameNanos) / 1_000_000_000f * speed;
            }
            lastFrameNanos = frameTimeNanos;
            long now = SystemClock.uptimeMillis();
            if (now - lastRenderMs >= FRAME_INTERVAL_MS) invalidate();
            Choreographer.getInstance().postFrameCallback(this);
        }
    };

    private void startAnimating() {
        if (animating || !supported()) return;
        if (getVisibility() != VISIBLE || !isAttachedToWindow()) return;
        animating = true;
        lastFrameNanos = 0L;
        Choreographer.getInstance().postFrameCallback(frame);
    }

    private void stopAnimating() {
        if (!animating) return;
        animating = false;
        Choreographer.getInstance().removeFrameCallback(frame);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        startAnimating();
    }

    @Override
    protected void onDetachedFromWindow() {
        stopAnimating();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (visibility == VISIBLE) startAnimating();
        else stopAnimating();
    }

    @Override
    public void onWindowFocusChanged(boolean hasWindowFocus) {
        super.onWindowFocusChanged(hasWindowFocus);
        if (hasWindowFocus) startAnimating();
        else stopAnimating();
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        if (MeasureSpec.getMode(heightSpec) == MeasureSpec.EXACTLY) {
            super.onMeasure(widthSpec, heightSpec);
            return;
        }
        setMeasuredDimension(getDefaultSize(getSuggestedMinimumWidth(), widthSpec), 0);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (oldw > 0 && oldh > 0 && (w != oldw || h != oldh)) {
            freezeUntil = SystemClock.uptimeMillis() + RESIZE_FREEZE_MS;
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (!supported()) return;
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;
        if (!canvas.isHardwareAccelerated()) {
            canvas.drawColor(Color.rgb(bgR, bgG, bgB));
            return;
        }
        try {
            if (shader == null) shader = new RuntimeShader(AGSL);

            int tw = Math.max(1, Math.round(w * RENDER_SCALE));
            int th = Math.max(1, Math.round(h * RENDER_SCALE));
            if (node == null) {
                node = new RenderNode("aurora");
                node.setUseCompositingLayer(true, null);
            }

            long now = SystemClock.uptimeMillis();
            boolean sizeChanged = tw != nodeWidth || th != nodeHeight;
            boolean frozen = now < freezeUntil && nodeDrawn;
            boolean render = !nodeDrawn || paramsDirty
                    || (!frozen && (sizeChanged || now - lastRenderMs >= FRAME_INTERVAL_MS));

            if (render) {
                if (sizeChanged) {
                    nodeWidth = tw;
                    nodeHeight = th;
                }
                node.setPosition(0, 0, nodeWidth, nodeHeight);
                Canvas rec = node.beginRecording(nodeWidth, nodeHeight);
                try {
                    drawPillar(rec, nodeWidth, nodeHeight);
                } finally {
                    node.endRecording();
                }
                nodeDrawn = true;
                paramsDirty = false;
                lastRenderMs = now;
            }

            canvas.save();
            canvas.scale(w / (float) nodeWidth, h / (float) nodeHeight);
            canvas.drawRenderNode(node);
            canvas.restore();
        } catch (Throwable t) {
            stopAnimating();
            shader = null;
            node = null;
            setWillNotDraw(true);
        }
    }

    private void drawPillar(Canvas canvas, int w, int h) {
        {
            shader.setFloatUniform("uResolution", w, h);
            shader.setFloatUniform("uTime", time);
            shader.setFloatUniform("uTopColor", topR, topG, topB);
            shader.setFloatUniform("uBottomColor", botR, botG, botB);
            shader.setFloatUniform("uBackground", bgR, bgG, bgB);
            shader.setFloatUniform("uIntensity", intensity);
            shader.setFloatUniform("uGlow", glow);
            shader.setFloatUniform("uWidth", pillarWidth);
            shader.setFloatUniform("uHeight", pillarHeight);
            shader.setFloatUniform("uNoise", noise);
            shader.setFloatUniform("uRotCos", (float) Math.cos(time * 0.3f));
            shader.setFloatUniform("uRotSin", (float) Math.sin(time * 0.3f));
            float rot = (float) Math.toRadians(rotationDegrees);
            shader.setFloatUniform("uPillarRotCos", (float) Math.cos(rot));
            shader.setFloatUniform("uPillarRotSin", (float) Math.sin(rot));
            paint.setShader(shader);
            canvas.drawRect(0f, 0f, w, h, paint);
        }
    }
}
