package com.coupletracker.android.data

import com.google.gson.annotations.SerializedName
import com.google.gson.JsonObject
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Query

// ============================================================
// 🔐 Supabase Auth Service  →  /auth/v1/*
// ============================================================
interface AuthService {

    /** 邮箱密码登录（Supabase GoTrue /token 要求 JSON body，grant_type 放 query） */
    @POST("token?grant_type=password")
    suspend fun signIn(
        @Body body: SignInBody
    ): Response<SupabaseAuthResp>

    /** 登出 */
    @POST("logout")
    suspend fun logout(): Response<Unit>
}

/** Supabase /token?grant_type=password 登录请求体（JSON 格式，旧版 FormUrlEncoded 已被 GoTrue 弃用） */
data class SignInBody(
    val email: String,
    val password: String
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
// 🎯 Supabase RPC Service  →  /rest/v1/rpc/*  (PostgREST function calls)
//   register_user RPC 是一个 SECURITY DEFINER 的 SQL 函数，
//   直接 INSERT auth.users + email_confirmed_at = now()，**永不发邮件**，
//   因此永远不会触发 429 over_email_send_rate_limit。
// ============================================================
interface RpcService {

    /** @see public.register_user(p_username, p_password, p_nickname, p_gender) returns jsonb
     *  Schema cache 已确认参数签名就是「带 p_ 前缀」，正好匹配 @SerializedName("p_xxx")
     *  SECURITY DEFINER，直接 INSERT public.profiles + bcrypt 密码哈希，**永不触发邮件** */
    @POST("register_user")
    suspend fun registerUser(
        @Body body: RegisterUserReq
    ): Response<RegisterUserResp>

    /** @see public.verify_login(p_username, p_password) returns jsonb
     *  Schema cache 已确认参数签名就是「带 p_ 前缀」，正好匹配 @SerializedName("p_xxx")
     *  用 crypt() 手动验证密码，返回 user_id + profile，**完全绕过 GoTrue** */
    @POST("verify_login")
    suspend fun verifyLogin(
        @Body body: VerifyLoginReq
    ): Response<VerifyLoginResp>

    /** @see public.pair_by_code(p_my_id uuid, p_their_code text) returns jsonb
     *  把两个人的 profile.couple_code 改成同一个值，极简配对，无需操作 couples 表
     *  返回：{ok: bool, reason?, couple_code?, their_id?, their_nickname?} */
    @POST("pair_by_code")
    suspend fun pairByCode(
        @Body body: PairByCodeReq
    ): Response<PairByCodeResp>
}

data class RegisterUserReq(
    @SerializedName("p_username") val username: String,
    @SerializedName("p_password") val password: String,
    @SerializedName("p_nickname") val nickname: String,
    @SerializedName("p_gender") val gender: String
)

/** RPC 返回结构，对应 register_user() 返回的 jsonb */
data class RegisterUserResp(
    val user_id: String? = null,
    val couple_code: String? = null,
    val email: String? = null,
    val username: String? = null,
    val nickname: String? = null,
    val gender: String? = null,
    val avatar: String? = null
)

data class VerifyLoginReq(
    @SerializedName("p_username") val username: String,
    @SerializedName("p_password") val password: String
)

data class VerifyLoginResp(
    val user_id: String? = null,
    /** 原始 profile JSON — cache 里旧函数返回 f1~f8 格式，新函数返回命名字段，LoginActivity 内手动兼容 */
    val profile: JsonObject? = null
)

/** @see public.pair_by_code(p_my_id, p_their_code) 请求体 —— 注意函数参数名必须带 p_ 前缀 */
data class PairByCodeReq(
    @SerializedName("p_my_id") val myId: String,
    @SerializedName("p_their_code") val theirCode: String
)

/** pair_by_code RPC 返回体 */
data class PairByCodeResp(
    val ok: Boolean = false,
    val reason: String? = null,
    val couple_code: String? = null,
    val their_id: String? = null,
    val their_nickname: String? = null
)

/** PostgREST RPC 错误格式（HTTP 4xx / 5xx） */
data class RpcErrorResp(
    val code: String? = null,
    val message: String? = null,
    val hint: String? = null,
    val details: String? = null
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

    /** 查某用户的 APP 使用（旧接口，无日期过滤，limit 100 仅用于排查） */
    @GET("app_usage")
    suspend fun getAppUsage(
        @Query("user_id") userId: String,
        @Query("order") order: String = "created_at.desc",
        @Query("limit") limit: Int = 100
    ): Response<List<AppUsageRow>>

    /** 查某用户在指定日期范围内的 APP 使用记录
     *  PostgREST 过滤：created_at=gte.<iso> AND created_at=lt.<iso>
     *  两个 @Query 同名 → 生成 created_at=gte..&created_at=lt.. 两条查询参数，PostgREST 解释为 AND
     *  limit 提到 1000：一天 60s 一行最坏 1440 行，实际有前台活动才记录，1000 够用 */
    @GET("app_usage")
    suspend fun getAppUsageInRange(
        @Query("user_id") userId: String,
        @Query("created_at") createdAtGte: String? = null,
        @Query("created_at") createdAtLt: String? = null,
        @Query("order") order: String = "created_at.desc",
        @Query("limit") limit: Int = 1000
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
    /** ⚠️ 必须可空：未配对用户传 null 才能避免 couples 外键约束（FK）被拒绝（409）
     *  旧代码 couple_id: String = "" 是非空类型，传 null 直接编译失败 */
    val couple_id: String? = null,
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
    /** ⚠️ 必须可空（同上），未配对用户也能正常上报 */
    val couple_id: String? = null,
    val package_name: String = "",
    val app_name: String? = null,
    val category: String? = null,
    val usage_seconds: Int = 0,
    val window_start: String? = null,
    val created_at: String? = null
)
