#version 300 es
precision highp float;
in float vAlong;
in float vSide;
uniform vec2 uAlpha;
uniform vec3 uAxisColor;
uniform float uSoft;
out vec4 o;
void main(){
  float e = abs(vSide);
  float body = smoothstep(1.0, 0.8, e);
  float halo = exp(-e * e * 4.0);
  float a = mix(body, halo * 0.5, uSoft) * mix(uAlpha.x, uAlpha.y, vAlong);
  o = vec4(uAxisColor, a);
}
