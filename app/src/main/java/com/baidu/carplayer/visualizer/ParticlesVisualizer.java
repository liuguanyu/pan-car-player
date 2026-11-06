package com.baidu.carplayer.visualizer;

import android.graphics.*;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.Random;

/** Bass-driven bursts and an orange pulsing core, ported from Kotlin. */
public final class ParticlesVisualizer implements AudioVisualizer {
    private static final class Particle { float x, y, vx, vy, life, maxLife, size; }
    private final ArrayList<Particle> particles = new ArrayList<>();
    private final Random random = new Random();
    private final AudioAnalyzer analyzer = new AudioAnalyzer();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int width, height;
    private float bass;
    @Override public String getName() { return "particles"; }
    @Override public void update(byte[] fft, int width, int height) {
        this.width = width; this.height = height;
        AudioAnalyzer.Analysis analysis = analyzer.analyze(fft);
        float next = bass + (analysis.bass - bass) * (analysis.bass > bass ? .45f : .18f);
        if (analysis.peak || (next > .45f && bass <= .45f)) {
            for (int i = 0; i < Math.max(5, (int) (next * 20)); i++) {
                double angle = random.nextDouble() * Math.PI * 2;
                float speed = (100 + next * 500) * random.nextFloat();
                Particle p = new Particle();
                p.x = width / 2f; p.y = height / 2f;
                p.vx = (float) Math.cos(angle) * speed; p.vy = (float) Math.sin(angle) * speed;
                p.life = p.maxLife = 20 + next * 40; p.size = 2 + random.nextFloat() * 6;
                particles.add(p);
            }
            while (particles.size() > 80) particles.remove(0);
        }
        bass = next;
    }
    @Override public void draw(Canvas canvas) {
        float cx = width / 2f, cy = height / 2f, radius = (30 + bass * 60) * 2;
        paint.setShader(new RadialGradient(cx, cy, radius,
                new int[]{0xb4ff9600, 0x64ff6400, 0x00ff3200}, null, Shader.TileMode.CLAMP));
        canvas.drawCircle(cx, cy, radius, paint); paint.setShader(null);
        Iterator<Particle> iterator = particles.iterator();
        while (iterator.hasNext()) {
            Particle p = iterator.next();
            p.x += p.vx * .016f; p.y += p.vy * .016f;
            if (--p.life <= 0) { iterator.remove(); continue; }
            float progress = p.life / p.maxLife;
            int alpha = Math.min(255, Math.max(0, (int) (progress * 255)));
            paint.setShader(new RadialGradient(p.x, p.y, p.size * 2,
                    Color.argb(alpha, 100, 200, 255), Color.TRANSPARENT, Shader.TileMode.CLAMP));
            canvas.drawCircle(p.x, p.y, p.size, paint);
        }
        paint.setShader(null);
    }
    @Override public void reset() { particles.clear(); bass = 0; analyzer.reset(); }
}
