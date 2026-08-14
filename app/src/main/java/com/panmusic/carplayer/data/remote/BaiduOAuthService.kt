package com.panmusic.carplayer.data.remote

import com.panmusic.carplayer.model.DeviceCodeResponse
import com.panmusic.carplayer.model.TokenResponse
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Query

/**
 * 百度 OAuth 授权接口（独立服务，独立 base URL）
 */
interface BaiduOAuthService {

    @GET(ApiConstants.DEVICE_CODE_PATH)
    suspend fun getDeviceCode(
        @Query("client_id") clientId: String,
        @Query("response_type") responseType: String = "device_code",
        @Query("scope") scope: String = "basic,netdisk"
    ): Response<DeviceCodeResponse>

    @GET(ApiConstants.TOKEN_PATH)
    suspend fun getTokenByDeviceCode(
        @Query("grant_type") grantType: String = "device_token",
        @Query("code") deviceCode: String,
        @Query("client_id") clientId: String,
        @Query("client_secret") clientSecret: String
    ): Response<TokenResponse>

    @GET(ApiConstants.TOKEN_PATH)
    suspend fun refreshToken(
        @Query("grant_type") grantType: String = "refresh_token",
        @Query("refresh_token") refreshToken: String,
        @Query("client_id") clientId: String,
        @Query("client_secret") clientSecret: String
    ): Response<TokenResponse>
}
