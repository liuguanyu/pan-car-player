#version 300 es
precision highp float;
in vec2 vP; flat in vec4 vSeg; flat in vec4 vMeta; out vec4 o;
uniform float uCore,uGlow,uFront,uTime;
void main(){
 if(vMeta.z>uFront) discard;
 vec2 A=vSeg.xy,B=vSeg.zw,ba=B-A;
 float h=clamp(dot(vP-A,ba)/max(dot(ba,ba),1e-9),0.,1.);
 float d=length(vP-A-ba*h);
 float w=vMeta.x, I=vMeta.y;
 float flick=.82+.18*sin(uTime*53.+vMeta.z*91.+vMeta.w*7.);
 float core=1.-smoothstep(w*.35,w,d);
 float inner=exp(-d*d/(w*w*7.));
 float outer=exp(-d/(w*4.5));
 float tip=uFront<1.?smoothstep(uFront-.06,uFront,vMeta.z):0.;
 vec3 col=vec3(1.,.97,1.)*core*(uCore+tip*1.2)
         +vec3(.62,.64,1.)*inner*(uCore*.65+uGlow*.40)
         +vec3(.36,.38,.92)*outer*uGlow*.42;
 o=vec4(col*I*flick,1.);
}
