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

    /** Auth API（/auth/v1）*/
    lateinit var authService: AuthService
        private set

    /** REST API（/rest/v1）*/
    lateinit var restService: RestService
        private set

    private const val SUPABASE_URL = BuildConfig.SUPABASE_URL
    private const val SUPABASE_ANON_KEY = BuildConfig.SUPABASE_ANON_KEY

    fun init() {
        val logging = HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC }

        val authClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .addInterceptor(logging)
            .addInterceptor(supabaseHeaderInterceptor(isAuth = true))
            .build()

        val restClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .addInterceptor(logging)
            .addInterceptor(supabaseHeaderInterceptor(isAuth = false))
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

        authService = authRetrofit.create(AuthService::class.java)
        restService = restRetrofit.create(RestService::class.java)
    }

    /**
     * 给每个请求加 Supabase 必须的 header：
     *   apikey: <anon_key>
     *   Authorization: Bearer <jwt>  (已登录时)
     */
    private fun supabaseHeaderInterceptor(isAuth: Boolean) = Interceptor { chain ->
        val original: Request = chain.request()
        val builder = original.newBuilder()
            .header("apikey", SUPABASE_ANON_KEY)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")

        // Auth API 本身不带 Authorization（登录前没 token）
        // REST API 必须带
        if (!isAuth) {
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
