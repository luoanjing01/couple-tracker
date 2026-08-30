package com.coupletracker.android.data

import com.coupletracker.android.data.model.*
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST

/**
 * 后端 REST API 接口，与 server.js 路由严格对应
 */
interface ApiService {

    // ===== 认证 =====
    @POST("/api/auth/register")
    suspend fun register(@Body body: RegisterRequest): Response<AuthRawResp>

    @POST("/api/auth/login")
    suspend fun login(@Body body: LoginRequest): Response<AuthRawResp>

    // 返回结构：{ user: {...}, partner: {...} }
    @GET("/api/user/me")
    suspend fun me(): Response<CoupleInfo>

    // ===== 情侣配对 =====
    @POST("/api/couples/pair-by-code")
    suspend fun pairByCode(@Body body: PairRequest): Response<Map<String, Any?>>

    // ===== 位置 =====
    @POST("/api/location/report")
    suspend fun reportLocation(@Body body: LocationRequest): Response<Map<String, Any?>>

    // ===== APP使用 =====
    @POST("/api/app-usage/foreground")
    suspend fun reportForeground(@Body body: ForegroundAppRequest): Response<Map<String, Any?>>

    @POST("/api/app-usage/heartbeat")
    suspend fun heartbeatApp(): Response<Map<String, Any?>>
}

/** 登录/注册返回的原始 JSON: { token, user } */
data class AuthRawResp(
    val token: String?,
    val user: UserInfo?,
    val error: String?,
    val message: String?
)
