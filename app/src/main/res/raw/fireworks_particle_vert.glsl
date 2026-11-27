#version 300 es
uniform vec4 uBurst;  // x, apex y, radius, age since launch
uniform vec4 uDetails; // seed, type, duration, rise time
uniform vec4 uView; // aspect, shoreline, reflection pass, reflection gain
out vec2 vLocal;
out float vLight;

float random(float id){
    return fract(sin(id * 73.135 + uDetails.x * 391.17) * 43758.5453);
}

void main(){
    const vec2 corners[6] = vec2[6](vec2(-1.,-1.), vec2(1.,-1.), vec2(1.,1.),
                                     vec2(-1.,-1.), vec2(1.,1.), vec2(-1.,1.));
    vLocal = corners[gl_VertexID];
    int tap = gl_InstanceID % 3;
    float particle = float(gl_InstanceID / 3);
    float trail = float(tap);
    float age = uBurst.w - trail * .045;
    vec2 center = uBurst.xy;
    float size = uView.x * .005;
    float light = 0.0;
    if (age >= 0.0 && age < uDetails.w && particle < 9.0) {
        float progress = clamp((age - particle * .018) / uDetails.w, 0.0, 1.0);
        float rise = progress * (2.0 - progress);
        center.y = mix(uView.y, uBurst.y, rise);
        center.x += sin(progress * 3.14159) * (uDetails.x - .5) * .043;
        size *= 1.3 - particle * .065;
        light = max(0., 1.0 - particle * .107) * step(particle * .018, age);
    } else if (age >= uDetails.w && age < uDetails.w + uDetails.z) {
        float seconds = age - uDetails.w;
        float life = seconds / uDetails.z;
        float theta = random(particle + 1.) * 6.283185;
        float spread = sqrt(random(particle + 63.));
        int type = int(uDetails.y + .5);
        if (type == 2) spread = .88 + .12 * spread;
        if (type == 3) theta = floor(theta * 1.27324) * .785398 + (spread - .5) * .17;
        vec2 direction = vec2(cos(theta), sin(theta));
        if (type == 2) direction.y *= .64;
        if (type == 5) {
            bool inner = mod(particle, 3.0) == 0.0;
            spread = (inner ? .48 : .94) + .055 * spread;
            direction.y *= .73;
        } else if (type == 6) {
            float s = sin(theta);
            direction.x = s * s * s;
            direction.y = -(13.0 * cos(theta) - 5.0 * cos(2.0 * theta)
                          - 2.0 * cos(3.0 * theta) - cos(4.0 * theta)) / 17.0;
            spread = .90 + .10 * spread;
        } else if (type == 7) {
            float ray = floor(random(particle + 29.0) * 7.0);
            theta = (ray - 3.0) * .29 + (spread - .5) * .12;
            direction = vec2(sin(theta) * 1.10, -cos(theta) * .87);
        }
        float radius = uBurst.z * spread * (1. - exp(-seconds * 3.5));
        center += direction * radius;
        center.y += uView.x * (type == 1 ? .040 : .020) * seconds * seconds;
        size *= .75 + .60 * random(particle + 152.);
        light = (1. - smoothstep(.10,1.,life)) * smoothstep(0., .11, seconds);
        if (type == 4) light *= .78 + .22 * sin(seconds * 18. + particle * 7.);
    }
    light *= pow(.66, trail);
    vec2 scale = vec2(size);
    if (uView.z > .5) {
        float depth = (uView.y - center.y) * .88;
        center.y = uView.y + depth;
        center.x += .010 * sin(sqrt(max(0., depth / uView.x) + .018) * 133. - uBurst.w * 3.6);
        scale *= vec2(1.55, 1.9);
        light *= uView.w * exp(-max(0., depth / uView.x) * 4.);
    }
    vLight = light;
    vec2 p = center + vLocal * scale * 2.4;
    gl_Position = light <= .001 ? vec4(3.,3.,0.,1.) : vec4(p.x*2.-1.,1.-p.y/uView.x*2.,0.,1.);
}
