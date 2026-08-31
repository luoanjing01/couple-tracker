package com.coupletracker.android.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.coupletracker.android.BuildConfig
import com.coupletracker.android.data.model.UserInfo
import com.google.gson.Gson
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * 用户与配置持久化仓库（基于DataStore）
 * 负责：token、当前用户信息、后端地址的读写
 */
class UserRepository private constructor(private val context: Context) {

    private val gson = Gson()

    companion object {
        private val Context.store by preferencesDataStore(name = "couple_tracker_prefs")
        private val KEY_TOKEN = stringPreferencesKey("auth_token")
        private val KEY_USER = stringPreferencesKey("user_info_json")
        private val KEY_API_BASE = stringPreferencesKey("server_api_base")
        private val KEY_WEB_BASE = stringPreferencesKey("server_web_base")
        // 采集频率（秒）—— 默认位置 8s、APP 4s，降低卡顿
        private val KEY_LOC_INTERVAL_SEC = intPreferencesKey("loc_interval_sec")
        private val KEY_APP_INTERVAL_SEC = intPreferencesKey("app_interval_sec")
        const val DEFAULT_LOC_INTERVAL_SEC = 8
        const val DEFAULT_APP_INTERVAL_SEC = 4
        const val MIN_LOC_INTERVAL_SEC = 3
        const val MAX_LOC_INTERVAL_SEC = 60
        const val MIN_APP_INTERVAL_SEC = 2
        const val MAX_APP_INTERVAL_SEC = 30

        @Volatile private var INSTANCE: UserRepository? = null
        fun init(context: Context) {
            if (INSTANCE == null) INSTANCE = UserRepository(context.applicationContext)
        }
        fun get(): UserRepository = INSTANCE ?: error("UserRepository未初始化")
    }

    // ---- token ----
    val tokenFlow: Flow<String?> = context.store.data.map { it[KEY_TOKEN] }
    suspend fun getToken(): String? = tokenFlow.first()
    suspend fun setToken(token: String?) {
        context.store.edit {
            if (token == null) it.remove(KEY_TOKEN) else it[KEY_TOKEN] = token
        }
    }

    // ---- user ----
    val userFlow: Flow<UserInfo?> = context.store.data.map { prefs ->
        prefs[KEY_USER]?.let { runCatching { gson.fromJson(it, UserInfo::class.java) }.getOrNull() }
    }
    suspend fun getUser(): UserInfo? = userFlow.first()
    suspend fun setUser(user: UserInfo?) {
        context.store.edit {
            if (user == null) it.remove(KEY_USER)
            else it[KEY_USER] = gson.toJson(user)
        }
    }

    // ---- 服务器地址（可在 APP 内修改，无需重新打包） ----
    val apiBaseFlow: Flow<String> = context.store.data.map { it[KEY_API_BASE] ?: BuildConfig.DEFAULT_API_BASE }
    suspend fun getApiBase(): String = apiBaseFlow.first()
    suspend fun setApiBase(url: String) {
        val normalized = url.trim().trimEnd('/')
        context.store.edit { it[KEY_API_BASE] = normalized }
    }

    val webBaseFlow: Flow<String> = context.store.data.map { it[KEY_WEB_BASE] ?: BuildConfig.DEFAULT_WEB_BASE }
    suspend fun getWebBase(): String = webBaseFlow.first()
    suspend fun setWebBase(url: String) {
        val normalized = url.trim().trimEnd('/')
        context.store.edit { it[KEY_WEB_BASE] = normalized }
    }

    /** NetworkModule 初始化前先调用，读取用户保存或默认的服务器地址 */
    suspend fun resolveApiBase(): String = getApiBase()
    suspend fun resolveWebBase(): String = getWebBase()

    // ---- 便捷 ----
    /** 判断一个字符串是否是合法 JWT 格式（三段 base64url 用点号分隔）
     *  假 token 如 "rpc_auth_xxx"、anon key 等都会返回 false */
    private val JWT_REGEX = Regex("^[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+$")
    fun isValidJwt(tok: String?): Boolean = tok != null && JWT_REGEX.matches(tok.trim())

    /** 检查并清洗非法 token：如果 token 不是合法 JWT，自动清除 */
    suspend fun sanitizeToken() {
        val t = getToken()
        if (t != null && !isValidJwt(t)) {
            setToken(null)
        }
    }

    /** 已登录 = 有合法 JWT token 且有 user 信息
     *  旧版本残留的假 token（如 "rpc_auth_xxx"）会被清洗 */
    suspend fun isLoggedIn(): Boolean {
        sanitizeToken()
        return getToken() != null && getUser() != null
    }
    suspend fun logout() { setToken(null); setUser(null) }

    // ---- 采集频率（可在设置页动态调整，Service 监听 Flow 自动重启） ----
    val locationIntervalSecFlow: Flow<Int> = context.store.data.map {
        (it[KEY_LOC_INTERVAL_SEC] ?: DEFAULT_LOC_INTERVAL_SEC)
            .coerceIn(MIN_LOC_INTERVAL_SEC, MAX_LOC_INTERVAL_SEC)
    }
    suspend fun getLocationIntervalSec(): Int = locationIntervalSecFlow.first()
    suspend fun setLocationIntervalSec(sec: Int) {
        val v = sec.coerceIn(MIN_LOC_INTERVAL_SEC, MAX_LOC_INTERVAL_SEC)
        context.store.edit { it[KEY_LOC_INTERVAL_SEC] = v }
    }

    val appIntervalSecFlow: Flow<Int> = context.store.data.map {
        (it[KEY_APP_INTERVAL_SEC] ?: DEFAULT_APP_INTERVAL_SEC)
            .coerceIn(MIN_APP_INTERVAL_SEC, MAX_APP_INTERVAL_SEC)
    }
    suspend fun getAppIntervalSec(): Int = appIntervalSecFlow.first()
    suspend fun setAppIntervalSec(sec: Int) {
        val v = sec.coerceIn(MIN_APP_INTERVAL_SEC, MAX_APP_INTERVAL_SEC)
        context.store.edit { it[KEY_APP_INTERVAL_SEC] = v }
    }
}
