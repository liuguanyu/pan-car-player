package com.baidu.carplayer.network;

/**
 * API常量定义
 */
public class ApiConstants {
    
    // 百度网盘API基础URL
    public static final String PAN_API_BASE_URL = "https://pan.baidu.com/rest/2.0/";
    public static final String OAUTH_BASE_URL = "https://openapi.baidu.com/oauth/2.0/";
    public static final String PCS_BASE_URL = "https://d.pcs.baidu.com/rest/2.0/pcs/";
    
    // API端点
    public static final String ENDPOINT_FILE = "xpan/file";
    public static final String ENDPOINT_MULTIMEDIA = "xpan/multimedia";
    public static final String ENDPOINT_NAS = "xpan/nas";
    
    // OAuth端点
    public static final String ENDPOINT_AUTHORIZE = "authorize";
    public static final String ENDPOINT_TOKEN = "token";
    public static final String ENDPOINT_DEVICE_CODE = "device/code";
    public static final String ENDPOINT_REVOKE = "revoke";
    
    // 请求超时时间（毫秒）- 车载环境可能网络不稳定，适当增加超时时间
    public static final int CONNECT_TIMEOUT = 45000;
    public static final int READ_TIMEOUT = 45000;
    public static final int WRITE_TIMEOUT = 45000;
    
    // 轮询间隔（毫秒）- 车载环境适当增加轮询间隔
    public static final int POLLING_INTERVAL = 6000;
    public static final int MAX_POLLING_COUNT = 60;
}