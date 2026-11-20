#version 300 es
uniform mat4 uVP;
uniform vec3 uA;
uniform vec3 uB;
uniform vec2 uHalfW;
uniform float uCurve;
uniform float uCurvePhase;
uniform vec3 uCam;
out float vAlong;
out float vSide;
void main(){
  float id = float(gl_VertexID);
  float e = floor(id / 2.0) / 31.0;
  float s = mod(id, 2.0) * 2.0 - 1.0;
  float x = mix(uA.x, uB.x, e);
  vec3 P = vec3(x, uCurve * sin(x * 0.72 + uCurvePhase) + 0.16 * sin(x * 1.43 - uCurvePhase * 1.7), uCurve * 0.44 * cos(x * 0.55 + uCurvePhase * 0.8));
  vec3 tangent = normalize(vec3(1.0, uCurve * 0.72 * cos(x * 0.72 + uCurvePhase) + 0.16 * 1.43 * cos(x * 1.43 - uCurvePhase * 1.7), -uCurve * 0.44 * 0.55 * sin(x * 0.55 + uCurvePhase * 0.8)));
  vec3 side = normalize(cross(tangent, normalize(uCam - P)));
  P += side * s * mix(uHalfW.x, uHalfW.y, e);
  vAlong = e;
  vSide = s;
  gl_Position = uVP * vec4(P, 1.0);
}
