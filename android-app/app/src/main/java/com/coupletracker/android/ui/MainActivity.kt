// ============================================================================
// 包声明：声明本文件所属的包名为 com.coupletracker.android.ui
// 包名通常与目录结构对应，方便 Android 系统和编译器定位类
// ============================================================================
package com.coupletracker.android.ui

// ----------------------------------------------------------------------------
// 导入区域：引入本文件需要用到的各类库和组件
// 初学者提示：Kotlin 通过 import 引用其他包中的类，类似 Java 的 import
// ----------------------------------------------------------------------------

// Android 系统相关导入
import android.annotation.SuppressLint        // 用于抑制 Android Studio 的警告（如允许 JS）
import android.content.ClipData              // 剪贴板数据载体（复制文本时使用）
import android.content.ClipboardManager     // 系统剪贴板服务管理器
import android.content.Context              // Android 上下文，访问系统服务的入口
import android.graphics.Bitmap              // 位图，WebView 加载 favicon 时用到
import android.os.Bundle                     // 用于保存 Activity 状态的容器
import android.webkit.*                     // WebView 相关：WebView、WebSettings、WebViewClient 等
import android.widget.Toast                 // Android 原生 Toast 提示（轻量级反馈）

// Jetpack Activity 库
import androidx.activity.ComponentActivity  // 基础 Activity 基类，比 AppCompatActivity 更轻量
import androidx.activity.compose.setContent  // 在 Activity 中使用 Jetpack Compose 写 UI 的入口

// Compose 基础组件
import androidx.compose.foundation.BorderStroke            // 边框样式
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable               // 背景修饰符
import androidx.compose.foundation.layout.*                 // 布局：Box、Column、Row、Spacer 等
import androidx.compose.foundation.rememberScrollState      // 记住滚动状态
import androidx.compose.foundation.shape.RoundedCornerShape // 圆角形状
import androidx.compose.foundation.verticalScroll           // 垂直滚动修饰符

// Material Design 3 组件库（按钮、卡片、Scaffold、导航栏等）
import androidx.compose.material3.*

// Compose 状态管理（remember、mutableStateOf、LaunchedEffect 等）
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable     // 可被系统重建时保留的状态（如配置变更）

// Compose UI 相关
import androidx.compose.ui.Alignment           // 对齐方式
import androidx.compose.ui.Modifier             // Compose 核心：修饰符链，用于装饰 UI
import androidx.compose.ui.draw.clip           // 裁剪修饰符
import androidx.compose.ui.graphics.Color      // 颜色定义
import androidx.compose.ui.graphics.SolidColor // 画刷类型（实心颜色）
import androidx.compose.ui.text.font.FontWeight // 字重（粗细）
import androidx.compose.ui.unit.dp             // 密度无关像素单位
import androidx.compose.ui.unit.sp             // 缩放无关像素单位（用于文字大小）
import androidx.compose.ui.viewinterop.AndroidView // 在 Compose 中嵌入传统 View（如 WebView）

// Lifecycle 协程作用域
import androidx.lifecycle.lifecycleScope       // 与 Lifecycle 绑定的协程作用域

// 应用内部依赖
import com.coupletracker.android.BuildConfig                // 编译期生成的配置常量（如版本号、Supabase URL）
import com.coupletracker.android.data.NetworkModule         // 网络模块：Retrofit、Supabase 配置
import com.coupletracker.android.data.PairByCodeReq         // 配对请求的请求体数据类
import com.coupletracker.android.data.CheckPairStatusReq    // 查询配对状态的请求体数据类
import com.coupletracker.android.data.AcceptPairReq          // 接受配对请求的请求体数据类
import com.coupletracker.android.data.UserRepository         // 用户数据仓库：保存用户信息、Token 等
import com.coupletracker.android.service.TrackerService     // 后台追踪服务（位置采集、APP 使用检测）
import com.coupletracker.android.ui.theme.Coral              // 潮汐主题：珊瑚主色
import com.coupletracker.android.ui.theme.TidalTheme         // 潮汐主题（透明状态栏 + 珊瑚配色）
import com.coupletracker.android.ui.tidal.TidalHomeScreen    // 潮汐卡片 v3 主界面（可拖拽底部抽屉）

// 协程相关
import kotlinx.coroutines.Dispatchers        // 协程调度器：IO（磁盘/网络）、Main（主线程）
import kotlinx.coroutines.delay              // 协程内非阻塞延时
import kotlinx.coroutines.flow.collectLatest // 收集 Flow 最新值
import kotlinx.coroutines.launch              // 启动协程
import kotlinx.coroutines.withContext        // 切换协程上下文（线程）
import org.json.JSONObject                   // JSON 对象构造与解析
import kotlin.math.roundToInt                 // 浮点数四舍五入转整数

/**
 * 主界面：底部3 Tab + WebView直接加载前端
 *   - 地图 Tab -> 访问 /map
 *   - 应用 Tab -> 访问 /apps
 *   - 统计 Tab -> 访问 /stats
 *   - 我的 Tab -> 原生设置页（配对码/退出登录/重启服务）
 *
 * Token注入：页面加载前写入 localStorage + Cookie，让Web端直接读取
 *
 * ============================================================================
 * 类说明（面向初学者）：
 * ============================================================================
 * - MainActivity 是整个 App 的"主界面"，等同于"应用启动后看到的第一屏"。
 * - 它继承自 ComponentActivity：这是 Jetpack 提供的轻量 Activity 基类，
 *   适合搭配 Jetpack Compose（声明式 UI 框架）使用。
 * - 本类的核心职责：
 *   ① 用 Compose 渲染底部 4 个 Tab 的导航栏和对应页面内容；
 *   ② 在地图 Tab 中嵌入一个 WebView，加载离线前端页面（assets/www/index.html）；
 *   ③ 通过 JS 注入把当前用户 token/信息同步给前端，让前端无需再次登录；
 *   ④ 在"我的"Tab 提供配对、采集频率调整、退出登录等设置功能。
 *
 * 关键概念解释：
 * - Activity：Android 四大组件之一，代表"一屏用户界面"。
 * - Compose：Google 推出的声明式 UI 框架，用 Kotlin 代码直接描述界面，
 *   不再需要 XML 布局文件。@Composable 注解标记的函数就是一个 UI 单元。
 * - WebView：Android 系统组件，可以在 App 内嵌一个"迷你浏览器"加载网页。
 *   这里用它来加载本地打包好的前端页面（Leaflet 地图等）。
 * - Jetpack lifecycleScope：与 Activity 生命周期绑定的协程作用域，
 *   在其中启动的协程会在 Activity 销毁时自动取消，避免内存泄漏。
 */
class MainActivity : ComponentActivity() {

    // ============================================================================
    // 旧版底部 Tab 枚举已移除：潮汐卡片 v3 改为单一宿主（TidalHomeScreen），
    // 四个区块（位置/状态/统计/我的）由抽屉内滚动锚点 + pill tabs 管理。
    // ============================================================================

    // ============================================================================
    // WebView 缓存：保存地图 WebView 实例，避免切 Tab 时重新创建
    // ----------------------------------------------------------------------------
    // - 用 var 而非 val 是因为该引用会在不同生命周期阶段被替换（如渲染进程崩溃后重建）。
    // - 用 nullable（WebView?）是因为初始状态下没有创建过 WebView。
    // - 设为 private 防止外部直接访问，只能通过本类内部方法间接操作。
    // ============================================================================
    private var webView: WebView? = null

    // ============================================================================
    // onCreate：Activity 的入口方法
    // ----------------------------------------------------------------------------
    // - 这是 Activity 生命周期中"创建时"被系统调用的第一个方法，
    //   通常在这里完成 UI 初始化、数据加载、服务启动等一次性工作。
    // - 参数 savedInstanceState：当 Activity 因配置变更（如旋转屏幕）被系统
    //   销毁又重建时，用于恢复之前保存的状态；首次启动时为 null。
    //
    // @SuppressLint("SetJavaScriptEnabled")：
    //   - 告诉 Android Studio 不要警告"开启 JS 有安全风险"。
    //   - 因为我们要在 WebView 里跑前端地图应用，必须开 JS。
    //
    // override：表示我们重写父类（ComponentActivity）的方法，Kotlin 要求显式声明。
    // ============================================================================
    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        // 调用父类 onCreate 是必须的：让父类完成它内部的初始化（如保存的实例状态恢复）
        super.onCreate(savedInstanceState)

        // 沉浸式：内容延伸到状态栏（潮汐设计稿要求全屏地图 + 顶部浮动气泡）
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)

        // ✅ 闪退兜底：启动前台服务前再做一次权限检查，缺少权限则不启动
        // （Service 内部也会再次检查，这里双保险）
        // ------------------------------------------------------------------------
        // - runCatching { ... }：Kotlin 标准库函数，等价于 try-catch，
        //   但不会抛出异常——出错时返回 Result，避免异常导致 App 闪退。
        // - TrackerService.canStartForeground(this)：检查是否具备启动前台服务的权限
        //   （Android 9+ 对前台服务有严格权限要求，如 POST_NOTIFICATIONS）。
        // - TrackerService.start(this)：启动后台追踪服务（采集位置、APP 使用情况）。
        //   注意这里用全限定名，是为了避免与下面的 import 冲突或提高可读性。
        // ------------------------------------------------------------------------
        runCatching {
            if (com.coupletracker.android.service.TrackerService.canStartForeground(this)) {
                com.coupletracker.android.service.TrackerService.start(this)
            }
        }

        // ============================================================================
        // setContent { ... }：把 Compose UI 绑定到本 Activity
        // ----------------------------------------------------------------------------
        // - 类似传统 setContentView(R.layout.xxx)，但这里用 Kotlin 代码直接描述 UI。
        // - 大括号内的所有 @Composable 函数调用构成最终的界面。
        // ============================================================================
        setContent {
            // ----------------------------------------------------------------------------
            // MaterialTheme：套用 Material Design 3 的主题样式
            // - colorScheme = lightColorScheme(...)：自定义浅色主题的颜色方案
            //   - primary：主色调（粉红色 #E75480，用于按钮、Tab 选中色等）
            //   - secondary：次色调（蓝紫色 #667EEA）
            //   - background：背景色（极浅粉 #FDF2F8，营造温柔氛围）
            // - Color(0xFFFF8B7B) 中：
            //   - 0xFF 表示完全不透明（FF=255）
            //   - E75480 是 RGB 十六进制值
            // ----------------------------------------------------------------------------
            // 潮汐主题（珊瑚配色 + 透明状态栏；地图在上，状态栏图标用深色）
            TidalTheme(darkStatusBarIcons = true) {
                // 旧 Tab 选中态已移除：区块切换由 TidalHomeScreen 内部锚点滚动管理

                // ============================================================================
                // 配对请求弹窗 + 全局轮询（覆盖所有 Tab）
                // ----------------------------------------------------------------------------
                // 背景：B 已登录过的用户重新打开 App 时直接进 MainActivity，LoginActivity 的
                // PairCard 不再显示，所以 MainActivity 必须自己处理 incoming_request 弹窗。
                // 与 LoginActivity.PairCard 的轮询逻辑一致，每 5 秒调 check_pair_status：
                //   - status == incoming_request -> AlertDialog 让 B 接受/拒绝，接受调 accept_pair
                //   - status == paired          -> setUser(me.copy(partnerId=...)) 触发 UI 与 WebView 刷新
                //   - partnerId 非空时停止轮询，省电
                // ============================================================================
                val pairUser by UserRepository.get().userFlow.collectAsState(initial = null)
                var incomingRequest by remember { mutableStateOf<com.coupletracker.android.data.CheckPairStatusResp?>(null) }
                // 已忽略的请求者 ID 集合（双保险）：拒绝后即使后端尚未清掉 pending_pair，
                // 本地也不重复弹同一个请求。用户重启 App 后集合重置（合理，给对方重试机会）。
                val dismissedRequesters = remember { mutableStateListOf<String>() }
                LaunchedEffect(pairUser?.id, pairUser?.partnerId) {
                    val me = pairUser ?: return@LaunchedEffect
                    if (!me.partnerId.isNullOrBlank()) return@LaunchedEffect  // 已配对，停止轮询
                    while (true) {
                        runCatching {
                            val resp = withContext(Dispatchers.IO) {
                                NetworkModule.rpcService.checkPairStatus(
                                    CheckPairStatusReq(myId = me.id)
                                )
                            }
                            val body = resp.body()
                            if (body != null) {
                                when (body.status) {
                                    // ✅ 已配对：写本地用户，userFlow 发新值触发 UI 重组 + WebView 重新注入
                                    "paired" -> if (!body.partnerId.isNullOrBlank()) {
                                        withContext(Dispatchers.Main) {
                                            UserRepository.get().setUser(me.copy(partnerId = body.partnerId))
                                        }
                                        return@LaunchedEffect
                                    }
                                    // 收到 A 发来的配对请求：弹窗（仅在未弹窗 + 未被本地忽略时）
                                    "incoming_request" -> {
                                        val rid = body.requesterId
                                        if (incomingRequest == null && rid != null && rid !in dismissedRequesters) {
                                            withContext(Dispatchers.Main) { incomingRequest = body }
                                        }
                                    }
                                }
                            }
                        }
                        delay(5000L)
                    }
                }
                // 收到配对请求时显示弹窗（接受/拒绝）
                incomingRequest?.let { req ->
                    AlertDialog(
                        onDismissRequest = { incomingRequest = null },
                        title = { Text("💑 配对请求") },
                        text = { Text("${req.requesterNickname ?: "TA"} 想和你配对，是否接受？") },
                        confirmButton = {
                            Button(
                                onClick = {
                                    val requesterId = req.requesterId ?: ""
                                    val me = pairUser
                                    incomingRequest = null
                                    if (me == null || requesterId.isBlank()) return@Button
                                    // 立即加入忽略集合，避免网络往返期间重复弹窗
                                    if (requesterId !in dismissedRequesters) dismissedRequesters.add(requesterId)
                                    lifecycleScope.launch(Dispatchers.IO) {
                                        val resp = runCatching {
                                            NetworkModule.rpcService.acceptPair(
                                                AcceptPairReq(myId = me.id, theirId = requesterId)
                                            )
                                        }
                                        val body = resp.getOrNull()?.body()
                                        if (body?.ok == true) {
                                            // 写本地用户，触发 userFlow 发新值 -> UI 自动刷新
                                            UserRepository.get().setUser(me.copy(partnerId = requesterId))
                                        }
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF8B7B))
                            ) { Text("接受 💕") }
                        },
                        dismissButton = {
                            TextButton(onClick = {
                                val requesterId = req.requesterId ?: ""
                                val me = pairUser
                                incomingRequest = null
                                // 立即加入忽略集合，避免 5 秒后再次弹同一个请求
                                if (requesterId.isNotBlank() && requesterId !in dismissedRequesters) {
                                    dismissedRequesters.add(requesterId)
                                }
                                if (me == null || requesterId.isBlank()) return@TextButton
                                lifecycleScope.launch(Dispatchers.IO) {
                                    // 用 REST PATCH 清掉对方 pending_pair（成熟方案：拒绝 = 删 pending 记录）
                                    // 直接 PATCH profiles 表，不依赖 reject_pair SQL 函数部署
                                    // RLS 策略 profiles_all 允许所有读写，所以可以直接更新对方记录
                                    runCatching {
                                        NetworkModule.restService.updateProfile(
                                            requesterId,
                                            mapOf(
                                                "pending_pair" to null,
                                                "pair_request_at" to null
                                            )
                                        )
                                    }
                                }
                            }) {
                                Text("拒绝", color = Color.Gray)
                            }
                        }
                    )
                }

                // ============================================================================
                // 潮汐卡片 v3：BottomSheetScaffold 单一宿主（设计稿 concept-d-tidal-cards-v3）
                // ----------------------------------------------------------------------------
                // - 背景层：全屏 Leaflet 地图 WebView（PlaceholderScreen 内缓存复用）
                // - 抽屉层：收起 200dp / 展开 = 屏高 - 125dp（不挡顶部头像气泡）
                // - 抽屉内容：位置 / 状态(AppScreen) / 统计(StatsScreen) / 我的(SettingsScreen)
                // - 顶部：「我」「TA」头像气泡常驻；底部：点状导航与滚动联动
                // ============================================================================
                TidalHomeScreen(
                    mapContent = {
                        // WebView 地图全屏（底层）
                        PlaceholderScreen(
                            icon = { Text("🗺️", fontSize = 40.sp) },
                            title = "实时地图",
                            desc = "",
                            accent = Coral,
                            useMapWebView = true
                        )
                    }
                ) { backToMap ->
                    SettingsScreen(onBackToMap = backToMap, embedded = true)
                }
            }
        }
    }

    /**
     * 构造注入脚本（每次同步读取最新 user/token，保证值不陈旧）
     *
     * ============================================================================
     * 函数说明（面向初学者）：
     * ============================================================================
     * - 这个方法的目的：返回一段 JavaScript 字符串，
     *   WebView 在加载前端页面时会执行这段 JS，
     *   把"当前用户的 Token 和信息"写入网页的 window 全局变量和 localStorage，
     *   让前端 JavaScript 可以直接拿到登录态，不需要让用户在 WebView 里再登录一次。
     *
     * - 为什么叫"注入"？因为原生 App 把数据"注入"到了网页运行时环境中，
     *   这是 Hybrid App（原生+Web 混合开发）中非常常见的"JS Bridge"技术。
     *
     * - 为什么每次都重新读取？
     *   用户可能在 App 内刚登录、刚配对、刚改昵称，
     *   如果用旧数据，前端会显示错误信息；所以每次都同步读取最新值。
     *
     * @return 返回一段可执行的 JavaScript 代码字符串
     */
    private fun buildInjectionJs(): String {
        // ============================================================================
        // 第 1 步：同步读取本地保存的 token
        // ----------------------------------------------------------------------------
        // - UserRepository.get().getToken() 是一个 suspend 函数（异步），
        //   但我们需要同步拿到值（不能让 UI 卡住），所以用 runBlocking 包一层。
        // - runBlocking { ... }：阻塞当前线程直到协程执行完毕（慎用，会卡线程，
        //   这里之所以能用，是因为本方法只在 WebView 的主线程回调中被调用，
        //   读取 DataStore 是磁盘 I/O，通常很快，不会显著卡顿）。
        // - Dispatchers.IO：把协程切换到 IO 线程池执行，避免占用主线程。
        // - runCatching { ... }.getOrNull()：出错时返回 null，避免抛异常导致 App 闪退。
        // ============================================================================
        val token = runCatching { kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) { UserRepository.get().getToken() } }.getOrNull()

        // 同步读取当前登录的用户对象（含 id、username、昵称、配对信息等）
        val u = runCatching { kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) { UserRepository.get().getUser() } }.getOrNull()

        // ============================================================================
        // 第 2 步：把用户对象转换成 JSON 字符串
        // ----------------------------------------------------------------------------
        // - u?.let { ... } ?: "null"：如果 u 为空就返回 JS 字面量 "null"
        //   （注意是字符串 "null"，不是 Kotlin 的 null；这样 JS 端 typeof 检查不会报错）
        // - JSONObject().apply { put(...) }：构造一个 JSON 对象并批量塞字段
        //   - apply 函数：在对象上下文里执行，省略重复的"对象名."
        //   - put(key, value)：往 JSON 里加一个键值对
        // - it.avatar ?: ""：如果 avatar 为 null，则用空字符串替代，
        //   防止前端 JSON.parse 后某些字段为 null 报错
        // - .toString()：把 JSONObject 序列化成 JSON 格式字符串
        // ============================================================================
        val userJson = u?.let { org.json.JSONObject().apply {
            put("id", it.id)
            put("username", it.username)
            put("nickname", it.nickname)
            put("avatar", it.avatar ?: "")
            put("gender", it.gender ?: "")
            put("coupleCode", it.coupleCode ?: "")
            put("partnerId", it.partnerId ?: "")
        }.toString() } ?: "null"

        // ============================================================================
        // 第 3 步：把 token 转成 JS 安全的字符串字面量
        // ----------------------------------------------------------------------------
        // - 如果 token 为空或空白：返回字面量 "null"（让 JS 端 typeof 判断为 null）
        // - 否则把 token 用双引号包起来变成 JS 字符串：
        //   - "abc" -> ""abc""
        //   - 同时转义 token 内的双引号，防止 token 本身包含 " 时破坏 JS 字符串
        //   - 例如 token = ab"c -> "\"ab\\\"c\""  (但 Kotlin 这层只需要转义一次)
        //   - replace("\"","\\\"")：把 " 替换成 \"（在 JS 字符串里 \" 表示一个双引号字符）
        // ============================================================================
        val tokenJs = if (token.isNullOrBlank()) "null" else "\"${token.replace("\"","\\\"")}\""

        // ============================================================================
        // 第 4 步：拼接最终的 JS 注入脚本
        // ----------------------------------------------------------------------------
        // - 使用 Kotlin 三引号字符串 """ ... """，可以原样保留换行和缩进
        // - trimIndent()：去除每行公共前导空格，让 JS 紧凑易读
        // - ${BuildConfig.SUPABASE_URL}：Kotlin 字符串模板，
        //   在编译时把 BuildConfig.SUPABASE_URL 的值嵌入到字符串中
        //
        // 脚本逻辑：
        //   ① 把 Supabase 配置、Token、用户信息挂到 window 全局对象上；
        //   ② 把同样的信息写入 localStorage（前端可能从 localStorage 读取）；
        //   ③ 调用前端的 __applyAndroidInjection() 回调（如果存在），
        //      通知前端"我刚刚塞了新数据，请重新读取用户并刷新界面"。
        //
        // 注：window.__SUPABASE_URL__ 这种命名（前后双下划线）
        //     是 JS 社区约定的"内部/私有变量"写法，不容易和业务字段撞名。
        // ============================================================================
        return """
            (function(){
              window.__SUPABASE_URL__ = "${BuildConfig.SUPABASE_URL}";
              window.__SUPABASE_ANON_KEY__ = "${BuildConfig.SUPABASE_ANON_KEY}";
              window.__AUTH_TOKEN__ = $tokenJs;
              window.__CURRENT_USER__ = $userJson;
              try {
                localStorage.setItem('sb_url',  window.__SUPABASE_URL__ || '');
                localStorage.setItem('sb_anon', window.__SUPABASE_ANON_KEY__ || '');
                localStorage.setItem('token',   window.__AUTH_TOKEN__ || '');
                localStorage.setItem('user',    typeof window.__CURRENT_USER__==='string' ? window.__CURRENT_USER__ : JSON.stringify(window.__CURRENT_USER__));
              } catch(e){}
              // 通知前端重新读取用户（解决 onPageStarted 注入时序 <-> HTML 脚本执行的竞态）
              if (typeof window.__applyAndroidInjection === 'function') { try { window.__applyAndroidInjection(); } catch(e){} }
            })();
        """.trimIndent()
    }

    // ========================================================================
    //  原生占位页 + WebView 地图（前端 dist 部署到 assets/www 后直接离线加载）
    // ========================================================================
    // ============================================================================
    // 函数说明（面向初学者）：
    // ============================================================================
    // - PlaceholderScreen：一个 Composable 函数，用于渲染"占位页面"。
    //   之所以叫"占位"：当某个功能尚未完整开发时，先用一个友好页面提示用户。
    // - 这里它有两种模式：
    //   ① useMapWebView = false：渲染普通的占位 UI（图标+标题+功能说明卡片）
    //   ② useMapWebView = true ：嵌入一个 WebView，加载前端地图页面
    //
    // 注解说明：
    // - @OptIn(ExperimentalMaterial3Api::class)：声明本函数使用了 Material3 的实验性 API
    //   （experimental API 可能不稳定，需要显式 opt-in 才能使用）
    // - @SuppressLint("SetJavaScriptEnabled")：抑制"WebView 启用 JS"的 lint 警告
    // - @Composable：标记此函数为 Compose 的 UI 函数，可以被其他 Composable 调用
    //
    // 参数说明：
    // - icon   : 顶部大图标（一个返回 UI 的 lambda）
    // - title  : 标题文本
    // - desc   : 功能说明长文本
    // - accent : 强调色（用于背景和标题）
    // - useMapWebView: 是否启用 WebView 地图模式（默认 false）
    // ============================================================================
    @OptIn(ExperimentalMaterial3Api::class)
    @SuppressLint("SetJavaScriptEnabled")
    @Composable
    fun PlaceholderScreen(
        icon: @Composable () -> Unit,
        title: String,
        desc: String,
        accent: Color,
        useMapWebView: Boolean = false
    ) {
        // ----------------------------------------------------------------------------
        // 状态收集：从 UserRepository 监听用户信息和采集频率的最新值
        // ----------------------------------------------------------------------------
        // - collectAsState：把 Flow 转换成 Compose 的 State，Flow 发新值时自动触发重组
        // - initial = null：Flow 第一次发值前用 null 占位，避免渲染时 NPE
        //
        // 这里订阅了 3 个状态：
        //   user   -> 当前登录的用户对象
        //   locSec -> 位置采集间隔（秒）
        //   appSec -> APP 使用检测间隔（秒）
        // ----------------------------------------------------------------------------
        val user by UserRepository.get().userFlow.collectAsState(initial = null)
        val locSec by UserRepository.get().locationIntervalSecFlow.collectAsState(
            initial = UserRepository.DEFAULT_LOC_INTERVAL_SEC
        )
        val appSec by UserRepository.get().appIntervalSecFlow.collectAsState(
            initial = UserRepository.DEFAULT_APP_INTERVAL_SEC
        )

        // ============================================================================
        // 分支：如果启用了 WebView 地图模式，就走下面的加载逻辑
        // ============================================================================
        if (useMapWebView) {
            // 配对请求轮询已上移到 MainActivity setContent 顶层（覆盖所有 Tab + 全局 AlertDialog），
            // 这里不再重复轮询，只负责渲染 WebView 地图。
            // ----------------------------------------------------------------------------
            // Box：Compose 中可以叠放多个子元素的容器（类似 FrameLayout）
            // 这里让它占满整个屏幕尺寸
            // ----------------------------------------------------------------------------
            Box(Modifier.fillMaxSize()) {
                // ----------------------------------------------------------------------------
                // AndroidView：Compose 中嵌入传统 View（如 WebView）的桥接组件
                // - factory = { ctx -> ... }：首次创建时执行的工厂函数，
                //   返回一个 WebView 实例。Compose 只会调用一次 factory。
                // - update = { wv -> ... }：每次 Compose 重组时调用，
                //   用于根据最新状态更新 View 的属性。
                // ----------------------------------------------------------------------------
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        // ✅ 缓存 WebView：切 Tab 回来不重新加载页面，保持实时轮询
                        // ------------------------------------------------------------------------
                        // - this@MainActivity.webView：引用 MainActivity 的成员变量
                        // - 如果之前已经创建过 WebView，从缓存里取出来复用
                        // - cached.parent != null：如果 WebView 之前被加到了别的父容器，
                        //   必须先从旧父容器移除（一个 View 只能有一个父）
                        // ------------------------------------------------------------------------
                        val cached = this@MainActivity.webView
                        if (cached != null && cached.parent != null) {
                            (cached.parent as? android.view.ViewGroup)?.removeView(cached)
                        }
                        // ------------------------------------------------------------------------
                        // cached ?: WebView(ctx).apply { ... }
                        // - Elvis 运算符 ?:：如果 cached 为空，就创建一个新的 WebView
                        // - apply { ... }：在 WebView 实例上下文里配置各种属性
                        //   （apply 内部的 this 就是 WebView 本身，可以省略前缀）
                        // ------------------------------------------------------------------------
                        cached ?: WebView(ctx).apply {
                            // ✅ 显式 LayoutParams：Compose AndroidView 有时不会自动给 match_parent
                            // ------------------------------------------------------------------------
                            // - LayoutParams 控制 View 在父容器中的尺寸
                            // - MATCH_PARENT：占满父容器（这里就是占满整个 AndroidView）
                            // - 不显式设置可能默认是 WRAP_CONTENT，导致 WebView 显示 0x0
                            // ------------------------------------------------------------------------
                            layoutParams = android.view.ViewGroup.LayoutParams(
                                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                                android.view.ViewGroup.LayoutParams.MATCH_PARENT
                            )
                            // 背景透明：避免 WebView 默认白色在加载完成前闪一下（提升视觉体验）
                            setBackgroundColor(0x00000000) // 透明背景，避免 WebView 默认白色闪烁
                            // 禁用过度滚动（拖到边缘不再有发光效果）
                            overScrollMode = android.view.View.OVER_SCROLL_NEVER
                            // 不作为滚动容器：让父容器处理滚动，避免地图手势冲突
                            isScrollContainer = false
                            // ✅ 渲染策略：硬件加速（软件渲染会导致地图瓦片极度卡顿）
                            //    闪退防护靠 onRenderProcessGone + largeHeap，不用软件渲染
                            // ------------------------------------------------------------------------
                            // - LAYER_TYPE_HARDWARE：用 GPU 加速绘制，地图瓦片渲染流畅
                            // - 软件渲染（LAYER_TYPE_SOFTWARE）会非常卡，因为地图瓦片多
                            // ------------------------------------------------------------------------
                            setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)

                            // ============================================================================
                            // WebView 的 WebSettings：配置浏览器内核的各种开关
                            // ----------------------------------------------------------------------------
                            // 这里开了几乎所有的"现代化网页功能"开关，
                            // 因为前端地图用到了 JS、DOM Storage、图片加载、缩放等
                            // ============================================================================
                            settings.javaScriptEnabled = true      // 启用 JS（前端地图必须）
                            settings.domStorageEnabled = true       // 启用 DOM Storage（localStorage 等）
                            settings.databaseEnabled = true        // 启用 Web SQL/IndexedDB
                            settings.allowFileAccess = true         // 允许访问 file:// 资源
                            settings.allowContentAccess = true      // 允许访问 ContentProvider
                            // ✅ 关键：file:// HTML 必须开这个才能加载外部 HTTPS 瓦片图片
                            // ------------------------------------------------------------------------
                            // - 这两个开关默认是 false（Android 4.1+ 出于安全考虑）
                            // - 但本地 file:// 加载的前端页面需要请求 HTTPS 地图瓦片，
                            //   不开这两个开关，瓦片图加载不出来，地图就一片空白
                            // ------------------------------------------------------------------------
                            settings.allowFileAccessFromFileURLs = true
                            settings.allowUniversalAccessFromFileURLs = true
                            settings.useWideViewPort = true         // 启用宽视口（让 HTML viewport 生效）
                            settings.loadWithOverviewMode = true    // 首次加载按屏幕宽度缩放
                            settings.setSupportZoom(true)          // 支持缩放手势
                            settings.builtInZoomControls = true    // 显示内置缩放控件
                            settings.displayZoomControls = false   // 不显示缩放按钮（保留手势）
                            // 混合内容兼容模式：HTTPS 页面可以加载 HTTP 资源（兼容老接口）
                            settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                            settings.blockNetworkImage = false      // 允许加载网络图片
                            settings.loadsImagesAutomatically = true // 自动加载图片
                            settings.cacheMode = WebSettings.LOAD_NO_CACHE  // 平板内存有限，不缓存瓦片
                            // 正确的 User-Agent，避免被 OSM/ArcGIS 瓦片服务器限流
                            // - 在默认 UA 后追加 App 名+版本号，瓦片服务器可识别为正常客户端
                            settings.userAgentString = settings.userAgentString + " CoupleTracker/1.0"

                            // ============================================================================
                            // WebViewClient：处理页面加载生命周期和导航事件
                            // - object : WebViewClient() { ... }：创建一个匿名内部类实例
                            //   （Kotlin 的 object 表达式，类似 Java 的匿名内部类）
                            // ============================================================================
                            webViewClient = object : WebViewClient() {

                                // ----------------------------------------------------------------------------
                                // onPageStarted：页面开始加载时回调
                                // - 参数 favicon：网站的 favicon 位图（通常已废弃为 null）
                                // ----------------------------------------------------------------------------
                                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                    super.onPageStarted(view, url, favicon)
                                    // 🚨 必须在主线程立刻注入：onPageStarted 时 evaluateJavascript 对 file:// 页面基本是同步生效的
                                    // 之前放在 lifecycleScope.launch(IO) 会延迟几十~几百毫秒，刚好错过 HTML <script> 的 readUser() 20次重试窗口，导致 me 永远 null，永远"等待位置"
                                    // ------------------------------------------------------------------------
                                    // - view ?: return：view 为空时直接返回（Elvis + return 联合用法）
                                    // - evaluateJavascript(js, null)：
                                    //   - 第 1 个参数：要执行的 JS 代码字符串
                                    //   - 第 2 个参数：JS 执行结果回调，这里不需要结果，传 null
                                    // - runCatching { ... }：防止 JS 执行抛异常导致 App 崩溃
                                    // ------------------------------------------------------------------------
                                    view ?: return
                                    runCatching { view.evaluateJavascript(buildInjectionJs(), null) }
                                }

                                // ----------------------------------------------------------------------------
                                // onPageFinished：页面加载完成时回调
                                // - 这里再注入一次作为"双保险"
                                // ----------------------------------------------------------------------------
                                override fun onPageFinished(view: WebView?, url: String?) {
                                    super.onPageFinished(view, url)
                                    // 双保险：onPageFinished 再注入一次 + 触发前端刷新回调
                                    // （HTML里的 Leaflet 初始化可能早于 onPageStarted，需要再手动通知）
                                    view ?: return
                                    runCatching {
                                        // 再执行一次注入脚本，确保前端拿到最新数据
                                        view.evaluateJavascript(buildInjectionJs(), null)
                                        // 100ms 后再发一次"信号"（如果前端在轮询用户，就当再踢一次）
                                        // ------------------------------------------------------------------------
                                        // - Handler(Looper.getMainLooper())：获取主线程的 Handler
                                        // - postDelayed({...}, 120)：120ms 后在主线程执行该 Runnable
                                        // - 为什么延迟 120ms？给前端一些时间完成 DOM 初始化后再通知
                                        // ------------------------------------------------------------------------
                                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                            runCatching { view.evaluateJavascript("(function(){try{window.__applyAndroidInjection&&window.__applyAndroidInjection();}catch(e){}})();", null) }
                                        }, 120)
                                    }
                                }

                                // ✅ 平板闪退修复：WebView 渲染进程崩溃时不杀 App
                                // ----------------------------------------------------------------------------
                                // - onRenderProcessGone：当 WebView 渲染进程崩溃时回调
                                // - 返回 true 表示自己处理（不崩溃 App），返回 false 让系统处理（App 会崩溃）
                                // ----------------------------------------------------------------------------
                                override fun onRenderProcessGone(
                                    view: WebView?,
                                    detail: android.webkit.RenderProcessGoneDetail?
                                ): Boolean {
                                    android.util.Log.e("CT-WebView", "渲染进程崩溃 didCrash=${detail?.didCrash()}")
                                    // 销毁崩溃的 WebView
                                    view?.let { w ->
                                        runCatching {
                                            // 先从父容器移除（避免 "View 被重复添加" 异常）
                                            (w.parent as? android.view.ViewGroup)?.removeView(w)
                                            // 调用 destroy() 释放 WebView 持有的 native 资源
                                            w.destroy()
                                        }
                                    }
                                    // 清除缓存，下次进入地图时自动重建
                                    this@MainActivity.webView = null
                                    return true  // 返回 true = 自己处理，App 不崩溃
                                }
                            }
                            // ----------------------------------------------------------------------------
                            // 保存当前 WebView 引用，便于下面 CookieManager 使用
                            // - val webViewRef = this：把 apply 内部的 this（WebView）保存到局部变量
                            //   这样在下面的匿名对象里也能引用到（避免歧义）
                            // ----------------------------------------------------------------------------
                            val webViewRef = this
                            // ============================================================================
                            // CookieManager：管理 Cookie，让 WebView 的请求带上登录态
                            // - setAcceptCookie(true)：允许接收 Cookie
                            // - setAcceptThirdPartyCookies(wv, true)：允许第三方 Cookie（同源策略放宽）
                            //   （某些情况下 Supabase 鉴权依赖 Cookie，需要开）
                            // ============================================================================
                            CookieManager.getInstance().apply {
                                setAcceptCookie(true)
                                setAcceptThirdPartyCookies(webViewRef, true)
                            }
                            // ============================================================================
                            // WebChromeClient：处理浏览器 UI 层面的事件
                            // （如 JS 的 console.log、alert、prompt、文件选择器、进度条等）
                            // ============================================================================
                            webChromeClient = object : WebChromeClient() {
                                // ✅ 把 JS 的 console.log/warn/error 都桥接到 Android Logcat
                                // ----------------------------------------------------------------------------
                                // - 这样开发时可以在 Android Studio Logcat 看到 JS 输出的日志
                                // - 用 TAG = "CT-WebView"，方便过滤
                                // ----------------------------------------------------------------------------
                                override fun onConsoleMessage(
                                    consoleMessage: android.webkit.ConsoleMessage?
                                ): Boolean {
                                    // 取出消息内容、来源文件名、行号
                                    val msg = consoleMessage?.message() ?: "?"
                                    // 截取文件名最后一段，简化日志输出
                                    val src = consoleMessage?.sourceId()?.substringAfterLast('/') ?: "?"
                                    val ln = consoleMessage?.lineNumber() ?: 0
                                    // 根据消息级别输出到不同 Log 级别（error/warn/debug）
                                    when (consoleMessage?.messageLevel()) {
                                        android.webkit.ConsoleMessage.MessageLevel.ERROR ->
                                            android.util.Log.e("CT-WebView", "JS ❌ [$src:$ln] $msg")
                                        android.webkit.ConsoleMessage.MessageLevel.WARNING ->
                                            android.util.Log.w("CT-WebView", "JS ⚠️ [$src:$ln] $msg")
                                        else ->
                                            android.util.Log.d("CT-WebView", "JS [$src:$ln] $msg")
                                    }
                                    return true  // 返回 true 表示已处理
                                }
                            }
                            // ============================================================================
                            // 加载页面：优先加载本地离线地图，找不到则加载兜底 HTML
                            // ----------------------------------------------------------------------------
                            // - assets.list("www")：列出 assets/www 目录下的所有文件
                            // - 不为空说明前端打包产物已部署到 assets 中，加载本地 file:// 资源
                            // - 为空时调用 buildFallbackMapHtml() 生成兜底页面
                            // - loadDataWithBaseURL：把 HTML 字符串作为内容加载到 WebView
                            //   - 第 1 个参数 baseURL：相对 URL 的 base，null 表示无
                            //   - 第 2 个参数 data：HTML 字符串
                            //   - 第 3 个参数 mimeType：text/html
                            //   - 第 4 个参数 encoding：UTF-8
                            //   - 第 5 个参数 historyUrl：历史记录 URL，null 表示无
                            // - runCatching { ... }.getOrElse { ... }：
                            //   try 失败时执行兜底逻辑，永远不抛异常
                            // ============================================================================
                            // 优先加载本地离线地图（assets/www/index.html）
                            // 若该资源不存在，回退到加载占位网页（不会崩）
                            runCatching {
                                val list = ctx.assets.list("www")
                                if (!list.isNullOrEmpty()) {
                                    // 加载本地前端页面（file:// 协议访问 assets 目录）
                                    // - #/map 是前端路由的 hash 模式路径，告诉前端显示地图页
                                    loadUrl("file:///android_asset/www/index.html#/map")
                                } else {
                                    // 没有前端产物，加载兜底 HTML
                                    loadDataWithBaseURL(
                                        null, buildFallbackMapHtml(),
                                        "text/html", "UTF-8", null
                                    )
                                }
                            }.getOrElse {
                                // 异常兜底：连读 assets 都失败时也加载兜底 HTML
                                loadDataWithBaseURL(
                                    null, buildFallbackMapHtml(),
                                    "text/html", "UTF-8", null
                                )
                            }
                            // 把创建好的 WebView 存到 MainActivity 的成员变量，下次复用
                            this@MainActivity.webView = this
                        }   // end: cached ?: WebView(ctx).apply { ... }
                    },
                    // ============================================================================
                    // update 回调：每次 Compose 重组时被调用
                    // ----------------------------------------------------------------------------
                    // - 这里在每次切回地图 Tab 时都会执行：
                    //   ① 重新注入用户信息（防止刚登录/配对后数据没更新）
                    //   ② 调用 Leaflet 的 map.invalidateSize() 强制重新计算尺寸
                    //      （WebView 在后台被切走/回来时尺寸可能变 0，需要通知地图重新布局）
                    //   ③ 调用前端的 poll() 函数（如果存在）触发立即位置刷新
                    // ============================================================================
                    update = { wv ->
                        // 🚨 Tab 切换回来时（PlaceholderScreen 重组会触发 update）
                        //    ① 重新注入用户信息：防止刚登录/刚配对后切回地图页，前端仍用旧数据
                        //    ② 踢一下地图尺寸：防止 WebView 在后台状态中尺寸被清零
                        //    ③ 触发前端立即刷新位置：不等待下次轮询
                        val js = buildInjectionJs() + "; try{ var m = (typeof map !== 'undefined' && map); if (m) { m.invalidateSize(true); setTimeout(function(){m.invalidateSize(true);},300);} } catch(e){} try{ if(typeof poll==='function') poll(); } catch(e){}"
                        runCatching { wv.evaluateJavascript(js, null) }
                    }
                )
                // 采集频率卡片已移除（会遮挡底部抽屉）
            }
            // ----------------------------------------------------------------------------
            // return：跳过后面的占位 UI 代码（避免既显示 WebView 又显示占位文字）
            // ----------------------------------------------------------------------------
            return
        }
        // ============================================================================
        // 非 WebView 模式的占位 UI：用 Column 垂直排列各组件
        // ----------------------------------------------------------------------------
        // Modifier 链式调用（用 . 连接）说明：
        // - .fillMaxSize()：占满父容器
        // - .padding(...)：内边距，左右 20dp、上下 28dp
        // - .verticalScroll(rememberScrollState())：让整个 Column 可垂直滚动
        //   - rememberScrollState()：记住滚动位置，重组时保持不变
        // ============================================================================
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 28.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally  // 子元素水平居中
        ) {
            // ----------------------------------------------------------------------------
            // 顶部图标圆角方框
            // - Box：可以容纳单个或多个子元素的容器
            // - .size(88.dp)：固定尺寸 88dp（dp 是密度无关像素，1dp ≈ 1px 在 160dpi 屏上）
            // - .background(color, shape)：设置背景色和形状
            //   - accent.copy(alpha = 0.12f)：把 accent 颜色透明度调到 12%，作为柔和背景
            //   - RoundedCornerShape(28.dp)：28dp 圆角的方形
            // - contentAlignment = Center：子元素在 Box 内居中
            // ----------------------------------------------------------------------------
            Box(
                Modifier
                    .size(88.dp)
                    .background(accent.copy(alpha = 0.12f), RoundedCornerShape(28.dp)),
                contentAlignment = Alignment.Center
            ) {
                icon()  // 渲染传入的 icon Composable
            }
            // Spacer：纯空白占位元素，类似 margin
            Spacer(Modifier.height(18.dp))
            // 标题文本
            // - fontSize = 24.sp：文字大小 24sp（sp = scaled pixel，会跟随系统字号缩放）
            // - fontWeight = Bold：粗体
            // - color = Color(0xFF3D2E2A)：深灰色（接近黑但更柔和）
            Text(title, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color(0xFF3D2E2A))
            Spacer(Modifier.height(10.dp))
            // ----------------------------------------------------------------------------
            // 副标题：显示当前账号
            // - ${ ... }：Kotlin 字符串模板，把表达式结果插入字符串
            // - user?.username ?: "-"：用户名为空时显示 "-"（防 NPE 写法）
            // ----------------------------------------------------------------------------
            Text(
                "正在为 ${"@" + (user?.username ?: "-")} 准备中...",
                color = Color(0xFFA89890), fontSize = 13.sp
            )
            Spacer(Modifier.height(22.dp))

            // ============================================================================
            // 第 1 张卡片：后台采集状态
            // ----------------------------------------------------------------------------
            // - Card：Material Design 的卡片组件，自带阴影和圆角
            // - shape = RoundedCornerShape(18.dp)：18dp 圆角
            // - colors = cardColors(containerColor = White)：背景白色
            // - 内部 Column + padding(20.dp)：内容列 + 20dp 内边距
            // ============================================================================
            Card(
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White)
            ) {
                Column(Modifier.padding(20.dp)) {
                    Text(
                        "💕 后台采集状态",
                        fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color(0xFF3D2E2A)
                    )
                    Spacer(Modifier.height(10.dp))
                    // ----------------------------------------------------------------------------
                    // Row：水平排列子元素，类似 LinearLayout horizontal
                    // - verticalAlignment = CenterVertically：子元素垂直居中
                    // - 第一项是一个小圆点（Box + 圆形 shape + 绿色背景）
                    // - Spacer(weight(1f))：弹性空白，把后面的元素挤到右边
                    // - 最后一项是数值文本
                    // ----------------------------------------------------------------------------
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // 一个 10dp 的圆点（绿色，表示"运行中"）
                        Box(Modifier.size(10.dp).background(Color(0xFF48BB78), RoundedCornerShape(50)))
                        Spacer(Modifier.width(8.dp))
                        Text("位置上报", color = Color(0xFF3D2E2A), fontSize = 13.sp)
                        Spacer(Modifier.weight(1f))
                        Text("每 ${locSec} 秒", color = Color(0xFFA89890), fontSize = 12.sp)
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(10.dp).background(Color(0xFF48BB78), RoundedCornerShape(50)))
                        Spacer(Modifier.width(8.dp))
                        Text("APP 使用", color = Color(0xFF3D2E2A), fontSize = 13.sp)
                        Spacer(Modifier.weight(1f))
                        Text("每 ${appSec} 秒检测", color = Color(0xFFA89890), fontSize = 12.sp)
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(10.dp).background(Color(0xFF48BB78), RoundedCornerShape(50)))
                        Spacer(Modifier.width(8.dp))
                        Text("数据存储", color = Color(0xFF3D2E2A), fontSize = 13.sp)
                        Spacer(Modifier.weight(1f))
                        Text("云端 Supabase", color = Color(0xFFA89890), fontSize = 12.sp)
                    }
                }
            }

            Spacer(Modifier.height(18.dp))
            // ============================================================================
            // 第 2 张卡片：功能说明长文本
            // - lineHeight = 22.sp：行高 22sp，提升长文本可读性
            // ============================================================================
            Card(
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White)
            ) {
                Column(Modifier.padding(20.dp)) {
                    Text("📝 功能说明", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color(0xFF3D2E2A))
                    Spacer(Modifier.height(12.dp))
                    Text(desc, color = Color(0xFF4A5568), fontSize = 13.sp, lineHeight = 22.sp)
                }
            }

            Spacer(Modifier.height(30.dp))
            // 底部：数据安全提示 + 版本号
            Text(
                "所有数据已安全保存到云端 ✅",
                color = Color(0xFF48BB78), fontSize = 12.sp, fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.height(6.dp))
            // 版本号：从 BuildConfig 读取编译期版本名
            // - BuildConfig.VERSION_NAME：在 build.gradle 中定义的 versionName
            Text(
                "版本 v${BuildConfig.VERSION_NAME}（测试版）",
                color = Color(0xFFA0AEC0), fontSize = 11.sp
            )
        }
    }

    /**
     * assets/www 读不到时的最小兜底页：带样式提示 + 1s 后自动重试跳 assets
     *
     * ============================================================================
     * 函数说明（面向初学者）：
     * ============================================================================
     * - 当 assets/www 目录没有前端产物时，构造一个最小的 HTML 页面作为兜底。
     * - 该页面会：
     *   ① 显示一个友好的"加载中"提示，告诉用户可能的问题和排查方式；
     *   ② 1 秒后自动尝试重新跳转到 file:///android_asset/www/index.html#/map，
     *      万一是偶发的资源加载失败，用户多等 1 秒就能自动恢复。
     *
     * - 使用 Kotlin 三引号字符串 """...""" 原样保留 HTML 内容，再用 trimIndent()
     *   去除每行公共前导空格，让 HTML 紧凑易读。
     * - 这是一种"防御式编程"：任何异常都不让 App 崩，而是给用户一个能看的页面。
     *
     * @return 兜底 HTML 字符串
     */
    private fun buildFallbackMapHtml(): String = """
        <!doctype html><html><head><meta charset="utf-8">
        <meta name="viewport" content="width=device-width,initial-scale=1">
        <title>情侣地图 💕</title>
        <style>html,body{margin:0;padding:0;height:100%;background:#fdf2f8;font-family:-apple-system,"PingFang SC","Microsoft YaHei",sans-serif;}
        .c{display:flex;align-items:center;justify-content:center;height:100%;padding:24px;text-align:center;flex-direction:column;}
        h1{color:#e75480;font-size:22px;margin:0 0 10px;}p{color:#718096;font-size:13px;line-height:1.8;}
        .e{color:#e53e3e;}</style></head><body>
        <div class="c">
          <div style="font-size:56px;">🗺️</div>
          <h1>正在加载情侣地图</h1>
          <p>如果长时间停留在此页，请退出 APP 后重新打开一次。<br>
          或切换到「我的」检查「上报状态」是否有定位权限失败提示。</p>
          <p class="e">如果报错：请确认已授予「定位」「通知」「使用情况访问」三项权限</p>
        </div>
        <script>setTimeout(function(){try{location.href='file:///android_asset/www/index.html#/map';}catch(e){}},1000);</script>
        </body></html>
    """.trimIndent()


    // ============================================================================
    // 占位方法：暂时不使用 WebView 切换路由
    // - 保留这个方法是为了将来扩展（比如用 WebView.loadUrl 切换前端路由）
    // - 目前所有 Tab 切换由 Compose 的 when(selected) 直接渲染不同 Composable
    // ============================================================================
    private fun navigate(path: String) { /* 占位，暂时不用 WebView */ }

    // ============================================================================
    // SettingsScreen：设置页（"我的" Tab 的内容）
    // ----------------------------------------------------------------------------
    // 这个 Composable 渲染整个设置页，包含以下模块（自上而下）：
    //   1. 顶部用户信息（头像 + 昵称 + 用户名）
    //   2. 配对状态卡片：
    //      - 已配对 → 显示配对人信息
    //      - 未配对 → 显示自己的配对码（可复制）+ 输入对方配对码的表单
    //   3. 采集频率设置卡片（位置/APP 使用，Slider 拖动调频率）
    //   4. 上报状态显示（位置/APP 最近一次上报成功/失败）
    //   5. "看地图" + "重启服务" 按钮
    //   6. 云端服务信息（Supabase 地址）
    //   7. 账号管理（账号信息 + 退出登录）
    //   8. 底部版本号和后端/前端地址
    //
    // @OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)：
    //   - 声明使用了 Material3 和 Layout 的实验性 API（FlowRow 等）
    //
    // 参数 onBackToMap：用户点击"看地图"按钮时调用，切回地图 Tab
    // ============================================================================
    @OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
    @Composable
    fun SettingsScreen(onBackToMap: () -> Unit, embedded: Boolean = false) {
        // ============================================================================
        // 状态收集：订阅用户信息、采集频率的最新值
        // - 与 PlaceholderScreen 类似，但这里是设置页，需要根据这些值渲染 UI
        // ============================================================================
        val user by UserRepository.get().userFlow.collectAsState(initial = null)
        val locSec by UserRepository.get().locationIntervalSecFlow.collectAsState(
            initial = UserRepository.DEFAULT_LOC_INTERVAL_SEC
        )
        val appSec by UserRepository.get().appIntervalSecFlow.collectAsState(
            initial = UserRepository.DEFAULT_APP_INTERVAL_SEC
        )
        // ============================================================================
        // Slider 的临时值：拖动时实时显示，松手才写入仓库
        // ----------------------------------------------------------------------------
        // - remember(locSec) {...}：key 为 locSec，当 locSec 变化时重新初始化 tmpLoc
        // - mutableFloatStateOf：可观察的 Float 状态（专为 Float 优化，避免装箱开销）
        // - 不直接用仓库值的原因：
        //   用户拖动 Slider 时希望实时看到数字变化，但写入仓库有 IO 延迟，
        //   所以先用临时值响应 UI，松手（onValueChangeFinished）时再写仓库
        // ============================================================================
        // Slider 的临时值（拖动时实时显示，松手写仓库）
        val tmpLoc = remember(locSec) { mutableFloatStateOf(locSec.toFloat()) }
        val tmpApp = remember(appSec) { mutableFloatStateOf(appSec.toFloat()) }
        // ============================================================================
        // LaunchedEffect：当 key 变化时启动一个协程执行副作用
        // ----------------------------------------------------------------------------
        // - 这里当 locSec/appSec 变化时（比如仓库被其他地方更新），
        //   把仓库值同步回 Slider 的临时值，保持 UI 与仓库一致
        // - LaunchedEffect 在 Compose 进入/离开组合时自动管理协程生命周期
        // ============================================================================
        // 确保仓库值变化时同步回临时值
        LaunchedEffect(locSec) { tmpLoc.floatValue = locSec.toFloat() }
        LaunchedEffect(appSec) { tmpApp.floatValue = appSec.toFloat() }
        // ============================================================================
        // 设置页根容器：Column（垂直滚动 + 浅粉色背景）
        // ============================================================================
        Column(
            // 嵌入潮汐抽屉时禁用自身滚动，由外层抽屉统一滚动
            if (embedded) Modifier.fillMaxWidth()
            else Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
        ) {
            // ============================================================================
            // 顶部：大爱心 emoji + 用户信息
            // ----------------------------------------------------------------------------
            // - Row：水平排列
            // - displayName：用户的展示名（优先昵称，回退用户名）
            // - "@" + username：用户名前加 @ 符号（社交账号习惯写法）
            // ============================================================================
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("💕 ", fontSize = 48.sp)
                Column {
                    Text(
                        user?.displayName ?: "未登录",
                        fontSize = 22.sp,
                        color = Color(0xFF3D2E2A),
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "@" + (user?.username ?: "-"),
                        color = Color(0xFFA89890), fontSize = 13.sp
                    )
                }
            }

            Spacer(Modifier.height(20.dp))

            // ============================================================================
            // 配对相关状态变量
            // ----------------------------------------------------------------------------
            // - code       : 当前用户的配对码（从 user 中读取，转大写显示）
            // - myPartnerId: 当前用户的伴侣 ID（UUID 格式，未配对时为 null/空）
            // - pairInput  : 用户输入的对方配对码（rememberSaveable 在配置变更时保留）
            // - pairMsg    : 配对结果提示文本（成功/失败）
            // - pairLoading: 配对中 loading 状态
            // - copyTip    : "复制"按钮的临时提示文字（"已复制" 1.5 秒后消失）
            // - hasPartner : 三态：null=加载中，true=已配对，false=未配对
            // - partnerName: 配对人昵称（异步从网络拉取）
            // ============================================================================
            val code = (user?.coupleCode ?: "").uppercase()
            val myPartnerId = user?.partnerId
            var pairInput by rememberSaveable { mutableStateOf("") }
            var pairMsg by rememberSaveable { mutableStateOf("") }
            var pairLoading by rememberSaveable { mutableStateOf(false) }
            var copyTip by remember { mutableStateOf("") }
            // 是否已配对：优先用 partner_id 查（不依赖共享 couple_code）
            var hasPartner by remember { mutableStateOf<Boolean?>(null) }
            var partnerName by remember { mutableStateOf("") }
            // ============================================================================
            // LaunchedEffect(code, myPartnerId)：当 code 或 partnerId 变化时重新检查配对状态
            // ----------------------------------------------------------------------------
            // - 启动一个协程，在 IO 线程上执行：
            //   ① 用正则校验 myPartnerId 是否是合法 UUID
            //   ② 是合法 UUID → hasPartner=true，并调用 API 拉取对方昵称
            //   ③ 否则 → hasPartner=false（未配对）
            // - 用 withContext(Dispatchers.IO)：切到 IO 线程避免阻塞主线程
            // ============================================================================
            LaunchedEffect(code, myPartnerId) {
                withContext(Dispatchers.IO) {
                    // UUID 格式校验
                    // ------------------------------------------------------------------------
                    // - Regex("...")：构造一个正则表达式对象
                    // - RegexOption.IGNORE_CASE：忽略大小写
                    // - 这个正则匹配标准 UUID 格式（8-4-4-4-12 个十六进制字符）
                    // ------------------------------------------------------------------------
                    val uuidPattern = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$", RegexOption.IGNORE_CASE)
                    if (!myPartnerId.isNullOrBlank() && uuidPattern.matches(myPartnerId)) {
                        // ✅ partner_id 是合法 UUID = 已配对
                        hasPartner = true
                        // 只在还没有 partnerName 时拉取，避免重复请求
                        if (partnerName.isBlank()) {
                            // - NetworkModule.restService.getProfile(id)：调用后端 API 查询用户资料
                            // - runCatching {...}.getOrNull()?.body()?.firstOrNull()?.let：
                            //   一长串链式安全调用，任何一步失败都不会崩
                            runCatching {
                                NetworkModule.restService.getProfile(id = myPartnerId)
                            }.getOrNull()?.body()?.firstOrNull()?.let { partner ->
                                // - partner.nickname.ifBlank { partner.username }：
                                //   昵称为空时回退到用户名
                                partnerName = partner.nickname.ifBlank { partner.username }
                            }
                        }
                    } else {
                        // ❌ 没有 partner_id 或格式非法 = 未配对
                        hasPartner = false
                        partnerName = ""
                    }
                }
            }

            // ============================================================================
            // 局部函数：复制配对码到系统剪贴板
            // ----------------------------------------------------------------------------
            // - getSystemService(CLIPBOARD_SERVICE) as ClipboardManager：获取剪贴板服务
            // - ClipData.newPlainText("couple_code", code)：构造一段纯文本剪贴板数据
            // - cm.setPrimaryClip(...)：写入系统剪贴板
            // - copyTip = "已复制"：更新 UI 显示提示
            // - lifecycleScope.launch { delay(1500); copyTip = "" }：
            //   1.5 秒后清空提示（避免一直显示"已复制"）
            // ============================================================================
            fun copyCoupleCode() {
                if (code.isBlank()) return
                runCatching {
                    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("couple_code", code))
                }
                copyTip = "已复制"
                lifecycleScope.launch { delay(1500); copyTip = "" }
            }

            // ============================================================================
            // 已配对状态：显示与TA绑定的卡片
            // ----------------------------------------------------------------------------
            // - hasPartner == true 表示用户已经有伴侣
            // - 这时配对码 + 输入框都隐藏，只显示"已绑定"的卡片
            // - 卡片背景 #F0FFF4（浅绿色），传达"成功"语义
            // - partnerName.ifBlank { "TA" }：昵称为空时显示泛指"TA"
            // ============================================================================
            // 🎯 已配对状态 → 配对码 + 配对按钮 全部消失，只显示配对详情
            if (hasPartner == true) {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFF0FFF4))
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("❤️", fontSize = 28.sp)
                            Spacer(Modifier.width(12.dp))
                            Column {
                                // 标题：已与谁绑定
                                Text(
                                    "💞 已与 ${partnerName.ifBlank { "TA" }} 绑定",
                                    fontWeight = FontWeight.ExtraBold,
                                    fontSize = 18.sp,
                                    color = Color(0xFF2F855A)
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    "配对人：${partnerName.ifBlank { "加载中..." }}",
                                    fontSize = 13.sp,
                                    color = Color(0xFF38A169)
                                )
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        // Divider：分隔线
                        Divider(color = Color(0xFFC6F6D5))
                        Spacer(Modifier.height(10.dp))
                        // 三条使用提示
                        Text("💡 去地图页查看彼此实时位置", fontSize = 12.sp, color = Color(0xFF38A169))
                        Text("💡 去应用/统计页查看TA的动态", fontSize = 12.sp, color = Color(0xFF38A169))
                        Text("💡 地图抽屉可查看TA的运动轨迹", fontSize = 12.sp, color = Color(0xFF38A169))
                    }
                }
            } else {
                // ============================================================================
                // 未配对 / 加载中：显示自己的配对码（含复制按钮）
                // ----------------------------------------------------------------------------
                // - 这是 hasPartner != true 的分支（包括 false 和 null 两种情况）
                // - 卡片内显示：
                //   ① "配对码" 标签
                //   ② 大字号显示自己的配对码（letterSpacing=4.sp 增加字间距）
                //   ③ 一个"复制"按钮（OutlinedButton，描边样式）
                //   ④ 底部提示文字（说明如何配对）
                // ============================================================================
                // —— 未配对 / 加载中：显示我的配对码（含复制按钮）——
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White)
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text("配对码", color = Color(0xFFA89890), fontSize = 12.sp)
                        Spacer(Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            // 配对码大字显示，留白处理空字符串
                            Text(
                                if (code.isBlank()) "暂无" else code,
                                fontSize = 30.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = Color(0xFFFF8B7B),
                                letterSpacing = 4.sp
                            )
                            Spacer(Modifier.weight(1f))
                            // 只有配对码不为空时才显示复制按钮
                            if (code.isNotBlank()) {
                                // OutlinedButton：描边按钮（与 Button 的实心样式对比）
                                // - border = BorderStroke(1.dp, color)：1dp 粉色描边
                                // - contentPadding：内边距，比默认更紧凑
                                // - shape = RoundedCornerShape(50)：完全圆角（胶囊形）
                                OutlinedButton(
                                    onClick = { copyCoupleCode() },
                                    border = BorderStroke(1.dp, Color(0xFFFF8B7B)),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                    shape = RoundedCornerShape(50)
                                ) {
                                    Text(
                                        "📋",
                                        color = Color(0xFFFF8B7B),
                                        fontSize = 12.sp
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    // 按钮文字：复制后短暂显示"已复制"，否则显示"复制"
                                    Text(
                                        if (copyTip.isNotBlank()) copyTip else "复制",
                                        color = Color(0xFFFF8B7B), fontSize = 12.sp
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        // 底部提示：未配对 vs 加载中，文案不同
                        Text(
                            if (hasPartner == false)
                                "把这串码发给TA，让TA在下面或登录页「配对」输入即可绑定"
                            else "正在加载绑定状态...",
                            color = Color(0xFFA89890),
                            fontSize = 12.sp
                        )
                    }
                }
            }

            // ============================================================================
            // 未配对：显示"输入对方配对码"的表单
            // ----------------------------------------------------------------------------
            // - hasPartner != true 时显示（包括 false 和 null 加载中）
            // - 用户可以输入对方的配对码，点击"立即配对"调用后端 RPC 完成配对
            // ============================================================================
            // —— 未配对：显示"输入TA的配对码"表单（RPC pair_by_code 极简配对）——
            if (hasPartner != true) {
                Spacer(Modifier.height(14.dp))
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White)
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            "🔗 还没绑定？在这里输入TA的配对码",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF3D2E2A)
                        )
                        Spacer(Modifier.height(8.dp))
                        // ============================================================================
                        // OutlinedTextField：Material 的描边文本输入框
                        // ----------------------------------------------------------------------------
                        // - value：当前文本（受控组件，必须配合 onValueChange）
                        // - onValueChange：文本变化回调，这里 trim 去空格 + uppercase 转大写
                        // - singleLine = true：单行输入（不换行）
                        // - label：输入框的浮动标签（"TA 的配对码（6 位）"）
                        // - leadingIcon：输入框左侧的图标（这里是 emoji 🔗）
                        // - trailingIcon：输入框右侧的"粘贴"按钮
                        // ============================================================================
                        OutlinedTextField(
                            value = pairInput,
                            onValueChange = { pairInput = it.trim().uppercase() },
                            singleLine = true,
                            label = { Text("TA 的配对码（6 位）") },
                            leadingIcon = { Text("🔗", fontSize = 18.sp) },
                            trailingIcon = {
                                // 粘贴按钮：从系统剪贴板读取内容到输入框
                                TextButton(onClick = {
                                    runCatching {
                                        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        val clip = cm.primaryClip
                                        // 防御性判断：剪贴板可能为空或没有 item
                                        if (clip != null && clip.itemCount > 0) {
                                            pairInput = clip.getItemAt(0).text.toString().trim().uppercase()
                                        }
                                    }
                                }) { Text("粘贴", fontSize = 12.sp, color = Color(0xFF3A9E91)) }
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                        // ============================================================================
                        // 配对结果提示文字
                        // - 只有 pairMsg 不为空时才显示
                        // - 颜色根据内容判断：含"成功"用绿色，否则用红色
                        // ============================================================================
                        if (pairMsg.isNotBlank()) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                pairMsg,
                                color = if (pairMsg.contains("成功")) Color(0xFF2F855A) else Color(0xFFE53E3E),
                                fontSize = 12.sp
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                        // ============================================================================
                        // "立即配对" 按钮 + 网络请求逻辑
                        // ----------------------------------------------------------------------------
                        // 点击流程：
                        //   1. 校验用户是否登录（me 为 null 时报错返回）
                        //   2. 进入 loading 状态，清空提示
                        //   3. 在 IO 协程中调用 NetworkModule.rpcService.pairByCode() 发起配对
                        //   4. 根据响应分支：
                        //      - 成功 + paired=true → 更新本地用户 partnerId，UI 切换到"已配对"
                        //      - waiting=true      → 提示"等待对方也输入你的码"
                        //      - CODE_NOT_FOUND    → 对方配对码不存在
                        //      - CANNOT_PAIR_SELF  → 不能和自己配对
                        //      - 网络异常          → 显示异常信息
                        //   5. 切回主线程更新 UI（withContext(Main)）
                        // ============================================================================
                        Button(
                            onClick = {
                                val me = user
                                // 用户信息丢失（不应该发生，但防御一下）
                                if (me == null) { pairMsg = "账号信息丢失，请重登"; return@Button }
                                pairLoading = true; pairMsg = ""
                                // 在 lifecycleScope 启动 IO 协程，避免阻塞主线程
                                lifecycleScope.launch(Dispatchers.IO) {
                                    // 调用后端 RPC 接口发起配对
                                    val resp = runCatching {
                                        NetworkModule.rpcService.pairByCode(
                                            com.coupletracker.android.data.PairByCodeReq(
                                                myId = me.id,                              // 我的用户 ID
                                                theirCode = pairInput.trim().uppercase()   // 对方的配对码
                                            )
                                        )
                                    }
                                    // 解构响应
                                    val body = resp.getOrNull()?.body()                                       // 成功响应体
                                    val err = runCatching { resp.getOrNull()?.errorBody()?.string() }.getOrNull().orEmpty()  // 错误响应体
                                    val ex = resp.exceptionOrNull()                                            // 网络异常
                                    // 切回主线程更新 UI（Compose 状态变更必须在主线程）
                                    withContext(Dispatchers.Main) {
                                        pairLoading = false
                                        // ----------------------------------------------------------------------------
                                        // when 表达式：根据响应情况显示不同提示
                                        // ----------------------------------------------------------------------------
                                        when {
                                            // 配对成功的响应
                                            resp.getOrNull()?.isSuccessful == true && body?.ok == true -> {
                                                val theirId = body.their_id
                                                val theirNick = body.their_nickname?.takeIf { it.isNotBlank() } ?: "TA"
                                                when {
                                                    // ✅ 双向配对成功：对方也已输入我的码
                                                    body.paired == true || body.already_paired == true -> {
                                                        // ✅ 双向配对成功
                                                        // 更新本地用户的 partnerId，触发 userFlow 发新值 → UI 自动刷新
                                                        UserRepository.get().setUser(me.copy(
                                                            partnerId = theirId
                                                        ))
                                                        hasPartner = true
                                                        partnerName = theirNick
                                                        pairMsg = "✅ 配对成功！已和 $theirNick 绑定"
                                                        pairInput = ""  // 清空输入框
                                                    }
                                                    // ⏳ 单向配对：等待对方也输入我的码
                                                    body.waiting == true -> {
                                                        // ⏳ 等待对方也输入我的码
                                                        pairMsg = body.msg ?: "⏳ 已发起配对请求，等待TA也输入你的配对码"
                                                        pairInput = ""
                                                    }
                                                    // 其他情况：用后端返回的 msg，没有则用默认文案
                                                    else -> {
                                                        pairMsg = body.msg ?: "配对请求已发送"
                                                        pairInput = ""
                                                    }
                                                }
                                            }
                                            // 对方配对码不存在
                                            body?.reason == "CODE_NOT_FOUND" ->
                                                pairMsg = "❌ 配对码不存在：让TA打开「我的」页确认TA的码"
                                            // 不能和自己配对
                                            body?.reason == "CANNOT_PAIR_SELF" ->
                                                pairMsg = "😅 不能和自己配对哦"
                                            // 当前用户在后端找不到
                                            body?.reason == "ME_NOT_FOUND" ->
                                                pairMsg = "账号信息丢失，请退出后重新登录"
                                            // 网络异常（如超时、断网）
                                            ex != null ->
                                                pairMsg = "网络异常：${ex.message?.take(40).orEmpty()}"
                                            // 后端返回错误体
                                            err.isNotBlank() ->
                                                pairMsg = "配对失败：${err.take(60)}"
                                            // 兜底
                                            else -> pairMsg = "配对失败，请稍后再试"
                                        }
                                    }
                                }
                            },
                            // 按钮可用条件：非 loading + 输入长度 ≥ 4
                            enabled = !pairLoading && pairInput.length >= 4,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .clip(RoundedCornerShape(24.dp)),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3A9E91))
                        ) {
                            // loading 时显示转圈，否则显示"立即配对 💕"
                            if (pairLoading) CircularProgressIndicator(
                                color = Color.White, modifier = Modifier.size(18.dp))
                            else Text("立即配对 💕", fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }

            Spacer(Modifier.height(14.dp))

            // ============================================================================
            // 采集频率设置卡片
            // ----------------------------------------------------------------------------
            // - 包含两个 Slider：
            //   ① 位置上报频率（多久采集一次 GPS 上报到云端）
            //   ② APP 使用检测频率（多久检测一次当前打开的应用）
            // - 调整后实时生效：TrackerService 在监听 Flow，值变化时自动重启采集
            // - 调大间隔可以省电、降低卡顿；调小则更实时但更耗电
            // ============================================================================
            // ====== 采集频率设置（实时生效，Service 监听 Flow 自动重启） ======
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White)
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "⚙️ 采集频率（调大可降低卡顿/省电）",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF3D2E2A)
                        )
                    }
                    Spacer(Modifier.height(14.dp))

                    // ============================================================================
                    // 位置采集频率 Slider
                    // ----------------------------------------------------------------------------
                    // - 显示当前秒数 + 范围提示
                    // - Slider 组件参数：
                    //   - value：当前值（tmpLoc.floatValue，临时状态）
                    //   - onValueChange：拖动时实时回调，把值取整后赋给 tmpLoc
                    //     （roundToInt() 四舍五入到整数，避免出现"每 3.7 秒"这种小数）
                    //   - onValueChangeFinished：松手时回调，把最终值写入仓库持久化
                    //   - valueRange：滑块范围（最小到最大秒数）
                    //   - steps：离散刻度数（让 Slider 只停在整数位置）
                    //   - colors：滑块颜色（粉色主题）
                    // ============================================================================
                    // —— 位置采集频率 Slider ——
                    Text("📍 位置上报", color = Color(0xFF4A5568), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(2.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // 大字号显示当前秒数
                        Text(
                            "每 ${tmpLoc.floatValue.toInt()} 秒",
                            color = Color(0xFFFF8B7B),
                            fontSize = 18.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                        Spacer(Modifier.weight(1f))
                        // 右侧灰字显示范围
                        Text(
                            "范围 ${UserRepository.MIN_LOC_INTERVAL_SEC}-${UserRepository.MAX_LOC_INTERVAL_SEC}s",
                            color = Color(0xFFA0AEC0), fontSize = 10.sp
                        )
                    }
                    Slider(
                        value = tmpLoc.floatValue,
                        onValueChange = { tmpLoc.floatValue = it.roundToInt().toFloat() },
                        onValueChangeFinished = {
                            // 松手时：把临时值写入仓库（IO 线程）
                            val sec = tmpLoc.floatValue.toInt()
                            lifecycleScope.launch(Dispatchers.IO) {
                                UserRepository.get().setLocationIntervalSec(sec)
                            }
                        },
                        valueRange = UserRepository.MIN_LOC_INTERVAL_SEC.toFloat()..UserRepository.MAX_LOC_INTERVAL_SEC.toFloat(),
                        steps = UserRepository.MAX_LOC_INTERVAL_SEC - UserRepository.MIN_LOC_INTERVAL_SEC - 1,
                        colors = SliderDefaults.colors(thumbColor = Color(0xFFFF8B7B), activeTrackColor = Color(0xFFFF8B7B))
                    )
                    Spacer(Modifier.height(10.dp))

                    // ============================================================================
                    // APP 使用检测频率 Slider（结构同上，颜色用蓝紫）
                    // ============================================================================
                    // —— APP 使用采集频率 Slider ——
                    Text("📱 APP 使用检测", color = Color(0xFF4A5568), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(2.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "每 ${tmpApp.floatValue.toInt()} 秒",
                            color = Color(0xFF3A9E91),
                            fontSize = 18.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            "范围 ${UserRepository.MIN_APP_INTERVAL_SEC}-${UserRepository.MAX_APP_INTERVAL_SEC}s",
                            color = Color(0xFFA0AEC0), fontSize = 10.sp
                        )
                    }
                    Slider(
                        value = tmpApp.floatValue,
                        onValueChange = { tmpApp.floatValue = it.roundToInt().toFloat() },
                        onValueChangeFinished = {
                            val sec = tmpApp.floatValue.toInt()
                            lifecycleScope.launch(Dispatchers.IO) {
                                UserRepository.get().setAppIntervalSec(sec)
                            }
                        },
                        valueRange = UserRepository.MIN_APP_INTERVAL_SEC.toFloat()..UserRepository.MAX_APP_INTERVAL_SEC.toFloat(),
                        steps = UserRepository.MAX_APP_INTERVAL_SEC - UserRepository.MIN_APP_INTERVAL_SEC - 1,
                        colors = SliderDefaults.colors(thumbColor = Color(0xFF3A9E91), activeTrackColor = Color(0xFF3A9E91))
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "✅ 调整后立即生效，无需重启APP",
                        color = Color(0xFF48BB78), fontSize = 11.sp, fontWeight = FontWeight.Medium
                    )
                    // ============================================================================
                    // 上报状态显示：方便用户排查"为什么地图没显示"
                    // ----------------------------------------------------------------------------
                    // - locStatus：最近一次位置上报的状态文字
                    // - appStatus：最近一次 APP 上报的状态文字
                    // - colorOf(s)：局部函数，根据文字内容返回对应颜色
                    //   （含"成功"用绿色，含"失败/异常"用红色，其他用灰色）
                    // ============================================================================
                    // 上报状态（方便用户排查"为什么地图没显示"）
                    Spacer(Modifier.height(10.dp))
                    val locStatus by NetworkModule.lastLocationReportStatusFlow.collectAsState()
                    val appStatus by NetworkModule.lastAppReportStatusFlow.collectAsState()
                    // 局部函数：根据状态文字返回颜色
                    fun colorOf(s: String) = when {
                        s.contains("成功") -> Color(0xFF2F855A)
                        s.contains("失败") || s.contains("异常") -> Color(0xFFE53E3E)
                        else -> Color(0xFFA89890)
                    }
                    Divider(color = Color(0xFFEDF2F7))
                    Spacer(Modifier.height(8.dp))
                    Text("🛰️ 上报状态 · 供排查参考", color = Color(0xFF4A5568), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text("📍 $locStatus", color = colorOf(locStatus), fontSize = 10.sp, lineHeight = 14.sp)
                    Spacer(Modifier.height(2.dp))
                    Text("📱 $appStatus", color = colorOf(appStatus), fontSize = 10.sp, lineHeight = 14.sp)
                    Spacer(Modifier.height(4.dp))
                    // 排查提示：如果上报失败，引导用户检查权限
                    Text(
                        "如果「位置上报」连续失败：打开系统设置 → 应用权限 → 允许定位（允许始终允许）→ 再打开一次本APP",
                        color = Color(0xFFA0AEC0), fontSize = 10.sp, lineHeight = 14.sp
                    )
                }
            }

            Spacer(Modifier.height(14.dp))
            // ============================================================================
            // 底部操作按钮行：看地图 + 重启服务
            // ----------------------------------------------------------------------------
            // - Row 占满宽度，用 spacedBy 让两个按钮之间有 10dp 间距
            // - 每个按钮 weight(1f)：两按钮平分宽度
            // ============================================================================
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // ----------------------------------------------------------------------------
                // 看地图按钮（描边样式）
                // - onClick = onBackToMap：调用传入的回调，切回地图 Tab
                // ----------------------------------------------------------------------------
                OutlinedButton(
                    onClick = onBackToMap,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp),
                    shape = RoundedCornerShape(24.dp)
                ) {
                    Text("🗺️", fontSize = 16.sp)
                    Spacer(Modifier.width(4.dp))
                    Text("看地图")
                }
                // ----------------------------------------------------------------------------
                // 重启服务按钮（实心样式，蓝紫色）
                // - 流程：stop 当前 TrackerService → 等 300ms → start 新服务
                // - 用 Thread.sleep(300L) 等待 Service 完全停止（这里在 IO 线程，不卡 UI）
                // - runCatching 包裹避免 stop/start 抛异常导致崩溃
                // ----------------------------------------------------------------------------
                Button(
                    onClick = {
                        lifecycleScope.launch(Dispatchers.IO) {
                            runCatching { TrackerService.stop(this@MainActivity) }
                            Thread.sleep(300L)
                            runCatching { TrackerService.start(this@MainActivity) }
                        }
                    },
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp),
                    shape = RoundedCornerShape(24.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3A9E91))
                ) {
                    Text("🔄", fontSize = 16.sp)
                    Spacer(Modifier.width(4.dp))
                    Text("重启服务")
                }
            }

            Spacer(Modifier.height(26.dp))

            // ============================================================================
            // 云端服务信息卡片（Supabase BaaS）
            // ----------------------------------------------------------------------------
            // - 显示当前使用的 Supabase 后端配置
            // - Auth URL：把 REST base URL 中的 /rest/v1 替换为 /auth/v1
            // - REST URL：直接显示 NetworkModule.getApiBase()
            // ============================================================================
            // ====== 云端服务信息（Supabase BaaS） ======
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White)
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("☁️ 云端服务", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color(0xFF3D2E2A))
                    Spacer(Modifier.height(8.dp))
                    Text("Supabase", color = Color(0xFFA89890), fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    // Auth 接口地址：把 /rest/v1 替换为 /auth/v1
                    Text("Auth: ${NetworkModule.getApiBase().replace("/rest/v1", "/auth/v1")}", color = Color(0xFFA89890), fontSize = 11.sp)
                    // REST 接口地址
                    Text("REST: ${NetworkModule.getApiBase()}", color = Color(0xFFA89890), fontSize = 11.sp)
                }
            }

            Spacer(Modifier.height(16.dp))

            // ============================================================================
            // 账号管理卡片：显示账号信息 + 退出登录按钮
            // ----------------------------------------------------------------------------
            // - 用 Row + Modifier.width(60.dp) 实现固定宽度的标签列
            // - 退出登录流程：
            //   ① UserRepository.get().logout()：清空本地用户数据和 Token
            //   ② TrackerService.stop(this)：停止后台采集服务
            //   ③ withContext(Main) { finish() }：切回主线程关闭 Activity
            // ============================================================================
            // ====== 账号管理 ======
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White)
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("👤 账号管理", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color(0xFF3D2E2A))
                    Spacer(Modifier.height(8.dp))
                    Divider(color = Color(0xFFEDF2F7))
                    Spacer(Modifier.height(12.dp))

                    // 账号信息：用户名
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("账号", color = Color(0xFFA89890), fontSize = 13.sp, modifier = Modifier.width(60.dp))
                        Text(user?.username ?: "-", color = Color(0xFF3D2E2A), fontSize = 13.sp)
                    }
                    Spacer(Modifier.height(6.dp))
                    // 账号信息：昵称
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("昵称", color = Color(0xFFA89890), fontSize = 13.sp, modifier = Modifier.width(60.dp))
                        Text(user?.displayName ?: "-", color = Color(0xFF3D2E2A), fontSize = 13.sp)
                    }
                    Spacer(Modifier.height(6.dp))
                    // 账号信息：配对状态
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("配对", color = Color(0xFFA89890), fontSize = 13.sp, modifier = Modifier.width(60.dp))
                        Text(
                            if (hasPartner == true) "已与 ${partnerName.ifBlank { "TA" }} 绑定" else "未配对",
                            color = if (hasPartner == true) Color(0xFF38A169) else Color(0xFFA89890),
                            fontSize = 13.sp
                        )
                    }

                    Spacer(Modifier.height(14.dp))
                    Divider(color = Color(0xFFEDF2F7))
                    Spacer(Modifier.height(14.dp))

                    // ----------------------------------------------------------------------------
                    // 危险操作按钮行：取消配对（左） + 退出登录（右）
                    // ----------------------------------------------------------------------------
                    // - 用 Row + spacedBy(10.dp) 让两个按钮水平并排，平分宽度
                    // - 取消配对：仅已配对（hasPartner == true）时显示，调 unpair RPC
                    //   单方面取消会让双方 partner_id 都被清空，对方下次拉到状态后会自动刷新
                    // - 退出登录：清本地登录信息 + 停服务 + 关闭 Activity
                    // - 两按钮都用红色描边样式，传达"危险操作"语义
                    // ----------------------------------------------------------------------------
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // 取消配对按钮：仅在已配对时显示
                        // 方案：直接用 REST PATCH 清双方 partner_id（RLS profiles_all 允许所有读写）
                        //       不依赖 unpair SQL 函数部署，更稳定可靠
                        if (hasPartner == true) {
                            var unpairing by remember { mutableStateOf(false) }
                            OutlinedButton(
                                onClick = {
                                    val me = user
                                    if (me == null) {
                                        Toast.makeText(this@MainActivity, "用户信息加载中，请稍后重试", Toast.LENGTH_SHORT).show()
                                        return@OutlinedButton
                                    }
                                    val myPartnerId = me.partnerId
                                    if (myPartnerId.isNullOrBlank()) {
                                        Toast.makeText(this@MainActivity, "当前未配对，无需取消", Toast.LENGTH_SHORT).show()
                                        return@OutlinedButton
                                    }
                                    if (unpairing) return@OutlinedButton  // 防抖：避免狂点导致多次请求
                                    unpairing = true
                                    Toast.makeText(this@MainActivity, "正在取消配对…", Toast.LENGTH_SHORT).show()
                                    lifecycleScope.launch(Dispatchers.IO) {
                                        // 清空字段：partner_id + pending_pair + pair_request_at
                                        val clearFields = mapOf<String, Any?>(
                                            "partner_id" to null,
                                            "pending_pair" to null,
                                            "pair_request_at" to null
                                        )
                                        // ① 清自己
                                        val selfResp = runCatching {
                                            NetworkModule.restService.updateProfile(me.id, clearFields)
                                        }
                                        // ② 清对方（单方取消 -> 双方都解除）
                                        val partnerResp = runCatching {
                                            NetworkModule.restService.updateProfile(myPartnerId, clearFields)
                                        }
                                        val selfOk = selfResp.getOrNull()?.isSuccessful == true
                                        val partnerOk = partnerResp.getOrNull()?.isSuccessful == true
                                        withContext(Dispatchers.Main) {
                                            unpairing = false
                                            if (selfOk) {
                                                // ✅ 至少自己清成功：更新本地 -> userFlow 发新值
                                                //   -> UI 自动切回未配对 + WebView 重新注入空 partnerId
                                                //   -> AppScreen/StatsScreen 切换按钮显示「💤 未配对」
                                                UserRepository.get().setUser(me.copy(partnerId = null))
                                                hasPartner = false
                                                partnerName = ""
                                                Toast.makeText(
                                                    this@MainActivity,
                                                    if (partnerOk) "已取消配对" else "已取消配对（对方数据稍后同步）",
                                                    Toast.LENGTH_SHORT
                                                ).show()
                                            } else {
                                                // ❌ 自己都没清成功（网络问题）
                                                Toast.makeText(
                                                    this@MainActivity,
                                                    "取消失败：网络异常，请稍后重试",
                                                    Toast.LENGTH_LONG
                                                ).show()
                                            }
                                        }
                                    }
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp),
                                shape = RoundedCornerShape(24.dp),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = Color(0xFFE53E3E)
                                ),
                                border = ButtonDefaults.outlinedButtonBorder.copy(
                                    brush = SolidColor(Color(0xFFE53E3E))
                                )
                            ) { Text(if (unpairing) "取消中…" else "取消配对", fontSize = 14.sp) }
                        }
                        // 退出登录按钮
                        OutlinedButton(
                            onClick = {
                                lifecycleScope.launch(Dispatchers.IO) {
                                    // 清空本地登录信息
                                    UserRepository.get().logout()
                                    // 停止后台采集服务
                                    TrackerService.stop(this@MainActivity)
                                    // 切回主线程关闭 Activity（finish 必须在主线程调用）
                                    withContext(Dispatchers.Main) { finish() }
                                }
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp),
                            shape = RoundedCornerShape(24.dp),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = Color(0xFFE53E3E)
                            ),
                            border = ButtonDefaults.outlinedButtonBorder.copy(
                                brush = SolidColor(Color(0xFFE53E3E))
                            )
                        ) { Text("退出登录", fontSize = 14.sp) }
                    }
                }
            }

            Spacer(Modifier.height(20.dp))
            // ============================================================================
            // 底部版本信息 + 后端/前端地址
            // - align(Alignment.CenterHorizontally)：水平居中
            // ============================================================================
            Text(
                "版本 v${BuildConfig.VERSION_NAME}",
                color = Color(0xFFA89890), fontSize = 12.sp,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
            Text(
                "后端 ${NetworkModule.getApiBase()}\n前端 ${BuildConfig.DEFAULT_WEB_BASE}",
                color = Color(0xFFA89890), fontSize = 10.sp,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
        }
    }

    // ============================================================================
    // onBackPressed：物理返回键的处理
    // ----------------------------------------------------------------------------
    // - 当用户按下手机返回键时调用
    // - 优先级：如果 WebView 有历史记录（canGoBack），让 WebView 后退一页
    // - 否则调用 super.onBackPressed() 走系统默认行为（通常是退出 Activity）
    //
    // 这样设计的好处：
    //   用户在地图页里点开了某些详情（前端路由切换），按返回键应该回到地图主页，
    //   而不是直接退出 App。
    // ============================================================================
    override fun onBackPressed() {
        if (webView?.canGoBack() == true) webView?.goBack()
        else super.onBackPressed()
    }
}
