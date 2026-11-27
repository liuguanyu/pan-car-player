#version 300 es
precision highp float;
in vec2 vUv;
uniform float uHorizon;   // 地平线在 0..1（自顶向下）中的位置
uniform float uTime;
uniform float uWaterEnergy;
uniform vec3  uGlowColor;
out vec4 o;

float hash(vec2 p){
    return fract(sin(dot(p, vec2(12.9898, 78.233))) * 43758.5453);
}

void main(){
    float h = vUv.y;              // 0 = 天顶, 1 = 水面底部
    if (h > uHorizon) {
        // 水面底色：离地平线越远越深，接住上方的城市辉光但不发暖
        float depth = clamp((h - uHorizon) / max(1.0 - uHorizon, .001), 0.0, 1.0);
        vec3 water = mix(vec3(.062, .080, .110), vec3(.012, .016, .026), depth);
        // 贴近地平线的横向细纹，让水面看起来是水而不是一块黑
        float phase = sqrt(depth + .035) * 105.0;
        float chop = sin(vUv.x * 24.0 - uTime * .85 + depth * 10.0);
        float ripple = sin(phase - uTime * 3.6 + chop * 1.4);
        float crossWave = sin(phase * .57 + vUv.x * 39.0 + uTime * 2.1);
        float crest = smoothstep(.36, .96, ripple * .7 + crossWave * .3);
        water += vec3(.029, .043, .064) * crest * (.72 + .55 * uWaterEnergy);
        o = vec4(water, 1.0);
        return;
    }

    // 自上而下：深蓝夜空 → 地平线附近被城市灯光染暖的雾
    vec3 top = vec3(.030, .048, .086);
    vec3 mid = vec3(.055, .074, .124);
    vec3 haze = uGlowColor;
    float t = clamp(h / max(uHorizon, .001), 0.0, 1.0);
    vec3 sky = mix(top, mid, smoothstep(0.0, .65, t));
    // 两层衰减：一档托住整片低空（城市辉光扩散），一档紧贴地平线（灯海核心）
    float glow = pow(t, 2.2) * .55 + pow(t, 6.0) * .40;
    sky += haze * glow;

    // 极低幅度的云带，避免天空是一块纯色
    float band = sin(vUv.x * 7.0 + uTime * .05) * .5 + .5;
    float cloud = smoothstep(.62, 1.0, band) * smoothstep(.35, .9, t) * .018;
    sky += vec3(.10, .12, .16) * cloud;

    // 紧贴地平线的一条暖亮边，是城市光污染的集中处
    float line = exp(-abs(h - uHorizon) * 55.0);
    sky += haze * line * .65;

    o = vec4(sky, 1.0);
}
