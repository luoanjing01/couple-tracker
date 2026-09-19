/*
 * ============================================================================
 * 文件说明（给初学者）
 * ============================================================================
 * 这个文件叫 NetworkModule.kt，是整个 App 的「网络通讯总管」。
 *
 * 它的职责：
 *   1. 帮 App 和云端服务器（Supabase）建立通讯连接；
 *   2. 给所有网络请求自动加上「身份凭证」（apikey 和登录令牌 JWT）；
 *   3. 暴露三个对外服务（authService、restService、rpcService）让其他页面调用。
 *
 * 名词解释（看不懂没关系，先有个印象）：
 *   - Supabase：一个后端云服务平台（BaaS），相当于「云端数据库 + 用户系统」，
 *     我们不用自己搭服务器，直接用它的接口即可。
 *   - Retrofit：Android 上常用的网络请求库，能把 HTTP 接口包装成 Kotlin 函数。
 *   - OkHttp：Retrofit 底层使用的「真实发请求」的工具，能加拦截器（Interceptor）。
 *   - JWT：登录成功后服务器发回的一串字符，相当于「电子通行证」，每次请求要带上。
 * ============================================================================
 */

// 声明当前文件所属的包名（相当于文件夹路径），告诉 Kotlin 这个文件归哪里管。
package com.coupletracker.android.data

// 下面这些 import 语句，是把别处写好的工具「搬过来」用，类似借工具箱。
// 引用本项目的 BuildConfig（编译时自动生成，里面存了 Supabase 的地址和密钥）
import com.coupletracker.android.BuildConfig
// runBlocking：让普通（非挂起）函数也能调用挂起函数（暂停等待结果），这里用来同步取登录令牌
import kotlinx.coroutines.runBlocking
// Interceptor：OkHttp 的「拦截器」，能在请求发出前/响应回来后做手脚（比如加请求头）
import okhttp3.Interceptor
// OkHttpClient：OkHttp 的客户端实例，负责真正发出 HTTP 请求
import okhttp3.OkHttpClient
// Request：表示一个 HTTP 请求对象（包含 URL、请求头、请求体等）
import okhttp3.Request
// HttpLoggingInterceptor：专门打印网络请求日志的拦截器，方便调试
import okhttp3.logging.HttpLoggingInterceptor
// Retrofit：把 HTTP 接口封装成 Kotlin 函数的主库
import retrofit2.Retrofit
// GsonConverterFactory：把服务器返回的 JSON 自动转成 Kotlin 对象（以及反向转换）
import retrofit2.converter.gson.GsonConverterFactory
// TimeUnit：时间单位（秒、毫秒等），用于设置超时时间
import java.util.concurrent.TimeUnit
// MutableStateFlow：一个可读可写的数据流，能在数据变化时通知界面自动刷新
import kotlinx.coroutines.flow.MutableStateFlow
// asStateFlow：把可写流转换成只读流，对外只允许读、不允许写
import kotlinx.coroutines.flow.asStateFlow

/**
 * ============================================================================
 * 网络模块单例 — 对接 Supabase BaaS（后端即服务）
 * ============================================================================
 *
 * 什么是「单例 object」？
 *   Kotlin 的 object 关键字表示「整个 App 里只有这一个实例」，不用 new，
 *   其他地方直接用 NetworkModule.xxx 就能访问，方便全局共享。
 *
 * 什么是 BaaS？
 *   Backend as a Service（后端即服务），就是把服务器端那些常见功能
 *   （用户登录、数据库增删改查）都做成了云端接口，我们直接调即可。
 *
 * 这里准备了三个 Retrofit（每个对应一种用途）：
 *   - authService : .../auth/v1/   ← 处理登录、注册、会话管理
 *   - restService : .../rest/v1/    ← 处理数据增删改查（PostgREST 协议）
 *   - rpcService  : .../rest/v1/rpc/← 调用服务器端自定义函数（如 register_user）
 *
 * 每个请求都会被拦截器自动加上两个凭证：
 *   1. apikey        —— Supabase 给本项目分配的「项目密钥」（公开匿名密钥）
 *   2. Authorization —— 登录后拿到的 JWT 令牌，格式为 "Bearer <jwt>"
 * ============================================================================
 */
object NetworkModule {

    /*
     * -----------------------------------------------------------------------
     * 下面三个是 Retrofit 实例，它们是「网络通讯的管道」。
     * lateinit 的意思是「稍后再初始化」：
     *   现在先不赋值，等 init() 被调用时才真正创建。
     *   如果在初始化之前就访问，会报错，所以一定要先调用 init()。
     * -----------------------------------------------------------------------
     */
    // 用来管理「认证类」请求（登录、注册、刷新令牌）
    private lateinit var authRetrofit: Retrofit
    // 用来管理「数据增删改查」类请求（查询/写入业务数据表）
    private lateinit var restRetrofit: Retrofit
    // 用来管理「服务器函数调用」类请求（如调用 register_user 这种后端函数）
    private lateinit var rpcRetrofit: Retrofit

    /*
     * -----------------------------------------------------------------------
     * 下面三个变量对外公开（别的文件可以 NetworkModule.authService 这样调用）。
     * private set 表示「外面只能读、不能改」，只有本类内部可以赋值，
     *   防止别人乱改导致出问题。
     * -----------------------------------------------------------------------
     */

    /** Auth API（地址 /auth/v1）—— 用于登录、注册、获取/刷新会话令牌 */
    lateinit var authService: AuthService
        private set

    /** REST API（地址 /rest/v1）—— 数据增删改查（PostgREST 风格的数据表操作） */
    lateinit var restService: RestService
        private set

    /** RPC API（地址 /rest/v1/rpc/）—— 调用 SECURITY DEFINER 函数（如 register_user 注册函数） */
    lateinit var rpcService: RpcService
        private set

    /*
     * -----------------------------------------------------------------------
     * 下面这组「状态流」用来在界面实时展示「最近一次上报是成功还是失败」。
     *
     * 为什么用 StateFlow 而不是普通变量？
     *   普通变量改了之后界面不会自动刷新；
     *   StateFlow 改了之后，所有监听它的界面会自动收到新值并更新显示，
     *   就像微信群里发消息，所有群成员都能看到一样。
     *
     * 双层结构说明：
     *   - MutableStateFlow（可读可写）：内部用来更新状态；
     *   - asStateFlow()（只读）：对外暴露，让别人能读但改不了。
     * -----------------------------------------------------------------------
     */

    /** 最近一次位置上报状态（成功 / 错误明细），供「我的」页采集频率卡片底部显示 */
    val lastLocationReportStatus = MutableStateFlow("等待启动服务…") // 初始文案
    /** 最近一次 APP 使用上报状态（成功 / 错误明细），同上 */
    val lastAppReportStatus = MutableStateFlow("等待启动服务…") // 初始文案
    // 把上面的可写流转成只读流对外发布（界面订阅这个只读流来显示状态）
    val lastLocationReportStatusFlow = lastLocationReportStatus.asStateFlow()
    val lastAppReportStatusFlow = lastAppReportStatus.asStateFlow()

    /*
     * -----------------------------------------------------------------------
     * Supabase 的连接配置（地址和密钥）
     * 这些值在 BuildConfig 里，编译时根据 build.gradle 注入，
     *   不会硬编码到代码里，方便不同环境（开发/生产）切换。
     * const val：编译期常量，值固定不变，性能比普通 val 更好。
     * -----------------------------------------------------------------------
     */
    // Supabase 项目的根地址，例如 https://abcd1234.supabase.co
    private const val SUPABASE_URL = BuildConfig.SUPABASE_URL
    // Supabase 匿名密钥（anon key）：公开密钥，每个请求都要带，用来标识是哪个项目
    private const val SUPABASE_ANON_KEY = BuildConfig.SUPABASE_ANON_KEY

    /*
     * ===================================================================
     * 初始化函数 init()
     * -------------------------------------------------------------------
     * App 启动时（一般在 Application.onCreate 里）调用一次，
     * 把三个 Retrofit 实例和对应的服务对象全部创建好。
     *
     * 初始化流程总体分四步：
     *   1. 准备一个日志拦截器（让网络请求在控制台打印日志，方便调试）；
     *   2. 给三种用途各配一个 OkHttpClient（每个带的拦截器略有不同）；
     *   3. 用这三个 Client 各构建一个 Retrofit（指定 baseUrl 和转换器）；
     *   4. 用 Retrofit.create() 把接口变成真正的可调用对象。
     * ===================================================================
     */
    fun init() {
        // ① 创建日志拦截器，BASIC 级别只打印请求行和响应行（不打印 body，避免泄露隐私）
        val logging = HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC }

        /*
         * ② 准备 authClient（认证专用客户端）
         * 为什么 addAuthHeader = false？
         *   认证接口（登录、注册）本身就是为了「拿到」令牌，
         *   调用前还没登录、没令牌，所以这里不强制带 Authorization 头，
         *   只带项目密钥 apikey 即可。
         */
        val authClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)  // 建立连接最多等 15 秒
            .readTimeout(15, TimeUnit.SECONDS)     // 读取响应最多等 15 秒
            .addInterceptor(logging)               // 加上日志拦截器
            .addInterceptor(supabaseHeaderInterceptor(addAuthHeader = false)) // 加 Supabase 头（不带登录令牌）
            .build()                               // 正式构建出 OkHttpClient

        /*
         * ③ 准备 restClient（数据增删改查专用客户端）
         * 这里 addAuthHeader = true：因为查询/修改业务数据需要「已登录」身份，
         *   服务器靠 Authorization 头里的 JWT 判断是谁在操作。
         */
        val restClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)  // 建立连接最多等 15 秒
            .readTimeout(15, TimeUnit.SECONDS)     // 读取响应最多等 15 秒
            .addInterceptor(logging)               // 加日志
            .addInterceptor(supabaseHeaderInterceptor(addAuthHeader = true)) // 加 Supabase 头 + 登录令牌
            .build()

        /*
         * ④ 准备 rpcClient（服务器函数调用专用客户端）
         *   RPC 也走 /rest/v1/rpc/，跟 REST 共用 base 也行，只是 path 加 "rpc/" 前缀；
         *   为了清晰独立一个 Retrofit（注册时还没 token，用的是 anon key）。
         *   RPC 调用：注册前没有 token（register_user 给 anon 调用），
         *   其他 RPC 之后可能需要 token，这里默认和 REST 一样优先带 token。
         */
        val rpcClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)  // 建立连接最多等 15 秒
            .readTimeout(15, TimeUnit.SECONDS)     // 读取响应最多等 15 秒
            .addInterceptor(logging)               // 加日志
            .addInterceptor(supabaseHeaderInterceptor(addAuthHeader = true)) // 加 Supabase 头 + 登录令牌
            .build()

        /*
         * ⑤ 用上面三个 Client 分别构建 Retrofit 实例
         *   - baseUrl：所有请求的「根地址」，接口里写的相对路径会拼到这个后面；
         *   - client：用刚才配好的 OkHttpClient；
         *   - addConverterFactory(Gson...)：让 Retrofit 用 Gson 自动做 JSON ↔ 对象转换。
         */
        // 认证接口的 Retrofit：根地址是 .../auth/v1/
        authRetrofit = Retrofit.Builder()
            .baseUrl("$SUPABASE_URL/auth/v1/")    // 认证根地址
            .client(authClient)                  // 用认证专用客户端
            .addConverterFactory(GsonConverterFactory.create()) // 启用 JSON 自动转换
            .build()

        // 数据接口的 Retrofit：根地址是 .../rest/v1/
        restRetrofit = Retrofit.Builder()
            .baseUrl("$SUPABASE_URL/rest/v1/")    // 数据接口根地址
            .client(restClient)                   // 用数据专用客户端
            .addConverterFactory(GsonConverterFactory.create()) // 启用 JSON 自动转换
            .build()

        // RPC 函数调用的 Retrofit：根地址是 .../rest/v1/rpc/
        rpcRetrofit = Retrofit.Builder()
            .baseUrl("$SUPABASE_URL/rest/v1/rpc/") // RPC 函数根地址
            .client(rpcClient)                     // 用 RPC 专用客户端
            .addConverterFactory(GsonConverterFactory.create()) // 启用 JSON 自动转换
            .build()

        /*
         * ⑥ 把 Retrofit 实例「翻译」成可调用的 Kotlin 接口对象
         *   create() 会在背后生成一个实现了接口的实例，
         *   调用接口方法 = 发 HTTP 请求，方法返回值 = 服务器响应。
         *   之后整个 App 就可以用 NetworkModule.authService.xxx() 这种方式调接口了。
         */
        authService = authRetrofit.create(AuthService::class.java) // 认证服务
        restService = restRetrofit.create(RestService::class.java) // 数据服务
        rpcService  = rpcRetrofit.create(RpcService::class.java)  // RPC 服务
    }

    /*
     * ===================================================================
     * supabaseHeaderInterceptor —— Supabase 请求头拦截器
     * -------------------------------------------------------------------
     * 拦截器是什么？
     *   类似快递分拣中心的「加工台」：所有进出的请求都会经过这里，
     *   在这里给它们贴上「标签」（HTTP 请求头），再放行出去。
     *
     * 这个拦截器给每个请求加上 Supabase 必备的请求头：
     *   - apikey        : <项目匿名密钥>  （标识是哪个项目，必带）
     *   - Content-Type  : application/json（告诉服务器我们发的是 JSON 数据）
     *   - Accept        : application/json（告诉服务器我们想收 JSON 响应）
     *   - Authorization : Bearer <jwt>    （登录令牌；仅当 addAuthHeader=true 且令牌合法时才加）
     *
     * 参数 addAuthHeader：
     *   true  = 用于已登录场景，请求会自动带上登录令牌；
     *   false = 用于登录/注册这种「还没拿到令牌」的场景，只带 apikey。
     * ===================================================================
     */
    /**
     * 给每个请求加 Supabase 必须的 header：
     *   apikey: <anon_key>
     *   Authorization: Bearer <jwt>  (addAuthHeader=true 且已登录时)
     */
    private fun supabaseHeaderInterceptor(addAuthHeader: Boolean) = Interceptor { chain ->
        // chain 是「请求链」，里面装着当前这个原始请求
        val original: Request = chain.request()
        // 拿到一个「请求构建器」，基于原请求做修改（不直接改原对象，更安全）
        val builder = original.newBuilder()
            .header("apikey", SUPABASE_ANON_KEY)            // 必带：项目匿名密钥
            .header("Content-Type", "application/json")     // 必带：发送的数据类型是 JSON
            .header("Accept", "application/json")           // 必带：希望收到的数据类型是 JSON

        // 仅当需要带登录令牌时，才尝试读取并加上 Authorization 头
        if (addAuthHeader) {
            // runCatching {...}.getOrNull()：尝试做一件事，失败就返回 null，不抛异常
            //   这里是从本地存储里读取登录令牌（可能在 IO 线程，用 runBlocking 阻塞等结果）
            val token = runCatching {
                runBlocking { UserRepository.get().getToken() } // 暂停等待取出令牌
            }.getOrNull() // 出错就得到 null，不影响请求继续
            // ✅ 只有合法 JWT 才加 Authorization 头
            //    假 token 如 "rpc_auth_xxx"（旧版本残留）一律跳过，避免 401 PGRST301
            //    PGRST301 是 PostgREST 的错误码，意思是「令牌无效或缺失」
            if (UserRepository.get().isValidJwt(token)) {
                // Bearer 是 HTTP 协议规定的格式，后面跟一串令牌字符串
                builder.header("Authorization", "Bearer $token")
            }
        }
        // 把加工好的请求送出去，proceed() 返回服务器的响应
        chain.proceed(builder.build())
    }

    /*
     * -------------------------------------------------------------------
     * 下面三个是简单的「取值函数」，给外部一个统一的地方拿 Supabase 地址。
     *   - getSupabaseUrl()：返回 Supabase 项目根地址
     *   - getApiBase()   ：返回 REST 数据接口的根地址（.../rest/v1）
     *   - getWebBase()   ：返回 Web 端根地址（同根地址，方便日后扩展）
     * -------------------------------------------------------------------
     */
    fun getSupabaseUrl(): String = SUPABASE_URL                  // 返回 Supabase 根地址
    fun getApiBase(): String = "$SUPABASE_URL/rest/v1"           // 返回 REST 接口根地址
    fun getWebBase(): String = SUPABASE_URL                      // 返回 Web 根地址（同根地址）
}
