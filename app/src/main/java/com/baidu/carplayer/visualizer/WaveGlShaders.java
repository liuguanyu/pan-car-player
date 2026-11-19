package com.baidu.carplayer.visualizer;

/**
 * GPU 月夜海浪。
 *
 * <p>海面是真正的透视海面：每个像素发射相机射线，用 Newton 迭代求射线与<b>起伏水面</b>的交点
 * （不是与平海面求交），因此浪脊会挡住它后面的浪谷，海面在竖直方向出现真实的隆起／凹陷。
 *
 * <p>高度场由 5 组方向不同的涌浪叠加，高度与梯度都解析求出（不用差分，不会被采样步长抹平），
 * 再按频率做距离衰减（LOD）避免远处摩尔纹。法线用于 Blinn-Phong 月光高光、
 * Fresnel 天空反射、迎光浪脊与泡沫。
 *
 * <p>远处有一艘帆船剪影，它是"海在动"的主要参考物：船的升沉直接取所在世界点的波高，
 * 横摇取该点的波面坡度，帆的鼓起与桅灯的闪动跟音乐的低频／能量／节拍挂钩。
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
            + "uniform float uWind,uBoatX;\n"
            + "uniform sampler2D uHistory; uniform int uHistoryHead;\n"

            + "float saturate(float x){return clamp(x,0.,1.);}\n"
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

            // ---------- 帆船几何（船体局部坐标：x 向右，y 向上，原点在水线中心） ----------
            // 凸多边形内部的有向距离：对每条边取到内侧的距离，取最小值；越界为负。
            + "float sdEdge(vec2 p,vec2 a,vec2 b){\n"
            + " float ex=b.x-a.x, ey=b.y-a.y;\n"
            + " return dot(p-a,vec2(-ey,ex)/max(1e-7,sqrt(ex*ex+ey*ey))); }\n"
            + "float tri(vec2 p,vec2 a,vec2 b,vec2 c,float aa){\n"
            + " float d=min(sdEdge(p,a,b),min(sdEdge(p,b,c),sdEdge(p,c,a)));\n"
            + " return smoothstep(-aa,aa,d); }\n"
            + "float quad(vec2 p,vec2 a,vec2 b,vec2 c,vec2 e,float aa){\n"
            + " float d=min(min(sdEdge(p,a,b),sdEdge(p,b,c)),min(sdEdge(p,c,e),sdEdge(p,e,a)));\n"
            + " return smoothstep(-aa,aa,d); }\n"
            // 曲边帆。luffX 为桅杆一侧，dir=-1 向左展开（主帆）、+1 向右（前帆）。
            // 风越大中段越鼓、后缘越绷；风弱时后缘抖动，抖动波沿帆向上传播。
            // 返回 (覆盖度, 离桅杆的相对位置 u∈[0,1])。
            + "vec2 sail(vec2 p,float luffX,float footY,float headY,float width,float dir,float wind,float ph,float aa){\n"
            + " float t=clamp((p.y-footY)/(headY-footY),0.,1.);\n"
            + " float env=sin(3.1416*t);\n"
            + " float belly=width*(.06+.30*wind)*env;\n"
            // 帆的几何轮廓保持平滑单峰：桅杆处固定，腹部向背风侧鼓出，后缘自然收窄。
            // 不能把周期性 flap 加到 reach，否则会出现截图中不符合物理的多瓣帆。
            + " float reach=max(0.,width*(1.-t)+belly);\n"
            + " float u=(p.x-luffX)*dir;\n"
            + " float d=min(min(u,reach-u),min(p.y-footY,headY-p.y));\n"
            + " return vec2(smoothstep(-aa,aa,d),clamp(u/max(reach,1e-4),0.,1.)); }\n"
            // 整船：x=木质部分（船体+桅杆）覆盖度，y=帆覆盖度，z=帆面 u，w=帆面高度 t
            + "vec4 boatMask(vec2 p,float wind,float aa){\n"
            + " float hull =quad(p,vec2(-.032,-.011),vec2(.030,-.011),vec2(.038,.011),vec2(-.038,.011),aa);\n"
            + " float mast =quad(p,vec2(-.0046,.010),vec2(-.0024,.010),vec2(-.0024,.150),vec2(-.0046,.150),aa);\n"
            + " vec2 ms=sail(p,-.0035,.013,.144,.040+.008*wind,-1.,wind,0.,aa);\n"
            + " vec2 js=sail(p,-.0035,.013,.098,.026+.005*wind,1.,wind*.9,1.7,aa);\n"
            + " float sc=max(ms.x,js.x); float su=ms.x>=js.x?ms.y:js.y;\n"
            + " return vec4(max(hull,mast),sc,su,clamp((p.y-.013)/.131,0.,1.)); }\n"

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
+ " float band=exp(-pow((vUv.x-moonPos.x)*uAspect,2.)*13.);\n"
            + " vec3 col=sky;\n"

            // ---------- 海面 ----------
            + " if(dy<-.002){\n"
            + "  float spc=specAt(vUv.x);\n"
            + "  float amp=.46+.14*uBass+.22*uEnergy+.08*spc;\n"              // 波幅（世界单位）
            + "  float chop=.22+.35*uEnergy;\n"
            // Newton 迭代求射线与起伏水面的交点。f(s)=camH+dy*s-h，f'(s)=dy-amp*(grad·dir)
            + "  float s=camH/(-dy); vec2 gp;\n"
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
            // 迎光浪脊
            + "  float ridge=smoothstep(.10,.70,hn)*max(0.,dot(n,light));\n"
            + "  water+=vec3(.10,.18,.34)*ridge*(.35+.45*uEnergy);\n"
            // Fresnel：掠射角（远处）反射天色，地平线自然变亮
            + "  float fres=pow(1.-clamp(dot(n,-view),0.,1.),4.);\n"
            + "  water=mix(water,vec3(.085,.105,.235),fres*.75);\n"
            // 月光镜面高光
            + "  float spec=pow(max(0.,dot(n,half1)),96.);\n"
                        + "  water+=vec3(1.,.97,.90)*spec*(.30+.40*uEnergy)*(.30+.85*band)*(1.-fog);\n"
            // 泡沫：被月光打亮的浪尖
            + "  float foam=smoothstep(.70,.98,hn)*(.12+.32*uEnergy+.15*uKick);\n"
            + "  water+=vec3(.72,.80,.95)*foam*.28*mix(.35,1.,band);\n"
            // 距离雾：远处融进地平线冷光
            + "  water=mix(water,vec3(.075,.085,.20),fog*.85);\n"
            + "  col=mix(water,sky,1.-smoothstep(.002,.024,-dy));\n"
            + " }\n"

            // ---------- 帆船 ----------
            // 船锚定在海面上的一个世界点，随该点的波面升沉、横摇。uBoatX 由 CPU 按风力积分（船在航行）。
            // 声学关联：
            //   风力 uWind  ← 响度包络（起风快、回落慢）+ 自然阵风噪声 + 鼓点阵风；驱动航速、帆的鼓起、船倾
            //   升沉        ← 该点波高 × 低频 uBass；鼓点 uKick 让船轻轻上顶
            //   横摇        ← 该点波面坡度 × 能量，叠加按乐曲节拍周期 uPeriod 的慢摆
            //   桅灯        ← 鼓点闪动
            + " {\n"
            + "  vec2 bOrig=vec2(uBoatX,.478);\n"
            + "  float bdy=(bOrig.y-.5)*spread-pitch;\n"
            + "  float bt=clamp(camH/max(.0015,-bdy),2.,60.);\n"
            + "  vec2 bg; float bhn=waveField(vec2((bOrig.x-.5)*bt,bt),.22+.35*uEnergy,bt*.008,bg);\n"
            + "  float bAmp=.46+.14*uBass+.22*uEnergy;\n"
            // 浪峰抬高船体：Δuv.y = +Δh/(t*spread)；浪的轨道运动给一点前后纵荡
            + "  float bob=(bhn*bAmp)/(bt*spread)*(.55+1.15*uBass)+.004*uKick;\n"
            + "  float surge=-bg.x*bAmp*.0030;\n"
            // 横摇：波面坡度给方向，能量给幅度，节拍周期给慢摆；风把船压向下风侧；总角度软限幅约 ±12°
            + "  float roll=bg.x*bAmp*.10*(.8+1.2*uEnergy)\n"
            + "            +.035*sin(uTime*3.1416*2./max(.30,uPeriod/1000.)*.5)\n"
            + "            -.07*uWind;\n"
            + "  roll=.21*tanh(roll/.21);\n"
            + "  vec2 p=vUv-(bOrig+vec2(surge,bob));\n"
            + "  p.x*=uAspect/1.7778;\n"                                      // 宽高比变化时船不被拉伸
            + "  float cr=cos(roll), sr=sin(roll);\n"
            + "  p=vec2(cr*p.x+sr*p.y,-sr*p.x+cr*p.y)/1.30;\n"
            + "  float aa=.9*max(dFdx(vUv.x),dFdy(vUv.y))/1.30;\n"
            + "  float wind=clamp(uWind,0.,1.2);\n"
            // 倒影：上下翻转、随波纹抖动，比水面更暗
            + "  vec2 rp=vec2(p.x,-p.y*1.15)+vec2(0.,.003*sin(uTime*2.1+p.x*90.));\n"
            + "  vec4 rm=boatMask(rp,wind,aa*2.6);\n"
            + "  col=mix(col,vec3(.020,.030,.060),max(rm.x,rm.y)*(.30+.22*band));\n"
            + "  vec4 bm=boatMask(p,wind,aa);\n"
            // 船身与桅杆：棕黄木材，木纹 + 竖向细纤维 + 船板缝
            + "  float hullT=saturate((p.y+.011)/.022);\n"
            + "  float grain=.5+.5*sin(p.x*210.+sin(p.x*47.+p.y*160.)*2.1);\n"
            + "  grain*=.6+.4*sin(p.y*640.+p.x*33.);\n"
            + "  float plank=abs(fract(p.y*46.+.5)-.5)*2.;\n"
            + "  float seam=smoothstep(.72,.96,plank);\n"
            + "  vec3 wood=mix(vec3(.20,.125,.052),vec3(.44,.300,.135),hullT*.55+grain*.45);\n"
            + "  wood*=1.-seam*.45;\n"
            + "  wood+=vec3(.16,.10,.03)*grain*.30;\n"
            + "  col=mix(col,wood,bm.x*.97);\n"
            // 帆：白灰布料。高处、受光侧更亮；鼓起的中段在背光处形成柔和阴影；
            // 风弱时抖动的褶皱沿帆向上走，风满时帆面绷平。
            + "  float su=bm.z, st=bm.w;\n"
            + "  float lit=smoothstep(.0,1.,st)*.45+smoothstep(-.052,.030,p.x)*.55;\n"
            + "  vec3 sailCol=mix(vec3(.34,.36,.40),vec3(.76,.76,.73),lit);\n"
            + "  sailCol*=1.-.20*(.35+.65*wind)*sin(3.1416*su)*(1.-st*.6);\n"   // 鼓起的阴影
            + "  sailCol+=vec3(.10,.10,.09)*smoothstep(.70,1.,su)*(.4+.6*wind);\n" // 绷紧的后缘更亮
            + "  float slack=clamp(1.05-wind,0.,1.);\n"
            // 低风时只让后缘轻微拍动，扰动颜色而不改变轮廓；风大时帆面更平、更绷。
            + "  float flutter=slack*.035*su*su*sin(st*13.-uTime*(1.2+1.8*wind));\n"
            + "  sailCol*=1.+flutter;\n"
            + "  col=mix(col,sailCol,saturate(bm.y*.94));\n"
            // 轮廓月光边
            + "  vec4 bmWide=boatMask(p,wind,aa*3.2);\n"
            + "  col+=vec3(.26,.28,.30)*(max(bmWide.x,bmWide.y)-max(bm.x,bm.y))*.42;\n"
            // 桅灯：随节拍闪动
            + "  vec2 lampP=(p-vec2(-.0035,.1515))/vec2(.010,.010);\n"
            + "  col+=vec3(1.,.93,.74)*exp(-dot(lampP,lampP)*2.0)*(.10+.85*uKick);\n"
            + " }\n"

            + " float vig=smoothstep(1.20,.30,length((vUv-.5)*vec2(uAspect*.60,1.)));\n"
            + " col*=mix(.74,1.,vig);\n"
            + " outColor=vec4(col,1.); }\n";
}
