package com.baidu.carplayer.network;

import com.baidu.carplayer.model.DeviceCodeResponse;
import com.baidu.carplayer.model.DownloadLinkResponse;
import com.baidu.carplayer.model.FileListResponse;
import com.baidu.carplayer.model.TokenResponse;

import java.util.Map;

import androidx.annotation.Nullable;

import retrofit2.Call;
import retrofit2.http.GET;
import retrofit2.http.Header;
import retrofit2.http.Query;
import retrofit2.http.QueryMap;

/**
 * 百度网盘API服务接口
 * 针对车载播放器简化了API接口
 */
public interface BaiduPanService {
    
    /**
     * 获取设备码
     */
    @GET(ApiConstants.ENDPOINT_DEVICE_CODE)
    Call<DeviceCodeResponse> getDeviceCode(
            @Query("client_id") String clientId,
            @Query("scope") String scope,
            @Query("response_type") String responseType
    );
    
    /**
     * 轮询设备码状态获取token
     */
    @GET(ApiConstants.ENDPOINT_TOKEN)
    Call<TokenResponse> getTokenByDeviceCode(
            @Query("grant_type") String grantType,
            @Query("code") String deviceCode,
            @Query("client_id") String clientId,
            @Query("client_secret") String clientSecret
    );
    
    /**
     * 刷新token
     */
    @GET(ApiConstants.ENDPOINT_TOKEN)
    Call<TokenResponse> refreshToken(
            @Query("grant_type") String grantType,
            @Query("refresh_token") String refreshToken,
            @Query("client_id") String clientId,
            @Query("client_secret") String clientSecret
    );
    
    /**
     * 获取文件列表
     */
    @GET(ApiConstants.ENDPOINT_FILE)
    Call<FileListResponse> getFileList(
            @Query("method") String method,
            @Query("access_token") String accessToken,
            @Query("dir") String dir,
            @Query("order") String order,
            @Query("desc") int desc,
            @Query("start") int start,
            @Query("limit") int limit,
            @Query("web") int web,
            @Query("folder") int folder,
            @Query("showempty") int showempty
    );
    
    /**
     * 获取文件下载链接。
     *
     * @param silent 非空时表示这是后台静默请求（预取）：失败不重试，避免退避重试
     *               长时间占用连接与线程而拖慢前台播放的加载。
     */
    @GET(ApiConstants.ENDPOINT_MULTIMEDIA)
    Call<DownloadLinkResponse> getFileDownloadLink(
            @Query("method") String method,
            @Query("access_token") String accessToken,
            @Query("fsids") String fsids,
            @Query("dlink") int dlink,  // 添加 dlink 参数，1 表示请求下载链接
            @Header(RetryInterceptor.SILENT_HEADER) @Nullable String silent
    );

    /**
     * 搜索文件
     */
    @GET(ApiConstants.ENDPOINT_FILE)
    Call<Map<String, Object>> search(
            @Query("method") String method,
            @Query("access_token") String accessToken,
            @Query("key") String key,
            @Query("dir") String dir
    );
}