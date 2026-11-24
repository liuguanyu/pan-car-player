#version 300 es
precision highp float;
in vec2 vUv;
out vec4 o;
uniform float uAspect;
uniform float uTime;
uniform float uPulse;
uniform float uRescue;
uniform vec4 uAudio;

float hash(vec2 p){ return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453); }

float noise(vec2 p){
    vec2 i = floor(p), f = fract(p);
    f = f * f * (3. - 2. * f);
    return mix(mix(hash(i), hash(i + vec2(1, 0)), f.x),
               mix(hash(i + vec2(0, 1)), hash(i + vec2(1, 1)), f.x), f.y);
}

void main(){
    vec2 p = (vUv - .5) * vec2(1., uAspect);
    float r = length(p);

    // 底色：灰蓝而不是纯黑。上深下浅，像有顶光的小房间，砖块和球在它上面才有层次。
    vec3 top = vec3(.085, .098, .125);
    vec3 bot = vec3(.130, .148, .183);
    vec3 col = mix(top, bot, smoothstep(0., 1., vUv.y));

    // 纹理一：细网格（1px 级别，靠 fwidth 抗锯齿），像背景板
    vec2 gridUv = vec2(vUv.x * 18., vUv.y * 11.);
    vec2 gd = abs(fract(gridUv) - .5) / fwidth(gridUv);
    float grid = 1. - min(min(gd.x, gd.y), 1.);
    col += vec3(.020, .026, .034) * grid * (.45 + .55 * uAudio.w);

    // 纹理二：低频云噪声，让纯色不呆板（随时间极慢漂移）
    float n1 = noise(vUv * vec2(4.2, 2.6) + vec2(uTime * .012, uTime * .006));
    float n2 = noise(vUv * vec2(11., 7.) - vec2(uTime * .02, 0.));
    col += vec3(.026, .032, .042) * (n1 - .5) * .9;
    col += vec3(.018, .022, .030) * (n2 - .5) * .6;

    // 纹理三：极淡的斜向细纹，避免大面积平涂
    float streak = sin((vUv.x * 1.6 + vUv.y) * 46. + uTime * .35) * .5 + .5;
    col += vec3(.012, .015, .020) * streak * .5;

    // 砖墙背后的柔光穹顶（冷色，衬出砖块轮廓）
    float aura = exp(-length((vUv - vec2(.5, .72)) * vec2(1.15, 1.7)) * 2.6);
    col += vec3(.030, .052, .072) * aura * (.35 + .65 * uAudio.x);

    // 大本营所在的地面光带
    float horizon = exp(-abs(vUv.y - .018) * 30.);
    col += vec3(.028, .075, .088) * horizon * (.40 + .60 * uAudio.x);

    // 音乐驱动的两道极淡波纹
    col += vec3(.020, .040, .052) * (sin(r * 22. - uTime * 1.8 + uAudio.x * 5.) * .5 + .5)
           * (.10 + .28 * uAudio.w);

    // 节拍与救球闪光
    col += vec3(.06, .10, .13) * uPulse * .10;
    col += vec3(.10, .16, .18) * uRescue * (1. - smoothstep(0., .22, abs(vUv.y - .02)));

    // 暗角：只轻微压暗，边缘仍保留灰蓝，和窗口背景过渡自然
    float vig = smoothstep(1.15, .30, length((vUv - .5) * vec2(1.10, 1.)));
    col = mix(col * .62, col, vig);

    o = vec4(col, 1.);
}
