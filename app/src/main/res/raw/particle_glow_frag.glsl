#version 300 es
precision highp float;
in vec2 vC;
uniform float uAlpha;
uniform vec3 uGlowColor;
out vec4 o;
void main(){
  float d = length(vC);
  if (d > 1.0) discard;
  float core = exp(-d * d * 24.0);
  float halo = exp(-d * d * 3.6);
  float a = clamp(core + halo * 0.45, 0.0, 1.0) * uAlpha;
  o = vec4(uGlowColor, a);
}
