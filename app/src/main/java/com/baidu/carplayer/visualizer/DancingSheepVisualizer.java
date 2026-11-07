package com.baidu.carplayer.visualizer;

import android.graphics.*;
import java.util.Arrays;
import java.util.Random;

/** Java port of the Kotlin sheep scene: clouds, fence, distant sheep, spectrum airship and dancing sheep. */
public final class DancingSheepVisualizer implements AudioVisualizer {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final AudioAnalyzer analyzer = new AudioAnalyzer();
    private final Random random = new Random();
    private int width, height, capFrames, capCooldown = 240, hopCooldown;
    private float bass, mid, treble, volume, beatPulse, sheepPulse, legPhase;
    /** 动作幅度：有音频时趋向 1，暂停/停止时平滑衰减到 0，羊随之站定。 */
    private float activity;
    private static final float REST = .01f;
    private float bodyBob, bodySway, bodyTilt, bodyStretch, headNod, headSwing, tongue, eyeWide, eyelid;
    private final float[] lamp = new float[4];
    // x ratio, y ratio, horizontal radius, vertical radius, speed, phase
    private final float[][] clouds = {{.2f,.15f,60,20,.010f,0}, {.6f,.1f,80,24,.007f,1.7f}, {.9f,.2f,50,16,.013f,3.1f}};
    // x ratio, y ratio, scale, phase, remaining hop milliseconds
    private final float[][] far = {{.15f,.68f,.4f,0,0}, {.25f,.70f,.3f,2,0}, {.85f,.67f,.35f,4,0}};
    private static final int[] LAMP_COLORS = {0xff38f93e,0xffc8ff33,0xffff9b1e,0xffff3131};
    private static float sin(float value) { return (float) Math.sin(value); }
    private static float cos(float value) { return (float) Math.cos(value); }
    @Override public String getName() { return "sheep"; }
    @Override public void update(byte[] fft, int width, int height) {
        this.width = width; this.height = height;
        AudioAnalyzer.Analysis a = analyzer.analyze(fft);
        float dt = 1f / 60;
        boolean hasAudio = fft != null && fft.length > 0;
        activity += ((hasAudio ? 1f : 0f) - activity) * dt * (hasAudio ? 6 : 3);
        if (!hasAudio && activity < REST) activity = 0;
        // 无数据时各频段也平滑回落，不做跳变。
        float k = hasAudio ? 1f : dt * 6;
        bass += (a.bass - bass) * k; mid += (a.mid - mid) * k;
        treble += (a.treble - treble) * k; volume += (a.volume - volume) * k;
        beatPulse += (bass - beatPulse) * dt * 8;
        sheepPulse += (volume - sheepPulse) * dt * 5;
        legPhase += dt * (2.8f + bass * 1.4f + volume * .9f) * (float) Math.PI * 2 * activity;
        tongue += ((bass > .4f ? bass : 0) - tongue) * dt * 12;
        float groove = sin(legPhase) * activity, side = sin(legPhase * .5f) * activity, jump = Math.max(0, groove);
        float landing = Math.max(0, -groove), step = cos(legPhase) * activity;
        bodySway += (side * (5 + treble * 5) + step * bass * 4 - bodySway) * dt * 9;
        bodyTilt += (side * .045f + step * bass * .035f - bodyTilt) * dt * 8;
        bodyBob += (groove * 2.2f + jump * (5 + bass * 10) - landing * bass * 3.5f + volume * 2 - bodyBob) * dt * 11;
        headNod += (groove * 1.5f + jump * volume * 3.5f + bass * 1.5f - headNod) * dt * 7;
        headSwing += (side * 6 + treble * 12 - bodySway * .35f - headSwing) * dt * 6;
        bodyStretch += (landing * (.08f + beatPulse * .08f) + jump * .025f + Math.abs(groove) * .02f - bodyStretch) * dt * 14;
        eyeWide += (treble * 1.5f - eyeWide) * dt * 6;
        eyelid += (beatPulse * 1.5f - eyelid) * dt * 8;
        if (capFrames > 0) { if (--capFrames == 0) capCooldown = 360; }
        else if (capCooldown > 0) capCooldown--;
        else if (random.nextFloat() < .0035f) capFrames = 240;
        float[] targets = {Math.max(bass, beatPulse), mid, treble, Math.max(volume, sheepPulse)};
        for (int i = 0; i < lamp.length; i++) lamp[i] += (targets[i] - lamp[i]) * (targets[i] > lamp[i] ? .45f : .16f);
        for (float[] c : clouds) {
            c[5] += dt * (.4f + volume) * activity; c[0] += c[4] * dt * (.6f + volume * .8f) * activity;
            if (width > 0 && c[0] * width - c[2] > width) c[0] = -c[2] / width;
        }
        if (hopCooldown > 0) hopCooldown--;
        if (bass > .5f && hopCooldown == 0) {
            for (int i = 0; i < far.length; i++) far[i][4] = 300 + i * 40;
            hopCooldown = 240;
        }
        for (float[] f : far) { f[3] += dt * (1.5f + bass * 2 + volume) * activity; f[4] = Math.max(0, f[4] - dt * 1000); }
    }
    @Override public boolean isAtRest() {
        if (activity > 0) return false;
        float motion = Math.abs(bodyBob) + Math.abs(bodySway) + Math.abs(bodyTilt) + Math.abs(bodyStretch)
                + Math.abs(headNod) + Math.abs(headSwing) + tongue + beatPulse + sheepPulse;
        for (float l : lamp) motion += l;
        return motion < REST;
    }
    private void oval(Canvas c, float x, float y, float rx, float ry, int color) {
        paint.setColor(color); c.drawOval(x-rx,y-ry,x+rx,y+ry,paint);
    }
    @Override public void draw(Canvas canvas) {
        if (width <= 0 || height <= 0) return;
        // 等比缩放：按高度适配，宽度方向扩展天空/草地/篱笆，羊和飞艇不被拉伸压扁。
        // 很窄的区域按宽度适配并垂直居中，保证整只羊可见。
        float scale = Math.min(height / 360f, width / 360f);
        float w = width / scale, h = height / scale;
        int save = canvas.save(); canvas.scale(scale, scale);
        paint.setShader(new LinearGradient(0,0,0,h*.65f,0xff9aa4ff,0xff001dff,Shader.TileMode.CLAMP));
        canvas.drawRect(0,0,w,h*.65f,paint); paint.setShader(null);
        oval(canvas,w*.85f,h*.12f,50,50,0x80fff36b); oval(canvas,w*.85f,h*.12f,30,30,0xfffff36b);
        for (float[] cloud : clouds) {
            float x = cloud[0]*w, y = cloud[1]*h+sin(cloud[5])*3, rx = cloud[2], ry = cloud[3];
            oval(canvas,x,y,rx,ry,Color.WHITE);
            oval(canvas,x,y-5,rx*.5f,ry*.6f,Color.WHITE);
        }
        paint.setColor(0xff20f020); canvas.drawRect(0,h*.65f,w,h,paint);
        paint.setColor(Color.WHITE); float fenceY = h*.65f+2;
        for (float x=0;x<w;x+=30) canvas.drawRect(x,fenceY,x+3,fenceY+20,paint);
        canvas.drawRect(0,fenceY+4,w,fenceY+7,paint); canvas.drawRect(0,fenceY+14,w,fenceY+17,paint);
        for (float[] f : far) {
            float hop = f[4] > 0 ? -sin(Math.max(0, Math.min(1,1-f[4]/300))*(float)Math.PI)*8*Math.max(.35f,bass*.5f) : 0;
            float x=f[0]*w, y=f[1]*h+hop+sin(f[3]*(float)Math.PI)*1.8f, s=f[2]*50;
            oval(canvas,x,y,s,s/2,0xfff3f3f3); oval(canvas,x-s,y-s/4,s/2,s/3,Color.BLACK);
            paint.setStrokeWidth(2); paint.setColor(Color.BLACK);
            float lift=sin(f[3]*(float)Math.PI*2)*3*(.5f+bass*.5f);
            canvas.drawLine(x-s/4,y+s/2,x-s/4,y+s-Math.max(0,lift),paint);
            canvas.drawLine(x+s/4,y+s/2,x+s/4,y+s-Math.max(0,-lift),paint);
        }
        drawAirship(canvas,w*.75f,h*.15f);
        float cx=w/2f-w*.06f*Math.min(1f,640f/w)+bodySway, cy=h*.57f+bodyBob;
        for (int i=0;i<3;i++) oval(canvas,cx+w*.03f,h*.74f,90-i,32-i,Color.argb((int)((.55f+sheepPulse*.37f)*(1-i*.3f)*255),255,250,205));
        canvas.save(); canvas.rotate(bodyTilt*57.29578f,cx,cy);
        float step=sin(legPhase)*activity, lift=step*(2+bass*5)+Math.max(0,step)*2;
        float stride=sin(legPhase*.5f)*(2+treble*3)*activity;
        paint.setColor(Color.BLACK); paint.setStrokeWidth(4); paint.setStrokeCap(Paint.Cap.ROUND);
        leg(canvas,cx-18+stride,cy+20,lift); leg(canvas,cx-4-stride*.4f,cy+20,-lift);
        leg(canvas,cx+18+stride*.25f,cy+22,-lift*.6f); leg(canvas,cx+34-stride,cy+22,lift*.6f);
        float rx=48*(1+bodyStretch), ry=30*(1-bodyStretch*.45f);
        paint.setColor(0xfff3f3f3);
        for (int i=0;i<16;i++) {
            float angle=i/16f*(float)Math.PI*2;
            canvas.drawCircle(cx+cos(angle)*rx,cy+sin(angle)*ry,10*(2+bodyStretch*.55f)/2,paint);
        }
        oval(canvas,cx,cy,rx+2,ry+2,0xfff3f3f3);
        drawHead(canvas,cx-56+headSwing*.8f,cy-6+headNod*.8f);
        canvas.restore(); canvas.restoreToCount(save);
    }
    private void leg(Canvas c,float x,float y,float lift) { c.drawLine(x,y,x,y+48-Math.max(0,lift),paint); }
    private void drawAirship(Canvas c,float x,float y) {
        paint.setColor(0xffd8d8d8); c.drawRect(x-5,y+6,x+1,y+14,paint);
        oval(c,x+18,y+11,18,11,0xffd8d8d8);
        paint.setColor(Color.BLACK); c.drawRect(x+8,y+5,x+26,y+17,paint);
        paint.setColor(0xffd8d8d8); c.drawRect(x+11,y+22,x+19,y+26,paint);
        for(int i=0;i<4;i++) { paint.setColor(LAMP_COLORS[i]); c.drawRect(x+10+i*4,y+16-Math.max(3,lamp[i]*10),x+13+i*4,y+16,paint); }
    }
    private void drawHead(Canvas c,float x,float y) {
        oval(c,x,y,24,18,Color.BLACK);
        path.reset(); path.moveTo(x-18,y-2); path.lineTo(x-44,y+4); path.lineTo(x-16,y+6); path.close(); c.drawPath(path,paint);
        path.reset(); path.moveTo(x+18,y-2); path.lineTo(x+38,y+18); path.lineTo(x+14,y+12); path.close(); c.drawPath(path,paint);
        paint.setColor(Color.WHITE);
        c.drawRect(x-12,y-8,x-4,y+eyeWide*2,paint); c.drawRect(x+4,y-8,x+12,y+eyeWide*2,paint);
        paint.setColor(Color.BLACK); c.drawRect(x-9,y-3,x-7,y-1,paint); c.drawRect(x+7,y-3,x+9,y-1,paint);
        paint.setStrokeWidth(1); float lid=y-9+eyelid*4;
        c.drawLine(x-14,lid,x-2,lid,paint); c.drawLine(x+2,lid,x+14,lid,paint);
        if(tongue>.05f) oval(c,x-2,y+12+tongue*5,5,5+tongue*13,0xffff8aa8);
        if(capFrames>0) {
            oval(c,x+1.5f,y-20.5f,17.5f,13.5f,0xffd71920);
            paint.setColor(0xffd71920); c.drawRect(x-7,y-30,x+19,y-14,paint);
            path.reset(); path.moveTo(x-9,y-16); path.cubicTo(x-22,y-19,x-35,y-16,x-43,y-11);
            path.cubicTo(x-30,y-9,x-17,y-10,x-6,y-13); path.close(); paint.setColor(0xff9e1018); c.drawPath(path,paint);
            oval(c,x-.5f,y-27,7.5f,3,0x5affffff);
        }
    }
    @Override public void reset() {
        bass=mid=treble=volume=beatPulse=sheepPulse=legPhase=0;
        bodyBob=bodySway=bodyTilt=bodyStretch=headNod=headSwing=tongue=eyeWide=eyelid=0;
        activity=0;
        capFrames=hopCooldown=0; capCooldown=240; Arrays.fill(lamp,0); analyzer.reset();
        for(float[] f:far) f[4]=0;
    }
}
