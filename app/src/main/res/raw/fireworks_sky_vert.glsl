#version 300 es
// 全屏三角形，不需要顶点缓冲。
out vec2 vUv;
void main(){
    vec2 p = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
    // p.y: 0 在屏幕底部（NDC -1），1 在顶部；翻成"0=顶"便于按高度着色。
    vUv = vec2(p.x, 1.0 - p.y);
    gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
}
