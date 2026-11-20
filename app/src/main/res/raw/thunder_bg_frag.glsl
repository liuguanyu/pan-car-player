#version 300 es
precision highp float;
in vec2 vUv; out vec4 o;
uniform float uTime,uAspect,uRain,uWind;
uniform vec4 uFlash[4];
float hash(vec2 p){return fract(sin(dot(p,vec2(127.1,311.7)))*43758.5453);}
float noise(vec2 p){vec2 i=floor(p),f=fract(p);f=f*f*(3.-2.*f); return mix(mix(hash(i),hash(i+vec2(1,0)),f.x),mix(hash(i+vec2(0,1)),hash(i+vec2(1,1)),f.x),f.y);}
float fbm(vec2 p){float v=0.,a=.5;mat2 m=mat2(1.6,1.2,-1.2,1.6); for(int i=0;i<5;i++){v+=a*noise(p);p=m*p;a*=.5;} return v;}
float rain(vec2 p,float t){
 float v=0.;
 for(int i=0;i<3;i++){
  float fi=float(i); float sc=7.+fi*5.;
  vec2 q=p*vec2(sc,sc*.22);
  q.x+=q.y*(.55+1.4*uWind)*.22*sc/sc;
  q.y+=t*(3.2+fi*1.3);
  vec2 c=floor(q); float j=hash(c+fi*17.);
  if(j>.55) continue;
  vec2 f=fract(q+vec2(j*.7,j*3.1));
  float aa=fwidth(q.x)*1.2+.002;
  float line=1.-smoothstep(.012,.012+aa,abs(f.x-.5));
  float tail=smoothstep(.0,.6,f.y)*(1.-smoothstep(.6,1.,f.y));
  v+=line*tail*(.55-.12*fi);
 }
 return v; }
void main(){
 vec2 p=vec2(vUv.x*uAspect,vUv.y);
 vec2 q=p*vec2(1.15,2.1);
 vec2 w=vec2(fbm(q+vec2(0.,uTime*.035)),fbm(q+vec2(5.2,1.3)-uTime*.028));
 float c=fbm(q*1.25+w*1.7+vec2(uTime*.018,0.));
 float dens=smoothstep(.32,.86,c);
 float top=smoothstep(.05,.75,vUv.y);
 dens*=.55+.45*top;
 vec3 skyTop=vec3(.045,.068,.150), skyLow=vec3(.080,.110,.215);
 vec3 base=mix(skyLow,skyTop,smoothstep(.0,1.,vUv.y));
 vec3 col=mix(base,base*1.55+vec3(.012,.014,.022),dens*.65);
 float light=0.,total=0.;
 for(int i=0;i<4;i++){
  vec4 f=uFlash[i]; vec2 d=(p-f.xy)/max(f.w,.05);
  light+=f.z*exp(-dot(d,d));
  total+=f.z;
 }
 col+=vec3(.42,.44,.66)*light*(.20+1.25*dens);
 col+=vec3(.05,.055,.085)*min(total,1.);
 float lit=.05+min(1.,light*1.6+total*.35);
 col+=vec3(.55,.60,.78)*rain(p,uTime)*lit*(.25+.75*uRain)*.55;
 vec2 e=(vUv-.5)*vec2(uAspect*.55,1.);
 float vig=1.-smoothstep(.35,1.05,length(e));
 col*=mix(.82,1.,vig);
 o=vec4(col,1.); }
