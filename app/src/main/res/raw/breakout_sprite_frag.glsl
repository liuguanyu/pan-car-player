#version 300 es
precision highp float;
in vec2 vP;
flat in vec4 vMeta;
out vec4 o;

// 形状用 SDF 表达，分辨率无关；边缘抗锯齿靠 fwidth，屏幕上恒为约 1px。
float roundBox(vec2 p, vec2 b, float r){
    vec2 q=abs(p)-b+r;
    return length(max(q,0.))+min(max(q.x,q.y),0.)-r;
}

// 胶囊线段：箭头/斜线都用它拼，避免为标志引入额外顶点。
float sdSeg(vec2 p, vec2 a, vec2 b, float r){
    vec2 pa=p-a, ba=b-a;
    float h=clamp(dot(pa,ba)/max(dot(ba,ba),1e-6),0.,1.);
    return length(pa-ba*h)-r;
}

// 技能砖标志：统一白色，靠形状区分类型（10 多球 11 加速 12 减速 13 加宽 14 变窄）
float itemGlyph(vec2 p, int t){
    if(t==10){                                  // 多球：三个点
        return min(min(length(p-vec2(-.58,0.)),length(p)),
                   length(p-vec2(.58,0.)))-.30;
    }
    if(t==11){                                  // 加速：双上箭头
        float d=1e3;
        for(int i=0;i<2;i++){
            float y=-.55+float(i)*.72;
            d=min(d,sdSeg(p,vec2(-.62,y),vec2(0.,y+.62),.13));
            d=min(d,sdSeg(p,vec2(0.,y+.62),vec2(.62,y),.13));
        }
        return d;
    }
    if(t==12){                                  // 减速：双下箭头
        float d=1e3;
        for(int i=0;i<2;i++){
            float y=.55-float(i)*.72;
            d=min(d,sdSeg(p,vec2(-.62,y),vec2(0.,y-.62),.13));
            d=min(d,sdSeg(p,vec2(0.,y-.62),vec2(.62,y),.13));
        }
        return d;
    }
    if(t==13){                                  // 加宽：横向展开
        float d=sdSeg(p,vec2(-.72,0.),vec2(.72,0.),.11);
        d=min(d,sdSeg(p,vec2(.72,0.),vec2(.34,.40),.11));
        d=min(d,sdSeg(p,vec2(.72,0.),vec2(.34,-.40),.11));
        d=min(d,sdSeg(p,vec2(-.72,0.),vec2(-.34,.40),.11));
        d=min(d,sdSeg(p,vec2(-.72,0.),vec2(-.34,-.40),.11));
        return d;
    }
    float d=1e3;                                // 变窄：两个向内收拢的箭头
    d=min(d,sdSeg(p,vec2(-.80,0.),vec2(-.26,0.),.11));
    d=min(d,sdSeg(p,vec2(-.26,0.),vec2(-.56,.36),.11));
    d=min(d,sdSeg(p,vec2(-.26,0.),vec2(-.56,-.36),.11));
    d=min(d,sdSeg(p,vec2(.80,0.),vec2(.26,0.),.11));
    d=min(d,sdSeg(p,vec2(.26,0.),vec2(.56,.36),.11));
    d=min(d,sdSeg(p,vec2(.26,0.),vec2(.56,-.36),.11));
    return d;
}

void main(){
    vec3 col=vMeta.rgb;
    float kind=vMeta.a;
    float d;
    float coreGain=.92;     // 实体亮度
    float glowGain=1.;      // 光晕强度倍率
    float fade=1.;

    if(kind>9.5){                     // 技能砖标志：白色，靠形状区分类型
        float gd=itemGlyph(vP,int(kind+.5));
        float gaa=max(fwidth(gd),1e-4);
        float gcore=1.-smoothstep(-gaa,gaa,gd);
        float gglow=exp(-max(gd,0.)*7.5)*.16;
        o=vec4(col,clamp(gcore*.95+gglow,0.,1.));
        return;
    }
    if(kind>6.5){                     // 弱音砖在小尺寸下也保留清晰的轮廓
        float edge=roundBox(vP,vec2(.96,.90),.22);
        float aa=max(fwidth(edge)*.5,.001);
        float face=1.-smoothstep(-aa,aa,edge);
        float stroke=smoothstep(-aa,aa,edge+max(.28,aa*2.));
        o=vec4(mix(col,vec3(.85,.91,1.),.24),face*mix(.14,.72,stroke));
        return;
    }else if(kind>4.5){               // 侧旋光环
        d=abs(length(vP)-.72)-.055;
        fade=.45; glowGain=1.3; coreGain=1.;
    }else if(kind>.5 && kind<1.5){    // 球
        d=length(vP)-1.;
        glowGain=.50; coreGain=1.;
    }else if(kind>3.5){               // 拖尾
        d=length(vP)-1.;
        fade=.55; glowGain=.14; coreGain=.85;
    }else if(kind>2.5 && kind<3.5){   // 命中冲击波
        d=abs(length(vP)-.72)-.045;
        fade=.55; glowGain=.9; coreGain=1.;
    }else if(kind>1.5 && kind<2.5){   // 大本营（挡板）
        d=roundBox(vP,vec2(1.,.62),.30);
        glowGain=.28; coreGain=1.;
    }else{                            // 砖块
        d=roundBox(vP,vec2(1.,.86),.26);
        glowGain=.26; coreGain=1.;
    }

    float aa=max(fwidth(d),1e-4);
    // 实体：1px 过渡，边缘干净利落
    float core=1.-smoothstep(-aa,aa,d);
    // 玻璃细亮边：紧贴边缘内侧的一圈高光，是"精致"的主要来源
    float rim=smoothstep(-aa*3.2,-aa*0.6,d)-smoothstep(-aa*0.6,aa*0.2,d);
    rim=clamp(rim,0.,1.)*core;
    // 光晕收紧：近处一档 + 远处极淡一档，幅度远低于之前，避免叠加成白团
    float dd=max(d,0.);
    float glow=(exp(-dd*9.0)*.12+exp(-dd*2.6)*.028)*glowGain;

    if(kind>.5 && kind<1.5){
        // 球：小范围白热核心 + 彩色边缘，看起来像发光体而不是白球
        float hot=1.-smoothstep(0.,.62,length(vP));
        col=mix(col,vec3(1.),hot*.45);
        col=mix(col,vec3(1.),rim*.35);
    }else if(kind>1.5 && kind<2.5){
        // 挡板：竖向渐变 + 顶部镜面线，做出实体感
        col*=mix(.72,1.22,smoothstep(-.7,.7,-vP.y));
        col+=vec3(.35)*rim*.45*smoothstep(.1,.9,-vP.y);
    }else if(kind<=.5){
        // 砖块：上亮下暗的面 + 顶部窄高光 + 底部内阴影，避免纸片感
        col*=mix(.66,1.16,smoothstep(.9,-.9,vP.y));
        float topEdge=(1.-smoothstep(0.,.16,abs(vP.y-.74)))*smoothstep(.9,.2,abs(vP.x));
        col+=vec3(.30,.32,.34)*topEdge*.55;
        float botShade=(1.-smoothstep(.0,.28,vP.y+.86))*.5;
        col*=1.-botShade*.35;
        col+=rim*vec3(.22,.24,.26)*.6;
    }else{
        col+=rim*.5;
    }

    // 加法混合下 alpha 即强度；rim 单独加一点，保证边缘锐但不过曝
    float a=clamp(core*coreGain+glow+rim*.35,0.,1.)*fade;
    o=vec4(col,a);
}
