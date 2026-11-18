package com.baidu.carplayer.visualizer;

/**
 * 粒子可视化的 GLSL ES 3.00 源码（Android 10 车机 GLES 3.0）。
 *
 * <p>全部几何都由着色器生成：不需要顶点缓冲，粒子用 {@code gl_VertexID}（圆周序号）+
 * {@code gl_InstanceID}（环槽位）实例化绘制，几万个点的 3D 变换 / 透视 / 轮廓谐波都在 GPU 上完成，
 * CPU 每帧只上传一个矩阵和每环的 3 个 vec4。
 *
 * <p>环轮廓 f(θ)=1+Σ A_h·cos(hθ+φ_h)，快照幅度 + 实时频谱幅度之上被钳制在 0.4 以上，
 * 保证曲线始终闭合且不自交。
 */
final class ParticleShaders {
    private ParticleShaders() { }

    /** 每环的圆周点数。 */
    static final int DOTS = 240;

    private static final String HEAD = "#version 300 es\n";
    private static final String FRAG_HEAD = "#version 300 es\nprecision highp float;\n";

    // ---------------- 背景：渐变 + 空间感 ----------------

    static final String BG_VERT = HEAD
          + "out vec2 vUv;\n"
          + "void main(){\n"
          + "  vec2 p = vec2(float(gl_VertexID & 1), float((gl_VertexID >> 1) & 1)) * 2.0 - 1.0;\n"
          + "  vUv = p * 0.5 + 0.5;\n"
          + "  gl_Position = vec4(p, 0.0, 1.0);\n"
          + "}\n";

    static final String BG_FRAG = FRAG_HEAD
          + "in vec2 vUv;\n"
          + "uniform vec2 uCenter;\n"
          + "uniform float uGlow;\n"
          + "uniform float uAspect;\n"
          + "uniform float uTime;\n"
          + "out vec4 o;\n"
          + "void main(){\n"
          + "  vec2 d = (vUv - uCenter) * vec2(uAspect, 1.0);\n"
          + "  float r = length(d);\n"
          // 边缘颜色 = 窗口背景 #121212（18/255），向中心渐变出一点冷蓝的空间感
          + "  vec2 q = (vUv - 0.5) * vec2(uAspect, 1.0);\n"
          + "  float vig = 1.0 - smoothstep(0.0, 1.15, length(q));\n"
          + "  vec3 edge = vec3(0.0706);\n"
          + "  vec3 core = vec3(0.020, 0.032, 0.062);\n"
          + "  vec3 col = mix(edge, core, vig * vig);\n"
          + "  float inner = min(min(vUv.x, 1.0 - vUv.x) * uAspect * 0.35, min(vUv.y, 1.0 - vUv.y));\n"
          + "  col = mix(edge, col, smoothstep(0.0, 0.55, inner));\n"
          + "  col += vec3(0.045, 0.060, 0.100) * uGlow * exp(-r * 2.2);\n"
          + "  float n = fract(sin(dot(gl_FragCoord.xy + uTime, vec2(12.9898, 78.233))) * 43758.5453);\n"
          + "  col += (n - 0.5) / 255.0;\n"
          + "  o = vec4(col, 1.0);\n"
          + "}\n";

    // ---------------- 环粒子 ----------------

    static final String RING_VERT = HEAD
          + "uniform mat4 uVP;\n"
          + "uniform vec4 uRingA[" + ParticleScene.MAX_RINGS + "];\n"   // birthX, age, r0, seed
          + "uniform vec4 uRingH0[" + ParticleScene.MAX_RINGS + "];\n"  // 快照 h2..h5
          + "uniform vec4 uRingH1[" + ParticleScene.MAX_RINGS + "];\n"  // 快照 h6..h8, 出生响度
          + "uniform vec4 uLive0;\n"
          + "uniform vec4 uLive1;\n"
          + "uniform float uTime;\n"
          + "uniform float uPulse;\n"
          + "uniform float uLevel;\n"
          + "uniform float uHeadX;\n"
          + "uniform float uHeadVis;\n"
          + "uniform float uPx;\n"
          + "uniform float uLife;\n"
          + "uniform float uEnergy;\n"
          + "uniform vec3 uRingColor;\n"
          + "uniform float uCurve;\n"
          + "uniform float uCurvePhase;\n"
          + "out float vAlpha;\n"
          + "out float vSoft;\n"
          + "float hash(float n){ return fract(sin(n) * 43758.5453123); }\n"
          + "void main(){\n"
          + "  int slot = gl_InstanceID;\n"
          + "  vec4 A = uRingA[slot];\n"
          + "  if (A.y < 0.0) { gl_Position = vec4(2.0, 2.0, 2.0, 1.0); gl_PointSize = 0.0; vAlpha = 0.0; vSoft = 0.0; return; }\n"
          + "  vec4 H0 = uRingH0[slot];\n"
          + "  vec4 H1 = uRingH1[slot];\n"
          + "  float age = A.y;\n"
          + "  float t = age / uLife;\n"
          + "  float id = float(gl_VertexID);\n"
          + "  float rnd = hash(id * 1.37 + A.w * 91.7);\n"
          + "  float rnd2 = hash(id * 3.11 + A.w * 17.3 + 5.0);\n"
          + "  float theta = (id + (rnd2 - 0.5) * 0.9) / " + DOTS + ".0 * 6.2831853;\n"
          // 半径：出生时很小，向外扩张（先快后慢）→ 彗星身后形成锥形尾迹
          + "  float grow = 1.0 - exp(-age * 0.34);\n"
          + "  float R = A.z * (0.30 + 2.4 * grow) * (1.0 + 0.45 * uPulse * exp(-age * 1.3));\n"
          // 彗星再次穿过该环：点亮并外弹（dh<0：彗头已越过，缓慢回落）
          + "  float dh = uHeadX - A.x;\n"
          + "  float near = exp(-dh * dh * 5.0);\n"
          + "  float after = dh < 0.0 ? exp(dh * 0.9) : 0.0;\n"
          + "  float flare = uHeadVis * clamp(near + after * 0.8, 0.0, 1.0) * smoothstep(0.6, 2.5, age);\n"
          + "  R *= 1.0 + 0.22 * flare;\n"
          // 轮廓：快照 + 实时；每阶相位漂移成行波；刚出生时轮廓平滑
          + "  float sd = A.w * 6.2831853;\n"
          + "  float k = 0.35 + 0.65 * smoothstep(0.0, 1.2, age);\n"
          + "  float live = (1.0 + 0.9 * uPulse) * (0.4 + 0.6 * smoothstep(0.0, 2.0, age));\n"
          + "  vec4 a0 = (H0 * k + uLive0 * live) * (1.0 + 0.8 * flare);\n"
          + "  vec4 a1 = (H1 * k + uLive1 * live) * (1.0 + 0.8 * flare);\n"
          + "  float w = uTime * 0.55;\n"
          + "  float rot = sd + age * (0.10 + 0.12 * A.w);\n"
          + "  float th = theta + rot;\n"
          + "  float f = 1.0;\n"
          + "  f += a0.x * cos(2.0 * th + sd + w);\n"
          + "  f += a0.y * cos(3.0 * th + sd * 1.3 - w * 1.2);\n"
          + "  f += a0.z * cos(4.0 * th + sd * 1.7 + w * 1.5);\n"
          + "  f += a0.w * cos(5.0 * th + sd * 2.1 - w * 1.8);\n"
          + "  f += a1.x * cos(6.0 * th + sd * 2.5 + w * 2.1);\n"
          + "  f += a1.y * cos(7.0 * th + sd * 2.9 - w * 2.4);\n"
          + "  f += a1.z * cos(8.0 * th + sd * 3.3 + w * 2.7);\n"
          + "  f = max(f, 0.4);\n"                          // 闭合且不自交的硬保证
          // 点级颗粒感：径向随机位移，随响度和年龄加大
          + "  float grain = (rnd - 0.5) * (0.06 + 0.14 * uLevel + 0.12 * t);\n"
          + "  float rr = R * (f + grain);\n"
          + "  float px = A.x + (rnd2 - 0.5) * 0.04;\n"
          + "  float py = uCurve * sin(px * 0.72 + uCurvePhase) + 0.16 * sin(px * 1.43 - uCurvePhase * 1.7);\n"
          + "  float pz = uCurve * 0.44 * cos(px * 0.55 + uCurvePhase * 0.8);\n"
          + "  vec3 tangent = normalize(vec3(1.0, uCurve * 0.72 * cos(px * 0.72 + uCurvePhase) + 0.16 * 1.43 * cos(px * 1.43 - uCurvePhase * 1.7), -uCurve * 0.44 * 0.55 * sin(px * 0.55 + uCurvePhase * 0.8)));\n"
          + "  vec3 sideA = normalize(cross(tangent, vec3(0.0, 1.0, 0.0)));\n"
          + "  vec3 sideB = normalize(cross(tangent, sideA));\n"
          + "  vec3 P = vec3(px, py, pz) + sideA * (rr * sin(th)) + sideB * (rr * cos(th));\n"
          + "  gl_Position = uVP * vec4(P, 1.0);\n"
          // 亮度：新环亮、老环暗；穿过时点亮；每点亮度不一
          + "  float fadeIn = smoothstep(0.0, 0.25, age);\n"
          + "  float fadeOut = 1.0 - smoothstep(0.55, 1.0, t);\n"
          + "  float base = 0.32 + 0.50 * H1.w;\n"
          + "  vAlpha = clamp((base * fadeIn * fadeOut + 0.65 * flare) * (0.5 + 0.5 * rnd) * uEnergy, 0.0, 1.0);\n"
          // 点大小：近大远小，靠近相机的点柔化成光斑
          + "  float d = max(gl_Position.w, 0.2);\n"
          + "  float size = (1.1 + 1.9 * rnd2) * uPx * (5.0 / d);\n"
          + "  size *= 1.0 + 0.9 * flare;\n"
          + "  gl_PointSize = clamp(size, 1.0, 28.0 * uPx);\n"
          + "  vSoft = smoothstep(2.4, 6.0, gl_PointSize / uPx);\n"
          + "}\n";

    static final String RING_FRAG = FRAG_HEAD
          + "uniform vec3 uRingColor;\n"
          + "in float vAlpha;\n"
          + "in float vSoft;\n"
          + "out vec4 o;\n"
          + "void main(){\n"
          + "  vec2 c = gl_PointCoord - 0.5;\n"
          + "  float d = length(c) * 2.0;\n"
          + "  float core = smoothstep(1.0, 0.5, d);\n"
          + "  float halo = exp(-d * d * 3.2);\n"
          + "  float a = mix(core, halo * 0.8, vSoft) * vAlpha;\n"
          + "  if (a < 0.004) discard;\n"
          + "  o = vec4(uRingColor, a);\n"
          + "}\n";

    // ---------------- 光轴 / 彗尾：广告牌条带，两端可各自设宽度与透明度 ----------------

    static final String AXIS_VERT = HEAD
          + "uniform mat4 uVP;\n"
          + "uniform vec3 uA;\n"
          + "uniform vec3 uB;\n"
          + "uniform vec2 uHalfW;\n"
          + "uniform float uCurve;\n"
          + "uniform float uCurvePhase;\n"        // A 端 / B 端的世界半宽
          + "uniform vec3 uCam;\n"
          + "out float vAlong;\n"
          + "out float vSide;\n"
          + "void main(){\n"
          + "  float id = float(gl_VertexID);\n"
          + "  float e = floor(id / 2.0) / 31.0;\n"       // 32 段曲线条带
          + "  float s = mod(id, 2.0) * 2.0 - 1.0;\n"
          + "  float x = mix(uA.x, uB.x, e);\n"
          + "  vec3 P = vec3(x, uCurve * sin(x * 0.72 + uCurvePhase) + 0.16 * sin(x * 1.43 - uCurvePhase * 1.7), uCurve * 0.44 * cos(x * 0.55 + uCurvePhase * 0.8));\n"
          + "  vec3 tangent = normalize(vec3(1.0, uCurve * 0.72 * cos(x * 0.72 + uCurvePhase) + 0.16 * 1.43 * cos(x * 1.43 - uCurvePhase * 1.7), -uCurve * 0.44 * 0.55 * sin(x * 0.55 + uCurvePhase * 0.8)));\n"
          + "  vec3 side = normalize(cross(tangent, normalize(uCam - P)));\n"
          + "  P += side * s * mix(uHalfW.x, uHalfW.y, e);\n"
          + "  vAlong = e;\n"
          + "  vSide = s;\n"
          + "  gl_Position = uVP * vec4(P, 1.0);\n"
          + "}\n";

    static final String AXIS_FRAG = FRAG_HEAD
          + "in float vAlong;\n"
          + "in float vSide;\n"
          + "uniform vec2 uAlpha;\n"        // A 端 / B 端的透明度
          + "uniform vec3 uAxisColor;\n"
          + "uniform float uSoft;\n"
          + "out vec4 o;\n"
          + "void main(){\n"
          + "  float e = abs(vSide);\n"
          + "  float body = smoothstep(1.0, 0.8, e);\n"
          + "  float halo = exp(-e * e * 4.0);\n"
          + "  float a = mix(body, halo * 0.5, uSoft) * mix(uAlpha.x, uAlpha.y, vAlong);\n"
          + "  o = vec4(uAxisColor, a);\n"
          + "}\n";

    // ---------------- 彗头泛光（屏幕空间广告牌） ----------------

    static final String GLOW_VERT = HEAD
          + "uniform mat4 uVP;\n"
          + "uniform vec3 uPos;\n"
          + "uniform vec2 uSize;\n"         // ndc 半尺寸（含宽高比修正）
          + "out vec2 vC;\n"
          + "void main(){\n"
          + "  vec2 c = vec2(float(gl_VertexID & 1), float((gl_VertexID >> 1) & 1)) * 2.0 - 1.0;\n"
          + "  vec4 p = uVP * vec4(uPos, 1.0);\n"
          + "  p.xy += c * uSize * p.w;\n"
          + "  vC = c;\n"
          + "  gl_Position = p;\n"
          + "}\n";

    static final String GLOW_FRAG = FRAG_HEAD
          + "in vec2 vC;\n"
          + "uniform float uAlpha;\n"
          + "uniform vec3 uGlowColor;\n"
          + "out vec4 o;\n"
          + "void main(){\n"
          + "  float d = length(vC);\n"
          + "  if (d > 1.0) discard;\n"
          + "  float core = exp(-d * d * 24.0);\n"
          + "  float halo = exp(-d * d * 3.6);\n"
          + "  float a = clamp(core + halo * 0.45, 0.0, 1.0) * uAlpha;\n"
          + "  o = vec4(uGlowColor, a);\n"
          + "}\n";
}
