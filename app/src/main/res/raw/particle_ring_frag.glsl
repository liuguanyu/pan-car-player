#version 300 es
precision highp float;
uniform vec3 uRingColor;
in float vAlpha;
in float vSoft;
out vec4 o;
void main(){
  vec2 c = gl_PointCoord - 0.5;
  float d = length(c) * 2.0;
  float core = smoothstep(1.0, 0.5, d);
  float halo = exp(-d * d * 3.2);
  float a = mix(core, halo * 0.8, vSoft) * vAlpha;
  if (a < 0.004) discard;
  o = vec4(uRingColor, a);
}
