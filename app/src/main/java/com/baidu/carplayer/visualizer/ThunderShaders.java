package com.baidu.carplayer.visualizer;

/**
 * 雷雨模式的 GLSL ES 3.00 源码。两个 pass，全部在 GPU：
 *
 * <ol>
 *   <li><b>背景</b>：全屏三角形。域扭曲 FBM 云层缓慢翻涌；每道雷击在自身位置把云层从内部照亮
 *       （越厚的云越亮，像云里透光）；雨丝只在被闪光照亮时可见。无月亮、无星星。</li>
 *   <li><b>闪电</b>：实例化绘制，每个线段一个四边形（{@code gl_InstanceID} 为线段序号）。
 *       线段端点存放在 RGBA32F 纹理里，用 {@code texelFetch} 读取（不需要过滤）。片元着色器计算
 *       到线段的距离，得到核心 + 内晕 + 外晕三层；混合方程用 {@code GL_MAX}，线段接头处不会叠出亮珠。</li>
 * </ol>
 *
 * <p>坐标：场景单位 x∈[0, aspect]、y∈[0, 1]（y 向上），横竖等比。
 */
final class ThunderShaders {
    private ThunderShaders() {}

    static final int FLASHES = ThunderScene.SLOTS;

    static final String BG_VERT = "#version 300 es\n"
            + "out vec2 vUv;\n"
            + "void main(){ vec2 p[3]=vec2[3](vec2(-1.,-1.),vec2(3.,-1.),vec2(-1.,3.));"
            + " vUv=p[gl_VertexID]*.5+.5; gl_Position=vec4(p[gl_VertexID],0.,1.); }\n";

    static final String BG_FRAG = "#version 300 es\nprecision highp float;\n"
            + "in vec2 vUv; out vec4 o;\n"
            + "uniform float uTime,uAspect,uRain,uWind;\n"
            + "uniform vec4 uFlash[" + FLASHES + "];\n"     // 中心 x, 中心 y, 亮度, 半径
            + "float hash(vec2 p){return fract(sin(dot(p,vec2(127.1,311.7)))*43758.5453);}\n"
            + "float noise(vec2 p){vec2 i=floor(p),f=fract(p);f=f*f*(3.-2.*f);"
            + " return mix(mix(hash(i),hash(i+vec2(1,0)),f.x),mix(hash(i+vec2(0,1)),hash(i+vec2(1,1)),f.x),f.y);}\n"
            + "float fbm(vec2 p){float v=0.,a=.5;mat2 m=mat2(1.6,1.2,-1.2,1.6);"
            + " for(int i=0;i<5;i++){v+=a*noise(p);p=m*p;a*=.5;} return v;}\n"
            // 斜向雨丝：分层视差 + 每格随机错位，fwidth 抗锯齿
            + "float rain(vec2 p,float t){\n"
            + " float v=0.;\n"
            + " for(int i=0;i<3;i++){\n"
            + "  float fi=float(i); float sc=7.+fi*5.;\n"
            + "  vec2 q=p*vec2(sc,sc*.22);\n"
            + "  q.x+=q.y*(.55+1.4*uWind)*.22*sc/sc;\n"
            + "  q.y+=t*(3.2+fi*1.3);\n"
            + "  vec2 c=floor(q); float j=hash(c+fi*17.);\n"
            + "  if(j>.55) continue;\n"                                         // 稀疏
            + "  vec2 f=fract(q+vec2(j*.7,j*3.1));\n"
            + "  float aa=fwidth(q.x)*1.2+.002;\n"
            + "  float line=1.-smoothstep(.012,.012+aa,abs(f.x-.5));\n"
            + "  float tail=smoothstep(.0,.6,f.y)*(1.-smoothstep(.6,1.,f.y));\n"
            + "  v+=line*tail*(.55-.12*fi);\n"
            + " }\n"
            + " return v; }\n"
            + "void main(){\n"
            + " vec2 p=vec2(vUv.x*uAspect,vUv.y);\n"
            // 域扭曲云：两层 fbm 作为位移场，云团会缓慢翻滚，而不是平移的纹理
            + " vec2 q=p*vec2(1.15,2.1);\n"
            + " vec2 w=vec2(fbm(q+vec2(0.,uTime*.035)),fbm(q+vec2(5.2,1.3)-uTime*.028));\n"
            + " float c=fbm(q*1.25+w*1.7+vec2(uTime*.018,0.));\n"
            + " float dens=smoothstep(.32,.86,c);\n"
            + " float top=smoothstep(.05,.75,vUv.y);\n"
            + " dens*=.55+.45*top;\n"
            + " vec3 skyTop=vec3(.045,.068,.150), skyLow=vec3(.080,.110,.215);\n"
            + " vec3 base=mix(skyLow,skyTop,smoothstep(.0,1.,vUv.y));\n"
            + " vec3 col=mix(base,base*1.55+vec3(.012,.014,.022),dens*.65);\n"
            // 雷击照亮云层：高斯落在闪电所在区域，厚云更亮（光在云里散射）
            + " float light=0.,total=0.;\n"
            + " for(int i=0;i<" + FLASHES + ";i++){\n"
            + "  vec4 f=uFlash[i]; vec2 d=(p-f.xy)/max(f.w,.05);\n"
            + "  light+=f.z*exp(-dot(d,d));\n"
            + "  total+=f.z;\n"
            + " }\n"
            + " col+=vec3(.42,.44,.66)*light*(.20+1.25*dens);\n"
            + " col+=vec3(.05,.055,.085)*min(total,1.);\n"                     // 整体天空微微提亮
            // 雨：平时几乎看不见，闪光时被照亮
            + " float lit=.05+min(1.,light*1.6+total*.35);\n"
            + " col+=vec3(.55,.60,.78)*rain(p,uTime)*lit*(.25+.75*uRain)*.55;\n"
            // 边缘与播放页背景 #121212 融合
            + " vec2 e=(vUv-.5)*vec2(uAspect*.55,1.);\n"
            + " float vig=1.-smoothstep(.35,1.05,length(e));\n"
            + " col*=mix(.82,1.,vig);\n"
            + " o=vec4(col,1.); }\n";

    static final String BOLT_VERT = "#version 300 es\n"
            + "uniform highp sampler2D uSeg;\n"
            + "uniform int uSlot; uniform float uAspect;\n"
            + "out vec2 vP; flat out vec4 vSeg; flat out vec4 vMeta;\n"
            + "void main(){\n"
            + " int id=gl_InstanceID;\n"
            + " vec4 a=texelFetch(uSeg,ivec2(id*2,uSlot),0);\n"
            + " vec4 m=texelFetch(uSeg,ivec2(id*2+1,uSlot),0);\n"
            + " vec2 A=a.xy,B=a.zw,d=B-A; float L=length(d);\n"
            + " vec2 t=L>1e-6?d/L:vec2(1.,0.); vec2 n=vec2(-t.y,t.x);\n"
            + " float r=m.x*16.;\n"                                              // 覆盖外晕
            + " vec2 c[6]=vec2[6](vec2(0,-1),vec2(1,-1),vec2(1,1),vec2(0,-1),vec2(1,1),vec2(0,1));\n"
            + " vec2 k=c[gl_VertexID];\n"
            + " vec2 P=mix(A-t*r,B+t*r,k.x)+n*r*k.y;\n"
            + " vP=P; vSeg=a; vMeta=m;\n"
            + " gl_Position=vec4(P.x/uAspect*2.-1.,P.y*2.-1.,0.,1.);\n"
            + "}\n";

    static final String BOLT_FRAG = "#version 300 es\nprecision highp float;\n"
            + "in vec2 vP; flat in vec4 vSeg; flat in vec4 vMeta; out vec4 o;\n"
            + "uniform float uCore,uGlow,uFront,uTime;\n"
            + "void main(){\n"
            + " if(vMeta.z>uFront) discard;\n"                                  // 先导尚未到达
            + " vec2 A=vSeg.xy,B=vSeg.zw,ba=B-A;\n"
            + " float h=clamp(dot(vP-A,ba)/max(dot(ba,ba),1e-9),0.,1.);\n"
            + " float d=length(vP-A-ba*h);\n"
            + " float w=vMeta.x, I=vMeta.y;\n"
            // 每段亮度轻微闪烁，通道不是一根均匀的灯管
            + " float flick=.82+.18*sin(uTime*53.+vMeta.z*91.+vMeta.w*7.);\n"
            + " float core=1.-smoothstep(w*.35,w,d);\n"
            + " float inner=exp(-d*d/(w*w*7.));\n"
            + " float outer=exp(-d/(w*4.5));\n"
            // 先导前端更亮：生长过程中能看到一个亮头往前爬
            + " float tip=uFront<1.?smoothstep(uFront-.06,uFront,vMeta.z):0.;\n"
            + " vec3 col=vec3(1.,.97,1.)*core*(uCore+tip*1.2)\n"
            + "         +vec3(.62,.64,1.)*inner*(uCore*.65+uGlow*.40)\n"
            + "         +vec3(.36,.38,.92)*outer*uGlow*.42;\n"
            + " o=vec4(col*I*flick,1.);\n"
            + "}\n";
}
