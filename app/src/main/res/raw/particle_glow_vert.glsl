#version 300 es
uniform mat4 uVP;
uniform vec3 uPos;
uniform vec2 uSize;
out vec2 vC;
void main(){
  vec2 c = vec2(float(gl_VertexID & 1), float((gl_VertexID >> 1) & 1)) * 2.0 - 1.0;
  vec4 p = uVP * vec4(uPos, 1.0);
  p.xy += c * uSize * p.w;
  vC = c;
  gl_Position = p;
}
