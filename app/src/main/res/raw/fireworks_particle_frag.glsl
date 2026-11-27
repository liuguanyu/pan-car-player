#version 300 es
precision mediump float;
uniform vec3 uColor;
in vec2 vLocal;
in float vLight;
out vec4 o;
void main(){
    float distanceToCenter = length(vLocal);
    float core = 1. - smoothstep(.10,.38,distanceToCenter);
    float halo = exp(-distanceToCenter * 5.) * (1. - smoothstep(.65,1.,distanceToCenter));
    o = vec4(mix(uColor, vec3(1.), core * .31), vLight * (core * .54 + halo * .31));
}
