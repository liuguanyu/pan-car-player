package com.baidu.carplayer.visualizer;

import android.opengl.GLES30;
import android.util.Log;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/** GLES 3.0 月夜海浪渲染器：GPU 生成波面、海雾、月光和泡沫。 */
public final class WaveGlRenderer implements android.opengl.GLSurfaceView.Renderer {
    private static final String TAG="WaveGl"; private static final byte[] EMPTY=new byte[0];
    public interface FftSource { byte[] latest(); int epoch(); }
    public interface TempoSource { float periodMs(); }
    public interface IdleListener { void onIdle(); }
    private volatile FftSource source; private volatile TempoSource tempo; private volatile IdleListener idle; private volatile boolean active;
    private boolean failed; private int epoch=Integer.MIN_VALUE,w=1,h=1,program,vao,tex,head; private long lastNs,lastFft; private float time,energy,bass,kick,previous;
    private int uTime,uEnergy,uBass,uKick,uAspect,uPeriod,uHistory,uHead;
    public void setFftSource(FftSource s){source=s;} public void setTempoSource(TempoSource s){tempo=s;} public void setIdleListener(IdleListener l){idle=l;} public void setWantActive(boolean v){active=v;}
    @Override public void onSurfaceCreated(GL10 x,EGLConfig c){try{program=link(WaveGlShaders.VERT,WaveGlShaders.FRAG);}catch(RuntimeException e){Log.e(TAG,"shader",e);failed=true;return;} uTime=GLES30.glGetUniformLocation(program,"uTime");uEnergy=GLES30.glGetUniformLocation(program,"uEnergy");uBass=GLES30.glGetUniformLocation(program,"uBass");uKick=GLES30.glGetUniformLocation(program,"uKick");uAspect=GLES30.glGetUniformLocation(program,"uAspect");uPeriod=GLES30.glGetUniformLocation(program,"uPeriod");uHistory=GLES30.glGetUniformLocation(program,"uHistory");uHead=GLES30.glGetUniformLocation(program,"uHistoryHead");int[] a=new int[1];GLES30.glGenVertexArrays(1,a,0);vao=a[0];GLES30.glBindVertexArray(vao);GLES30.glGenTextures(1,a,0);tex=a[0];GLES30.glBindTexture(GLES30.GL_TEXTURE_2D,tex);GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D,GLES30.GL_TEXTURE_MIN_FILTER,GLES30.GL_LINEAR);GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D,GLES30.GL_TEXTURE_MAG_FILTER,GLES30.GL_LINEAR);GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D,0,GLES30.GL_R32F,128,48,0,GLES30.GL_RED,GLES30.GL_FLOAT,null);GLES30.glEnable(GLES30.GL_BLEND);GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA,GLES30.GL_ONE);}
    @Override public void onSurfaceChanged(GL10 x,int w,int h){this.w=Math.max(1,w);this.h=Math.max(1,h);GLES30.glViewport(0,0,this.w,this.h);}
    @Override public void onDrawFrame(GL10 x){if(failed)return;long n=System.nanoTime();float dt=lastNs==0?.016f:Math.min(.05f,(n-lastNs)/1e9f);lastNs=n;time+=dt;update();kick*=(float)Math.pow(.90,dt*60);energy+=(((bass+previous)*.5f>.003f?1f:0f)-energy)*.18f;GLES30.glClearColor(.004f,.006f,.012f,1);GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT);GLES30.glUseProgram(program);GLES30.glBindVertexArray(vao);GLES30.glActiveTexture(GLES30.GL_TEXTURE0);GLES30.glBindTexture(GLES30.GL_TEXTURE_2D,tex);GLES30.glUniform1i(uHistory,0);GLES30.glUniform1i(uHead,head);GLES30.glUniform1f(uTime,time);GLES30.glUniform1f(uEnergy,energy);GLES30.glUniform1f(uBass,bass);GLES30.glUniform1f(uKick,kick);GLES30.glUniform1f(uAspect,w/(float)h);GLES30.glUniform1f(uPeriod,tempo==null?500:tempo.periodMs());GLES30.glDrawArrays(GLES30.GL_TRIANGLES,0,3);if(!active&&energy<.004f&&kick<.01f&&idle!=null)idle.onIdle();}
    private void update(){FftSource fs=source;byte[] f=fs==null?EMPTY:fs.latest();if(fs!=null&&epoch!=fs.epoch()){epoch=fs.epoch();head=0;}float[] r=AudioAnalyzer.fftToMagnitudes(f,256), row=new float[128];float total=0,low=0;for(int i=0;i<128;i++){float v=i<r.length?r[i]:0;row[i]=v;total+=v;if(i<32)low+=v;}bass+=(Math.min(1,low/32*2.2f)-bass)*.3f;previous=Math.min(1,total/128*3);if(previous>energy+.04f)kick=Math.min(1.2f,kick+.5f);long ms=System.currentTimeMillis();if(ms-lastFft>45){lastFft=ms;head=(head+1)%48;FloatBuffer b=ByteBuffer.allocateDirect(128*4).order(ByteOrder.nativeOrder()).asFloatBuffer();b.put(row).position(0);GLES30.glBindTexture(GLES30.GL_TEXTURE_2D,tex);GLES30.glTexSubImage2D(GLES30.GL_TEXTURE_2D,0,0,head,128,1,GLES30.GL_RED,GLES30.GL_FLOAT,b);}}
    private static int shader(int t,String s){int x=GLES30.glCreateShader(t);GLES30.glShaderSource(x,s);GLES30.glCompileShader(x);int[] ok=new int[1];GLES30.glGetShaderiv(x,GLES30.GL_COMPILE_STATUS,ok,0);if(ok[0]==0)throw new RuntimeException(GLES30.glGetShaderInfoLog(x));return x;}
    private static int link(String v,String f){int a=shader(GLES30.GL_VERTEX_SHADER,v),b=shader(GLES30.GL_FRAGMENT_SHADER,f),p=GLES30.glCreateProgram();GLES30.glAttachShader(p,a);GLES30.glAttachShader(p,b);GLES30.glLinkProgram(p);int[] ok=new int[1];GLES30.glGetProgramiv(p,GLES30.GL_LINK_STATUS,ok,0);if(ok[0]==0)throw new RuntimeException(GLES30.glGetProgramInfoLog(p));return p;}
}
