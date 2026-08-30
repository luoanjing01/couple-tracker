package com.coupletracker.android.data

import android.content.Context
import androidx.datastore.preferences.core.edit
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
    suspend fun isLoggedIn(): Boolean = getToken() != null && getUser() != null
    suspend fun logout() { setToken(null); setUser(null) }
}
