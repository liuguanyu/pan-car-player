#version 300 es
uniform highp sampler2D uInst;
uniform float uAspect;
uniform float uHorizon;   // 场景坐标下的地平线 y
uniform float uFlip;      // 1 = 水面倒影
uniform float uStretch;   // 倒影竖直拉伸
out vec2 vUv;
out vec2 vWorld;
flat out float vHalfWidth;
flat out vec4 vInfo;      // xy=层/样式打包, z=seed, w=倒影强度

void main(){
    vec4 g = texelFetch(uInst, ivec2(gl_InstanceID * 2, 0), 0);
    vec4 m = texelFetch(uInst, ivec2(gl_InstanceID * 2 + 1, 0), 0);

    // 两个三角形的六个角，顺序与 glDrawArraysInstanced 的 gl_VertexID 对应。
    const vec2 CORNERS[6] = vec2[6](vec2(-1.,-1.), vec2(1.,-1.), vec2(1.,1.),
                                    vec2(-1.,-1.), vec2(1.,1.), vec2(-1.,1.));
    vec2 k = CORNERS[gl_VertexID];

    float halfW = g.z;
    float height = g.w;
    float x = g.x + k.x * (halfW + uFlip * .016);

    float yTop = uHorizon - height;
    float yBot = uHorizon;
    if (uFlip > .5) {
        yTop = uHorizon;
        yBot = uHorizon + height * uStretch;
    }
    float t = k.y * .5 + .5;          // 0 = 底部, 1 = 顶部
    float y = mix(yBot, yTop, t);

    vUv = vec2((x - g.x) / (2.0 * halfW) + .5, t);
    vWorld = vec2(x, y / uAspect);
    vHalfWidth = halfW;
    vInfo = vec4(m.x, m.y, m.z, uFlip);
    gl_Position = vec4(x * 2.0 - 1.0, 1.0 - y / uAspect * 2.0, 0.0, 1.0);
}
