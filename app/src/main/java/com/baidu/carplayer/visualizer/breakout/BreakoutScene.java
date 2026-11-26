package com.baidu.carplayer.visualizer.breakout;

/**
 * 音乐驱动打砖块的纯 Java 场景仿真。不依赖 Android 类，可在 JVM 上单测。
 *
 * <p>坐标：x ∈ [0,1]，y ∈ [0,aspect]，y 向下；aspect = 视图高/宽，
 * 这样圆在任意屏幕上都是圆。固定步长 1/120s 推进，音频事件在每帧更新一次。
 *
 * <p>球永不掉落由三层保证：解析展开反射求落点 → 挡板临界阻尼追踪 + 磁吸修正 →
 * 底边托底强制反弹。前两层让接球看起来自然，第三层保证不变量。
 */
public final class BreakoutScene {
    public static final int COLS = 10;
    public static final int ROWS = 5;
    public static final int BRICK_COUNT = COLS * ROWS;
    public static final int MAX_BALLS = 5;
    public static final int TRAIL = 6;
    public static final int MAX_SHOCKS = 16;
    public static final int FORMATIONS = 6;
    public static final float BALL_R = 0.012f;
    /** 挡板中心距底边的距离（y 向下为正）。越小挡板越靠下，砖块可用高度越大。 */
    public static final float PADDLE_MARGIN = 0.035f;
    /** 砖块区在场景中的纵向范围（相对 aspect 的比例），顶部起点与终点。 */
    public static final float BRICK_TOP = 0.045f;
    public static final float BRICK_SPAN = 0.475f;
    /** 砖墙纵向中心（相对 aspect），瞄准逻辑用它当目标高度。 */
    public static final float BRICK_CENTER = BRICK_TOP + BRICK_SPAN * 0.5f;
    public static final float STEP = 1f / 120f;
    public static final float BASE_SPEED = 0.42f;

    public static final int F_GRID = 0, F_DIAMOND = 1, F_ARCH = 2,
            F_WAVE = 3, F_RING = 4, F_SPECTRUM = 5;

    /** 大本营回击类型：常规推挡、削球、搓球、救球。 */
    public static final int SHOT_DRIVE = 0, SHOT_CHOP = 1,
            SHOT_SPIN = 2, SHOT_SAVE = 3;

    /** 道具砖类型：减速、加速、挡板加宽、挡板变窄、分身多球。 */
    public static final int ITEM_NONE = 0, ITEM_SLOW = 1, ITEM_FAST = 2,
            ITEM_WIDE = 3, ITEM_NARROW = 4, ITEM_MULTI = 5;

    public static final class Audio {
        public float bass, mid, treble, volume, centroid, flux;
        public double beatPosition = Double.NaN;
        public float beatConfidence, periodMs = 500f;
        public boolean peak;
    }

    public static final class Ball {
        public boolean active;
        public float x, y, vx, vy;
        public float speedScale = 1f;
        /** 侧旋：飞行中的横向加速度，随时间衰减；撞墙时反向衰减。 */
        public float spin;
        public final float[] trailX = new float[TRAIL];
        public final float[] trailY = new float[TRAIL];
    }

    public static final class Brick {
        public boolean alive;
        public int col, row, band, type;
        public float x, y, targetX, targetY, w, h;
    }

    public static final class Shock {
        public boolean active;
        public float x, y, age;
        public float scale = 1f;
    }

    public static final class Paddle {
        public float x = .5f, v, width = .16f, targetWidth = .16f;
        public int shotType = SHOT_DRIVE;
        /** 本次回击动作的残留时间，供渲染器做光效。 */
        public float shotTimer;
        /** 扑救强度 0..1，>0.45 时回击判为救球。 */
        public float dash;
    }

    private float aspect = .6f;
    private float accumulator, time, colorT, rescueFlash;
    private float emaBass, emaMid, emaVolume, emaTreble, quietTime;
    private double lastBeat = Double.NaN;
    private long beatCount;
    private int formation;
    private float slowFactor = 1f;
    private float speedBoostUntil, wideUntil, splitCooldownUntil;
    private float pierceUntil, chainUntil, shockwaveFlash;
    private float fastUntil, slowUntil, narrowUntil;
    /** 最近一次音频状态，供击球瞬间决定回击类型。 */
    private float lastBass, lastTreble, lastCentroid, lastConfidence;

    private final Ball[] balls = new Ball[MAX_BALLS];
    private final Brick[] bricks = new Brick[BRICK_COUNT];
    private final Shock[] shocks = new Shock[MAX_SHOCKS];
    private final Paddle paddle = new Paddle();
    private int shockCursor;
    private final float[] layout = new float[2];

    public BreakoutScene() {
        for (int i = 0; i < MAX_BALLS; i++) balls[i] = new Ball();
        for (int i = 0; i < BRICK_COUNT; i++) bricks[i] = new Brick();
        for (int i = 0; i < MAX_SHOCKS; i++) shocks[i] = new Shock();
        reset();
    }

    public Ball[] balls() { return balls; }
    public Brick[] bricks() { return bricks; }
    public Shock[] shocks() { return shocks; }
    public Paddle paddle() { return paddle; }
    public float aspect() { return aspect; }
    public float time() { return time; }
    public float colorT() { return colorT; }
    public float rescueFlash() { return rescueFlash; }
    public float shockwaveFlash() { return shockwaveFlash; }
    public boolean piercing() { return time < pierceUntil; }
    public boolean chaining() { return time < chainUntil; }
    public boolean fastActive() { return time < fastUntil; }
    public boolean slowActive() { return time < slowUntil; }
    public boolean narrowActive() { return time < narrowUntil; }
    public boolean wideActive() { return time < wideUntil; }
    public float bass() { return emaBass; }
    public float mid() { return emaMid; }
    public float treble() { return emaTreble; }
    public float volume() { return emaVolume; }
    public int formation() { return formation; }
    public long beatCount() { return beatCount; }

    /** 旋转屏幕会重置场景：坐标以宽为基准，比例变了必须重排。 */
    public void setAspect(float value) {
        float a = Math.max(.25f, Math.min(2f, value));
        if (Math.abs(a - aspect) < 1e-3f) return;
        aspect = a;
        reset();
    }

    public void reset() {
        accumulator = time = colorT = rescueFlash = shockwaveFlash = 0f;
        emaBass = emaMid = emaVolume = emaTreble = quietTime = 0f;
        lastBeat = Double.NaN;
        beatCount = 0;
        slowFactor = 1f;
        speedBoostUntil = wideUntil = splitCooldownUntil = 0f;
        pierceUntil = chainUntil = 0f;
        fastUntil = slowUntil = narrowUntil = 0f;
        lastBass = lastTreble = lastCentroid = lastConfidence = 0f;
        paddle.x = .5f; paddle.v = 0f; paddle.width = paddle.targetWidth = .16f;
        paddle.shotType = SHOT_DRIVE; paddle.shotTimer = 0f; paddle.dash = 0f;
        for (Ball b : balls) { b.active = false; b.spin = 0f; }
        Ball b = balls[0];
        b.active = true;
        b.x = .5f; b.y = aspect * .74f;
        b.speedScale = 1f;
        float sp = BASE_SPEED;
        b.vx = sp * .42f;
        b.vy = -(float) Math.sqrt(sp * sp - b.vx * b.vx);
        for (int i = 0; i < TRAIL; i++) { b.trailX[i] = b.x; b.trailY[i] = b.y; }
        for (Shock s : shocks) s.active = false;
        for (Brick brick : bricks) { brick.x = 0f; brick.y = 0f; }
        rebuild(0);
    }

    /** 每帧调用：处理音频事件，然后以固定步长推进物理。 */
    public void update(float dt, Audio a) {
        dt = Math.max(0f, Math.min(.05f, dt));
        time += dt;
        float k = Math.min(1f, dt * 2f);
        emaBass += (a.bass - emaBass) * k;
        emaVolume += (a.volume - emaVolume) * k;
        emaMid += (a.mid - emaMid) * k;
        emaTreble += (a.treble - emaTreble) * k;
        colorT += (a.centroid - colorT) * Math.min(1f, dt * 5f);

        handleAudio(a, dt);
        accumulator += dt;
        int guard = 0;
        while (accumulator >= STEP && guard++ < 8) {
            step(STEP);
            accumulator -= STEP;
        }
    }

    private void handleAudio(Audio a, float dt) {
        lastBass = emaBass;
        lastTreble = emaTreble;
        lastCentroid = colorT;
        lastConfidence = a.beatConfidence;
        float period = Math.max(.3f, a.periodMs / 1000f);
        if (!Double.isNaN(a.beatPosition)) {
            int now = (int) Math.floor(a.beatPosition);
            if (!Double.isNaN(lastBeat) && now > (int) Math.floor(lastBeat)) {
                beatCount++;
                if (a.beatConfidence > .5f) {
                    if (beatCount % 8 == 0) wideUntil = time + 4f * period;
                    if (emaBass > .12f && a.bass - emaBass > .12f && a.flux > .6f) {
                        speedBoostUntil = time + 4f * period;
                    }
                    // 低频强拍：球进入穿透状态，连续碎砖不反弹。
                    if (emaBass > .10f && a.bass - emaBass > .08f && a.flux > .45f) {
                        pierceUntil = time + 3f * period;
                    }
                    if (beatCount % 16 == 0) rebuild((formation + 1) % FORMATIONS);
                }
            }
            lastBeat = a.beatPosition;
        }
        if (a.peak && emaTreble > .55f) {
            // 高频 peak：碎砖时波及相邻砖块，形成连锁爆破。
            chainUntil = time + 4f * period;
            if (time >= splitCooldownUntil) {
                split();
                splitCooldownUntil = time + 8f * period;
            }
        }
        quietTime = a.volume < .18f ? quietTime + dt : 0f;
        float targetSlow = quietTime > 2f ? .72f : 1f;
        slowFactor += (targetSlow - slowFactor) * Math.min(1f, dt * 3f);
        paddle.targetWidth = time < wideUntil ? .16f * 1.55f
                : (time < narrowUntil ? .16f * .60f : .16f);
    }

    private void split() {
        Ball source = null;
        for (Ball b : balls) if (b.active && (source == null || b.y > source.y)) source = b;
        if (source == null) return;
        int spawned = 0;
        for (Ball b : balls) {
            if (b.active || b == source) continue;
            b.active = true;
            b.x = source.x; b.y = source.y;
            b.speedScale = source.speedScale;
            b.spin = 0f;
            float angle = (spawned == 0 ? -.32f : .32f);
            b.vx = BASE_SPEED * (float) Math.sin(angle);
            b.vy = -(float) Math.sqrt(Math.max(.01f,
                    BASE_SPEED * BASE_SPEED - b.vx * b.vx));
            for (int i = 0; i < TRAIL; i++) { b.trailX[i] = b.x; b.trailY[i] = b.y; }
            if (++spawned >= 2) break;
        }
    }

    private void step(float h) {
        rescueFlash *= (float) Math.pow(.86, h * 60f);
        shockwaveFlash *= (float) Math.pow(.90, h * 60f);
        paddle.width += (paddle.targetWidth - paddle.width) * Math.min(1f, h * 8f);
        paddle.shotTimer = Math.max(0f, paddle.shotTimer - h);
        paddle.dash *= Math.max(0f, 1f - h * 2.5f);

        Ball track = threatBall();
        if (track != null) drivePaddle(track, h);

        float speed = BASE_SPEED
                * (time < speedBoostUntil || time < fastUntil ? 1.22f : 1f)
                * (time < slowUntil ? .72f : 1f)
                * slowFactor;

        for (Ball b : balls) {
            if (!b.active) continue;
            setSpeed(b, speed * b.speedScale);
            float top = paddleTop();
            if (b.y > top - .12f && b.vy > 0f) {
                float want = clamp((paddle.x - b.x) * 1.8f, -speed, speed);
                b.vx += (want - b.vx) * Math.min(1f, h * 4f);
                setSpeed(b, speed * b.speedScale);
            }
            // 搓球侧旋作为额外横向速度，随飞行衰减；撞墙后反向并大幅减弱。
            b.x += (b.vx + b.spin) * h;
            b.y += b.vy * h;
            b.spin *= Math.max(0f, 1f - h * 2.2f);
            if (b.x < BALL_R) { b.x = BALL_R; b.vx = Math.abs(b.vx); b.spin = Math.abs(b.spin) * .5f; }
            if (b.x > 1f - BALL_R) { b.x = 1f - BALL_R; b.vx = -Math.abs(b.vx); b.spin = -Math.abs(b.spin) * .5f; }
            if (b.y < BALL_R) { b.y = BALL_R; b.vy = Math.abs(b.vy); }
            hitBricks(b);
            hitPaddle(b, top);
            // 球顶已低于挡板面且在下落：没被接住，判扑救。贴底是最后兜底
            // （留白小于接触带时，旧条件在球穿过接触带前就失效）。
            if (b.y + BALL_R >= aspect
                    || (b.vy > 0f && b.y - BALL_R > top)) rescue(b);
            for (int i = TRAIL - 1; i > 0; i--) {
                b.trailX[i] = b.trailX[i - 1];
                b.trailY[i] = b.trailY[i - 1];
            }
            b.trailX[0] = b.x; b.trailY[0] = b.y;
        }
        for (Shock s : shocks) {
            if (!s.active) continue;
            s.age += h;
            if (s.age > .42f) s.active = false;
        }
        if (noBricksLeft()) {
            for (Ball b : balls) b.active = false;
            balls[0].active = true;
            balls[0].x = .5f; balls[0].y = aspect * .74f;
            balls[0].vx = BASE_SPEED * .42f;
            balls[0].vy = -(float) Math.sqrt(BASE_SPEED * BASE_SPEED - balls[0].vx * balls[0].vx);
            balls[0].speedScale = 1f;
            balls[0].spin = 0f;
            rebuild((formation + 1) % FORMATIONS);
        }
    }

    /**
     * 多球时选"最危险"的那个：优先到达挡板线时间最短的；
     * 已经无法可及时也纳入，由 drivePaddle 的救球冲刺处理。供测试直接验证。
     */
    public Ball threatBall() {
        Ball best = null;
        float bestEta = Float.MAX_VALUE;
        float top = paddleTop();
        for (Ball b : balls) {
            if (!b.active) continue;
            float eta = b.vy > 1e-5f ? (top - b.y) / b.vy : Float.MAX_VALUE;
            // 用 <= ：球正在上升时 eta 也是 MAX_VALUE，仍必须被选中待命。
            if (best == null || eta <= bestEta) { bestEta = eta; best = b; }
        }
        return best;
    }

    private void drivePaddle(Ball b, float h) {
        float top = paddleTop();
        float t = b.vy > 1e-5f ? (top - BALL_R - b.y) / b.vy : 0f;
        // 侧旋会改变落点，预测时折算一半（spin 在飞行中指数衰减）。
        float target = t > 0f ? predict(b.x + (b.vx + b.spin * .5f) * t) : b.x;
        float half = paddle.width * .5f;

        if (t <= 0f) {
            // 球正远离挡板：提前回砖区下方待命，而不是停在原地空等。
            target = brickTargetX(b.x, b.y);
        } else if (t > .35f && aliveBricks() > 0) {
            // 安全区间的"垫球"：把落点往一侧偏一点，让普通回击也朝砖区走。
            // 偏移量不超过半个挡板，临近时收回中心，保证不漏球。
            float center = brickTargetX(b.x, b.y);
            float dx = unfoldToward(b.x, center) - b.x;
            float steer = clamp(dx * .35f, -half * .55f, half * .55f);
            target = clamp(target - steer, half, 1f - half);
        }
        target = clamp(target, half, 1f - half);

        float gap = Math.abs(target - paddle.x);
        // 救球：球将到底且挡板差得远，临时解除速度上限做扑救。
        boolean urgent = t > 0f && t < .40f && gap > .11f;
        if (urgent) paddle.dash = Math.min(1f, paddle.dash + h * 6f);
        // 球垂直飞行时 vx≈0，挡板仍需有独立巡航能力，不能被球的横向速度锁死。
        float maxSpeed = urgent ? Math.max(.95f, Math.abs(b.vx) * 2.8f + .90f)
                : Math.max(.55f, Math.abs(b.vx) * 1.35f + .12f);
        float stiffness = urgent ? 42f : 18f;
        float damping = urgent ? 10f : 8f;
        float accel = (target - paddle.x) * stiffness - paddle.v * damping;
        paddle.v = clamp(paddle.v + accel * h, -maxSpeed, maxSpeed);
        paddle.x = clamp(paddle.x + paddle.v * h, half, 1f - half);
    }

    /** 把两侧墙展开，解析求球到达挡板线时的 x；周期为 2（x 域 [0,1] 来回反射）。 */
    public float predict(float unwrappedX) {
        float p = unwrappedX - 2f * (float) Math.floor(unwrappedX / 2f);
        return p <= 1f ? p : 2f - p;
    }

    /** 供测试直接验证落点预测。 */
    public float predictLanding(Ball b) {
        float top = paddleTop();
        if (b.vy <= 1e-5f) return b.x;
        float t = (top - BALL_R - b.y) / b.vy;
        return t <= 0f ? b.x : predict(b.x + b.vx * t);
    }

    private void hitPaddle(Ball b, float top) {
        if (b.vy <= 0f || b.y + BALL_R < top || b.y - BALL_R > top + .02f) return;
        float half = paddle.width * .5f;
        if (Math.abs(b.x - paddle.x) > half + BALL_R) return;
        float offset = clamp((b.x - paddle.x) / half, -1f, 1f);
        applyShot(b, chooseShot(b, offset), offset);
        b.y = top - BALL_R;
    }

    /**
     * 回击类型由音乐决定：低频主导偏削球，高频或频谱质心高偏搓球；
     * 挡板正处于扑救冲刺状态时强制为救球。
     */
    private int chooseShot(Ball b, float offset) {
        if (paddle.dash > .45f) return SHOT_SAVE;
        float bias = lastBass - lastTreble;
        if (bias > .06f && lastConfidence > .25f) return SHOT_CHOP;
        // 残局：球和剩余砖块分居两侧时，用削球把球主动送回砖区。
        if (aliveBricks() <= 8) {
            float targetX = brickTargetX(b.x, b.y);
            if (Math.abs(unfoldToward(b.x, targetX) - b.x) > .25f) return SHOT_CHOP;
        }
        if (-bias > .08f || lastCentroid > .58f) return SHOT_SPIN;
        return SHOT_DRIVE;
    }

    private int aliveBricks() {
        int n = 0;
        for (Brick brick : bricks) if (brick.alive) n++;
        return n;
    }

    /** 横向价值场，brickTargetX 复用，避免每次调用分配数组。 */
    private final float[] targetScore = new float[COLS];

    /**
     * 球的目标点：扫描横向价值密度，选"威力更大、剩余砖更多"的一侧。
     * 道具砖权重高于普通砖（触发技能、连锁清场）；相邻列计入密度加成；
     * 离球远的略降权，但不会压过价值差——配合 unfoldToward 仍可打到远端密集区。
     */
    private float brickTargetX(float fromX, float fromY) {
        for (int i = 0; i < COLS; i++) targetScore[i] = 0f;
        for (Brick brick : bricks) {
            if (!brick.alive) continue;
            int bin = (int) (brick.x * COLS);
            if (bin < 0) bin = 0;
            else if (bin >= COLS) bin = COLS - 1;
            // 道具砖威力更大：打中能触发技能与连锁清场，权重 3 倍。
            float value = brick.type == ITEM_NONE ? 1f : 3f;
            // 靠上方的砖更早被球命中，略加权。
            if (brick.y < fromY) value *= 1.1f;
            targetScore[bin] += value;
        }
        // 穿透/连锁状态下更吃密集区，一次能带走更多砖。
        float neighborWeight = piercing() || chaining() ? .75f : .45f;
        int best = -1;
        float bestScore = -1f;
        for (int i = 0; i < COLS; i++) {
            float density = targetScore[i]
                    + (i > 0 ? targetScore[i - 1] : 0f) * neighborWeight
                    + (i < COLS - 1 ? targetScore[i + 1] : 0f) * neighborWeight;
            if (density <= 0f) continue;
            float centerX = (i + .5f) / COLS;
            float reach = 1f / (1f + Math.abs(centerX - fromX) * .45f);
            float score = density * reach;
            if (score > bestScore) { bestScore = score; best = i; }
        }
        return best >= 0 ? (best + .5f) / COLS : .5f;
    }

    /** x 域 [0,1] 侧墙反射周期为 2，取离fromX最近的镜像作为瞄准点。 */
    private static float unfoldToward(float fromX, float targetX) {
        float k = Math.round((fromX - targetX) / 2f);
        return targetX + 2f * k;
    }

    private void applyShot(Ball b, int shot, float offset) {
        paddle.shotType = shot;
        paddle.shotTimer = .65f;
        if (shot == SHOT_SAVE) {
            paddle.dash = 1f;
            rescueFlash = Math.max(rescueFlash, .80f);
            // 救球奖励：物理挡板同步加宽，和白热宽板的视觉反馈一致。
            wideUntil = Math.max(wideUntil, time + .65f);
        }
        float sp = (float) Math.sqrt(b.vx * b.vx + b.vy * b.vy);
        if (sp < 1e-5f) sp = BASE_SPEED;
        float vx, vy;
        if (shot == SHOT_CHOP || shot == SHOT_SPIN || shot == SHOT_SAVE) {
            // 三种主动技巧都瞄准剩余砖块，不让球被送进空区。
            // 削球压平降速；搓球带弧线；救球允许更陡并加速。
            float targetX = brickTargetX(b.x, b.y);
            float dx = unfoldToward(b.x, targetX) - b.x;
            float dy = aspect * BRICK_CENTER - b.y; // 瞄准砖墙中心，随布局常量一起变化
            float limit = shot == SHOT_SAVE ? 1.10f
                    : (shot == SHOT_SPIN ? 1.05f : .95f);
            float angle = clamp((float) Math.atan2(dx, -dy), -limit, limit);
            if (Math.abs(angle) < .18f) angle = (angle >= 0f ? 1f : -1f) * .18f;
            vx = sp * (float) Math.sin(angle);
            vy = -(float) Math.sqrt(Math.max(sp * sp * .04f, sp * sp - vx * vx));
        } else {
            float angle = offset * 1.05f;
            vx = sp * (float) Math.sin(angle);
            if (Math.abs(vx) < sp * .18f) vx = (vx >= 0f ? 1f : -1f) * sp * .18f;
            vy = -(float) Math.sqrt(Math.max(sp * sp * .04f, sp * sp - vx * vx));
        }
        b.vx = vx;
        b.vy = vy;
        if (shot == SHOT_CHOP) b.speedScale = Math.max(.66f, b.speedScale * .76f);
        else if (shot == SHOT_SAVE) b.speedScale = Math.min(1.38f, b.speedScale * 1.14f);
        // 侧旋方向要跟瞄准一致：球在挡板右侧接球（offset>0）时若仍按 offset
        // 给正 spin，弧线会把球继续往右带，抵消朝砖区的初速度。
        float aimDx = unfoldToward(b.x, brickTargetX(b.x, b.y)) - b.x;
        if (shot == SHOT_SPIN) {
            float spin = Math.signum(aimDx) * Math.min(.14f,
                    Math.max(.04f, Math.abs(aimDx) * .18f));
            b.spin = spin;
        } else if (shot == SHOT_SAVE) {
            b.spin = Math.signum(aimDx) * Math.abs(offset) * .08f;
        } else {
            b.spin = 0f;
        }
    }

    private void rescue(Ball b) {
        float half = paddle.width * .5f;
        float offset = clamp((b.x - paddle.x) / half, -1f, 1f);
        paddle.x = clamp(b.x, half, 1f - half);
        paddle.v = 0f;
        applyShot(b, SHOT_SAVE, offset);
        b.y = paddleTop() - BALL_R;
        rescueFlash = 1f;
        // 救球释放震荡波，清掉落点周围一片砖块，避免反复救同一区域。
        shockwave(b.x, b.y, .16f);
    }

    /** 救球反击：清除落点上方一条柱状区域的砖块，波及宽度由 radius 给出。 */
    private void shockwave(float cx, float cy, float radius) {
        shockwaveFlash = 1f;
        for (Brick brick : bricks) {
            if (!brick.alive) continue;
            if (Math.abs(brick.x - cx) < radius) {
                brick.alive = false;
                spawnShock(brick.x, brick.y, 1.3f);
            }
        }
        spawnShock(cx, cy, 2.2f);
    }

    private void setSpeed(Ball b, float speed) {
        float m = (float) Math.sqrt(b.vx * b.vx + b.vy * b.vy);
        if (m < 1e-5f) { b.vx = 0f; b.vy = -speed; return; }
        float k = speed / m;
        b.vx *= k; b.vy *= k;
    }

    private void hitBricks(Ball b) {
        boolean pierce = time < pierceUntil;
        int broken = 0;
        for (Brick brick : bricks) {
            if (!brick.alive) continue;
            float nx = clamp(b.x, brick.x - brick.w * .5f, brick.x + brick.w * .5f);
            float ny = clamp(b.y, brick.y - brick.h * .5f, brick.y + brick.h * .5f);
            float dx = b.x - nx, dy = b.y - ny;
            if (dx * dx + dy * dy > BALL_R * BALL_R) continue;
            destroyBrick(b, brick, pierce, dx, dy);
            // 穿透时一步最多碎 3 块，避免瞬间清空整面墙。
            if (!pierce || ++broken >= 3) break;
        }
    }

    private void destroyBrick(Ball b, Brick brick, boolean pierce, float dx, float dy) {
        brick.alive = false;
        if (!pierce) {
            if (Math.abs(dx) > Math.abs(dy)) b.vx = -b.vx;
            else b.vy = -b.vy;
        }
        applyItem(brick.type);
        spawnShock(brick.x, brick.y, 1f);
        if (time < chainUntil) {
            for (Brick other : bricks) {
                if (!other.alive) continue;
                if (Math.abs(other.col - brick.col) <= 1
                        && Math.abs(other.row - brick.row) <= 1) {
                    other.alive = false;
                    spawnShock(other.x, other.y, .7f);
                }
            }
        }
    }

    /** 道具砖被击碎时立即生效；限时类道具用绝对时间记录，到点自动失效。 */
    private void applyItem(int type) {
        switch (type) {
            case ITEM_SLOW:
                slowUntil = time + 6f;
                break;
            case ITEM_FAST:
                fastUntil = time + 6f;
                break;
            case ITEM_WIDE:
                wideUntil = Math.max(wideUntil, time + 8f);
                break;
            case ITEM_NARROW:
                narrowUntil = time + 5f;
                break;
            case ITEM_MULTI:
                split();
                break;
            default:
                break;
        }
    }

    private void spawnShock(float x, float y, float scale) {
        Shock s = shocks[shockCursor];
        shockCursor = (shockCursor + 1) % MAX_SHOCKS;
        s.active = true; s.x = x; s.y = y; s.age = 0f; s.scale = scale;
    }

    private boolean noBricksLeft() {
        for (Brick b : bricks) if (b.alive) return false;
        return true;
    }

    private float paddleTop() { return aspect - PADDLE_MARGIN; }

    private void rebuild(int next) {
        formation = next;
        for (int row = 0; row < ROWS; row++) {
            for (int col = 0; col < COLS; col++) {
                Brick b = bricks[row * COLS + col];
                b.col = col; b.row = row;
                b.band = row < ROWS / 3 ? 0 : (row < 2 * ROWS / 3 ? 1 : 2);
                int h = (col * 7 + row * 13 + formation * 5) % 23;
                b.type = h == 0 ? ITEM_MULTI
                        : (h == 1 || h == 2) ? ITEM_FAST
                        : (h == 3 || h == 4) ? ITEM_SLOW
                        : h == 5 ? ITEM_WIDE
                        : h == 6 ? ITEM_NARROW
                        : ITEM_NONE;
                b.alive = mask(formation, col, row);
                b.w = (.9f / COLS) * .88f;
                b.h = (aspect * BRICK_SPAN / ROWS) * .72f;
                layout(formation, col, row, layout);
                // 环阵把每行排成同心圆，半径越小的圈周长越短；
                // 砖宽必须按各自周长算，否则内圈 10 块砖会互相叠压。
                if (formation == F_RING) {
                    float v0 = (row + .5f) / ROWS;
                    float radiusX = .10f + v0 * .32f;
                    float arc = (float) (Math.PI * 2) * radiusX / COLS;
                    b.w = Math.min(b.w, arc * .80f);
                    b.h = Math.min(b.h, aspect * .10f * .70f);
                }
                b.targetX = layout[0]; b.targetY = layout[1];
                if (b.x == 0f && b.y == 0f) { b.x = b.targetX; b.y = b.targetY; }
            }
        }
        // 阵型切换后砖块不是瞬移：渲染器每帧把 x/y 弹向 target。
    }

    /** 渲染器每帧调用：砖块目标位置的弹簧插值。 */
    public void easeBricks(float dt) {
        float k = Math.min(1f, dt * 6f);
        for (Brick b : bricks) {
            b.x += (b.targetX - b.x) * k;
            b.y += (b.targetY - b.y) * k;
        }
    }

    /** 供测试把场景切到指定阵型（走真实的 rebuild，尺寸与位置都是生产逻辑）。 */
    void forceFormation(int f) { rebuild(f); }

    /** 供测试验证阵型掩码，不改动仿真状态。 */
    public static boolean formationMask(int f, int col, int row) {
        return mask(f, col, row);
    }

    private static boolean mask(int f, int col, int row) {
        float u = (col + .5f) / COLS;
        float v = (row + .5f) / ROWS;
        switch (f) {
            case F_DIAMOND:
                return Math.abs(u - .5f) / .5f + Math.abs(v - .5f) / .5f <= 1.05f;
            case F_ARCH: {
                float edge = Math.abs(u - .5f) * 2f;
                return v >= .15f + .45f * edge * edge;
            }
            case F_WAVE:
                return Math.abs(v - (.5f + .22f * (float) Math.sin(u * Math.PI * 3f))) <= .18f;
            default:
                return true;
        }
    }

    private void layout(int f, int col, int row, float[] out) {
        float u = (col + .5f) / COLS;
        float v = (row + .5f) / ROWS;
        float x = .05f + u * .9f;
        float y = aspect * (BRICK_TOP + v * BRICK_SPAN);
        switch (f) {
            case F_DIAMOND: {
                float taper = .55f + .45f * (1f - Math.abs(v - .5f) * 2f);
                x = .5f + (x - .5f) * taper;
                break;
            }
            case F_ARCH: {
                float nx = (x - .5f) * 2f;
                y -= aspect * .10f * (1f - nx * nx);
                break;
            }
            case F_WAVE:
                y += aspect * .045f * (float) Math.sin(u * Math.PI * 3f);
                break;
            case F_RING: {
                float angle = u * (float) (Math.PI * 2);
                float radiusY = aspect * (.10f + v * .13f);
                float radiusX = .10f + v * .32f;
                x = .5f + (float) Math.cos(angle) * radiusX;
                y = aspect * .28f + (float) Math.sin(angle) * radiusY;
                break;
            }
            default:
                break;
        }
        out[0] = clamp(x, .02f, .98f);
        out[1] = clamp(y, aspect * (BRICK_TOP - .01f), aspect * (BRICK_TOP + BRICK_SPAN + .02f));
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
