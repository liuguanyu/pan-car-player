package com.baidu.carplayer.visualizer;

/**
 * GPU 月夜海浪。
 *
 * <p>真正的透视海面：每个像素发射相机射线，用不动点迭代求射线与<b>起伏水面</b>的交点
 * （不是与平海面求交），因此浪脊会挡住它后面的浪谷，海面在竖直方向出现真实的隆起／凹陷。
 *
 * <p>高度场由 5 组方向不同的涌浪叠加，高度与梯度都解析求出（不用差分，不会被采样步长抹平），
 * 再按频率做距离衰减（LOD）避免远处摩尔纹。法线用于 Blinn-Phong 月光高光、
 * Fresnel 天空反射、迎光浪脊与泡沫。
 */
final class WaveGlShaders {
    private WaveGlShaders() {}

    static final String VERT = "#version 300 es\n"
            + "out vec2 vUv;\n"
            // 一个覆盖全屏的三角形（比两个三角形少一条光栅化接缝）
            + "void main(){ vec2 p[3]=vec2[3](vec2(-1.0,-1.0),vec2(3.0,-1.0),vec2(-1.0,3.0));"
            + " vUv=p[gl_VertexID]*.5+.5; gl_Position=vec4(p[gl_VertexID],0,1); }\n";

    static final String FRAG = "#version 300 es\nprecision highp float;\n"
            + "in vec2 vUv; out vec4 outColor;\n"
            + "uniform float uTime,uEnergy,uBass,uKick,uAspect,uPeriod;\n"
            + "uniform sampler2D uHistory; uniform int uHistoryHead;\n"

            + "float hash(vec2 p){return fract(sin(dot(p,vec2(127.1,311.7)))*43758.5453);}\n"

            // 5 组方向不同的涌浪：返回归一化高度 [-1,1]，grad 是它对世界坐标的梯度。
            // chop 越大剖面越尖（深水涌浪），lodK 按频率淡出远处细节。
            + "float waveField(vec2 p,float chop,float lodK,out vec2 grad){\n"
            + " float h=0.; float norm=0.; grad=vec2(0.);\n"
            + " for(int i=0;i<5;i++){\n"
            + "  float fi=float(i);\n"
            + "  float k=.55+fi*.55;\n"                                     // 波长约 11.4 → 2.3 世界单位
            + "  vec2 dir=normalize(vec2(sin(fi*2.1+1.3),cos(fi*1.7+.4)));\n"
            + "  float ph=dot(p,dir)*k+uTime*sqrt(9.8*k)*.35+fi*1.7;\n"
            + "  float sv=sin(ph), cv=cos(ph);\n"
            + "  float a=.5+.5*sv;\n"
            + "  a+=chop*(a*a-a);\n"                                        // 尖峰宽谷
            + "  float fade=clamp(1.-k*lodK,0.,1.);\n"                       // 远处高频淡出
            + "  float w=1./(1.+fi*.35); norm+=w;\n"
            + "  h+=w*fade*(a-.5)*2.;\n"
            // d/da (a + chop*(a^2-a)) = 1 + chop*(2a-1)；da/dph = .5*cos
            + "  grad+=w*fade*(1.+chop*(2.*a-1.))*cv*k*dir;\n"
            + " }\n"
            + " grad/=norm; return h/norm; }\n"

            // 频谱：按屏幕横向位置取当前行（保持"左侧低频、右侧高频"的分布）
            + "float specAt(float x){\n"
            + " float u=clamp(abs(x)*1.15,0.,1.);\n"
            + " float row=(float(uHistoryHead)+.5)/48.;\n"
            + " return texture(uHistory,vec2(u,row)).r; }\n"

            + "void main(){\n"
            + " float spread=1.0, pitch=.075, camH=.95;\n"
            + " float dx=vUv.x-.5; float dy=(vUv.y-.5)*spread-pitch;\n"
            + " float horizon=.5+pitch/spread;\n"

            // ---------- 天空 ----------
            + " float sy=clamp((vUv.y-horizon)/max(.001,1.-horizon),0.,1.);\n"
            + " vec3 sky=mix(vec3(.115,.105,.24),vec3(.010,.014,.045),smoothstep(.0,.62,sy));\n"
            + " sky+=vec3(.045,.055,.135)*(1.-smoothstep(.0,.30,sy));\n"
            + " sky+=step(.9975,hash(floor(gl_FragCoord.xy*.5)))*vec3(.5)*smoothstep(.10,.60,sy);\n"
            + " vec2 moonPos=vec2(.70,.855);\n"
            + " float md=length((vUv-moonPos)*vec2(uAspect,1.));\n"
            + " sky+=vec3(.14,.18,.38)*exp(-md*8.)+vec3(.95,.97,1.)*(1.-smoothstep(.028,.034,md));\n"
            + " vec3 col=sky;\n"

            // ---------- 海面 ----------
            + " if(dy<-.002){\n"
            + "  float spc=specAt(vUv.x);\n"
            + "  float amp=.46+.14*uBass+.22*uEnergy+.08*spc;\n"             // 波幅（世界单位）
            + "  float chop=.22+.35*uEnergy;\n"
            // 不动点迭代：s = (camH - h(worldPoint(s))) / (-dy)，收敛到与起伏水面的交点
            + "  float s=camH/(-dy); vec2 gp;\n"
            // Newton 迭代求射线与起伏水面的交点。f(s)=camH+dy*s-h，f'(s)=dy-amp*(grad·dir)
            + "  for(int i=0;i<5;i++){\n"
            + "   float e=waveField(vec2(dx*s,s),chop,s*.008,gp)*amp;\n"
            + "   float f=camH+dy*s-e;\n"
            + "   float fp=dy-amp*(gp.x*dx+gp.y);\n"
            + "   if(abs(fp)<.02) break;\n"
            + "   float ns=clamp(s-f/fp,.05,200.);\n"
            + "   float d=abs(ns-s); s=ns; if(d<1e-3) break;\n"
            + "  }\n"
            + "  vec2 wp=vec2(dx*s,s);\n"
            + "  vec2 grad;\n"
            + "  float hn=waveField(wp,chop,s*.008,grad);\n"
            + "  float fog=1.-exp(-s*.017);\n"
            + "  vec3 view=normalize(vec3(-dx,-dy,-1.));\n"
            + "  vec3 light=normalize(vec3(.26,.42,1.));\n"
            + "  vec3 half1=normalize(light+view);\n"
            // 高频细浪只扰动法线，不参与几何，并随距离淡出
            + "  float near=clamp(1.-s*.045,0.,1.);\n"
            + "  vec2 slope=grad*amp*1.35;\n"
            + "  vec3 n=normalize(vec3(-slope.x+.12*near*sin(wp.x*5.7+uTime*2.1),1.,-slope.y+.12*near*sin(wp.y*5.1-uTime*1.7)));\n"
            // 水体：法线决定明暗，浪峰偏青，波谷压暗
            + "  float lit=.34+.66*max(0.,dot(n,light));\n"
            + "  vec3 deep=vec3(.013,.032,.080), crestCol=vec3(.15,.32,.58);\n"
            + "  vec3 water=mix(deep,crestCol,smoothstep(-.45,.80,hn))*lit;\n"
            + "  water*=clamp(1.+.38*hn,.68,1.30);\n"
            // 迎光浪脊：这是"起伏"最主要的视觉线索
            + "  float ridge=smoothstep(.10,.70,hn)*max(0.,dot(n,light));\n"
            + "  water+=vec3(.10,.18,.34)*ridge*(.35+.45*uEnergy);\n"
            // Fresnel：掠射角（远处）反射天色，地平线自然变亮
            + "  float fres=pow(1.-clamp(dot(n,-view),0.,1.),4.);\n"
            + "  water=mix(water,vec3(.085,.105,.235),fres*.75);\n"
            // 月光镜面高光
            + "  float spec=pow(max(0.,dot(n,half1)),96.);\n"
            + "  float band=exp(-pow((vUv.x-moonPos.x)*uAspect,2.)*13.);\n"
            + "  water+=vec3(1.,.97,.90)*spec*(.30+.40*uEnergy)*(.30+.85*band)*(1.-fog);\n"
            // 泡沫：被月光打亮的浪尖
            + "  float foam=smoothstep(.70,.98,hn)*(.12+.32*uEnergy+.15*uKick);\n"
            + "  water+=vec3(.72,.80,.95)*foam*.28*mix(.35,1.,band);\n"
            // 距离雾：远处融进地平线冷光
            + "  water=mix(water,vec3(.075,.085,.20),fog*.85);\n"
            + "  col=mix(water,sky,1.-smoothstep(.002,.024,-dy));\n"
            + " }\n"

            + " float vig=smoothstep(1.20,.30,length((vUv-.5)*vec2(uAspect*.60,1.)));\n"
            + " col*=mix(.74,1.,vig);\n"
            + " outColor=vec4(col,1.); }\n";
}
