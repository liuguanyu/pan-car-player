#version 300 es
precision highp float;
in vec2 vUv; out vec4 outColor;
uniform float uTime,uEnergy,uBass,uKick,uAspect,uPeriod;
uniform float uWind,uBoatX;
uniform sampler2D uHistory; uniform int uHistoryHead;
float saturate(float x){return clamp(x,0.,1.);}
float hash(vec2 p){return fract(sin(dot(p,vec2(127.1,311.7)))*43758.5453);}
float waveField(vec2 p,float chop,float lodK,out vec2 grad){
 float h=0.; float norm=0.; grad=vec2(0.);
 for(int i=0;i<5;i++){
  float fi=float(i);
  float k=.55+fi*.55;
  vec2 dir=normalize(vec2(sin(fi*2.1+1.3),cos(fi*1.7+.4)));
  float ph=dot(p,dir)*k+uTime*sqrt(9.8*k)*.35+fi*1.7;
  float sv=sin(ph), cv=cos(ph);
  float a=.5+.5*sv;
  a+=chop*(a*a-a);
  float fade=clamp(1.-k*lodK,0.,1.);
  float w=1./(1.+fi*.35); norm+=w;
  h+=w*fade*(a-.5)*2.;
  grad+=w*fade*(1.+chop*(2.*a-1.))*cv*k*dir;
 }
 grad/=norm; return h/norm; }
float specAt(float x){
 float u=clamp(abs(x)*1.15,0.,1.);
 float row=(float(uHistoryHead)+.5)/48.;
 return texture(uHistory,vec2(u,row)).r; }
float sdEdge(vec2 p,vec2 a,vec2 b){
 float ex=b.x-a.x, ey=b.y-a.y;
 return dot(p-a,vec2(-ey,ex)/max(1e-7,sqrt(ex*ex+ey*ey))); }
float tri(vec2 p,vec2 a,vec2 b,vec2 c,float aa){
 float d=min(sdEdge(p,a,b),min(sdEdge(p,b,c),sdEdge(p,c,a)));
 return smoothstep(-aa,aa,d); }
float quad(vec2 p,vec2 a,vec2 b,vec2 c,vec2 e,float aa){
 float d=min(min(sdEdge(p,a,b),sdEdge(p,b,c)),min(sdEdge(p,c,e),sdEdge(p,e,a)));
 return smoothstep(-aa,aa,d); }
vec2 sail(vec2 p,float luffX,float footY,float headY,float width,float dir,float wind,float ph,float aa){
 float t=clamp((p.y-footY)/(headY-footY),0.,1.);
 float env=sin(3.1416*t);
 float belly=width*(.06+.30*wind)*env;
 float reach=max(0.,width*(1.-t)+belly);
 float u=(p.x-luffX)*dir;
 float d=min(min(u,reach-u),min(p.y-footY,headY-p.y));
 return vec2(smoothstep(-aa,aa,d),clamp(u/max(reach,1e-4),0.,1.)); }
vec4 boatMask(vec2 p,float wind,float aa){
 float hull =quad(p,vec2(-.032,-.011),vec2(.030,-.011),vec2(.038,.011),vec2(-.038,.011),aa);
 float mast =quad(p,vec2(-.0046,.010),vec2(-.0024,.010),vec2(-.0024,.150),vec2(-.0046,.150),aa);
 vec2 ms=sail(p,-.0035,.013,.144,.040+.008*wind,-1.,wind,0.,aa);
 vec2 js=sail(p,-.0035,.013,.098,.026+.005*wind,1.,wind*.9,1.7,aa);
 float sc=max(ms.x,js.x); float su=ms.x>=js.x?ms.y:js.y;
 return vec4(max(hull,mast),sc,su,clamp((p.y-.013)/.131,0.,1.)); }
void main(){
 float spread=1.0, pitch=.075, camH=.95;
 float dx=vUv.x-.5; float dy=(vUv.y-.5)*spread-pitch;
 float horizon=.5+pitch/spread;
 float sy=clamp((vUv.y-horizon)/max(.001,1.-horizon),0.,1.);
 vec3 sky=mix(vec3(.115,.105,.24),vec3(.010,.014,.045),smoothstep(.0,.62,sy));
 sky+=vec3(.045,.055,.135)*(1.-smoothstep(.0,.30,sy));
 sky+=step(.9975,hash(floor(gl_FragCoord.xy*.5)))*vec3(.5)*smoothstep(.10,.60,sy);
 vec2 moonPos=vec2(.70,.855);
 float md=length((vUv-moonPos)*vec2(uAspect,1.));
 sky+=vec3(.14,.18,.38)*exp(-md*8.)+vec3(.95,.97,1.)*(1.-smoothstep(.028,.034,md));
 float band=exp(-pow((vUv.x-moonPos.x)*uAspect,2.)*13.);
 vec3 col=sky;
 if(dy<-.002){
  float spc=specAt(vUv.x);
  float amp=.46+.14*uBass+.22*uEnergy+.08*spc;
  float chop=.22+.35*uEnergy;
  float s=camH/(-dy); vec2 gp;
  for(int i=0;i<5;i++){
   float e=waveField(vec2(dx*s,s),chop,s*.008,gp)*amp;
   float f=camH+dy*s-e;
   float fp=dy-amp*(gp.x*dx+gp.y);
   if(abs(fp)<.02) break;
   float ns=clamp(s-f/fp,.05,200.);
   float d=abs(ns-s); s=ns; if(d<1e-3) break;
  }
  vec2 wp=vec2(dx*s,s);
  vec2 grad;
  float hn=waveField(wp,chop,s*.008,grad);
  float fog=1.-exp(-s*.017);
  vec3 view=normalize(vec3(-dx,-dy,-1.));
  vec3 light=normalize(vec3(.26,.42,1.));
  vec3 half1=normalize(light+view);
  float near=clamp(1.-s*.045,0.,1.);
  vec2 slope=grad*amp*1.35;
  vec3 n=normalize(vec3(-slope.x+.12*near*sin(wp.x*5.7+uTime*2.1),1.,-slope.y+.12*near*sin(wp.y*5.1-uTime*1.7)));
  float lit=.34+.66*max(0.,dot(n,light));
  vec3 deep=vec3(.013,.032,.080), crestCol=vec3(.15,.32,.58);
  vec3 water=mix(deep,crestCol,smoothstep(-.45,.80,hn))*lit;
  water*=clamp(1.+.38*hn,.68,1.30);
  float ridge=smoothstep(.10,.70,hn)*max(0.,dot(n,light));
  water+=vec3(.10,.18,.34)*ridge*(.35+.45*uEnergy);
  float fres=pow(1.-clamp(dot(n,-view),0.,1.),4.);
  water=mix(water,vec3(.085,.105,.235),fres*.75);
  float spec=pow(max(0.,dot(n,half1)),96.);
  water+=vec3(1.,.97,.90)*spec*(.30+.40*uEnergy)*(.30+.85*band)*(1.-fog);
  float foam=smoothstep(.70,.98,hn)*(.12+.32*uEnergy+.15*uKick);
  water+=vec3(.72,.80,.95)*foam*.28*mix(.35,1.,band);
  water=mix(water,vec3(.075,.085,.20),fog*.85);
  col=mix(water,sky,1.-smoothstep(.002,.024,-dy));
 }
 {
  vec2 bOrig=vec2(uBoatX,.478);
  float bdy=(bOrig.y-.5)*spread-pitch;
  float bt=clamp(camH/max(.0015,-bdy),2.,60.);
  vec2 bg; float bhn=waveField(vec2((bOrig.x-.5)*bt,bt),.22+.35*uEnergy,bt*.008,bg);
  float bAmp=.46+.14*uBass+.22*uEnergy;
  float bob=(bhn*bAmp)/(bt*spread)*(.55+1.15*uBass)+.004*uKick;
  float surge=-bg.x*bAmp*.0030;
  float roll=bg.x*bAmp*.10*(.8+1.2*uEnergy)
            +.035*sin(uTime*3.1416*2./max(.30,uPeriod/1000.)*.5)
            -.07*uWind;
  roll=.21*tanh(roll/.21);
  vec2 p=vUv-(bOrig+vec2(surge,bob));
  p.x*=uAspect/1.7778;
  float cr=cos(roll), sr=sin(roll);
  p=vec2(cr*p.x+sr*p.y,-sr*p.x+cr*p.y)/1.30;
  float aa=.9*max(dFdx(vUv.x),dFdy(vUv.y))/1.30;
  float wind=clamp(uWind,0.,1.2);
  vec2 rp=vec2(p.x,-p.y*1.15)+vec2(0.,.003*sin(uTime*2.1+p.x*90.));
  vec4 rm=boatMask(rp,wind,aa*2.6);
  col=mix(col,vec3(.020,.030,.060),max(rm.x,rm.y)*(.30+.22*band));
  vec4 bm=boatMask(p,wind,aa);
  float hullT=saturate((p.y+.011)/.022);
  float grain=.5+.5*sin(p.x*210.+sin(p.x*47.+p.y*160.)*2.1);
  grain*=.6+.4*sin(p.y*640.+p.x*33.);
  float plank=abs(fract(p.y*46.+.5)-.5)*2.;
  float seam=smoothstep(.72,.96,plank);
  vec3 wood=mix(vec3(.20,.125,.052),vec3(.44,.300,.135),hullT*.55+grain*.45);
  wood*=1.-seam*.45;
  wood+=vec3(.16,.10,.03)*grain*.30;
  col=mix(col,wood,bm.x*.97);
  float su=bm.z, st=bm.w;
  float lit=smoothstep(.0,1.,st)*.45+smoothstep(-.052,.030,p.x)*.55;
  vec3 sailCol=mix(vec3(.34,.36,.40),vec3(.76,.76,.73),lit);
  sailCol*=1.-.20*(.35+.65*wind)*sin(3.1416*su)*(1.-st*.6);
  sailCol+=vec3(.10,.10,.09)*smoothstep(.70,1.,su)*(.4+.6*wind);
  float slack=clamp(1.05-wind,0.,1.);
  float flutter=slack*.035*su*su*sin(st*13.-uTime*(1.2+1.8*wind));
  sailCol*=1.+flutter;
  col=mix(col,sailCol,saturate(bm.y*.94));
  vec4 bmWide=boatMask(p,wind,aa*3.2);
  col+=vec3(.26,.28,.30)*(max(bmWide.x,bmWide.y)-max(bm.x,bm.y))*.42;
  vec2 lampP=(p-vec2(-.0035,.1515))/vec2(.010,.010);
  col+=vec3(1.,.93,.74)*exp(-dot(lampP,lampP)*2.0)*(.10+.85*uKick);
 }
 float vig=smoothstep(1.20,.30,length((vUv-.5)*vec2(uAspect*.60,1.)));
 col*=mix(.74,1.,vig);
 outColor=vec4(col,1.); }
