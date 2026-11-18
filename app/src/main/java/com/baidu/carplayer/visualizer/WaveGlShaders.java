package com.baidu.carplayer.visualizer;

/** GPU 月夜海浪：全屏三角形 + fragment shader 生成多层透视海面。 */
final class WaveGlShaders {
    private WaveGlShaders() {}

    static final String VERT = "#version 300 es\n"
            + "out vec2 vUv;\n"
            + "void main(){ vec2 p[3]=vec2[3](vec2(-1.0,-1.0),vec2(3.0,-1.0),vec2(-1.0,3.0)); vUv=p[gl_VertexID]*.5+.5; gl_Position=vec4(p[gl_VertexID],0,1); }\n";

    static final String FRAG = "#version 300 es\nprecision highp float;\n"
            + "in vec2 vUv; out vec4 outColor;\n"
            + "uniform float uTime,uEnergy,uBass,uKick,uAspect,uPeriod;\n"
            + "uniform sampler2D uHistory; uniform int uHistoryHead;\n"
            + "const float PI=3.14159265;\n"
            + "float hash(vec2 p){return fract(sin(dot(p,vec2(127.1,311.7)))*43758.5453);}\n"
            + "float wave(float x,float z,float t){\n"
            + " float h=0.; float k[5]=float[5](.48,.82,1.45,2.35,3.7);\n"
            + " float a[5]=float[5](.30,.19,.10,.055,.028);\n"
            + " for(int i=0;i<5;i++){ float ph=k[i]*(x*(.18+float(i)*.12)+z*(1.0-float(i)*.06))+uTime*sqrt(9.8*k[i])*.55+float(i)*1.7; float c=.5+.5*sin(ph); float sharp=.35+.55*uEnergy; c+=sharp*(c*c-c); h+=a[i]*(.55+.9*uBass)*(c-.5); }\n"
            + " return h; }\n"
            + "float spec(float x,float z){ float u=clamp(abs(x)*.45,0.,1.); float v=texture(uHistory,vec2(u,fract(z*.055))).r; return v; }\n"
            + "void main(){\n"
            + " vec2 q=(vUv-.5)*vec2(uAspect,1.); float horizon=.38+.02*sin(uTime*.17);\n"
            + " vec3 sky=mix(vec3(.008,.012,.035),vec3(.12,.10,.28),smoothstep(0.,horizon,vUv.y));\n"
            + " vec2 moonPos=vec2(.70,.78); vec2 moonD=(vUv-moonPos)*vec2(uAspect,1.0); float moonR=length(moonD); float moonGlow=exp(-moonR*9.); float moonDisc=1.0-smoothstep(.035,.041,moonR); sky+=vec3(.18,.21,.38)*moonGlow+vec3(.92,.95,1.0)*moonDisc;\n"
            + " float reflX=abs(vUv.x-.68); float reflY=max(0.0,horizon-vUv.y); float reflection=exp(-reflX*reflX*190.0-reflY*reflY*7.0); reflection*=1.0-smoothstep(horizon-.02,horizon+.22,vUv.y); reflection*=.35+.65*sin(uTime*2.0+vUv.y*95.0)*sin(uTime*2.0+vUv.x*41.0); sky+=vec3(.28,.34,.62)*max(0.0,reflection);\n"
            + " vec3 col=sky; float seaMask=1.0-smoothstep(horizon-.10,horizon+.04,vUv.y); float z=1.0;\n"
            + " for(int layer=0;layer<30;layer++){ float t=float(layer)/29.; float depth=pow(t,1.65)*18.+1.; float perspective=1./(1.+depth*.38); float x=q.x*depth*1.25; float h=wave(x,depth,t)+spec(x,depth)*(.12+.35*uEnergy)+.08*sin(x*.35-uTime*.5); float screen=horizon+(1.0-t)*.72-h*perspective*2.8; float dist=abs(vUv.y-screen); float width=.010+.026*perspective; float line=smoothstep(width,width*.16,dist); float foam=smoothstep(.20,.42,h)*line; vec3 water=mix(vec3(.006,.018,.055),vec3(.10,.13,.30),t); float body=smoothstep(width*2.8,width*.15,dist); col+=water*body*(.12+.55*perspective)*seaMask; col+=water*line*(.12+.65*perspective*uEnergy)*seaMask; col+=vec3(.70,.80,1.0)*foam*(.35+.95*uEnergy)*seaMask; }\n"
            + " col=mix(col,vec3(.006,.014,.035),(1.0-seaMask)*.20);\n"
            + " float fog=smoothstep(.72,.15,vUv.y); col=mix(col,vec3(.025,.025,.07),fog*.35); float stars=step(.997,hash(gl_FragCoord.xy)); col+=stars*vec3(.7); outColor=vec4(col,1.); }\n";
}
