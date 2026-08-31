package com.coupletracker.android.data

import com.coupletracker.android.BuildConfig
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 网络模块单例 — 对接 Supabase BaaS
 *
 * 两个 Retrofit：
 *   - authService : https://...supabase.co/auth/v1/   ← 登录/注册/会话
 *   - restService : https://...supabase.co/rest/v1/   ← 数据 CRUD（PostgREST）
 *
 * 每个请求自动带：apikey + Authorization: Bearer <jwt>
 */
object NetworkModule {

    private lateinit var authRetrofit: Retrofit
    private lateinit var restRetrofit: Retrofit
    private lateinit var rpcRetrofit: Retrofit

    /** Auth API（/auth/v1）*/
    lateinit var authService: AuthService
        private set

    /** REST API（/rest/v1） - 数据 CRUD（PostgREST table）*/
    lateinit var restService: RestService
        private set

    /** RPC API（/rest/v1/rpc/） - 调用 SECURITY DEFINER 函数（如 register_user）*/
    lateinit var rpcService: RpcService
        private set

    /** 最近一次位置上报状态（成功/错误明细），供「我的」页采集频率卡片底部显示 */
    val lastLocationReportStatus = MutableStateFlow("等待启动服务…")
    /** 最近一次 APP 使用上报状态 */
    val lastAppReportStatus = MutableStateFlow("等待启动服务…")
    val lastLocationReportStatusFlow = lastLocationReportStatus.asStateFlow()
    val lastAppReportStatusFlow = lastAppReportStatus.asStateFlow()

    private const val SUPABASE_URL = BuildConfig.SUPABASE_URL
    private const val SUPABASE_ANON_KEY = BuildConfig.SUPABASE_ANON_KEY

    fun init() {
        val logging = HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC }

        val authClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .addInterceptor(logging)
            .addInterceptor(supabaseHeaderInterceptor(addAuthHeader = false))
            .build()

        val restClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .addInterceptor(logging)
            .addInterceptor(supabaseHeaderInterceptor(addAuthHeader = true))
            .build()

        // RPC 也走 /rest/v1/rpc/，但和 REST 共用 base 可以，只是 path 加 "rpc/" 前缀
        // 为了清晰独立一个 Retrofit（注册时还没 token，用的是 anon key）
        val rpcClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .addInterceptor(logging)
            // RPC 调用：注册前没有 token（register_user 给 anon 调用），
            // 其他 RPC 之后可能需要 token，这里默认和 REST 一样优先带 token。
            .addInterceptor(supabaseHeaderInterceptor(addAuthHeader = true))
            .build()

        authRetrofit = Retrofit.Builder()
            .baseUrl("$SUPABASE_URL/auth/v1/")
            .client(authClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()

        restRetrofit = Retrofit.Builder()
            .baseUrl("$SUPABASE_URL/rest/v1/")
            .client(restClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()

        rpcRetrofit = Retrofit.Builder()
            .baseUrl("$SUPABASE_URL/rest/v1/rpc/")
            .client(rpcClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()

        authService = authRetrofit.create(AuthService::class.java)
        restService = restRetrofit.create(RestService::class.java)
        rpcService  = rpcRetrofit.create(RpcService::class.java)
    }

    /**
     * 给每个请求加 Supabase 必须的 header：
     *   apikey: <anon_key>
     *   Authorization: Bearer <jwt>  (addAuthHeader=true 且已登录时)
     */
    private fun supabaseHeaderInterceptor(addAuthHeader: Boolean) = Interceptor { chain ->
        val original: Request = chain.request()
        val builder = original.newBuilder()
            .header("apikey", SUPABASE_ANON_KEY)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")

        if (addAuthHeader) {
            val token = runCatching {
                runBlocking { UserRepository.get().getToken() }
            }.getOrNull()
            if (!token.isNullOrBlank()) {
                builder.header("Authorization", "Bearer $token")
            }
        }
        chain.proceed(builder.build())
    }

    fun getSupabaseUrl(): String = SUPABASE_URL
    fun getApiBase(): String = "$SUPABASE_URL/rest/v1"
    fun getWebBase(): String = SUPABASE_URL
}
