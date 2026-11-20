#version 300 es
precision highp float;
in vec2 vUv;
out vec4 o;
uniform float uAspect;
uniform float uHorizon;   // 地面基线的屏幕 uv 高度，由 CPU 按投影反解

// 背景：地面基线以上是接近 #121212 的夜空（带一点冷光），以下是地面，
// 靠近基线略有光泽、向下淡回背景色。亮线与柱脚严格重合。
void main(){
    vec3 edge=vec3(.0706);
    float below=smoothstep(uHorizon+.30,uHorizon,vUv.y);   // 1 = 基线附近及以下
    vec3 floorCol=mix(edge,vec3(.105,.115,.165),below);
    float above=smoothstep(uHorizon,uHorizon+.18,vUv.y);   // 1 = 远离基线向上
    vec3 skyCol=mix(vec3(.090,.100,.145),edge,above);
    vec3 col=vUv.y<uHorizon?floorCol:skyCol;
    // 柱排基线：一条极细的亮分界，与柱脚重合
    col+=vec3(.10,.12,.18)*(1.-smoothstep(0.,.006,abs(vUv.y-uHorizon)));
    // 边缘压回 #121212，与窗口背景无缝
    float vig=smoothstep(1.25,.30,length((vUv-.5)*vec2(uAspect*.55,1.)));
    col=mix(edge,col,vig);
    o=vec4(col,1.);
}
