#version 300 es
precision highp float;
in float vT;
in float vY;
in float vFace;
in float vWY;
in float vDepth;
out vec4 o;
uniform float uKick;        // 鼓点脉冲 0..1.2
uniform float uMirror;      // 1 = 倒影
uniform float uMirrorAlpha; // 倒影单遍透明度（多遍叠加成模糊）
uniform float uHeartbeat;   // 帧内伪随机，给倒影加一点抖动

// 绿→青→蓝：左（低频）绿，右（高频）蓝，保留柱形模式的既有辨识度
vec3 palette(float t){
    vec3 g=vec3(.05,1.,.15), c=vec3(.0,.85,1.), b=vec3(.16,.47,1.);
    return t<.5?mix(g,c,t*2.):mix(c,b,t*2.-1.);
}

void main(){
    vec3 base=palette(vT);
    vec3 col;
    if(vFace<.5){
        // 正面：下暗上亮 + 顶部一点暖白辉光
        col=base*(.55+.55*vY);
        col+=vec3(1.,1.,.85)*exp(-(1.-vY)*14.)*.5;
    } else if(vFace<1.5){
        // 顶面：受光面，最亮
        col=base*1.35+vec3(.22,.30,.35);
    } else {
        // 侧面：背光，压暗
        col=base*.40;
    }
    col*=1.+.30*uKick;

    float a=1.;
    if(uMirror>.5){
        // 倒影：随高度指数淡出 + 轻微噪点，避免出现"第二排实柱"的整齐感
        float fade=exp(-1.9*vWY);
        float noise=fract(sin(dot(vec2(vT*97.3,vWY*51.7+uHeartbeat),vec2(12.9898,78.233)))*43758.5453);
        col*=.85*fade*(.80+.40*noise);
        a=uMirrorAlpha;
    } else {
        // 近处柱略亮，强化纵深
        col*=mix(.82,1.,smoothstep(3.,1.,abs(vDepth)));
    }
    o=vec4(col,a);
}
