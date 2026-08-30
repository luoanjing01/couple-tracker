package com.coupletracker.android.data

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Query

// ============================================================
// 🔐 Supabase Auth Service  →  /auth/v1/*
// ============================================================
interface AuthService {

    /** 注册：Supabase 自动生成 email + password 用户，
     *   并通过 handle_new_user() trigger 创建 profiles 记录 */
    @POST("signup")
    suspend fun signUp(
        @Body body: SignUpBody,
        @Header("Authorization") apikey: String = ""  // signup 不需要 auth header
    ): Response<SupabaseAuthResp>

    /** 邮箱密码登录 */
    @FormUrlEncoded
    @POST("token?grant_type=password")
    suspend fun signIn(
        @Field("email") email: String,
        @Field("password") password: String
    ): Response<SupabaseAuthResp>

    /** 登出 */
    @POST("logout")
    suspend fun logout(): Response<Unit>
}

/** Supabase 注册请求体（email 当用户名用，真实用户名放 data）*/
data class SignUpBody(
    val email: String,
    val password: String,
    val data: SignUpMetadata? = null
)

data class SignUpMetadata(
    val username: String? = null,
    val nickname: String? = null
)

/** Supabase Auth 返回的会话 */
data class SupabaseAuthResp(
    val access_token: String? = null,
    val token_type: String? = null,
    val expires_in: Int? = null,
    val refresh_token: String? = null,
    val user: SupabaseUser? = null
)

data class SupabaseUser(
    val id: String? = null,
    val email: String? = null,
    val user_metadata: Map<String, Any>? = null
)

// ============================================================
// 📊 Supabase REST Service  →  /rest/v1/*  (PostgREST)
// ============================================================
interface RestService {

    // --- profiles ---

    /** 查自己的 profile（返回 list，取第一个）*/
    @GET("profiles")
    suspend fun getProfile(
        @Query("select") select: String = "*",
        @Query("id") id: String? = null,
        @Query("username") username: String? = null,
        @Query("couple_code") coupleCode: String? = null
    ): Response<List<Profile>>

    /** 更新 profile */
    @PATCH("profiles")
    suspend fun updateProfile(
        @Query("id") id: String,
        @Body body: Map<String, Any>
    ): Response<Unit>

    // --- couples ---

    /** 创建情侣（用自己的 profile 数据）*/
    @POST("couples")
    suspend fun createCouple(
        @Body body: Map<String, Any>
    ): Response<Couple>

    /** 查 couple（按 code 或 user_a）*/
    @GET("couples")
    suspend fun getCouple(
        @Query("select") select: String = "*",
        @Query("code") code: String? = null,
        @Query("user_a") userA: String? = null
    ): Response<List<Couple>>

    /** 更新 couple（user_b 加入）*/
    @PATCH("couples")
    suspend fun updateCouple(
        @Query("id") id: String,
        @Body body: Map<String, Any>
    ): Response<Unit>

    // --- locations ---

    /** 上报位置 */
    @POST("locations")
    suspend fun reportLocation(
        @Body body: LocationRow
    ): Response<Unit>

    /** 查情侣的最近位置（limit 1 拿最新的）*/
    @GET("locations")
    suspend fun getCoupleLocations(
        @Query("couple_id") coupleId: String,
        @Query("order") order: String = "created_at.desc",
        @Query("limit") limit: Int = 20
    ): Response<List<LocationRow>>

    // --- app_usage ---

    /** 上报 APP 时长 */
    @POST("app_usage")
    suspend fun reportAppUsage(
        @Body body: AppUsageRow
    ): Response<Unit>

    /** 查某天的 APP 使用 */
    @GET("app_usage")
    suspend fun getAppUsage(
        @Query("user_id") userId: String,
        @Query("order") order: String = "created_at.desc",
        @Query("limit") limit: Int = 100
    ): Response<List<AppUsageRow>>
}

// ============================================================
// 📦 Supabase 表对应的 data class（PostgREST 返回格式）
// ============================================================

data class Profile(
    val id: String = "",
    val username: String = "",
    val nickname: String = "",
    val avatar: String = "",
    val gender: String = "unknown",
    val couple_code: String = "",
    val couple_id: String? = null,
    val created_at: String? = null
)

data class Couple(
    val id: String = "",
    val code: String = "",
    val user_a: String = "",
    val user_b: String? = null,
    val created_at: String? = null
)

data class LocationRow(
    val id: String? = null,
    val user_id: String = "",
    val couple_id: String = "",
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val accuracy: Double? = null,
    val speed: Double? = null,
    val battery_level: Int? = null,
    val is_moving: Boolean = false,
    val created_at: String? = null
)

data class AppUsageRow(
    val id: String? = null,
    val user_id: String = "",
    val couple_id: String = "",
    val package_name: String = "",
    val app_name: String? = null,
    val category: String? = null,
    val usage_seconds: Int = 0,
    val window_start: String? = null,
    val created_at: String? = null
)
