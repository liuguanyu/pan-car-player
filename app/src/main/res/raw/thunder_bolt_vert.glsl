#version 300 es
uniform highp sampler2D uSeg;
uniform int uSlot; uniform float uAspect;
out vec2 vP; flat out vec4 vSeg; flat out vec4 vMeta;
void main(){
 int id=gl_InstanceID;
 vec4 a=texelFetch(uSeg,ivec2(id*2,uSlot),0);
 vec4 m=texelFetch(uSeg,ivec2(id*2+1,uSlot),0);
 vec2 A=a.xy,B=a.zw,d=B-A; float L=length(d);
 vec2 t=L>1e-6?d/L:vec2(1.,0.); vec2 n=vec2(-t.y,t.x);
 float r=m.x*16.;
 vec2 c[6]=vec2[6](vec2(0,-1),vec2(1,-1),vec2(1,1),vec2(0,-1),vec2(1,1),vec2(0,1));
 vec2 k=c[gl_VertexID];
 vec2 P=mix(A-t*r,B+t*r,k.x)+n*r*k.y;
 vP=P; vSeg=a; vMeta=m;
 gl_Position=vec4(P.x/uAspect*2.-1.,P.y*2.-1.,0.,1.);
}
