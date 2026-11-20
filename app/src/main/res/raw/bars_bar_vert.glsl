#version 300 es
uniform mat4 uVP;
uniform vec4 uHeights[16];   // 64 根柱高打包为 16 个 vec4
uniform float uSide;         // 相机在右侧为 -1（看到左侧面），左侧为 +1
uniform float uMirror;       // 1 = 画倒影（高度取负）
uniform float uBarPitch;     // 柱距（世界单位），由 CPU 按投影反解
uniform float uBarW;         // 柱宽
uniform float uBarH;         // 柱高上限
uniform float uRipple;       // 倒影的横向涟漪幅度
uniform float uBlurOffset;   // 倒影的屏幕空间垂直偏移（NDC），用于多重采样模糊
out float vT;    // 柱序号 0..1（调色板参数）
out float vY;    // 柱内高度 0..1
out float vFace; // 0 正面 / 1 顶面 / 2 侧面
out float vWY;   // 世界高度（倒影淡出用）
out float vDepth;

const int BARS = 64;

void main(){
    int id=gl_VertexID;
    int face=id/6;
    int tri=(id/3)%2;
    int vert=id%3;
    // 面内二维参数：每个面用两个三角形拼成
    float u=(vert==1)||(tri==0&&vert==2)?1.:0.;
    float v=(vert==2)||(tri==1&&vert==1)?1.:0.;
    int bar=gl_InstanceID;
    float H=clamp(uHeights[bar/4][bar%4]*uBarH,.03,uBarH);
    if(uMirror>.5) H=-H;
    float W=uBarW;
    float D=uBarPitch*.65;
    // bar 0（低频）在左侧，与 Canvas 版的频率方向一致
    float cx=(float(BARS-1)*.5-float(bar))*uBarPitch;
    float x,y,z;
    vY=0.;
    if(face==0){ x=cx+(u-.5)*W; y=v*H; z=.5*D; vY=v; }         // 正面
    else if(face==1){ x=cx+(u-.5)*W; y=H; z=(v-.5)*D; vY=1.; }  // 顶面
    else { x=cx+uSide*.5*W; y=v*H; z=(u-.5)*D; vY=v; }          // 侧面
    vT=float(bar)/float(BARS-1);
    vFace=float(face);
    vWY=abs(vY*H);
    vDepth=z;

    // 倒影：水中倒影会随距离产生横向摆动，越深（越高）摆得越多
    if(uMirror>.5){
        x+=sin(vWY*5.5+float(bar)*.83+uSide)*uRipple*vWY;
    }
    gl_Position=uVP*vec4(x,y,z,1.);
    // 多重采样：沿屏幕 Y 方向偏移，与低透明度叠加得到柔和边缘
    if(uMirror>.5){
        gl_Position.y+=uBlurOffset*gl_Position.w;
    }
}
