// 声明该文件所属的包名，所有类都属于 com.coupletracker.android.data 这个数据层包
package com.coupletracker.android.data

// 以下是导入的依赖库，逐一说明用途：
import android.content.Context                                              // Android 上下文，用于访问应用资源、文件目录等
import androidx.datastore.preferences.core.edit                            // DataStore 的扩展函数：以事务方式修改偏好设置
import androidx.datastore.preferences.core.intPreferencesKey              // 创建一个 Int 类型的 DataStore 键
import androidx.datastore.preferences.core.stringPreferencesKey           // 创建一个 String 类型的 DataStore 键
import androidx.datastore.preferences.preferencesDataStore                 // 通过扩展属性创建 Preferences DataStore 实例
import com.coupletracker.android.BuildConfig                               // 编译期生成的配置类，包含默认 API/Web 地址等常量
import com.coupletracker.android.data.model.UserInfo                       // 用户信息数据模型（POJO）
import com.google.gson.Gson                                                // Google 的 JSON 序列化/反序列化库
import kotlinx.coroutines.flow.Flow                                        // Kotlin 协程的冷流，用于观察数据变化
import kotlinx.coroutines.flow.first                                       // 收集 Flow 的第一个值然后结束（一次性读取）
import kotlinx.coroutines.flow.map                                         // 操作符：把 Flow 中的值映射成另一种类型

/**
 * 用户与配置持久化仓库（基于DataStore）
 *
 * 这是整个应用的"本地配置仓库"，负责把以下数据持久化保存到设备本地：
 *   1. 登录凭证 token
 *   2. 当前登录用户的信息（UserInfo）
 *   3. 后端服务地址（API 地址、Web 地址）
 *   4. 位置采集、APP 采集的频率参数（秒）
 *
 * 采用单例模式：构造函数被 private 修饰，外部只能通过 [init] 初始化、[get] 获取实例。
 * 使用 Android 官方推荐的 Jetpack DataStore（Preferences 方式）替代传统的 SharedPreferences，
 * 优势：支持协程、线程安全、避免 ANR。
 *
 * 负责：token、当前用户信息、后端地址的读写
 */
class UserRepository private constructor(private val context: Context) {

    // Gson 实例：用于把 UserInfo 对象 ↔ JSON 字符串互相转换，便于存进 DataStore
    private val gson = Gson()

    companion object {
        // 通过扩展属性为 Context 关联一个 PreferencesDataStore 实例
        // name = "couple_tracker_prefs" 是存储文件名，最终文件位于 /data/data/<包名>/files/datastore/
        private val Context.store by preferencesDataStore(name = "couple_tracker_prefs")

        // 下面定义了所有用到的存储键（Key），每个 Key 都对应存储里的一项数据：
        private val KEY_TOKEN = stringPreferencesKey("auth_token")              // 登录 token 的键
        private val KEY_USER = stringPreferencesKey("user_info_json")          // 用户信息（以 JSON 字符串形式存储）的键
        private val KEY_API_BASE = stringPreferencesKey("server_api_base")      // 后端 API 接口地址的键
        private val KEY_WEB_BASE = stringPreferencesKey("server_web_base")      // 前端 Web 页面地址的键
        // 采集频率（秒）—— 默认位置 8s、APP 4s，降低卡顿
        private val KEY_LOC_INTERVAL_SEC = intPreferencesKey("loc_interval_sec")  // 位置采集间隔
        private val KEY_APP_INTERVAL_SEC = intPreferencesKey("app_interval_sec")  // APP 使用信息采集间隔

        // 下面是采集频率的默认值与上下限（单位：秒），用于约束用户输入，防止极端值
        const val DEFAULT_LOC_INTERVAL_SEC = 8   // 位置采集默认间隔 8 秒
        const val DEFAULT_APP_INTERVAL_SEC = 4   // APP 采集默认间隔 4 秒
        const val MIN_LOC_INTERVAL_SEC = 3       // 位置采集最小允许间隔 3 秒（太短会耗电）
        const val MAX_LOC_INTERVAL_SEC = 60      // 位置采集最大允许间隔 60 秒（太长会丢失实时性）
        const val MIN_APP_INTERVAL_SEC = 2       // APP 采集最小允许间隔 2 秒
        const val MAX_APP_INTERVAL_SEC = 30      // APP 采集最大允许间隔 30 秒

        // 单例实例，@Volatile 保证多线程下的可见性（防止双重检查锁定失效）
        @Volatile private var INSTANCE: UserRepository? = null

        /**
         * 初始化单例。一般在 Application.onCreate() 中调用一次即可。
         * 使用 applicationContext 防止 Activity/Service 上下文造成的内存泄漏。
         *
         * @param context 任意上下文，内部会取其 applicationContext
         */
        fun init(context: Context) {
            // 双重检查锁定的简化形式：仅当尚未初始化时创建实例
            if (INSTANCE == null) INSTANCE = UserRepository(context.applicationContext)
        }

        /**
         * 获取已初始化的单例实例。
         * 如果未调用过 [init]，会抛出 IllegalStateException，提示"UserRepository未初始化"。
         *
         * @return 已初始化的 UserRepository 实例
         */
        fun get(): UserRepository = INSTANCE ?: error("UserRepository未初始化")
    }

    // ===================== token（登录凭证）相关 =====================

    /**
     * Token 的响应式流（Flow）。
     *
     * 工作原理：
     *   1. context.store.data 是 DataStore 暴露的 Preferences 数据流（每次写入都会推送新值）
     *   2. 通过 map 操作符取出 KEY_TOKEN 对应的字符串，可能为 null（未登录时）
     *
     * 使用场景：在 ViewModel / Compose 中观察登录状态变化，自动刷新 UI。
     */
    val tokenFlow: Flow<String?> = context.store.data.map { it[KEY_TOKEN] }   // 从偏好中读取 KEY_TOKEN，可能为 null

    /**
     * 一次性获取当前 token。挂起函数，需在协程中调用。
     * 通过 [first] 只收集首个值后取消订阅，适合不需要持续观察的场景。
     *
     * @return 当前 token；未登录时返回 null
     */
    suspend fun getToken(): String? = tokenFlow.first()

    /**
     * 保存或清除 token。
     * 传入 null 时表示清除 token（例如退出登录或清洗非法 token）。
     * 通过 DataStore 的 edit 事务写入，保证线程安全。
     *
     * @param token 要保存的 JWT；传 null 则删除该项
     */
    suspend fun setToken(token: String?) {
        context.store.edit {
            // 如果传入 null 则移除键值；否则写入新 token
            if (token == null) it.remove(KEY_TOKEN) else it[KEY_TOKEN] = token
        }
    }

    // ===================== user（用户信息）相关 =====================

    /**
     * 当前用户信息的响应式流。
     *
     * 由于 DataStore 只能存原始类型，UserInfo 以 JSON 字符串形式存储，
     * 读取时通过 Gson 反序列化回对象。
     *
     * 容错设计：使用 runCatching 包裹反序列化，若 JSON 损坏或格式不符，
     * 返回 null 而非抛异常，避免整个 App 因脏数据崩溃。
     */
    val userFlow: Flow<UserInfo?> = context.store.data.map { prefs ->
        // prefs[KEY_USER] 取出 JSON 字符串（可能为 null）
        // ?.let { ... } 仅在非 null 时反序列化
        // runCatching { ... }.getOrNull()：尝试解析，失败返回 null
        prefs[KEY_USER]?.let { runCatching { gson.fromJson(it, UserInfo::class.java) }.getOrNull() }
    }

    /**
     * 一次性获取当前登录用户信息。
     *
     * @return 当前 UserInfo；未登录时返回 null
     */
    suspend fun getUser(): UserInfo? = userFlow.first()

    /**
     * 保存或清除用户信息。
     * 传入 null 表示清除用户信息（例如退出登录）。
     * 对象通过 Gson 转为 JSON 字符串后写入 DataStore。
     *
     * @param user 要保存的用户对象；传 null 则删除该项
     */
    suspend fun setUser(user: UserInfo?) {
        context.store.edit {
            if (user == null) it.remove(KEY_USER)                                  // 清除用户
            else it[KEY_USER] = gson.toJson(user)                                  // 序列化为 JSON 后保存
        }
    }

    // ===================== 服务器地址（API / Web）相关 =====================
    // 注：把服务器地址放进本地存储的好处是用户可以在 APP 内修改，无需重新打包发布。
    // 这对于自部署、多环境（测试/正式）切换场景非常方便。

    /**
     * 后端 API 地址的响应式流。
     *
     * 如果用户没有自定义过地址，则回退到 BuildConfig.DEFAULT_API_BASE
     * （编译期注入的默认地址，来自 build.gradle 中的配置）。
     */
    val apiBaseFlow: Flow<String> = context.store.data.map { it[KEY_API_BASE] ?: BuildConfig.DEFAULT_API_BASE }

    /**
     * 一次性获取当前后端 API 地址。
     *
     * @return 当前 API base URL（形如 https://api.example.com）
     */
    suspend fun getApiBase(): String = apiBaseFlow.first()

    /**
     * 保存后端 API 地址。
     * 保存前会做规范化处理：去除首尾空白字符、去掉末尾的斜杠 "/"，
     * 防止拼接路径时出现 "https://a.com//login" 这种重复斜杠的 bug。
     *
     * @param url 用户输入的 API 地址
     */
    suspend fun setApiBase(url: String) {
        val normalized = url.trim().trimEnd('/')   // 去空白 + 去末尾斜杠
        context.store.edit { it[KEY_API_BASE] = normalized }
    }

    /**
     * 前端 Web 页面地址的响应式流。
     * 用于 WebView 加载登录页、个人主页等。
     *
     * 同 [apiBaseFlow] 的逻辑：未设置时使用 BuildConfig 中的默认值。
     */
    val webBaseFlow: Flow<String> = context.store.data.map { it[KEY_WEB_BASE] ?: BuildConfig.DEFAULT_WEB_BASE }

    /**
     * 一次性获取当前 Web 地址。
     *
     * @return 当前 Web base URL
     */
    suspend fun getWebBase(): String = webBaseFlow.first()

    /**
     * 保存 Web 地址。规范化处理同 [setApiBase]。
     *
     * @param url 用户输入的 Web 地址
     */
    suspend fun setWebBase(url: String) {
        val normalized = url.trim().trimEnd('/')
        context.store.edit { it[KEY_WEB_BASE] = normalized }
    }

    /**
     * NetworkModule（网络模块）初始化前先调用，读取用户保存或默认的 API 地址。
     *
     * 设计原因：OkHttp/Retrofit 的初始化通常只发生一次，需要在构建时确定 baseUrl，
     * 这里把 resolve 单独暴露，便于网络模块在创建时同步获取真实地址。
     *
     * @return 当前生效的 API base URL
     */
    suspend fun resolveApiBase(): String = getApiBase()

    /**
     * 同 [resolveApiBase]，但返回的是 Web base URL。
     *
     * @return 当前生效的 Web base URL
     */
    suspend fun resolveWebBase(): String = getWebBase()

    // ===================== token 合法性校验与登录态 =====================

    /** 判断一个字符串是否是合法 JWT 格式（三段 base64url 用点号分隔）
     *  假 token 如 "rpc_auth_xxx"、anon key 等都会返回 false */
    // JWT 标准格式：header.payload.signature，每段使用 base64url 编码，三段用点 "." 分隔
    private val JWT_REGEX = Regex("^[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+$")

    /**
     * 检查给定的字符串是否为合法的 JWT 格式。
     *
     * 注意：这里只做格式校验，不验证签名是否真实有效（签名验证由后端完成）。
     * 用于过滤掉旧版本残留的假 token（如 "rpc_auth_xxx"、Supabase 的 anon key 等）。
     *
     * @param tok 待校验的 token 字符串
     * @return true 表示格式合法；false 表示非法或为 null
     */
    fun isValidJwt(tok: String?): Boolean = tok != null && JWT_REGEX.matches(tok.trim())

    /**
     * 检查并清洗非法 token：如果 token 不是合法 JWT，自动清除。
     *
     * 应用场景：用户从旧版本升级到新版本时，本地可能存有"假 token"，
     * 这种 token 会导致所有网络请求失败，所以登录前必须先清洗。
     */
    suspend fun sanitizeToken() {
        val t = getToken()                                  // 先读取当前 token
        if (t != null && !isValidJwt(t)) {                  // 不为空但不是合法 JWT
            setToken(null)                                   // 清除非法 token
        }
    }

    /**
     * 判断当前用户是否处于已登录状态。
     *
     * 判定规则：已登录 = 本地存在 UserInfo（用户信息）
     *
     * 为什么 token 可以为 null？
     *   因为 RPC 认证（如 Supabase 的 rpc_auth）不依赖 JWT，可能没有 token。
     *   所以是否登录以 UserInfo 为准，而不是 token。
     *
     * 旧版本残留的假 token（如 "rpc_auth_xxx"）会被 [sanitizeToken] 清洗。
     *
     * @return true 表示已登录；false 表示未登录
     */
    suspend fun isLoggedIn(): Boolean {
        sanitizeToken()                  // 先清洗可能存在的非法 token
        return getUser() != null         // 有用户信息就算登录
    }

    /**
     * 退出登录：彻底清除本地所有用户数据，避免旧账号残留。
     *
     * 清理范围：
     *   1. DataStore 中所有键值对（token、user、服务器地址、采集频率…）
     *   2. WebView 的缓存和本地存储（localStorage 里可能存有用户信息、token 等）
     *   3. 应用的整个缓存目录 cacheDir
     *
     * 所有清理操作均使用 runCatching 包裹，避免某一步失败导致整体崩溃。
     */
    suspend fun logout() {
        // ✅ 清除所有本地数据，避免旧账号数据残留
        context.store.edit { it.clear() }            // 清空整个 DataStore

        // 清除 WebView 缓存（localStorage 里存的用户信息、token 等）
        runCatching {
            android.webkit.WebView(context).apply {
                clearCache(true)                     // 清除网页缓存（包括磁盘缓存）
                clearHistory()                       // 清除访问历史
                clearFormData()                      // 清除表单自动填充数据
                // 清除所有 WebView 存储（localStorage/sessionStorage/indexedDB）
                // 这里通过删除 WebView 自身的 SharedPreferences 文件来达到目的
                context.getSharedPreferences("WebViewChromiumPrefs", 0).edit().clear().apply()
            }
        }

        // 清除应用缓存目录（图片缓存、HTTP 缓存、临时文件等）
        runCatching {
            context.cacheDir.deleteRecursively()    // 递归删除整个缓存目录
        }
    }

    // ===================== 采集频率相关 =====================
    // 注：采集频率支持在设置页动态调整，后台 Service 通过监听 Flow 实现自动重启采集任务，
    // 用户改完即生效，无需重启 APP。

    /**
     * 位置采集间隔（秒）的响应式流。
     *
     * 工作流程：
     *   1. 从 DataStore 读取 KEY_LOC_INTERVAL_SEC
     *   2. 如果未设置，使用默认值 [DEFAULT_LOC_INTERVAL_SEC]（8 秒）
     *   3. 使用 coerceIn 把值约束在 [MIN_LOC_INTERVAL_SEC] 和 [MAX_LOC_INTERVAL_SEC] 之间，
     *      即使用户手动改坏了存储值，也不会越界，保证系统稳定。
     */
    val locationIntervalSecFlow: Flow<Int> = context.store.data.map {
        (it[KEY_LOC_INTERVAL_SEC] ?: DEFAULT_LOC_INTERVAL_SEC)    // 未设置则取默认值
            .coerceIn(MIN_LOC_INTERVAL_SEC, MAX_LOC_INTERVAL_SEC) // 强制约束到 [3, 60] 范围
    }

    /**
     * 一次性获取当前位置采集间隔（秒）。
     *
     * @return 当前生效的位置采集间隔
     */
    suspend fun getLocationIntervalSec(): Int = locationIntervalSecFlow.first()

    /**
     * 保存位置采集间隔。保存前会做范围约束，防止用户输入越界值。
     *
     * @param sec 用户设置的间隔（秒）
     */
    suspend fun setLocationIntervalSec(sec: Int) {
        val v = sec.coerceIn(MIN_LOC_INTERVAL_SEC, MAX_LOC_INTERVAL_SEC)  // 约束到合法范围
        context.store.edit { it[KEY_LOC_INTERVAL_SEC] = v }
    }

    /**
     * APP 使用信息采集间隔（秒）的响应式流。
     *
     * 与 [locationIntervalSecFlow] 类似，只是针对的是 APP 使用情况的采集频率，
     * 默认值 [DEFAULT_APP_INTERVAL_SEC]（4 秒），约束范围 [MIN_APP_INTERVAL_SEC, MAX_APP_INTERVAL_SEC]。
     */
    val appIntervalSecFlow: Flow<Int> = context.store.data.map {
        (it[KEY_APP_INTERVAL_SEC] ?: DEFAULT_APP_INTERVAL_SEC)
            .coerceIn(MIN_APP_INTERVAL_SEC, MAX_APP_INTERVAL_SEC)
    }

    /**
     * 一次性获取当前 APP 采集间隔（秒）。
     *
     * @return 当前生效的 APP 采集间隔
     */
    suspend fun getAppIntervalSec(): Int = appIntervalSecFlow.first()

    /**
     * 保存 APP 采集间隔。同 [setLocationIntervalSec]，保存前做范围约束。
     *
     * @param sec 用户设置的间隔（秒）
     */
    suspend fun setAppIntervalSec(sec: Int) {
        val v = sec.coerceIn(MIN_APP_INTERVAL_SEC, MAX_APP_INTERVAL_SEC)
        context.store.edit { it[KEY_APP_INTERVAL_SEC] = v }
    }
}
