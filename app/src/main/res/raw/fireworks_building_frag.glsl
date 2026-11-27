#version 300 es
precision highp float;
in vec2 vUv;
flat in vec4 vInfo;
uniform float uTime;
uniform float uWaterEnergy;
uniform float uWaterline;
in vec2 vWorld;
flat in float vHalfWidth;
out vec4 o;

float hash(vec2 p){
    return fract(sin(dot(p, vec2(12.9898, 78.233))) * 43758.5453);
}

void main(){
    float packed = vInfo.x;             // layer + style/4
    float seed = vInfo.y;
    float reflect = vInfo.w;
    float layer = floor(packed + .001);
    float style = floor(fract(packed) * 4.0 + .5);

    float t = vUv.y;                    // 0 底 1 顶
    float depth = max(0.0, vWorld.y - uWaterline);
    float phase = sqrt(depth + .015) * 133.0 - uTime * 3.6;
    float wave = .66 * sin(phase + sin(vWorld.x * 24.0 - uTime))
               + .34 * sin(phase * .53 - vWorld.x * 37.0 + uTime * 2.0);
    float shift = reflect * wave * (.007 + .006 * uWaterEnergy) * smoothstep(0.0, .07, depth);
    float surfaceX = vUv.x - shift / (2.0 * vHalfWidth);
    float x = surfaceX * 2.0 - 1.0;

    // 轮廓：矩形按样式裁切，形成收分塔 / 三角桁架 / 弧形屋顶
    float inside;
    if (style == 1.0) {
        float w = t < .42 ? .82 : (t < .72 ? .66 : (t < .90 ? .50 : .34));
        inside = step(abs(x), w);
    } else if (style == 2.0) {
        float body = step(t, .70);
        float roof = step(abs(x), (1.0 - t) / .30 * .95);
        inside = max(body, roof * step(.70, t));
    } else if (style == 3.0) {
        float body = step(t, .78);
        float arc = 1.0 - smoothstep(.92, 1.02, length(vec2(x, (t - .78) / .22)));
        inside = max(body, arc * step(.78, t));
    } else {
        float taper = .98 - .04 * (1.0 - t);
        inside = step(abs(x), taper);
    }
    if (inside < .5) discard;

    // 楼体本色：越近越暗（逆光剪影），越远越被雾染蓝
    // 大气透视：远处楼被城市辉光"提亮"并偏蓝，近处接近纯剪影
    vec3 near = vec3(.022, .026, .038);
    vec3 mid  = vec3(.052, .062, .090);
    vec3 far  = vec3(.098, .112, .150);
    vec3 body = layer > 1.5 ? near : (layer > .5 ? mid : far);
    body *= mix(.85, 1.15, t);          // 顶部略亮，天际线更清楚

    // 窗格：低频噪声决定"有没有人"，再加零星闪烁
    float cols = layer > 1.5 ? 7.0 : (layer > .5 ? 6.0 : 5.0);
    float rows = 12.0 + layer * 3.0;
    vec2 cell = vec2(surfaceX * cols, t * rows);
    vec2 id = floor(cell);
    vec2 f = fract(cell);
    float occupied = hash(id + vec2(seed * 91.7, seed * 13.3));
    float grid = step(.22, f.x) * step(f.x, .78) * step(.28, f.y) * step(f.y, .72);
    float lit = step(.58, occupied) * grid;
    float flicker = .88 + .12 * sin(uTime * 1.7 + occupied * 42.0);
    float brightness = layer > 1.5 ? 1.0 : (layer > .5 ? .62 : .26);
    vec3 windowColor = mix(vec3(.95, .80, .52), vec3(.72, .86, 1.0),
                           hash(id * 1.7 + seed));
    float meter = smoothstep(t - .08, t + .08, vInfo.z);
    vec3 col = body + windowColor * lit * brightness * flicker * (.40 + .60 * meter);

    if (reflect > .5) {
        // 倒影：压暗、被水面拉丝、越远离地平线越淡
        float fade = exp(-depth * 6.0) * (.26 + .13 * wave);
        o = vec4(windowColor * lit * brightness, fade * (.55 + .45 * meter));
    } else {
        o = vec4(col, 1.0);
    }
}
