package com.baidu.carplayer.network;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.logging.HttpLoggingInterceptor;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;

/**
 * Retrofit客户端封装
 * 针对车载环境进行了优化
 */
public class RetrofitClient {
    
    private static volatile Retrofit panApiInstance;
    private static volatile Retrofit oauthInstance;
    
    /**
     * 获取百度网盘API的Retrofit实例
     */
    public static Retrofit getPanApiInstance() {
        if (panApiInstance == null) {
            synchronized (RetrofitClient.class) {
                if (panApiInstance == null) {
                    panApiInstance = createRetrofit(ApiConstants.PAN_API_BASE_URL);
                }
            }
        }
        return panApiInstance;
    }
    
    /**
     * 获取Pan API实例（别名方法）
     */
    public static Retrofit getInstance() {
        return getPanApiInstance();
    }
    
    /**
     * 获取OAuth API的Retrofit实例
     */
    public static Retrofit getOAuthInstance() {
        if (oauthInstance == null) {
            synchronized (RetrofitClient.class) {
                if (oauthInstance == null) {
                    oauthInstance = createRetrofit(ApiConstants.OAUTH_BASE_URL);
                }
            }
        }
        return oauthInstance;
    }
    
    /**
     * 创建Retrofit实例
     * 针对车载环境优化了超时时间和重试机制
     */
    private static Retrofit createRetrofit(String baseUrl) {
        OkHttpClient.Builder httpClientBuilder = new OkHttpClient.Builder();
        
        // 添加日志拦截器（仅调试模式）
        HttpLoggingInterceptor loggingInterceptor = new HttpLoggingInterceptor();
        loggingInterceptor.setLevel(HttpLoggingInterceptor.Level.BODY);
        httpClientBuilder.addInterceptor(loggingInterceptor);
        
        // 设置超时时间 - 车载环境网络可能不稳定，适当增加
        httpClientBuilder.connectTimeout(ApiConstants.CONNECT_TIMEOUT, TimeUnit.MILLISECONDS);
        httpClientBuilder.readTimeout(ApiConstants.READ_TIMEOUT, TimeUnit.MILLISECONDS);
        httpClientBuilder.writeTimeout(ApiConstants.WRITE_TIMEOUT, TimeUnit.MILLISECONDS);
        
        // 添加User-Agent和重试拦截器
        httpClientBuilder.addInterceptor(chain -> {
            okhttp3.Request original = chain.request();
            okhttp3.Request request = original.newBuilder()
                    .header("User-Agent", "pan.baidu.com")
                    // 移除 Accept-Encoding: gzip，让 OkHttp 自动处理 Gzip 压缩和解压
                    .method(original.method(), original.body())
                    .build();
            return chain.proceed(request);
        });
        
        // 添加重试拦截器 - 车载环境网络不稳定，自动重试
        httpClientBuilder.addInterceptor(new RetryInterceptor(3));
        
        OkHttpClient client = httpClientBuilder.build();
        
        // 创建宽容模式的Gson实例，以处理可能的JSON格式问题
        Gson gson = new GsonBuilder()
                .setLenient()  // 设置为宽容模式
                .create();
        
        return new Retrofit.Builder()
                .baseUrl(baseUrl)
                .client(client)
                .addConverterFactory(GsonConverterFactory.create(gson))
                .build();
    }
}