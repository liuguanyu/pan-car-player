#version 300 es
uniform highp sampler2D uInst;
uniform float uAspect;
out vec2 vP;
flat out vec4 vMeta;

// 精灵四边形放大到形状的 2.4 倍，给 SDF 外发光留出采样空间；
// 形状本身仍在 ±1 的局部坐标内，所以可见尺寸不变。
const float SPREAD = 1.75;

void main(){
    vec4 g=texelFetch(uInst,ivec2(gl_InstanceID*2,0),0);
    vec4 m=texelFetch(uInst,ivec2(gl_InstanceID*2+1,0),0);
    vec2 c[6]=vec2[6](vec2(-1.,-1.),vec2(1.,-1.),vec2(1.,1.),
                      vec2(-1.,-1.),vec2(1.,1.),vec2(-1.,1.));
    vec2 k=c[gl_VertexID];
    vP=k*SPREAD;
    vMeta=m;
    vec2 world=g.xy+k*g.zw*SPREAD;
    gl_Position=vec4(world.x*2.-1.,1.-world.y/uAspect*2.,0.,1.);
}
