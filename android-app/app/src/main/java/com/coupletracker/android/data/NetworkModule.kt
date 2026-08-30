package com.coupletracker.android.data

import com.coupletracker.android.BuildConfig
import io.socket.client.IO
import io.socket.client.Socket
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

/**
 * 网络模块单例：OkHttpClient / Retrofit / Socket.IO
 * Socket.IO 认证方式：handshake.auth.token（与后端一致）
 *
 * ⚠️ 服务器地址来源（优先级从高到低）：
 *   1. DataStore 里用户在「设置页」改过的地址
 *   2. BuildConfig.DEFAULT_API_BASE（打 APK 时写死的局域网 IP）
 *   这样换网络/换电脑时只需在 APP 内改一次地址，不用重新打包。
 */
object NetworkModule {

    private lateinit var retrofit: Retrofit
    lateinit var api: ApiService
        private set
    private var socket: Socket? = null

    /** 当前生效的后端地址（Retrofit baseUrl 与 Socket.IO 都用它） */
    @Volatile private var currentApiBase: String = BuildConfig.DEFAULT_API_BASE

    /** 模拟器 / 回环地址集合，真机上这些地址无效 → 自动回退到 BuildConfig 默认值 */
    private val BAD_HOSTS = setOf("10.0.2.2", "localhost", "127.0.0.1", "0.0.0.0")

    private fun isLikelyEmulatorUrl(url: String): Boolean {
        val host = url.trim().trimEnd('/')
            .removePrefix("http://").removePrefix("https://")
            .split(':').firstOrNull().orEmpty()
        return host in BAD_HOSTS
    }

    /**
     * 必须在 UserRepository.init() 之后、首次发请求之前调用。
     * 从 DataStore 读取用户上次保存的地址；如果存的是模拟器残留地址，自动回退到 BuildConfig 默认值。
     */
    fun init() {
        val (api, web) = runBlocking {
            val rawApi = runCatching { UserRepository.get().getApiBase() }.getOrDefault(BuildConfig.DEFAULT_API_BASE)
            val rawWeb = runCatching { UserRepository.get().getWebBase() }.getOrDefault(BuildConfig.DEFAULT_WEB_BASE)
            // 模拟器残留地址清理（覆盖安装旧 APK 后 DataStore 会保留旧值）
            val fixedApi = if (isLikelyEmulatorUrl(rawApi)) BuildConfig.DEFAULT_API_BASE else rawApi
            val fixedWeb = if (isLikelyEmulatorUrl(rawWeb)) BuildConfig.DEFAULT_WEB_BASE else rawWeb
            if (fixedApi != rawApi || fixedWeb != rawWeb) {
                // 写回清理后的值，下次启动就不会再命中旧值了
                UserRepository.get().setApiBase(fixedApi)
                UserRepository.get().setWebBase(fixedWeb)
            }
            fixedApi to fixedWeb
        }
        currentApiBase = api
        rebuildRetrofit()
    }

    /** 用户在设置页改了地址后调用：重建 Retrofit + 重连 Socket */
    suspend fun updateServer(apiBase: String, webBase: String) {
        UserRepository.get().setApiBase(apiBase)
        UserRepository.get().setWebBase(webBase)
        currentApiBase = apiBase.trim().trimEnd('/')
        rebuildRetrofit()
        disconnectSocket()
    }

    private fun rebuildRetrofit() {
        val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .addInterceptor(HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BASIC
            })
            .addInterceptor(authInterceptor())
            .build()

        retrofit = Retrofit.Builder()
            .baseUrl(currentApiBase)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
        api = retrofit.create(ApiService::class.java)
    }

    /** 给 MainActivity 取 WebView 地址用 */
    suspend fun getWebBase(): String = UserRepository.get().getWebBase()
    fun getApiBase(): String = currentApiBase

    private fun authInterceptor() = Interceptor { chain ->
        val original: Request = chain.request()
        val token = runCatching {
            runBlocking { UserRepository.get().getToken() }
        }.getOrNull()
        val builder = original.newBuilder()
        if (!token.isNullOrBlank()) builder.header("Authorization", "Bearer $token")
        chain.proceed(builder.method(original.method, original.body).build())
    }

    fun getSocket(): Socket {
        if (socket == null || !socket!!.connected()) {
            runCatching { socket?.disconnect() }
            val token = runCatching {
                runBlocking { UserRepository.get().getToken() }
            }.getOrNull().orEmpty()
            val opts = IO.Options().apply {
                forceNew = true
                transports = arrayOf("websocket", "polling")
                // ✅ 用 handshake.auth.token（后端 socket.handshake.auth.token）
                auth = mapOf("token" to token)
                reconnection = true
                reconnectionDelay = 3000
            }
            socket = IO.socket(currentApiBase.trimEnd('/'), opts)
        }
        return socket!!
    }

    fun disconnectSocket() {
        runCatching { socket?.disconnect() }
        socket = null
    }
}
