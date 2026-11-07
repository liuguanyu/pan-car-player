package com.baidu.carplayer.visualizer;

import android.graphics.*;
import java.util.Random;

/**
 * RealPlayer "Annabelle the Sheep" 风格的跳舞小羊复刻。
 *
 * <p>场景：渐变天空、亮绿草地、左侧透视栅栏、像素云；白天偶尔有带均衡器灯的飞艇飞过，
 * 夜晚出现星星、弯月，并会降下迪斯科球在草地上投出光斑，偶尔有 UFO 用绿光照向小羊；
 * 鸟群不时掠过；小猫偶尔来串门，它出现时前景草丛从右侧长出。
 * 小羊侧身朝左，慵懒半闭眼，随节拍点头、轻微起伏、踏步，偶尔吐舌头，并定期更换装扮：
 * 牛仔帽+靴子、礼帽、红帽+咧嘴笑、墨镜+和平标志、粉色朋克（粉毛、莫西干、粉靴、耳环）、粉色蝴蝶结。
 *
 * <p>昼夜：白天/夜晚各持续 {@link #DAY_SECONDS}/{@link #NIGHT_SECONDS} 秒，之间用 {@link #TRANSITION_SECONDS}
 * 秒平滑过渡，绝不跳变。场景时钟只在播放时推进，暂停后整个场景平滑静止。
 */
public final class DancingSheepVisualizer implements AudioVisualizer {
    static final float DAY_SECONDS = 150f;
    static final float NIGHT_SECONDS = 150f;
    static final float TRANSITION_SECONDS = 15f;
    static final float CYCLE_SECONDS = DAY_SECONDS + NIGHT_SECONDS + 2 * TRANSITION_SECONDS;

    private static final float DT = 1f / 60;
    private static final float REST = .01f;
    /** 场景基准高度（逻辑单位），宽度随视图比例扩展。 */
    private static final float SCENE_H = 360f;

    // 配色
    private static final int DAY_SKY_TOP = 0xff8c96ff, DAY_SKY_BOT = 0xff0a1ef0;
    private static final int NIGHT_SKY_TOP = 0xff0b0f3a, NIGHT_SKY_BOT = 0xff1a2a86;
    private static final int DAY_GRASS = 0xff22e022, NIGHT_GRASS = 0xff119a11;
    private static final int DAY_CLOUD = 0xffffffff, NIGHT_CLOUD = 0xff6e6e78;
    private static final int MOON = 0xfffff04a;
    /** 飞艇侧面的均衡器：EQ_BANDS 个频段 × EQ_SEGS 节 LED，下绿中黄上红，带峰值保持。 */
    static final int EQ_BANDS = 10, EQ_SEGS = 7;
    private static final int EQ_GREEN = 0xff38f93e, EQ_YELLOW = 0xffffe14a, EQ_RED = 0xffff3131;

    enum Outfit { NONE, COWBOY, TOP_HAT, RED_CAP, SUNGLASSES, PUNK, BOW }
    /** 鬼脸：短暂出现后回到慵懒表情。 */
    enum Face { NORMAL, BIG_EYE, WINK, CROSS_EYED, GRIN, TONGUE }
    /** 舞步：每隔几个节拍随机切换。 */
    enum Move { BOB, HOP, LUNGE, CROUCH, TAP }

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final AudioAnalyzer analyzer = new AudioAnalyzer();
    private final Random random = new Random(7);

    private int width, height;
    /** 场景时钟（秒），只在播放时推进。 */
    private float clock;
    /** 动作幅度：有音频趋向 1，暂停后衰减到 0。 */
    private float activity;
    private float bass, treble, volume;
    private float breathe, blinkTimer = 3f, blink;
    /** 节拍来源：生产环境由 {@link VisualizerManager} 注入真实节拍跟踪；为空时按 120 BPM 自由律动。 */
    interface BeatSource {
        double beatPosition(long nowMs);
        float confidence(long nowMs);
    }
    /** 采集到显示之间的大致延迟（毫秒）：按预测的未来拍位置摆姿势，让落拍和听到的鼓点对齐。 */
    private static final long LATENCY_MS = 70;
    private BeatSource beatSource;
    private java.util.function.LongSupplier nowMs = android.os.SystemClock::uptimeMillis;
    private double freeBeat;
    /** 当前拍位置（整数=拍点）与节拍可信度。 */
    private double beatPos;
    private float beatConfidence;
    // 姿态（平滑后的实际值）：身体抬升/下蹲、前后冲、倾斜，头部角度/下沉，抬脚
    private float bodyLift, bodyShift, bodyTilt, crouch, headAngle, headDrop, feetLift, legTap;
    /**
     * 跳跃按竖直上抛运动计算：重力加速度 GRAVITY 恒定（场景单位/秒²），起跳与落地都在拍点。
     * 腾空时长 T = airBeats × 每拍秒数，初速度 v0 = g·T/2，高度 h(t) = v0·t − g·t²/2 = g·t·(T−t)/2，
     * 最高点 g·T²/8。腾空前有下蹲蓄力，落地后有缓冲下压。
     */
    static final float GRAVITY = 540;
    /** 大跳最高不超过此高度：慢歌时改为 1 拍腾空，保持重力不变。 */
    static final float MAX_JUMP = 90;
    private static final double PREP_BEATS = .45;
    private static final float LAND_SECONDS = .16f;
    /** 跳跃阶段：地面 → 蓄力（等待拍点）→ 腾空 → 落地缓冲。 */
    private enum JumpPhase { GROUND, PREP, AIR, LAND }
    private JumpPhase jumpPhase = JumpPhase.GROUND;
    private double jumpStart;                 // 起跳拍点
    private int jumpAirBeats;
    private float jumpAirSeconds, jumpT;      // 起跳时冻结腾空时长；腾空/缓冲按真实时间积分
    private int barsUntilJump = 3;
    private float jumpLift, jumpSquash;
    private float secondsPerBeat = .5f;
    private Move move = Move.BOB;
    private int moveBeats = 8;
    private long lastBeat = Long.MIN_VALUE;
    private Face face = Face.NORMAL;
    private float faceT, faceTimer = 6f;

    // 装扮
    private Outfit outfit = Outfit.NONE;
    private boolean peace;
    private float outfitTimer = 20f;

    // 天空元素：x(0..1 相对宽度)、y、尺寸
    private final float[][] stars = new float[60][3];
    private final float[][] clouds = {{.12f, 40, 1f}, {.45f, 70, .8f}, {.78f, 30, 1.1f}};

    // 白天飞艇：方向随机，-1 从右向左，+1 从左向右
    private float blimpX = -1, blimpTimer = 25f, blimpDir = -1;
    private final float[] lamp = new float[EQ_BANDS];
    private final float[] lampPeak = new float[EQ_BANDS];

    // 夜晚迪斯科球：0 未出现，降下 → 停留 → 升起
    private float discoT = -1, discoTimer = 30f, spotGlow;
    private final float[][] spots = new float[14][2];

    // 小猫（同时带出前景草丛）
    private float catT = -1, catTimer = 40f;
    static final float CAT_SECONDS = 40f;

    // 鸟群：从右向左掠过
    private float birdsX = -1, birdsTimer = 15f, birdsY = 60;

    // UFO：仅夜晚，飞入 → 照射 → 飞走；照射时小羊瞪眼吐舌
    private float ufoT = -1, ufoTimer = 70f;
    static final float UFO_SECONDS = 9f;


    public DancingSheepVisualizer() {
        for (float[] s : stars) {
            s[0] = random.nextFloat(); s[1] = 8 + random.nextFloat() * 150; s[2] = random.nextFloat() * 6.28f;
        }
        shuffleSpots();
    }

    /** 夜晚程度 0（白天）..1（夜晚），使用 smoothstep 平滑过渡。 */
    static float nightFactor(float clockSeconds) {
        float t = clockSeconds % CYCLE_SECONDS;
        if (t < 0) t += CYCLE_SECONDS;
        if (t < DAY_SECONDS) return 0f;
        t -= DAY_SECONDS;
        if (t < TRANSITION_SECONDS) return smooth(t / TRANSITION_SECONDS);
        t -= TRANSITION_SECONDS;
        if (t < NIGHT_SECONDS) return 1f;
        t -= NIGHT_SECONDS;
        return 1f - smooth(t / TRANSITION_SECONDS);
    }

    private static float smooth(float x) {
        x = Math.max(0, Math.min(1, x));
        return x * x * (3 - 2 * x);
    }

    private static int lerpColor(int a, int b, float t) {
        int ar = (a >> 16) & 0xff, ag = (a >> 8) & 0xff, ab = a & 0xff;
        int br = (b >> 16) & 0xff, bg = (b >> 8) & 0xff, bb = b & 0xff;
        return 0xff000000 | ((int) (ar + (br - ar) * t) << 16) | ((int) (ag + (bg - ag) * t) << 8)
                | (int) (ab + (bb - ab) * t);
    }

    private static float sin(float v) { return (float) Math.sin(v); }

    @Override public String getName() { return "sheep"; }

    @Override public void update(byte[] fft, int width, int height) {
        this.width = width; this.height = height;
        AudioAnalyzer.Analysis a = analyzer.analyze(fft);
        boolean hasAudio = fft != null && fft.length > 0;
        activity += ((hasAudio ? 1f : 0f) - activity) * DT * (hasAudio ? 6 : 3);
        if (!hasAudio && activity < REST) activity = 0;
        float k = hasAudio ? .5f : DT * 6;
        bass += (a.bass - bass) * k; treble += (a.treble - treble) * k; volume += (a.volume - volume) * k;

        float dt = DT * activity;
        clock += dt;

        // 舞蹈：姿态由音乐的拍位置驱动——落拍时点头/落地/下蹲；舞步在小节（4 拍）边界切换。
        if (beatSource != null) {
            long now = nowMs.getAsLong();
            beatPos = beatSource.beatPosition(now + LATENCY_MS);
            beatConfidence = beatSource.confidence(now);
            double perSecond = beatSource.beatPosition(now + LATENCY_MS + 1000) - beatPos;
            if (perSecond > .5 && perSecond < 5) secondsPerBeat = (float) (1 / perSecond);
        } else {
            secondsPerBeat = .5f;
            freeBeat += dt * 2;   // 无节拍来源：120 BPM
            beatPos = freeBeat;
            beatConfidence = 1;
        }
        long beatIndex = (long) Math.floor(beatPos);
        if (beatIndex != lastBeat) {
            boolean first = lastBeat == Long.MIN_VALUE;
            lastBeat = beatIndex;
            if (!first && activity > .5f) onBeat();
        }
        // 节拍不确定时（安静段落、无鼓点）动作收敛为轻微律动，不乱跳
        float sync = .35f + .65f * beatConfidence;
        updateJump();
        updatePose(activity * sync * Math.min(1.3f, .75f + bass * .9f));
        breathe += (volume * activity - breathe) * DT * 4;

        // 鬼脸：持续约 2 秒后恢复；新的鬼脸在拍点上出现（见 onBeat）
        if (faceT > 0) {
            faceT -= dt;
            if (faceT <= 0) { faceT = 0; face = Face.NORMAL; }
        } else {
            faceTimer -= dt;
        }

        // 慵懒眨眼
        blinkTimer -= dt;
        if (blinkTimer <= 0) { blink = 1; blinkTimer = 2.5f + random.nextFloat() * 4; }
        blink = Math.max(0, blink - DT * 6);

        // 装扮轮换：大约一半时间不戴任何装扮，只是“有时候”戴帽子
        outfitTimer -= dt;
        if (outfitTimer <= 0) {
            Outfit next;
            if (outfit != Outfit.NONE && random.nextFloat() < .55f) next = Outfit.NONE;
            else {
                Outfit[] all = Outfit.values();
                do { next = all[1 + random.nextInt(all.length - 1)]; } while (next == outfit);
            }
            outfit = next;
            peace = next == Outfit.SUNGLASSES || (next == Outfit.NONE && random.nextFloat() < .25f);
            outfitTimer = 18 + random.nextFloat() * 18;
        }

        float night = nightFactor(clock);
        for (float[] c : clouds) {
            c[0] += dt * .006f;
            if (c[0] > 1.15f) c[0] = -.15f;
        }

        // 飞艇：仅白天出现，从右向左飞过
        updateEqualizer(a.spectrum);
        if (blimpX < 0) {
            blimpTimer -= dt;
            if (blimpTimer <= 0 && night < .2f) { blimpX = 0; blimpDir = random.nextBoolean() ? 1 : -1; }
        } else {
            blimpX += dt * .025f;
            if (blimpX > 1) { blimpX = -1; blimpTimer = 40 + random.nextFloat() * 30; }
        }

        // 迪斯科球：仅深夜，降下 3s → 停留 20s → 升起 3s
        if (discoT < 0) {
            discoTimer -= dt;
            if (discoTimer <= 0 && night > .9f) discoT = 0;
        } else {
            discoT += dt;
            if (discoT > 26) { discoT = -1; discoTimer = 45 + random.nextFloat() * 30; }
        }
        boolean discoOn = discoT > 3 && discoT < 23;
        if (discoOn && a.peak && activity > .5f) { shuffleSpots(); spotGlow = 1; }
        spotGlow += ((discoOn ? .8f + bass * .2f : 0) * activity - spotGlow) * DT * 4;

        // 小猫：偶尔坐在草地上一段时间
        if (catT < 0) {
            catTimer -= dt;
            if (catTimer <= 0) catT = 0;
        } else {
            catT += dt;
            if (catT > CAT_SECONDS) { catT = -1; catTimer = 60 + random.nextFloat() * 60; }
        }

        // 鸟群
        if (birdsX < 0) {
            birdsTimer -= dt;
            if (birdsTimer <= 0) { birdsX = 0; birdsY = 40 + random.nextFloat() * 50; }
        } else {
            birdsX += dt * .06f;
            if (birdsX > 1) { birdsX = -1; birdsTimer = 35 + random.nextFloat() * 40; }
        }

        // UFO：深夜且没有迪斯科球时
        if (ufoT < 0) {
            ufoTimer -= dt;
            if (ufoTimer <= 0 && night > .9f && discoT < 0) ufoT = 0;
        } else {
            ufoT += dt;
            if (ufoT > UFO_SECONDS) { ufoT = -1; ufoTimer = 80 + random.nextFloat() * 60; }
        }

    }

    /** 腾空 n 拍时的最高点（场景单位）。 */
    private float apexFor(int airBeats) {
        float t = airBeats * secondsPerBeat;
        return GRAVITY * t * t / 8;
    }

    /** 预约下一拍起跳（当前拍用于下蹲蓄力）。 */
    private void scheduleJump(boolean big) {
        int n = big ? 2 : 1;
        if (big && apexFor(2) > MAX_JUMP) n = 1;          // 慢歌：2 拍腾空会飞太高
        if (!big && apexFor(1) < 10) n = 2;               // 快歌：1 拍腾空几乎看不出
        jumpStart = lastBeat + 1;
        jumpAirBeats = n;
        jumpPhase = JumpPhase.PREP;
    }

    /**
     * 上抛运动：蓄力 → 腾空（恒定重力）→ 落地缓冲。
     * 起跳时机取自拍点；腾空和缓冲按真实时间 DT 积分——节拍估计的抖动不会让重力忽大忽小，
     * 暂停时已在空中的小羊也会按物理规律落回地面。
     */
    private void updateJump() {
        jumpLift = 0;
        float squash = 0;
        switch (jumpPhase) {
            case PREP: {
                double toTakeoff = jumpStart - beatPos;
                if (activity < .3f) { jumpPhase = JumpPhase.GROUND; break; }
                if (toTakeoff <= 0) {
                    jumpPhase = JumpPhase.AIR;
                    jumpAirSeconds = jumpAirBeats * secondsPerBeat;
                    // 进入腾空时补上已越过拍点的那一小段时间
                    jumpT = (float) Math.min(-toTakeoff * secondsPerBeat, DT);
                } else {
                    if (toTakeoff < PREP_BEATS) squash = (float) Math.sin((1 - toTakeoff / PREP_BEATS) * Math.PI / 2);
                    break;
                }
            }
            // fall through：本帧已起跳
            case AIR: {
                if (jumpT >= jumpAirSeconds) {
                    jumpPhase = JumpPhase.LAND;
                    jumpT = 0;
                } else {
                    float t = jumpT;
                    jumpLift = GRAVITY * t * (jumpAirSeconds - t) / 2;   // h = v0·t − g·t²/2
                    squash = Math.max(0, 1 - t / .06f);                   // 蹬地瞬间释放蓄力
                    jumpT += DT;
                }
                break;
            }
            case LAND: {
                // 落地缓冲：冲击后迅速压到最低，再弹回站姿
                float v = jumpT / LAND_SECONDS;
                if (v >= 1) { jumpPhase = JumpPhase.GROUND; break; }
                squash = (float) (Math.sin(Math.PI * Math.min(1, v * 1.6 + .25)) * (1 - v) * 1.1);
                jumpT += DT;
                break;
            }
            default:
                break;
        }
        float scale = jumpAirBeats >= 2 ? 1f : .6f;   // 小跳只轻微屈膝
        jumpSquash = Math.max(0, squash) * scale;
    }

    /** 每个拍点触发一次：小节边界切换舞步，安排跳跃，偶尔在拍点上做鬼脸。 */
    private void onBeat() {
        boolean grounded = jumpPhase == JumpPhase.GROUND;
        if (grounded && beatConfidence > .5f) {
            if (Math.floorMod(lastBeat, 4) == 3 && --barsUntilJump <= 0) {
                scheduleJump(true);                           // 大跳：下一小节第一拍起跳
                barsUntilJump = 2 + random.nextInt(4);        // 每 2~5 小节一次
            } else if (move == Move.HOP && Math.floorMod(lastBeat, 2) == 1) {
                scheduleJump(false);                          // 原地小跳：每两拍一跳
            }
        }
        if (--moveBeats <= 0 && Math.floorMod(lastBeat, 4) == 0) {
            Move[] moves = Move.values();
            Move next;
            do { next = moves[random.nextInt(moves.length)]; } while (next == move);
            move = next;
            moveBeats = random.nextBoolean() ? 4 : 8;
        }
        if (faceT <= 0 && faceTimer <= 0) {
            Face[] faces = Face.values();
            face = faces[1 + random.nextInt(faces.length - 1)];
            faceT = 1.6f + random.nextFloat() * 1.2f;
            faceTimer = 7 + random.nextFloat() * 9;
        }
    }

    /** 拍点脉冲：拍点处为 1，拍与拍中间为 0，平滑无跳变。 */
    static float pulse(double beatPos) {
        float c = (float) Math.cos(Math.PI * beatPos);
        return c * c;
    }

    /**
     * 根据当前舞步和拍位置计算目标姿态并平滑逼近；amp 为 0 时所有姿态回到站立。
     * pulse 每拍一次（拍点=1），alt 每拍换向（偶数拍 +1、奇数拍 -1），sway 四拍一个来回。
     */
    private void updatePose(float amp) {
        float pulse = pulse(beatPos);
        float alt = (float) Math.cos(Math.PI * beatPos);
        float sway = (float) Math.sin(Math.PI * beatPos / 2);
        float tLift = 0, tShift = 0, tTilt = 0, tCrouch = 0, tAngle = 0, tDrop = 0, tFeet = 0, tTap = 0;
        switch (move) {
            case BOB:     // 拍点上大幅低头，身体同时下沉
                tAngle = pulse * 34 - 6; tDrop = pulse * 18; tLift = (1 - pulse) * 8; tCrouch = pulse * .15f;
                break;
            case HOP:     // 原地小跳（腾空由 updateJump 按上抛运动计算），落地时点头
                tAngle = pulse * 14 - 4; tDrop = pulse * 6; tTilt = -alt * 3;
                break;
            case LUNGE:   // 偶数拍向前冲、奇数拍后撤，腿斜成一排
                tShift = alt * 26; tTilt = -alt * 6; tAngle = alt * 14 + 6; tDrop = pulse * 6; tCrouch = .12f;
                break;
            case CROUCH:  // 拍点上蹲到最低、头点到底
                tCrouch = .35f + pulse * .35f; tAngle = pulse * 30; tDrop = pulse * 14;
                break;
            case TAP:     // 每拍换脚高抬，身体四拍左右摇一次
                tShift = sway * 14; tTilt = sway * 6; tTap = alt * 18; tAngle = sway * 22 + pulse * 8; tLift = pulse * 6;
                break;
        }
        // 平滑时间常数约 50ms：足够消除抖动，又不会让落拍明显滞后
        float k = DT * 20;
        bodyLift += (tLift * amp - bodyLift) * k;
        bodyShift += (tShift * amp - bodyShift) * k;
        bodyTilt += (tTilt * amp - bodyTilt) * k;
        crouch += (tCrouch * amp - crouch) * k;
        headAngle += (tAngle * amp - headAngle) * k;
        headDrop += (tDrop * amp - headDrop) * k;
        feetLift += (tFeet * amp - feetLift) * k;
        legTap += (tTap * amp - legTap) * k;
    }

    private void shuffleSpots() {
        for (float[] s : spots) { s[0] = random.nextFloat(); s[1] = random.nextFloat(); }
    }

    @Override public void draw(Canvas canvas) {
        if (width <= 0 || height <= 0) return;
        // 等比缩放：按高度适配，宽度方向扩展场景；很窄时按宽度适配。
        float scale = Math.min(height / SCENE_H, width / 360f);
        float w = width / scale, h = height / scale;
        int save = canvas.save();
        canvas.scale(scale, scale);
        paint.setStyle(Paint.Style.FILL);

        float night = nightFactor(clock);
        float horizon = h * .5f, ground = h * .86f;
        int skyBot = lerpColor(DAY_SKY_BOT, NIGHT_SKY_BOT, night);

        paint.setShader(new LinearGradient(0, 0, 0, horizon,
                lerpColor(DAY_SKY_TOP, NIGHT_SKY_TOP, night), skyBot, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, w, horizon, paint);
        paint.setShader(null);

        if (night > 0) drawNightSky(canvas, w, night, skyBot);
        drawClouds(canvas, w, night);
        if (blimpX >= 0) {
            // 进出画面各留 70 单位，保证整艇完全飞出后才消失
            float span = w + 140;
            float bx = blimpDir < 0 ? w + 70 - blimpX * span : -70 + blimpX * span;
            drawBlimp(canvas, bx, 50, blimpDir > 0 ? 1 : -1);
        }

        paint.setColor(lerpColor(DAY_GRASS, NIGHT_GRASS, night));
        canvas.drawRect(0, horizon, w, h, paint);
        if (birdsX >= 0) drawBirds(canvas, w, night);
        drawFence(canvas, horizon, night);

        if (discoT >= 0) drawDisco(canvas, w, h, horizon);
        if (catT >= 0) {
            float catX = w * .5f - 190;
            drawTufts(canvas, w, h, night, catX + 18);
            drawCat(canvas, catX, h * .96f);
        }
        if (ufoT >= 0) drawUfo(canvas, w * .5f + 30, ground);
        drawSheep(canvas, w * .5f, ground);

        canvas.restoreToCount(save);
    }

    private void drawNightSky(Canvas c, float w, float night, int skyBot) {
        for (float[] s : stars) {
            float tw = .55f + .45f * sin(clock * 2.4f + s[2]);
            paint.setColor(Color.argb((int) (255 * night * tw), 255, 255, 230));
            float x = s[0] * w, y = s[1];
            c.drawRect(x - 1, y - 1, x + 1, y + 1, paint);
        }
        // 弯月：黄圆减去偏移的天空色圆
        float mx = w * .86f, my = 46, r = 22;
        paint.setColor(Color.argb((int) (255 * night), 255, 240, 74));
        c.drawCircle(mx, my, r, paint);
        paint.setColor(lerpColor(skyBot, NIGHT_SKY_TOP, .7f));
        paint.setAlpha((int) (255 * night));
        c.drawCircle(mx - 9, my - 4, r * .92f, paint);
        paint.setAlpha(255);
    }

    private void drawClouds(Canvas c, float w, float night) {
        paint.setColor(lerpColor(DAY_CLOUD, NIGHT_CLOUD, night));
        for (float[] cl : clouds) {
            float x = cl[0] * w, y = cl[1], s = cl[2];
            c.drawOval(x - 34 * s, y - 7 * s, x + 34 * s, y + 7 * s, paint);
            c.drawOval(x - 20 * s, y - 13 * s, x + 16 * s, y + 3 * s, paint);
            c.drawOval(x - 4 * s, y - 11 * s, x + 26 * s, y + 4 * s, paint);
        }
    }

    /**
     * 把 FFT 幅度按对数频率分成 EQ_BANDS 段（低频在左），快升慢落，峰值帽缓慢下坠。
     * 暂停后 activity→0，所有频段自然回落到 0。
     */
    private void updateEqualizer(float[] spectrum) {
        int n = spectrum == null ? 0 : spectrum.length;
        for (int i = 0; i < EQ_BANDS; i++) {
            float target = 0;
            if (n > 1) {
                int from = 1 + (int) (Math.pow(n - 1, i / (double) EQ_BANDS)) - 1;
                int to = Math.max(from + 1, (int) Math.pow(n - 1, (i + 1) / (double) EQ_BANDS));
                from = Math.max(1, from); to = Math.min(n, Math.max(from + 1, to));
                float max = 0;
                for (int j = from; j < to; j++) max = Math.max(max, spectrum[j]);
                // 高频能量天然偏小，逐段加权；再做压缩让中等音量也能点亮多节
                float tilt = 1f + i * .22f;
                target = (float) Math.min(1, Math.sqrt(Math.min(1, max * tilt * 1.6f)));
            }
            target *= activity;
            lamp[i] += (target - lamp[i]) * (target > lamp[i] ? .6f : .18f);
            if (lamp[i] >= lampPeak[i]) lampPeak[i] = lamp[i];
            else lampPeak[i] = Math.max(lamp[i], lampPeak[i] - DT * .6f);
        }
    }

    /** 飞艇：艇身按飞行方向镜像，侧面均衡器始终正向绘制（低频在左）。 */
    private void drawBlimp(Canvas c, float x, float y, int dir) {
        c.save();
        if (dir > 0) c.scale(-1, 1, x, y);   // 机头朝前进方向
        // 艇身
        paint.setColor(0xffd8d8d8);
        c.drawOval(x - 52, y - 22, x + 52, y + 22, paint);
        paint.setColor(0xffbdbdbd);
        c.drawOval(x - 48, y + 4, x + 48, y + 21, paint);
        // 尾翼
        paint.setColor(0xffc4c4c4);
        path.reset(); path.moveTo(x + 40, y - 6); path.lineTo(x + 66, y - 26); path.lineTo(x + 66, y - 4); path.close();
        c.drawPath(path, paint);
        path.reset(); path.moveTo(x + 40, y + 6); path.lineTo(x + 66, y + 26); path.lineTo(x + 66, y + 4); path.close();
        c.drawPath(path, paint);
        // 吊舱
        paint.setColor(0xff9a9a9a);
        c.drawRoundRect(x - 12, y + 21, x + 10, y + 28, 3, 3, paint);
        c.restore();

        // 均衡器屏幕（不随镜像翻转）
        float left = x - 36, right = x + 30, top = y - 14, bottom = y + 13;
        paint.setColor(0xff101010);
        c.drawRoundRect(left - 2, top - 2, right + 2, bottom + 2, 3, 3, paint);
        float bandW = (right - left) / EQ_BANDS, segH = (bottom - top) / EQ_SEGS;
        for (int i = 0; i < EQ_BANDS; i++) {
            float bx = left + i * bandW;
            int lit = Math.round(lamp[i] * EQ_SEGS);
            for (int sIdx = 0; sIdx < EQ_SEGS; sIdx++) {
                int color = sIdx >= EQ_SEGS - 1 ? EQ_RED : sIdx >= EQ_SEGS - 3 ? EQ_YELLOW : EQ_GREEN;
                paint.setColor(color);
                paint.setAlpha(sIdx < lit ? 255 : 38);   // 未点亮的 LED 保留暗格
                float sy = bottom - (sIdx + 1) * segH;
                c.drawRect(bx + .8f, sy + .8f, bx + bandW - .8f, sy + segH - .8f, paint);
            }
            int peak = Math.min(EQ_SEGS - 1, (int) (lampPeak[i] * EQ_SEGS));
            if (lampPeak[i] > .02f && peak >= lit) {
                paint.setColor(Color.WHITE);
                float py = bottom - (peak + 1) * segH;
                c.drawRect(bx + .8f, py + segH - 2.2f, bx + bandW - .8f, py + segH - .8f, paint);
            }
        }
        paint.setAlpha(255);
    }

    float[] equalizerForTest() { return lamp.clone(); }

    /** 前景草丛：随小猫出现从右侧长到小猫身边，小猫离开时从左侧收回。 */
    private void drawTufts(Canvas c, float w, float h, float night, float leftEnd) {
        float grow = Math.min(1, catT / 2f), shrink = Math.min(1, (CAT_SECONDS - catT) / 2f);
        float from = w - (w - leftEnd) * grow + (w - leftEnd) * (1 - shrink);
        if (from >= w) return;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(2);
        paint.setColor(lerpColor(0xff14b414, 0xff0a700a, night));
        float y = h * .96f;
        for (float x = leftEnd; x < w; x += 16) {
            if (x < from) continue;
            c.drawArc(x, y - 8, x + 16, y + 8, 180, 180, false, paint);
        }
        paint.setStyle(Paint.Style.FILL);
    }

    private void drawBirds(Canvas c, float w, float night) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.6f);
        paint.setColor(lerpColor(Color.WHITE, 0xff9a9aa8, night));
        float flap = sin(clock * 9) * 3;
        float[][] flock = {{0, 0}, {26, -10}, {44, 6}, {70, -4}};
        for (float[] b : flock) {
            float x = w * (1.05f - birdsX * 1.2f) + b[0], y = birdsY + b[1] + sin(clock * 1.3f + b[0]) * 2;
            path.reset();
            path.moveTo(x - 6, y - flap); path.lineTo(x, y); path.lineTo(x + 6, y - flap);
            c.drawPath(path, paint);
        }
        paint.setStyle(Paint.Style.FILL);
    }

    private boolean ufoBeaming() { return ufoT > 2.5f && ufoT < UFO_SECONDS - 2f; }

    private void drawUfo(Canvas c, float x, float ground) {
        float t = ufoT, y;
        float hover = 34 + sin(clock * 3) * 3;
        if (t < 2.5f) y = -30 + (hover + 30) * smooth(t / 2.5f);
        else if (t > UFO_SECONDS - 2f) y = hover - (hover + 30) * smooth((t - (UFO_SECONDS - 2f)) / 2f);
        else y = hover;
        if (ufoBeaming()) {
            float a = Math.min(1, Math.min(t - 2.5f, UFO_SECONDS - 2f - t) * 2);
            paint.setColor(Color.argb((int) (170 * a), 60, 230, 40));
            path.reset();
            path.moveTo(x - 7, y + 6); path.lineTo(x + 7, y + 6);
            path.lineTo(x + 34, ground - 70); path.lineTo(x - 34, ground - 70); path.close();
            c.drawPath(path, paint);
        }
        paint.setColor(0xffb8b8c0);
        c.drawOval(x - 22, y - 5, x + 22, y + 7, paint);
        paint.setColor(0xffe8e8f0);
        c.drawArc(x - 10, y - 12, x + 10, y + 4, 180, 180, true, paint);
        for (int i = 0; i < 3; i++) {
            paint.setColor(((int) (clock * 4) + i) % 3 == 0 ? 0xff7cff5a : 0xff3a8a2a);
            c.drawCircle(x - 12 + i * 12, y + 2, 2, paint);
        }
    }

    /** 左侧斜向透视栅栏。 */
    private void drawFence(Canvas c, float horizon, float night) {
        int white = lerpColor(Color.WHITE, 0xffc8c8d8, night);
        paint.setColor(white);
        paint.setStrokeCap(Paint.Cap.SQUARE);
        paint.setStrokeWidth(5);
        float x0 = 14, y0 = horizon + 80, x1 = 74, y1 = horizon - 20;
        for (int i = 0; i < 3; i++) {
            float t = i / 2f, x = x0 + (x1 - x0) * t, top = y0 + (y1 - y0) * t;
            c.drawLine(x, top - 6, x, top + 52 - 18 * t, paint);
        }
        paint.setStrokeWidth(4);
        c.drawLine(x0 - 6, y0 + 4, x1 + 6, y1 - 2, paint);
        c.drawLine(x0 - 6, y0 + 24, x1 + 6, y1 + 12, paint);
        paint.setStrokeCap(Paint.Cap.BUTT);
    }

    private void drawDisco(Canvas c, float w, float h, float horizon) {
        float drop = discoT < 3 ? discoT / 3 : discoT > 23 ? Math.max(0, (26 - discoT) / 3) : 1;
        float x = w * .42f, y = -30 + drop * 80;
        paint.setColor(0xff9a9a9a); paint.setStrokeWidth(1.5f);
        c.drawLine(x, 0, x, y - 18, paint);
        paint.setColor(0xffdcdcdc);
        c.drawCircle(x, y, 22, paint);
        // 镜面小格随时钟闪烁
        for (int i = 0; i < 25; i++) {
            float gx = x - 17 + (i % 5) * 7, gy = y - 17 + (i / 5) * 7;
            if (Math.hypot(gx + 2.5f - x, gy + 2.5f - y) > 19) continue;
            float f = .5f + .5f * sin(clock * 6 + i * 1.7f);
            paint.setColor(lerpColor(0xff9a9a9a, Color.WHITE, f));
            c.drawRect(gx, gy, gx + 5, gy + 5, paint);
        }
        if (spotGlow > .01f) {
            paint.setColor(Color.argb((int) (255 * Math.min(1, spotGlow)), 255, 255, 255));
            for (float[] s : spots) {
                float sx = s[0] * w, sy = horizon + 20 + s[1] * (h - horizon - 30);
                c.drawOval(sx - 10, sy - 5, sx + 10, sy + 5, paint);
            }
        }
    }

    private void drawCat(Canvas c, float x, float y) {
        float enter = Math.min(1, catT / .6f) * Math.min(1, (15 - catT) / .6f);
        int alpha = (int) (255 * Math.max(0, enter));
        paint.setColor(0xfff08a24); paint.setAlpha(alpha);
        c.drawOval(x - 14, y - 26, x + 14, y + 6, paint);
        c.drawCircle(x, y - 32, 11, paint);
        path.reset();
        path.moveTo(x - 10, y - 38); path.lineTo(x - 8, y - 48); path.lineTo(x - 2, y - 41);
        path.moveTo(x + 10, y - 38); path.lineTo(x + 8, y - 48); path.lineTo(x + 2, y - 41);
        c.drawPath(path, paint);
        paint.setColor(0xffb85a10); paint.setAlpha(alpha);
        c.drawRect(x - 8, y - 4, x - 3, y + 6, paint); c.drawRect(x + 3, y - 4, x + 8, y + 6, paint);
        paint.setColor(Color.BLACK); paint.setAlpha(alpha);
        c.drawCircle(x - 4, y - 33, 1.6f, paint); c.drawCircle(x + 4, y - 33, 1.6f, paint);
        paint.setAlpha(255);
    }

    private static final float LEG = 54;

    private void drawSheep(Canvas c, float cx, float ground) {
        float rx = 62 * (1 + breathe * .04f), ry = 38;
        float baseX = cx + 24;
        float bx = baseX + bodyShift, by = ground - 74 - bodyLift - jumpLift + (crouch + jumpSquash * .5f) * 30;

        // 腿：脚掌落地点固定（跳起时整体离地），髋部随身体移动 → 前冲时腿斜、下蹲时屈膝
        paint.setColor(Color.BLACK);
        paint.setStrokeWidth(4);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setStyle(Paint.Style.STROKE);
        float[] legX = {-36, -20, 30, 46};
        float hipY = by + ry * .55f;
        float tilt = (float) Math.toRadians(bodyTilt);
        for (int i = 0; i < 4; i++) {
            float hipX = bx + legX[i] * (float) Math.cos(tilt);
            float hy = hipY - legX[i] * (float) Math.sin(tilt);
            float tap = (i == 0 || i == 3) ? Math.max(0, legTap) : Math.max(0, -legTap);
            float footX = baseX + legX[i] + (feetLift > 1 ? bodyShift : 0) - tap * .4f;
            // 腾空时脚掌比髋部上升得少一些 → 收腿；落地瞬间脚掌正好回到地面
            float footY = ground - feetLift - tap - jumpLift * .82f;
            boolean front = i < 2;
            drawLeg(c, hipX, hy, footX, footY, front);
            if (outfit == Outfit.COWBOY || outfit == Outfit.PUNK) {
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(outfit == Outfit.PUNK ? 0xffe0308a : 0xff8b4a1c);
                c.drawRect(footX - 4, footY - 9, footX + 6, footY + 1, paint);
                paint.setColor(Color.BLACK);
                paint.setStyle(Paint.Style.STROKE);
            }
        }
        paint.setStyle(Paint.Style.FILL);
        paint.setStrokeCap(Paint.Cap.BUTT);

        c.save();
        c.rotate(bodyTilt, bx, by);
        // 羊毛：扇贝描边 + 填充
        int n = 22;
        paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(3); paint.setColor(Color.BLACK);
        for (int i = 0; i < n; i++) {
            float ang = i / (float) n * (float) Math.PI * 2;
            c.drawCircle(bx + (float) Math.cos(ang) * rx, by + sin(ang) * ry, 11, paint);
        }
        int wool = outfit == Outfit.PUNK ? 0xffff8cf0 : Color.WHITE;
        paint.setStyle(Paint.Style.FILL); paint.setColor(wool);
        for (int i = 0; i < n; i++) {
            float ang = i / (float) n * (float) Math.PI * 2;
            c.drawCircle(bx + (float) Math.cos(ang) * rx, by + sin(ang) * ry, 11, paint);
        }
        c.drawOval(bx - rx, by - ry, bx + rx, by + ry, paint);
        if (peace) drawPeace(c, bx + 26, by - 4);

        // 头：绕脖子大幅甩动
        float neckX = bx - rx + 24, neckY = by - 20;
        float hx = bx - rx + 4, hy = by - 16 + headDrop;
        c.rotate(4 - headAngle, neckX, neckY);   // headAngle 为正 → 低头
        paint.setColor(Color.BLACK);
        path.reset();
        path.moveTo(hx + 10, hy - 18); path.quadTo(hx + 44, hy - 14, hx + 40, hy + 18);
        path.quadTo(hx + 30, hy + 4, hx + 8, hy - 6); path.close();
        c.drawPath(path, paint);
        c.drawOval(hx - 30, hy - 24, hx + 24, hy + 22, paint);
        c.drawOval(hx - 40, hy - 6, hx - 6, hy + 26, paint);
        drawMouth(c, hx - 24, hy + 14);
        drawEyes(c, hx - 10, hy - 6);
        drawHat(c, hx - 4, hy - 22);
        c.restore();
    }

    /** 两段式腿：髋到脚距离小于腿长时膝盖弯曲（前腿膝盖朝前、后腿朝后）。 */
    private void drawLeg(Canvas c, float hx, float hy, float fx, float fy, boolean front) {
        float dx = fx - hx, dy = fy - hy, d = (float) Math.hypot(dx, dy);
        float half = LEG / 2;
        if (d >= LEG - .5f || d < 1) { c.drawLine(hx, hy, fx, fy, paint); return; }
        float bend = (float) Math.sqrt(half * half - (d / 2) * (d / 2));
        float nx = -dy / d, ny = dx / d;                 // 垂直方向
        float dir = front ? -1 : 1;
        if (nx * dir < 0) { nx = -nx; ny = -ny; }        // 膝盖朝前（左）或朝后（右）
        float kx = hx + dx / 2 + nx * bend, ky = hy + dy / 2 + ny * bend;
        path.reset(); path.moveTo(hx, hy); path.lineTo(kx, ky); path.lineTo(fx, fy);
        c.drawPath(path, paint);
    }

    private void drawMouth(Canvas c, float x, float y) {
        if (face == Face.GRIN && !ufoBeaming()) {
            // 咧嘴笑露出牙齿
            paint.setColor(Color.WHITE);
            c.drawRect(x - 6, y - 4, x + 16, y + 3, paint);
            paint.setColor(Color.BLACK); paint.setStrokeWidth(1.2f);
            for (float tx = x - 1; tx < x + 16; tx += 5) c.drawLine(tx, y - 4, tx, y + 3, paint);
            c.drawLine(x - 6, y, x + 16, y, paint);
        }
        if (face == Face.TONGUE || ufoBeaming()) {
            float out = ufoBeaming() ? 1 : Math.min(1, faceT * 4);
            paint.setColor(0xffff7aa0);
            c.drawRoundRect(x - 2, y - 2, x + 9, y + 6 + out * 10, 5, 5, paint);
        }
    }

    private void drawEyes(Canvas c, float x, float y) {
        if (ufoBeaming()) {
            // 被 UFO 照到：瞪大眼睛
            paint.setColor(Color.WHITE);
            c.drawCircle(x - 9, y, 9, paint); c.drawCircle(x + 11, y, 9, paint);
            paint.setColor(Color.BLACK);
            c.drawCircle(x - 9, y, 3, paint); c.drawCircle(x + 11, y, 3, paint);
            return;
        }
        if (outfit != Outfit.SUNGLASSES && outfit != Outfit.PUNK && face != Face.NORMAL
                && face != Face.TONGUE && face != Face.GRIN) {
            drawFunnyEyes(c, x, y);
            return;
        }
        if (outfit == Outfit.SUNGLASSES || outfit == Outfit.PUNK) {
            paint.setColor(0xff8a8a8a);
            c.drawRoundRect(x - 18, y - 6, x - 2, y + 7, 4, 4, paint);
            c.drawRoundRect(x + 2, y - 6, x + 18, y + 7, 4, 4, paint);
            paint.setStrokeWidth(2); c.drawLine(x - 2, y - 2, x + 2, y - 2, paint);
            paint.setColor(0xffd0d0d0);
            c.drawRect(x - 15, y - 4, x - 11, y - 1, paint); c.drawRect(x + 5, y - 4, x + 9, y - 1, paint);
            return;
        }
        paint.setColor(Color.WHITE);
        c.drawOval(x - 17, y - 7, x - 3, y + 6, paint);
        c.drawOval(x + 3, y - 7, x + 17, y + 6, paint);
        paint.setColor(Color.BLACK);
        c.drawCircle(x - 9, y + 2, 2.4f, paint);
        c.drawCircle(x + 11, y + 2, 2.4f, paint);
        // 慵懒的半闭眼皮 + 眨眼
        float lid = .45f + blink * .55f;
        c.drawRect(x - 18, y - 8, x - 2, y - 8 + 14 * lid, paint);
        c.drawRect(x + 2, y - 8, x + 18, y - 8 + 14 * lid, paint);
        paint.setColor(Color.WHITE); paint.setStrokeWidth(1.5f);
        c.drawLine(x - 17, y - 8 + 14 * lid, x - 3, y - 8 + 14 * lid, paint);
        c.drawLine(x + 3, y - 8 + 14 * lid, x + 17, y - 8 + 14 * lid, paint);
    }

    private void drawFunnyEyes(Canvas c, float x, float y) {
        switch (face) {
            case BIG_EYE:   // 一只眼睛瞪得巨大，另一只照旧慵懒
                paint.setColor(Color.WHITE); c.drawCircle(x - 9, y + 1, 12, paint);
                paint.setColor(Color.BLACK); c.drawCircle(x - 6, y + 2, 2.6f, paint);
                paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(1.5f);
                c.drawCircle(x - 9, y + 1, 12, paint); paint.setStyle(Paint.Style.FILL);
                paint.setColor(Color.WHITE); c.drawOval(x + 5, y - 6, x + 17, y + 5, paint);
                paint.setColor(Color.BLACK); c.drawRect(x + 4, y - 7, x + 18, y - 1, paint);
                c.drawCircle(x + 11, y + 2, 2.2f, paint);
                break;
            case WINK:      // 一只眼闭成弧线
                paint.setColor(Color.WHITE); c.drawOval(x - 17, y - 8, x - 1, y + 7, paint);
                paint.setColor(Color.BLACK); c.drawCircle(x - 9, y, 3, paint);
                paint.setColor(Color.WHITE); paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(2.2f);
                c.drawArc(x + 3, y - 4, x + 17, y + 6, 200, 140, false, paint);
                paint.setStyle(Paint.Style.FILL);
                break;
            case CROSS_EYED: // 斗鸡眼
                paint.setColor(Color.WHITE);
                c.drawCircle(x - 9, y, 8, paint); c.drawCircle(x + 10, y, 8, paint);
                paint.setColor(Color.BLACK);
                c.drawCircle(x - 4, y + 2, 3, paint); c.drawCircle(x + 5, y + 2, 3, paint);
                break;
            default:
                break;
        }
    }

    private void drawHat(Canvas c, float x, float y) {
        switch (outfit) {
            case COWBOY:
                paint.setColor(0xff8b4a1c);
                c.drawRoundRect(x - 16, y - 22, x + 18, y + 2, 8, 8, paint);
                c.drawOval(x - 36, y - 4, x + 38, y + 6, paint);
                paint.setColor(0xff5e3010);
                c.drawRect(x - 16, y - 6, x + 18, y - 2, paint);
                break;
            case TOP_HAT:
                paint.setColor(Color.BLACK);
                c.drawRect(x - 13, y - 34, x + 15, y, paint);
                c.drawOval(x - 26, y - 4, x + 28, y + 4, paint);
                paint.setColor(0xff444444);
                c.drawRect(x - 13, y - 8, x + 15, y - 4, paint);
                break;
            case PUNK:
                // 粉色莫西干 + 金色耳环
                paint.setColor(0xffff3aa8);
                path.reset();
                path.moveTo(x - 10, y + 4); path.lineTo(x - 6, y - 16); path.lineTo(x - 1, y + 2);
                path.lineTo(x + 3, y - 20); path.lineTo(x + 7, y + 2); path.lineTo(x + 12, y - 14);
                path.lineTo(x + 14, y + 6); path.close();
                c.drawPath(path, paint);
                paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(2); paint.setColor(0xffffd23a);
                c.drawCircle(x - 30, y + 20, 4, paint);
                paint.setStyle(Paint.Style.FILL);
                break;
            case BOW:
                paint.setColor(0xffff8ac8);
                path.reset();
                path.moveTo(x, y + 2); path.lineTo(x - 14, y - 8); path.lineTo(x - 14, y + 12); path.close();
                path.moveTo(x, y + 2); path.lineTo(x + 14, y - 8); path.lineTo(x + 14, y + 12); path.close();
                c.drawPath(path, paint);
                paint.setColor(0xffe04a9a);
                c.drawCircle(x, y + 2, 4, paint);
                break;
            case RED_CAP:
                paint.setColor(0xffe0222a);
                c.drawArc(x - 20, y - 14, x + 20, y + 14, 180, 180, true, paint);
                paint.setColor(0xffa8141a);
                c.drawOval(x - 38, y - 3, x - 6, y + 4, paint);
                break;
            default:
                break;
        }
    }

    private void drawPeace(Canvas c, float x, float y) {
        paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(2); paint.setColor(0xff333333);
        float r = 10;
        c.drawCircle(x, y, r, paint);
        c.drawLine(x, y - r, x, y + r, paint);
        c.drawLine(x, y, x - r * .7f, y + r * .7f, paint);
        c.drawLine(x, y, x + r * .7f, y + r * .7f, paint);
        paint.setStyle(Paint.Style.FILL);
    }

    @Override public boolean isAtRest() {
        if (activity > 0 || jumpPhase != JumpPhase.GROUND) return false;
        float motion = Math.abs(bodyLift) + Math.abs(bodyShift) + Math.abs(bodyTilt) + crouch
                + Math.abs(headAngle) + headDrop + feetLift + Math.abs(legTap) + breathe + spotGlow + jumpLift + jumpSquash;
        for (int i = 0; i < EQ_BANDS; i++) motion += lamp[i] + lampPeak[i];
        return motion < REST;
    }

    @Override public void reset() {
        activity = bass = treble = volume = 0;
        breathe = blink = spotGlow = 0;
        jumpPhase = JumpPhase.GROUND; jumpLift = jumpSquash = 0; barsUntilJump = 3;
        freeBeat = beatPos = 0; lastBeat = Long.MIN_VALUE;
        bodyLift = bodyShift = bodyTilt = crouch = headAngle = headDrop = feetLift = legTap = 0;
        face = Face.NORMAL; faceT = 0;
        java.util.Arrays.fill(lamp, 0);
        java.util.Arrays.fill(lampPeak, 0);
        analyzer.reset();
    }

    // ---- 测试辅助 ----
    float clockForTest() { return clock; }
    void setClockForTest(float seconds) { clock = seconds; }
    void setBeatSource(BeatSource source) { beatSource = source; }
    void setTimeSourceForTest(java.util.function.LongSupplier clock) { nowMs = clock; }
    double beatPosForTest() { return beatPos; }
    Move moveForTest() { return move; }
    float jumpLiftForTest() { return jumpLift; }
    float jumpSquashForTest() { return jumpSquash; }
    void setFaceForTest(Face f) { face = f; faceT = f == Face.NORMAL ? 0 : 999; faceTimer = 999; }
    void setMoveForTest(Move m) { move = m; moveBeats = 999; }
    float poseMagnitudeForTest() {
        return Math.max(Math.max(bodyLift + jumpLift, Math.abs(bodyShift)), Math.max(crouch * 30, headAngle));
    }
    float blimpDirForTest() { return blimpDir; }
    void setOutfitForTest(Outfit o) { outfit = o; peace = o == Outfit.SUNGLASSES; outfitTimer = 999; }
    void triggerForTest(String event) {
        switch (event) {
            case "cat": catT = 8; break;
            case "ufo": ufoT = 4; discoT = -1; break;
            case "disco": discoT = 10; ufoT = -1; break;
            case "birds": birdsX = .4f; break;
            case "blimp": blimpX = .3f; break;
            case "blimpRight": blimpX = .3f; blimpDir = 1; break;
            default: break;
        }
    }
    float activityForTest() { return activity; }
}
