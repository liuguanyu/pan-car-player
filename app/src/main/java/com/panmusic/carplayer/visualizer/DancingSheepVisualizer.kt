package com.panmusic.carplayer.visualizer

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * 跳舞小羊可视化 — 参考 Go 版 pan-player-cmd/internal/visualizer/sheep.go 实现
 *
 * 音频响应映射：
 *   - 低频 bass → 身体跳动 (BodyBob) + 腿抬起 (LegLift) + 吐舌头 (TongueValue)
 *   - 高频 treble → 头部摇摆 (HeadSwing) + 睁大眼 (EyeWide)
 *   - 音量 volume → 身体缩放 (BeatPulse/SheepPulse)
 *   - 节拍 peak → 眼睑闭合 (Eyelid)
 */
class DancingSheepVisualizer : AudioVisualizer {

    override val name = "sheep"

    // === 颜色（与 Go 版 canvas.go 一致）===
    private object Colors {
        val SKY_TOP = Color.parseColor("#FF9AA4FF")      // 白天天顶
        val SKY_BOT = Color.parseColor("#FF001DFF")      // 白天地平线
        val GRASS = Color.parseColor("#FF20F020")        // 草地绿
        val FENCE = Color.parseColor("#FFFFFFFF")        // 栅栏白
        val CLOUD = Color.parseColor("#FFFFFFFF")        // 云白
        val MOON = Color.parseColor("#FFFFF36B")         // 月亮黄（Go 版白天也画在此位置）
        val MOON_GLOW = Color.parseColor("#80FFF36B")    // 月亮光晕

        val SHEEP_WOOL = Color.parseColor("#FFF3F3F3")   // 羊毛白
        val SHEEP_HEAD = Color.parseColor("#FF000000")   // 头/腿黑
        val SHEEP_EYE = Color.parseColor("#FFFFFFFF")    // 眼白
        val SHEEP_PUPIL = Color.parseColor("#FF000000")  // 瞳孔黑
        val TONGUE = Color.parseColor("#FFFF8AA8")       // 舌头粉

        val SPECT_SHELL = Color.parseColor("#FFD8D8D8")  // 飞艇外壳灰

        // 飞艇频谱柱颜色（与 Go 版 drawSpectrumLamp 一致）
        val LAMP_BAR = intArrayOf(
            Color.parseColor("#FF38F93E"),  // 绿
            Color.parseColor("#FFC8FF33"),  // 黄绿
            Color.parseColor("#FFFF9B1E"),  // 橙
            Color.parseColor("#FFFF3131")   // 红
        )
    }

    // === 画布尺寸 ===
    private var canvasWidth = 0
    private var canvasHeight = 0

    // === 音频分析值 ===
    private var bass = 0f
    private var mid = 0f
    private var treble = 0f
    private var volume = 0f
    private val analyzer = AudioAnalyzer()

    // 飞艇舷窗里的 4 根均衡器柱（bass / mid / treble / volume），逐帧平滑
    private val lampBars = FloatArray(4)

    // 云朵：RealOne 小羊场景里云是缓慢飘动的装饰层
    private data class Cloud(
        var xRatio: Float,
        val yRatio: Float,
        val rx: Float,
        val ry: Float,
        val speedRatio: Float,
        var phase: Float
    )

    private val clouds = listOf(
        Cloud(0.20f, 0.15f, 60f, 20f, 0.010f, 0f),
        Cloud(0.60f, 0.10f, 80f, 24f, 0.007f, 1.7f),
        Cloud(0.90f, 0.20f, 50f, 16f, 0.013f, 3.1f)
    )

    // === 动画状态（参考 Go 版 SceneState）===
    private var bodyBob = 0f          // 身体上下跳动
    private var bodySway = 0f         // 身体重心左右转移
    private var bodyTilt = 0f         // 身体倾斜角度
    private var bodyStretch = 0f      // 身体拉伸（节拍压缩）
    private var legPhase = 0f         // 腿部相位（连续走动）
    private var headNod = 0f          // 头部点头
    private var headSwing = 0f        // 头部左右摇摆
    private var tongueValue = 0f      // 舌头伸出程度 0~1
    private var eyeWide = 0f          // 眼睛睁大程度
    private var eyelid = 0f           // 眼睑闭合程度
    private var beatPulse = 0f        // 节拍脉冲（低频驱动）
    private var sheepPulse = 0f       // 羊脉冲（音量驱动，更平滑）

    // 红色棒球帽：偶发出现，避免每帧随机导致闪烁
    private var capVisible = false
    private var capRemainingFrames = 0
    private var capCooldownFrames = 240
    private val random = Random.Default

    // === 远景小羊（参考 Go 版 NewSceneState：3 只不同位置/大小/相位）===
    private class FarSheep(
        val xRatio: Float,
        val yRatio: Float,
        val scale: Float,
        var phase: Float,
        var hopRemainingMs: Float = 0f
    )

    private val farSheep = listOf(
        FarSheep(0.15f, 0.68f, 0.4f, 0f),
        FarSheep(0.25f, 0.70f, 0.3f, 2f),
        FarSheep(0.85f, 0.67f, 0.35f, 4f)
    )
    private var farSheepKick = 0f     // 远景羊跳跃力度（低频驱动）
    private var farHopCooldownFrames = 0

    // 帧计数
    private var frameCount = 0

    // === 绘制工具 ===
    private val paint = Paint().apply { isAntiAlias = true }

    override fun update(fft: ByteArray, width: Int, height: Int) {
        canvasWidth = width
        canvasHeight = height

        val analysis = analyzer.analyze(fft)
        if (analysis.rawSpectrum.isEmpty()) {
            // 无音频数据：使用默认值，保持动画但不跳动
            bass = 0f
            mid = 0f
            treble = 0f
            volume = 0f
            updateAnimation(0f, 0f, 0f)
            frameCount++
            return
        }

        // 使用统一的 signed FFT 解码结果，避免负数被当作 0..255 导致响应过强、混乱
        bass = analysis.bass
        mid = analysis.mid
        treble = analysis.treble
        volume = analysis.volume

        updateAnimation(bass, treble, volume)
        frameCount++
    }

    /**
     * 更新动画状态 — 参考 Go 版 updateSheepCore
     */
    private fun updateAnimation(be: Float, te: Float, av: Float) {
        val dt = 1f / 60f  // 假设 60fps

        // Beat pulse — 低频驱动，快速响应
        beatPulse += (be - beatPulse) * minOf(dt * 8, 1f)

        // Sheep pulse — 音量驱动，更平滑
        sheepPulse += (av - sheepPulse) * minOf(dt * 5, 1f)

        // Leg phase — 比原版更慢，避免主羊跳得过快；强节拍只适度加速
        val speed = 2.8f + be * 1.4f + av * 0.9f
        legPhase += dt * speed * Math.PI.toFloat() * 2

        // Tongue — 低频超过阈值时伸出
        val tongueTarget = if (be > 0.4f) be else 0f
        tongueValue += (tongueTarget - tongueValue) * minOf(dt * 12, 1f)

        // 连贯舞步：先蓄力下沉，再起跳，落地时压缩身体。
        // 使用半波包络而不是让每个部位各自独立摆动，避免“机械抖动”。
        val groove = sin(legPhase)
        val sideGroove = sin(legPhase * 0.5f)
        val jumpEnvelope = maxOf(0f, sin(legPhase))
        val landingEnvelope = maxOf(0f, -sin(legPhase))
        val stepWeight = sin(legPhase + Math.PI.toFloat() / 2f)

        // 身体重心：左右换重心，起跳时整体上移，落地时短暂压低。
        val swayTarget = sideGroove * (5f + te * 5f) + stepWeight * be * 4f
        bodySway += (swayTarget - bodySway) * minOf(dt * 9f, 1f)
        val tiltTarget = sideGroove * 0.045f + stepWeight * be * 0.035f
        bodyTilt += (tiltTarget - bodyTilt) * minOf(dt * 8f, 1f)

        val bobTarget = groove * 2.2f + jumpEnvelope * (5f + be * 10f) - landingEnvelope * be * 3.5f + av * 2f
        bodyBob += (bobTarget - bodyBob) * minOf(dt * 11f, 1f)

        // 头部略微滞后于身体，形成真实的惯性摆动。
        val headNodTarget = groove * 1.5f + jumpEnvelope * av * 3.5f + be * 1.5f
        headNod += (headNodTarget - headNod) * minOf(dt * 7f, 1f)
        val swingTarget = sideGroove * 6f + te * 12f - bodySway * 0.35f
        headSwing += (swingTarget - headSwing) * minOf(dt * 6f, 1f)

        // 身体在落地瞬间横向变宽、纵向压扁；腾空时恢复。
        val stretchTarget = landingEnvelope * (0.08f + beatPulse * 0.08f) + jumpEnvelope * 0.025f + abs(groove) * 0.02f
        bodyStretch += (stretchTarget - bodyStretch) * minOf(dt * 14f, 1f)

        // Eye wide — 高频驱动睁大眼
        val eyeTarget = te * 1.5f
        eyeWide += (eyeTarget - eyeWide) * minOf(dt * 6, 1f)

        // Eyelid — 节拍驱动闭眼
        val lidTarget = beatPulse * 1.5f
        eyelid += (lidTarget - eyelid) * minOf(dt * 8, 1f)

        updateBaseballCap()

        // === 装饰动画（参考 Go 版 updateDecorations / maybeTriggerEffects）===

        // 飞艇舷窗均衡器：分别响应低频 / 中频 / 高频 / 总音量，而不是固定 beatPulse/sheepPulse
        val lampTargets = floatArrayOf(
            maxOf(be, beatPulse),
            mid,
            te,
            maxOf(av, sheepPulse)
        )
        for (i in lampBars.indices) {
            val factor = if (lampTargets[i] > lampBars[i]) 0.45f else 0.16f
            lampBars[i] += (lampTargets[i] - lampBars[i]) * factor
        }

        // 云朵缓慢飘动。速度略受音量影响，避免完全机械匀速。
        for (cloud in clouds) {
            cloud.phase += dt * (0.4f + av)
            cloud.xRatio += cloud.speedRatio * dt * (0.6f + av * 0.8f)
            if (canvasWidth > 0 && cloud.xRatio * canvasWidth - cloud.rx > canvasWidth) {
                cloud.xRatio = -cloud.rx / canvasWidth
            }
        }

        // 远景羊：持续轻微跳步 + 低频触发 300ms 起跳
        farSheepKick += (be * 0.5f - farSheepKick) * minOf(dt * 4, 1f)
        if (farHopCooldownFrames > 0) farHopCooldownFrames--
        if (be > 0.5f && farHopCooldownFrames <= 0) {
            for ((index, fs) in farSheep.withIndex()) {
                fs.hopRemainingMs = 300f + index * 40f
            }
            farHopCooldownFrames = (4f / dt).toInt()
        }
        for (fs in farSheep) {
            fs.phase += dt * (1.5f + be * 2f + av)
            if (fs.hopRemainingMs > 0f) {
                fs.hopRemainingMs = maxOf(0f, fs.hopRemainingMs - dt * 1000f)
            }
        }
    }

    override fun draw(canvas: Canvas) {
        if (canvasWidth == 0 || canvasHeight == 0) return

        val w = canvasWidth.toFloat()
        val h = canvasHeight.toFloat()

        // === 背景 ===
        drawBackground(canvas, w, h)

        // === 主角小羊 ===
        drawMainSheep(canvas, w, h)
    }

    private fun drawBackground(canvas: Canvas, w: Float, h: Float) {
        // 天空渐变
        val skyGradient = android.graphics.LinearGradient(
            0f, 0f, 0f, h * 0.65f,
            Colors.SKY_TOP, Colors.SKY_BOT,
            Shader.TileMode.CLAMP
        )
        paint.shader = skyGradient
        canvas.drawRect(0f, 0f, w, h * 0.65f, paint)
        paint.shader = null

        // 月亮（Go 版白天也画在 0.85w, 0.12h 位置）
        val moonX = w * 0.85f
        val moonY = h * 0.12f
        // 光晕
        paint.color = Colors.MOON_GLOW
        canvas.drawCircle(moonX, moonY, 50f, paint)
        // 本体
        paint.color = Colors.MOON
        canvas.drawCircle(moonX, moonY, 30f, paint)

        // 云朵（缓慢飘动）
        for (cloud in clouds) {
            val driftY = sin(cloud.phase) * 3f
            drawCloud(canvas, cloud.xRatio * w, cloud.yRatio * h + driftY, cloud.rx, cloud.ry)
        }

        // 草地
        val grassTop = h * 0.65f
        paint.color = Colors.GRASS
        canvas.drawRect(0f, grassTop, w, h, paint)

        // 栅栏
        drawFence(canvas, w, grassTop)

        // 远景小羊（栅栏之后，参考 Go 版 DrawBackground 顺序）
        for (fs in farSheep) {
            drawFarSheep(canvas, fs, w, h)
        }

        // 飞艇（Spectrum Lamp）— 右上天空，带频谱柱
        drawSpectrumLamp(canvas, w * 0.75f, h * 0.15f, w / 640f)
    }

    /**
     * 画远景小羊 — 参考 Go 版 drawFarSheep
     * 简单椭圆身体 + 头 + 两条腿，低频时跳跃
     */
    private fun drawFarSheep(canvas: Canvas, fs: FarSheep, w: Float, h: Float) {
        // RealOne/Go 版后景羊有持续小步走动，并在低频峰值时一起跳起。
        val hopOffset = if (fs.hopRemainingMs > 0f) {
            val progress = 1f - fs.hopRemainingMs / 300f
            -sin(progress.coerceIn(0f, 1f) * Math.PI.toFloat()) * 8f * maxOf(0.35f, farSheepKick)
        } else 0f
        val idleBob = sin(fs.phase * Math.PI.toFloat()) * 1.8f

        val x = fs.xRatio * w
        val y = fs.yRatio * h + hopOffset + idleBob
        val s = fs.scale * 50
        val legH = s / 2
        val legLift = sin(fs.phase * Math.PI.toFloat() * 2f) * 3f * (0.5f + farSheepKick)

        // 身体
        paint.color = Colors.SHEEP_WOOL
        canvas.drawOval(x - s, y - s / 2, x + s, y + s / 2, paint)
        // 头
        paint.color = Colors.SHEEP_HEAD
        canvas.drawOval(x - s - s / 2, y - s / 4 - s / 3, x - s + s / 2, y - s / 4 + s / 3, paint)
        // 腿：两条腿错相位，避免背景羊像静态图标
        paint.strokeWidth = 2f
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        canvas.drawLine(x - s / 4, y + s / 2, x - s / 4, y + s / 2 + legH - maxOf(0f, legLift), paint)
        canvas.drawLine(x + s / 4, y + s / 2, x + s / 4, y + s / 2 + legH - maxOf(0f, -legLift), paint)
        paint.style = Paint.Style.FILL
    }

    /**
     * 画飞艇（Spectrum Lamp）— 参考 Go 版 drawSpectrumLamp
     * 外壳 + 舷窗 + 尾鳍 + 吊舱，舷窗内 4 根频谱柱随节拍/音量起伏
     */
    private fun drawSpectrumLamp(canvas: Canvas, x: Float, y: Float, scale: Float) {
        val shellW = 36f * scale
        val shellH = 22f * scale

        // 尾鳍（在外壳下层）
        paint.color = Colors.SPECT_SHELL
        canvas.drawRect(x - 5 * scale, y + 6 * scale, x + scale, y + 14 * scale, paint)

        // 外壳
        canvas.drawOval(x, y, x + shellW, y + shellH, paint)

        // 舷窗（黑色，频谱柱画在里面）
        paint.color = Colors.SHEEP_HEAD
        canvas.drawRect(x + 8 * scale, y + 5 * scale, x + 26 * scale, y + 17 * scale, paint)

        // 吊舱
        paint.color = Colors.SPECT_SHELL
        canvas.drawRect(x + 11 * scale, y + shellH, x + 19 * scale, y + shellH + 4 * scale, paint)

        // 频谱柱：bass / mid / treble / volume，跟随真实音频数据平滑起伏
        val bars = lampBars
        for (i in bars.indices) {
            val bh = maxOf(3f * scale, bars[i] * 10 * scale)
            paint.color = Colors.LAMP_BAR[i]
            canvas.drawRect(
                x + (10 + i * 4) * scale, y + 16 * scale - bh,
                x + (13 + i * 4) * scale, y + 16 * scale,
                paint
            )
        }
    }

    private fun drawCloud(canvas: Canvas, cx: Float, cy: Float, rx: Float, ry: Float) {
        paint.color = Colors.CLOUD
        canvas.drawOval(cx - rx, cy - ry, cx + rx, cy + ry, paint)
        canvas.drawOval(cx - rx * 0.5f, cy - ry * 0.6f - 5, cx + rx * 0.5f, cy + ry * 0.4f, paint)
        canvas.drawOval(cx - rx * 0.7f, cy - ry * 0.3f, cx + rx * 0.3f, cy + ry * 0.5f, paint)
    }

    private fun drawFence(canvas: Canvas, w: Float, grassTop: Float) {
        paint.color = Colors.FENCE
        val fenceY = grassTop + 2
        val postH = 20f
        val postW = 3f
        val railH = 3f

        // 栅栏柱
        var x = 0f
        while (x < w) {
            canvas.drawRect(x, fenceY, x + postW, fenceY + postH, paint)
            x += 30f
        }
        // 上栏杆
        canvas.drawRect(0f, fenceY + 4, w, fenceY + 4 + railH, paint)
        // 下栏杆
        canvas.drawRect(0f, fenceY + 14, w, fenceY + 14 + railH, paint)
    }

    /**
     * 画主角小羊 — 参考 Go 版 DrawMainSheep
     */
    private fun drawMainSheep(canvas: Canvas, w: Float, h: Float) {
        // 中心位置（参考 Go 版：centerX = w * 0.44, centerY = h * 0.57 + bodyBob）
        val centerX = w * 0.44f
        val centerY = h * 0.57f + bodyBob

        // 聚光灯（在羊之前画，作为地面阴影）
        drawSpotlight(canvas, centerX + w * 0.03f, h * 0.74f, w / 640f)

        // 身体、腿和头属于同一个舞步，整体随重心轻微倾斜。
        val danceCenterX = centerX + bodySway
        canvas.save()
        canvas.rotate(bodyTilt * 57.29578f, danceCenterX, centerY)

        // 腿（在身体之前画，身体覆盖腿的上半部分）。前后腿反相抬起。
        val step = sin(legPhase)
        val jump = maxOf(0f, step)
        val legLift = step * (2f + bass * 5f) + jump * 2f
        val stride = sin(legPhase * 0.5f) * (2f + treble * 3f)
        val lw = 4f * (w / 640f)  // 腿宽

        paint.color = Colors.SHEEP_HEAD
        paint.strokeWidth = lw
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND

        // 4 条腿，有抬起动画
        drawLeg(canvas, danceCenterX - 18 + stride, centerY + 20, legLift)
        drawLeg(canvas, danceCenterX - 4 - stride * 0.4f, centerY + 20, -legLift)
        drawLeg(canvas, danceCenterX + 18 + stride * 0.25f, centerY + 22, -legLift * 0.6f)
        drawLeg(canvas, danceCenterX + 34 - stride, centerY + 22, legLift * 0.6f)

        paint.style = Paint.Style.FILL

        // 羊毛身体（16 个重叠圆组成蓬松效果）
        val bodyScaleX = 1f + bodyStretch
        val bodyScaleY = 1f - bodyStretch * 0.45f
        drawWoolBody(canvas, danceCenterX, centerY, bodyScaleX, bodyScaleY)

        // 头部跟随身体位置，但保留滞后摆动。
        val headX = danceCenterX - 56 + headSwing * 0.8f
        val headY = centerY - 6 + headNod * 0.8f
        drawSheepHead(canvas, headX, headY)
        if (capVisible) drawBaseballCap(canvas, headX, headY)
        canvas.restore()
    }

    /** 更新棒球帽的随机出现、持续时间和冷却。 */
    private fun updateBaseballCap() {
        if (capRemainingFrames > 0) {
            capRemainingFrames--
            if (capRemainingFrames == 0) {
                capVisible = false
                capCooldownFrames = 360 // 消失后至少冷却 6 秒
            }
            return
        }
        if (capCooldownFrames > 0) {
            capCooldownFrames--
            return
        }
        // 每帧低概率尝试，平均不会频繁出现；帽子持续约 4 秒。
        if (random.nextFloat() < 0.0035f) {
            capVisible = true
            capRemainingFrames = 240
        }
    }

    /**
     * 画红色棒球帽，帽檐朝左。
     * 头部坐标已包含主羊的摆动，因此帽子会自然跟随头部。
     */
    private fun drawBaseballCap(canvas: Canvas, cx: Float, cy: Float) {
        val red = Color.parseColor("#FFD71920")
        val darkRed = Color.parseColor("#FF9E1018")

        // 帽冠：覆盖头顶，略向右偏，留出左侧帽檐空间。
        paint.color = red
        canvas.drawOval(cx - 16f, cy - 34f, cx + 19f, cy - 7f, paint)
        canvas.drawRect(cx - 7f, cy - 30f, cx + 19f, cy - 14f, paint)

        // 帽檐朝左，使用略弯的路径而不是矩形。
        val brim = Path().apply {
            moveTo(cx - 9f, cy - 16f)
            cubicTo(cx - 22f, cy - 19f, cx - 35f, cy - 16f, cx - 43f, cy - 11f)
            cubicTo(cx - 30f, cy - 9f, cx - 17f, cy - 10f, cx - 6f, cy - 13f)
            close()
        }
        paint.color = darkRed
        canvas.drawPath(brim, paint)

        // 帽冠高光，增加塑料/布料立体感。
        paint.color = Color.argb(90, 255, 255, 255)
        canvas.drawOval(cx - 8f, cy - 30f, cx + 7f, cy - 24f, paint)
    }

    /**
     * 画腿 — 参考 Go 版 drawLeg
     */
    private fun drawLeg(canvas: Canvas, baseX: Float, baseY: Float, lift: Float) {
        val endY = baseY + 48 - maxOf(0f, lift)
        canvas.drawLine(baseX, baseY, baseX, endY, paint)
    }

    /**
     * 画羊毛身体 — 参考 Go 版 drawWoolBody
     * 16 个重叠圆组成蓬松效果
     */
    private fun drawWoolBody(canvas: Canvas, cx: Float, cy: Float, scaleX: Float, scaleY: Float) {
        paint.color = Colors.SHEEP_WOOL

        val nPuffs = 16
        val rx = 48f * scaleX
        val ry = 30f * scaleY
        val puffR = 10f * (scaleX + scaleY) / 2f

        // 外围 16 个圆
        for (i in 0 until nPuffs) {
            val angle = i.toFloat() / nPuffs * Math.PI.toFloat() * 2
            val px = cx + cos(angle) * rx
            val py = cy + sin(angle) * ry
            canvas.drawCircle(px, py, maxOf(1f, puffR), paint)
        }

        // 中心填充椭圆，避免空隙
        canvas.drawOval(cx - rx - 2, cy - ry - 2, cx + rx + 2, cy + ry + 2, paint)
    }

    /**
     * 画羊头 — 参考 Go 版 drawSheepHead
     */
    private fun drawSheepHead(canvas: Canvas, cx: Float, cy: Float) {
        // 头部本体（黑色椭圆）
        paint.color = Colors.SHEEP_HEAD
        canvas.drawOval(cx - 24, cy - 18, cx + 24, cy + 18, paint)

        // 耳朵（三角形）
        val earPath = Path()
        // 左耳
        earPath.moveTo(cx - 18, cy - 2)
        earPath.lineTo(cx - 44, cy + 4)
        earPath.lineTo(cx - 16, cy + 6)
        earPath.close()
        canvas.drawPath(earPath, paint)
        // 右耳
        earPath.reset()
        earPath.moveTo(cx + 18, cy - 2)
        earPath.lineTo(cx + 38, cy + 18)
        earPath.lineTo(cx + 14, cy + 12)
        earPath.close()
        canvas.drawPath(earPath, paint)

        // 眼睛（矩形，不是圆形）
        val eyeW = 8f
        val eyeH = 8f + eyeWide * 2

        paint.color = Colors.SHEEP_EYE
        // 左眼
        canvas.drawRect(cx - 12, cy - 8, cx - 12 + eyeW, cy - 8 + eyeH, paint)
        // 右眼
        canvas.drawRect(cx + 4, cy - 8, cx + 4 + eyeW, cy - 8 + eyeH, paint)

        // 瞳孔
        val pupilY = cy - 3
        paint.color = Colors.SHEEP_PUPIL
        canvas.drawRect(cx - 9, pupilY, cx - 7, pupilY + 2, paint)
        canvas.drawRect(cx + 7, pupilY, cx + 9, pupilY + 2, paint)

        // 眼睑线
        val lidOffset = eyelid * 4
        val lidY = cy - 9 + lidOffset
        paint.strokeWidth = 1f
        paint.style = Paint.Style.STROKE
        canvas.drawLine(cx - 14, lidY, cx - 2, lidY, paint)
        canvas.drawLine(cx + 2, lidY, cx + 14, lidY, paint)
        paint.style = Paint.Style.FILL

        // 舌头（节拍触发）
        if (tongueValue > 0.05f) {
            val tongueLen = tongueValue * 13
            val tongueX = cx - 2
            val tongueY = cy + 12 + tongueValue * 5

            paint.color = Colors.TONGUE
            canvas.drawOval(
                tongueX - 5, tongueY - 5 - tongueLen,
                tongueX + 5, tongueY + 5 + tongueLen,
                paint
            )
        }
    }

    /**
     * 画聚光灯 — 参考 Go 版 drawSpotlight
     */
    private fun drawSpotlight(canvas: Canvas, cx: Float, cy: Float, scale: Float) {
        val alpha = 0.55f + sheepPulse * 0.37f  // 0.55 ~ 0.92
        val rx = 90f * scale
        val ry = 32f * scale

        // 3 层递减透明度椭圆，模拟软阴影
        for (i in 0 until 3) {
            val offset = i.toFloat()
            val layerAlpha = (alpha * (1f - i * 0.3f) * 255).toInt().coerceIn(0, 255)
            paint.color = Color.argb(layerAlpha, 255, 250, 205)  // 淡黄
            canvas.drawOval(
                cx - rx + offset, cy - ry + offset,
                cx + rx - offset, cy + ry - offset,
                paint
            )
        }
    }

    override fun reset() {
        bass = 0f
        mid = 0f
        treble = 0f
        volume = 0f
        bodyBob = 0f
        bodySway = 0f
        bodyTilt = 0f
        bodyStretch = 0f
        legPhase = 0f
        headNod = 0f
        headSwing = 0f
        tongueValue = 0f
        eyeWide = 0f
        eyelid = 0f
        beatPulse = 0f
        sheepPulse = 0f
        capVisible = false
        capRemainingFrames = 0
        capCooldownFrames = 240
        lampBars.fill(0f)
        farSheepKick = 0f
        farHopCooldownFrames = 0
        farSheep.forEach { it.hopRemainingMs = 0f }
        frameCount = 0
    }
}
