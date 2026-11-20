#version 300 es
precision highp float;
in vec2 vUv;
uniform vec2 uCenter;
uniform float uGlow;
uniform float uAspect;
uniform float uTime;
out vec4 o;
void main(){
  vec2 d = (vUv - uCenter) * vec2(uAspect, 1.0);
  float r = length(d);
  vec2 q = (vUv - 0.5) * vec2(uAspect, 1.0);
  float vig = 1.0 - smoothstep(0.0, 1.15, length(q));
  vec3 edge = vec3(0.0706);
  vec3 core = vec3(0.020, 0.032, 0.062);
  vec3 col = mix(edge, core, vig * vig);
  float inner = min(min(vUv.x, 1.0 - vUv.x) * uAspect * 0.35, min(vUv.y, 1.0 - vUv.y));
  col = mix(edge, col, smoothstep(0.0, 0.55, inner));
  col += vec3(0.045, 0.060, 0.100) * uGlow * exp(-r * 2.2);
  float n = fract(sin(dot(gl_FragCoord.xy + uTime, vec2(12.9898, 78.233))) * 43758.5453);
  col += (n - 0.5) / 255.0;
  o = vec4(col, 1.0);
}
