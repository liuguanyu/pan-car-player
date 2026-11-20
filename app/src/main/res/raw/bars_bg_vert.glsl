#version 300 es
out vec2 vUv;
void main(){
    vec2 p[3]=vec2[3](vec2(-1.,-1.),vec2(3.,-1.),vec2(-1.,3.));
    vUv=p[gl_VertexID]*.5+.5;
    gl_Position=vec4(p[gl_VertexID],0.,1.);
}
