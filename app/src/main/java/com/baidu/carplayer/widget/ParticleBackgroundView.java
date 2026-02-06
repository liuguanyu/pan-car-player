package com.baidu.carplayer.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

import java.util.Random;

/**
 * 轻量级粒子背景视图
 * 为播放界面添加动态背景效果
 */
public class ParticleBackgroundView extends View {

    private static final int PARTICLE_COUNT = 25; // 粒子数量，限制在25个以保证性能
    private static final float BASE_SPEED = 0.5f; // 基础速度
    private static final float PLAYING_SPEED_MULTIPLIER = 2.0f; // 播放时速度倍数

    private Particle[] particles;
    private Paint particlePaint;
    private Random random;
    private boolean isPlaying = false;
    private float speedMultiplier = 1.0f;

    public ParticleBackgroundView(Context context) {
        super(context);
        init();
    }

    public ParticleBackgroundView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public ParticleBackgroundView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        particlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        random = new Random();
        particles = new Particle[PARTICLE_COUNT];
        
        // 初始化粒子
        for (int i = 0; i < PARTICLE_COUNT; i++) {
            particles[i] = new Particle();
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        
        // 重新初始化粒子位置
        for (int i = 0; i < PARTICLE_COUNT; i++) {
            particles[i].reset(w, h);
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        
        int width = getWidth();
        int height = getHeight();
        
        if (width == 0 || height == 0) return;
        
        // 更新并绘制所有粒子
        for (int i = 0; i < PARTICLE_COUNT; i++) {
            particles[i].update(width, height, speedMultiplier);
            particles[i].draw(canvas, particlePaint);
        }
        
        // 持续重绘
        postInvalidate();
    }

    /**
     * 设置播放状态
     * @param playing 是否正在播放
     */
    public void setPlaying(boolean playing) {
        this.isPlaying = playing;
        this.speedMultiplier = playing ? PLAYING_SPEED_MULTIPLIER : 1.0f;
    }

    /**
     * 粒子类
     */
    private class Particle {
        float x, y;
        float vx, vy;
        float radius;
        int alpha;
        float alphaSpeed;
        int color;

        Particle() {
            // 随机颜色：青色、蓝色、紫色、粉色
            int[] colors = {
                0xFF00FFFF, // 青色
                0xFF00BFFF, // 深天蓝
                0xFF9370DB, // 紫色
                0xFFFF69B4  // 粉色
            };
            color = colors[random.nextInt(colors.length)];
        }

        void reset(int width, int height) {
            x = random.nextFloat() * width;
            y = random.nextFloat() * height;
            
            // 随机速度方向
            float angle = random.nextFloat() * (float) Math.PI * 2;
            float speed = BASE_SPEED + random.nextFloat() * BASE_SPEED;
            vx = (float) Math.cos(angle) * speed;
            vy = (float) Math.sin(angle) * speed;
            
            // 随机大小
            radius = 2 + random.nextFloat() * 4;
            
            // 随机透明度变化速度
            alpha = 50 + random.nextInt(100);
            alphaSpeed = 0.5f + random.nextFloat() * 1.0f;
        }

        void update(int width, int height, float speedMult) {
            // 更新位置
            x += vx * speedMult;
            y += vy * speedMult;
            
            // 边界检测，超出边界则从另一侧出现
            if (x < -radius) x = width + radius;
            if (x > width + radius) x = -radius;
            if (y < -radius) y = height + radius;
            if (y > height + radius) y = -radius;
            
            // 更新透明度（闪烁效果）
            alpha += alphaSpeed;
            if (alpha > 150 || alpha < 30) {
                alphaSpeed = -alphaSpeed;
            }
        }

        void draw(Canvas canvas, Paint paint) {
            paint.setColor(color);
            paint.setAlpha((int) alpha);
            canvas.drawCircle(x, y, radius, paint);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        // View 销毁时停止重绘
    }
}