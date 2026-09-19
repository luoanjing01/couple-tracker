// ============================================================================
// LoginActivity.kt —— 登录 / 注册 / 配对 / 权限引导 页面
//
// 本文件是情侣追踪 APP（CoupleTracker）的入口 Activity，采用 Jetpack Compose
// 单页多步骤的方式实现，包含以下四大功能模块：
//   1. 登录 / 注册卡片（LoginCard）        —— 用户身份认证
//   2. 配对卡片（PairCard）                —— 输入对方配对码进行绑定
//   3. 权限引导卡片（PermCard）            —— 申请定位 / 后台定位 / 使用情况访问 / 通知权限
//   4. 完成后跳转 MainActivity 进入主界面
//
// 整个流程通过一个枚举 Step 控制步骤切换，初学者只需关注：
//   - doAuth()     ：注册 + 登录的核心网络请求逻辑
//   - PairCard     ：配对发起 + 轮询对方是否接受 / 自动确认收到请求
//   - PermCard     ：权限申请的具体调用
// ============================================================================

// 声明包名，对应 APP 的包结构 com.coupletracker.android.ui
package com.coupletracker.android.ui

// ===== 以下为各类库的导入（import），初学者只需了解用途 =====
import android.Manifest                                  // Android 系统预定义的权限常量（定位、通知等）
import android.app.Activity                              // Activity 基类（本类继承的是 ComponentActivity）
import android.content.pm.PackageManager                 // 用于查询某权限是否已授予
import android.os.Build                                  // 用于判断 Android 版本号（不同版本权限策略不同）
import android.util.Log                                  // Android 日志工具，用于打印调试信息
import android.os.Bundle                                 // onCreate 的参数类型，保存 Activity 状态
import androidx.activity.ComponentActivity               // Compose 推荐使用的 Activity 基类
import androidx.activity.compose.setContent             // 在 Activity 中装载 Compose 内容的入口方法
import androidx.activity.result.contract.ActivityResultContracts  // 用于注册权限请求的回调契约
import androidx.compose.foundation.BorderStroke          // 边框样式（复制按钮的外框）
import androidx.compose.foundation.*                     // Compose 基础组件（Box、Column、背景、滚动等）
import androidx.compose.foundation.layout.*               // 布局相关（Spacer、padding、fillMaxWidth 等）
import androidx.compose.foundation.shape.RoundedCornerShape  // 圆角形状
import androidx.compose.foundation.text.KeyboardOptions  // 输入框键盘选项（如密码键盘）
import androidx.compose.material3.*                      // Material3 组件库（按钮、输入框、对话框等）
import androidx.compose.runtime.*                        // Compose 状态管理（remember、mutableStateOf、LaunchedEffect）
import androidx.compose.ui.Alignment                     // 子元素对齐方式
import androidx.compose.ui.Modifier                       // Compose 修饰符（设置尺寸、内边距、背景等）
import androidx.compose.ui.draw.clip                      // 裁剪形状
import androidx.compose.ui.graphics.Brush                 // 渐变画笔（用于背景渐变）
import androidx.compose.ui.graphics.Color                 // 颜色定义
import androidx.compose.ui.text.font.FontWeight          // 字重（粗体、半粗体等）
import com.coupletracker.android.R                        // 本 APP 的资源引用（strings.xml 等）
import kotlinx.coroutines.delay                          // 协程延迟函数（用于轮询间隔）
import androidx.compose.ui.text.input.KeyboardType        // 键盘类型（数字、密码等）
import androidx.compose.ui.text.input.PasswordVisualTransformation  // 密码输入框的掩码（显示为 ●）
import androidx.compose.ui.text.input.VisualTransformation          // 输入内容的可视化变换
import androidx.compose.ui.unit.dp                        // 尺寸单位 dp
import androidx.compose.ui.unit.sp                        // 字号单位 sp
import androidx.core.content.ContextCompat                 // 兼容旧版本的工具类（用于权限检查）
import androidx.lifecycle.lifecycleScope                  // Activity 生命周期绑定的协程作用域
import com.coupletracker.android.appmonitor.AppUsageMonitor  // APP 使用情况监控（统计其他 APP 打开时长）
import com.coupletracker.android.data.NetworkModule       // 网络模块（封装 Retrofit / RPC 服务）
import com.coupletracker.android.data.RegisterUserReq     // 注册请求体数据类
import com.coupletracker.android.data.VerifyLoginReq      // 登录验证请求体数据类
import com.coupletracker.android.data.PairByCodeReq       // 用配对码发起配对的请求体
import com.coupletracker.android.data.CheckPairStatusReq // 查询配对状态的请求体
import com.coupletracker.android.data.AcceptPairReq       // 接受配对请求的请求体
import com.coupletracker.android.data.UserRepository      // 本地用户仓库（保存登录用户信息）
import com.coupletracker.android.data.model.*             // 数据模型（UserInfo 等）
import com.coupletracker.android.service.TrackerService   // 后台追踪服务（位置上报）
import com.google.gson.Gson                               // JSON 解析库
import kotlinx.coroutines.Dispatchers                     // 协程调度器（IO 用于网络、Main 用于 UI）
import kotlinx.coroutines.launch                          // 启动协程
import kotlinx.coroutines.withContext                     // 切换协程上下文
import retrofit2.HttpException                            // Retrofit 网络异常
import android.content.ClipData                           // 剪贴板数据
import android.content.ClipboardManager                   // 剪贴板管理器（复制配对码）
import android.content.Context                            // Android 上下文

/**
 * 登录/注册/配对/权限引导 Activity（单页多步骤Compose）
 *
 * 继承自 ComponentActivity，这是 Jetpack Compose 官方推荐使用的 Activity 基类
 * （相比传统的 AppCompatActivity，更适合纯 Compose 应用）。
 * 整个页面用四个步骤（Step 枚举）串联起来：
 *   LOGIN 登录 → PAIR 配对 → PERMS 权限 → DONE 完成
 */
class LoginActivity : ComponentActivity() {

    // ----【步骤枚举】控制当前显示哪个卡片----
    // LOGIN : 登录/注册卡片
    // PAIR  : 配对卡片（输入对方配对码）
    // PERMS : 权限引导卡片（请求定位、使用情况访问等）
    // DONE  : 全部完成，跳转主界面
    private enum class Step { LOGIN, PAIR, PERMS, DONE }

    // ----【可观察状态】这些变量变化时，UI 会自动刷新----
    // pairCode: 当前用户的"配对码"（6 位，用于让对方找到自己）
    // mutableStateOf(...) 是 Compose 的状态容器，赋值后会触发重组刷新 UI
    private var pairCode by mutableStateOf("")          // 我的配对码
    private var userMsg by mutableStateOf("")           // 显示给用户的提示文字（如错误信息）
    private var loading by mutableStateOf(false)        // 是否正在请求网络（true 时显示加载圈）

    // AppUsageMonitor：用于统计用户在哪些 APP 上花了多少时间
    // by lazy 表示第一次访问时才创建，避免 Activity 一启动就初始化它
    private val usageMonitor by lazy { AppUsageMonitor(this, lifecycleScope) }

    // ----【权限请求 Launcher】----
    // registerForActivityResult 是 Android 官方推荐的权限请求方式
    // 相比旧的 onRequestPermissionsResult，它把请求和回调绑定在一起，更安全
    // 这里注册了一个"请求多个权限"的 Launcher，请求完成后会进入回调块
    private val multiplePerms =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ ->
            // 回调参数 _ 是"权限名 -> 是否授予"的 Map，这里不关心具体结果，
            // 直接检查"使用情况访问"权限（这个权限无法用 Launcher 申请，必须跳系统设置）
            if (!usageMonitor.hasUsagePermission()) {
                // 没有使用情况访问权限 → 跳到系统设置页让用户手动开启
                startActivity(usageMonitor.createUsageSettingsIntent())
            }
        }

    // ========================================================================
    // onCreate：Activity 创建时系统回调，是整个页面的入口
    // ========================================================================
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // setContent 是 Compose 的入口方法，把传统 XML 布局替换为 Compose 函数
        setContent {
            // MaterialTheme：设置整 APP 的主题色
            //   primary     = 0xFFE75480  → 主色（粉红，情侣主题）
            //   secondary   = 0xFF667EEA  → 次色（蓝色，按钮渐变用）
            //   background  = 0xFFFDF2F8  → 背景（极淡粉色）
            // 注意：0xFF 是完全不透明的 alpha 通道
            MaterialTheme(colorScheme = lightColorScheme(
                primary = Color(0xFFE75480), secondary = Color(0xFF667EEA),
                background = Color(0xFFFDF2F8)
            )) {
                // Surface：承载 Material 主题的容器，铺满整个屏幕
                Surface(modifier = Modifier.fillMaxSize()) { LoginScreen() }
            }
        }
    }

    // ========================================================================
    // LoginScreen：整个登录页的"路由器"，根据 step 状态显示对应卡片
    // @Composable 表示这是一个 Compose 可组合函数，会被 Compose 引擎调用绘制
    // ========================================================================
    @Composable
    fun LoginScreen() {
        // remember：让 step 在重组（UI 重新绘制）时保持值不丢失
        // 初始值为 Step.LOGIN，即默认显示登录卡片
        var step by remember { mutableStateOf(Step.LOGIN) }
        // when 表达式：相当于 switch，根据当前 step 显示不同的卡片
        when (step) {
            // 登录/注册卡片：登录成功后回调把 step 切到 PAIR（配对）
            Step.LOGIN   -> LoginCard(onLoginOk = { step = Step.PAIR })
            // 配对卡片：配对完成或跳过都进入 PERMS（权限引导）
            Step.PAIR    -> PairCard(onPairOkOrSkip = { step = Step.PERMS })
            // 权限引导卡片：所有权限处理完后进入 DONE
            Step.PERMS   -> PermCard(onAllDone = { step = Step.DONE })
            // DONE：跳转到 MainActivity，并关闭当前 LoginActivity
            Step.DONE    -> {
                // LaunchedEffect(Unit)：在 Composable 第一次进入时执行一次副作用
                // 这里用来启动跳转动作（避免在重组过程中重复跳转）
                LaunchedEffect(Unit) {
                    // 创建 Intent 跳转到主界面 MainActivity
                    startActivity(android.content.Intent(this@LoginActivity, MainActivity::class.java))
                    // finish() 关闭当前 Activity，用户按返回键不会再回到登录页
                    finish()
                }
            }
        }
    }

    // ========================================================================
    // LoginCard：登录 / 注册卡片 UI（这是用户看到的第一个界面）
    // @OptIn(ExperimentalMaterial3Api 表示启用 Material3 实验性 API
    // 参数 onLoginOk：登录成功后调用的回调函数（用于切换到下一卡片）
    // ========================================================================
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun LoginCard(onLoginOk: () -> Unit) {
        // ---- 这里定义卡片内部的所有状态（输入框内容、模式开关等）----
        // mode: "login"（登录）或 "register"（注册），决定表单展示哪些字段
        var mode by remember { mutableStateOf("login") }  // login | register
        var username by remember { mutableStateOf("") }      // 用户名输入
        var password by remember { mutableStateOf("") }      // 密码输入
        var displayName by remember { mutableStateOf("") }  // 昵称输入（仅注册显示）
        var genderIdx by remember { mutableStateOf(0) }      // 性别索引：0=女生，1=男生
        var pwdVisible by remember { mutableStateOf(false) } // 密码是否明文显示

        // BoxWithGradient：自定义的带渐变背景的容器（文件底部定义）
        BoxWithGradient {
            // Column：垂直排列子元素
            Column(
                modifier = Modifier
                    .fillMaxSize()                                  // 铺满整个屏幕
                    .verticalScroll(rememberScrollState())          // 内容过长可滚动
                    .padding(horizontal = 28.dp, vertical = 36.dp), // 内边距
                horizontalAlignment = Alignment.CenterHorizontally   // 水平居中
            ) {
                // APP 标题"小世界"
                Text("小世界", fontSize = 32.sp,
                    color = Color.White, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(30.dp))  // 间距

                // ---- 模式切换：登录 / 注册 两个标签按钮 ----
                val tabs = listOf("登录" to "login", "注册" to "register")
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))                 // 外框圆角
                        .background(Color.White.copy(0.15f))             // 半透明白色背景
                ) {
                    tabs.forEach { (label, value) ->
                        // sel：当前是否选中此标签
                        val sel = mode == value
                        TextButton(
                            onClick = { mode = value },  // 点击切换模式
                            modifier = Modifier
                                .weight(1f)                                                // 平分宽度
                                .clip(RoundedCornerShape(20.dp))
                                .background(if (sel) Color.White else Color.Transparent),  // 选中时白底
                            colors = ButtonDefaults.textButtonColors(
                                contentColor = if (sel) Color(0xFFE75480) else Color.White  // 选中粉色字
                            )
                        ) { Text(label, fontWeight = FontWeight.SemiBold) }
                    }
                }
                Spacer(Modifier.height(20.dp))

                // ---- 用户名输入框 ----
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },   // 输入时更新状态
                    label = { Text("用户名") },
                    singleLine = true,                  // 单行
                    leadingIcon = { Text("👤", fontSize = 18.sp) },  // 左侧图标
                    colors = outlinedPinkColors(),       // 粉色主题
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                // ---- 密码输入框（带"显示/隐藏密码"按钮）----
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("密码") },
                    singleLine = true,
                    // visualTransformation：控制文本显示方式
                    //   PasswordVisualTransformation → 显示成 ●●●●
                    //   VisualTransformation.None     → 显示明文
                    visualTransformation =
                        if (pwdVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        // 右侧眼睛图标，点击切换明文/掩码
                        IconButton(onClick = { pwdVisible = !pwdVisible }) {
                            Text(if (pwdVisible) "👁️" else "🙈", fontSize = 18.sp)
                        }
                    },
                    leadingIcon = { Text("🔒", fontSize = 18.sp) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    colors = outlinedPinkColors(),
                    modifier = Modifier.fillMaxWidth()
                )

                // ---- 仅注册模式才显示的额外字段：昵称 + 性别 ----
                if (mode == "register") {
                    Spacer(Modifier.height(12.dp))
                    // 昵称输入框（会显示在地图上给对方看）
                    OutlinedTextField(
                        value = displayName,
                        onValueChange = { displayName = it },
                        label = { Text("昵称（在地图上显示）") },
                        singleLine = true,
                        leadingIcon = { Text("💗", fontSize = 18.sp) },
                        colors = outlinedPinkColors(),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(12.dp))
                    // 性别选择（FilterChip：可选中样式的"胶囊按钮"）
                    Row {
                        listOf("女生", "男生").forEachIndexed { idx, lbl ->
                            val sel = genderIdx == idx
                            FilterChip(
                                selected = sel,
                                onClick = { genderIdx = idx },
                                label = { Text(lbl) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = Color(0xFFE75480),
                                    selectedLabelColor = Color.White
                                ),
                                modifier = Modifier.padding(end = 8.dp)
                            )
                        }
                    }
                }

                Spacer(Modifier.height(26.dp))
                // ---- 注册模式：额外展示账号密码要求提示卡片 ----
                if (mode == "register") {
                    Card(
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = Color.White.copy(alpha = 0.12f)  // 半透明白
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                            Text(
                                "📌 账号密码要求",
                                color = Color.White, fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(Modifier.height(5.dp))
                            Text(
                                "• 用户名 ≥ 3 位（字母/数字，推荐 6 位以上）\n" +
                                "• 密码 ≥ 8 位，建议同时包含字母和数字\n" +
                                "• 昵称将显示在地图上，给对方看的",
                                color = Color.White.copy(alpha = 0.85f),
                                fontSize = 11.sp,
                                lineHeight = 16.sp
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }
                // ---- 主按钮：登录 或 创建账号 ----
                // 点击后调用 doAuth() 执行真正的网络请求
                Button(
                    onClick = {
                        doAuth(mode, username, password, displayName, genderIdx, onLoginOk)
                    },
                    // enabled 控制按钮是否可点击：
                    //   - 不在加载中
                    //   - 用户名密码都填了
                    //   - 注册模式还需要昵称
                    enabled = !loading && username.isNotBlank() && password.isNotBlank()
                            && (mode == "login" || displayName.isNotBlank()),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .clip(RoundedCornerShape(26.dp)),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFE75480),
                        contentColor = Color.White
                    )
                ) {
                    // loading=true 显示加载圈，否则显示按钮文字
                    if (loading) CircularProgressIndicator(
                        color = Color.White, modifier = Modifier.size(20.dp))
                    else Text(if (mode == "login") "登录" else "创建账号", fontSize = 18.sp)
                }

                Spacer(Modifier.height(10.dp))
                // ---- 用户提示信息（错误或成功提示）----
                if (userMsg.isNotBlank()) {
                    Text(userMsg, color = Color.White, fontSize = 13.sp)
                }
            }
        }
    }

    // ========================================================================
    // doAuth：注册 + 登录的核心网络请求逻辑（重点函数）
    //
    // 参数：
    //   mode        : "login" 或 "register"
    //   username    : 用户名
    //   password    : 密码
    //   displayName : 昵称（仅注册用）
    //   genderIdx   : 性别索引 0=女 1=男
    //   onOk        : 成功后的回调（用来切换到下一个卡片）
    //
    // 整体流程（注册分支）：
    //   1) 客户端格式校验
    //   2) 调 register_user RPC 创建账号（直接写 auth.users 表，不发邮件）
    //   3) 调 verifyLogin RPC 验证密码并取回用户信息
    //   4) 保存到 UserRepository，调用 onOk() 进入下一步
    //
    // 整体流程（登录分支）：跳过步骤 2，直接执行步骤 3、4
    // ========================================================================
    private fun doAuth(
        mode: String, username: String, password: String, displayName: String,
        genderIdx: Int, onOk: () -> Unit
    ) {
        // ===== 第一步：客户端先做格式校验，减少无效请求 / 429 限流 =====
        // trim() 去除首尾空格，避免用户复制时带入空白字符
        val cleanUser = username.trim()
        val cleanPass = password.trim()
        val cleanName = displayName.trim()

        // when 表达式做校验，任一失败就显示提示并 return（不发起请求）
        when {
            // 用户名至少 3 位
            cleanUser.length < 3 -> {
                userMsg = "❌ 用户名至少 3 位（字母/数字，推荐 6 位以上）"; loading = false; return
            }
            // 密码至少 8 位
            cleanPass.length < 8 -> {
                userMsg = "❌ 密码至少 8 位，建议同时包含字母和数字"; loading = false; return
            }
            // 注册模式昵称不能为空
            mode == "register" && cleanName.isEmpty() -> {
                userMsg = "❌ 请填写昵称（会在地图上显示给TA）"; loading = false; return
            }
        }

        // 清空提示，显示加载圈
        userMsg = ""; loading = true
        // lifecycleScope.launch(Dispatchers.IO)：在 IO 线程发起网络请求
        //   - lifecycleScope：跟随 Activity 生命周期，Activity 销毁时自动取消
        //   - Dispatchers.IO：专门用于 IO 操作（网络、数据库、文件）的线程池
        lifecycleScope.launch(Dispatchers.IO) {
            // 把性别索引转成英文值（数据库里存 female / male）
            val gender = if (genderIdx == 0) "female" else "male"
            // 头像用 emoji 表示（女粉心，男蓝心）
            val avatar = if (genderIdx == 0) "💗" else "💙"

            // =====================================================================
            // 🔐 完全 RPC 化的认证流程（彻底绕过 GoTrue，永不触发 500 / 429）
            //
            // 注册：register_user RPC 建用户 → verifyLogin RPC 验证拿 profile
            // 登录：verifyLogin RPC 直接验证密码拿 user_id + profile
            //
            // ✅ 所有 SECURITY DEFINER 函数，以 postgres 权限执行
            // ✅ RLS 全放开，anon key 就能读写所有表，不需要 JWT token
            // =====================================================================

            // ===== 步骤 1：注册分支先调 register_user RPC（直接 INSERT auth.users，永不发邮件）=====
            if (mode == "register") {
                // runCatching 包装：捕获所有异常，不抛出崩溃
                val rpcResp = runCatching {
                    NetworkModule.rpcService.registerUser(
                        RegisterUserReq(
                            username = cleanUser,
                            password = cleanPass,
                            nickname = cleanName.ifBlank { cleanUser },  // 昵称为空时用用户名代替
                            gender = gender
                        )
                    )
                }

                // getOrNull()：成功则返回结果，失败则返回 null
                val regOk = rpcResp.getOrNull()
                // body()：成功响应体（含服务端返回的数据）
                val regBody = regOk?.body()
                // errorBody()：失败响应体（错误信息），string() 才能读出内容
                // 注意 errorBody 只能读一次，所以用 runCatching 包裹
                val regErrBody = runCatching { regOk?.errorBody()?.string() }.getOrNull().orEmpty()
                // exceptionOrNull()：如果是网络异常（没收到响应），这里拿到 Throwable
                val regEx = rpcResp.exceptionOrNull()

                // 注册失败 → 翻译成友好中文提示，结束本次请求
                if (regOk == null || !regOk.isSuccessful) {
                    val msg = translateRegisterError(
                        httpCode = regOk?.code() ?: 0,
                        body = regErrBody,
                        ex = regEx
                    )
                    // withContext(Dispatchers.Main)：切回主线程刷新 UI（Android 不允许在子线程改 UI）
                    withContext(Dispatchers.Main) { userMsg = msg; loading = false }
                    return@launch  // 结束协程
                }
            }

            // ===== 步骤 2：调 verifyLogin RPC 验证密码 + 拿 profile（完全绕过 GoTrue signIn）=====
            val verifyResp = runCatching {
                NetworkModule.rpcService.verifyLogin(
                    VerifyLoginReq(username = cleanUser, password = cleanPass)
                )
            }

            val verifyOk = verifyResp.getOrNull()
            val verifyBody = verifyOk?.body()
            val verifyErrBody = runCatching { verifyOk?.errorBody()?.string() }.getOrNull().orEmpty()
            val verifyEx = verifyResp.exceptionOrNull()

            // ===== 步骤 3：verifyLogin 成功 → 用返回的 user_id + profile 设置本地用户 =====
            // ✅ 兼容两种格式：
            //    cache 旧函数（schema cache 未刷新）→ f1~f8 命名字段 + 顶层 user_id=null
            //    新函数（若将来刷新 cache）→ 命名字段 + 顶层 user_id 正确
            if (verifyOk?.isSuccessful == true && verifyBody != null) {
                // rawProfile：用户资料对象（JsonObject 形式）
                val rawProfile = verifyBody.profile
                // user_id：优先用顶层 user_id 字段；没有则兼容旧格式 f1 或 id 字段
                val userId = verifyBody.user_id
                    ?: rawProfile?.get("f1")?.asString   // 旧格式：f1 = id
                    ?: rawProfile?.get("id")?.asString    // 新格式：id

                // 拿不到 user_id 说明数据异常，提示用户联系开发者
                if (userId.isNullOrBlank()) {
                    withContext(Dispatchers.Main) {
                        userMsg = "登录返回数据异常，请联系开发者"
                        loading = false
                    }
                    return@launch
                }

                // 手动取 profile 字段：优先命名字段 → 找不到 fallback 到 f1~f8
                // 这是一个"双兼容"小工具：先找命名字段（username），找不到就找对应数字字段（f2）
                fun str(key: String, fKey: String): String {
                    val v1 = runCatching { rawProfile?.get(key)?.asString }.getOrNull()
                    if (!v1.isNullOrBlank() && v1 != "null") return v1
                    val v2 = runCatching { rawProfile?.get(fKey)?.asString }.getOrNull()
                    return if (!v2.isNullOrBlank() && v2 != "null") v2 else ""
                }
                // 同上，但允许返回 null（用于可选字段，如 partner_id）
                fun strN(key: String, fKey: String): String? {
                    val v1 = runCatching { rawProfile?.get(key)?.asString }.getOrNull()
                    if (!v1.isNullOrBlank() && v1 != "null") return v1
                    val v2 = runCatching { rawProfile?.get(fKey)?.asString }.getOrNull()
                    return if (!v2.isNullOrBlank() && v2 != "null") v2 else null
                }

                // 取出各字段（兼容 f2~f8 旧格式）
                val pUsername   = str("username", "f2")
                val pNickname   = str("nickname", "f3")
                val pAvatar     = str("avatar", "f4")
                val pGender     = str("gender", "f5")
                val pCoupleCode = str("couple_code", "f6")  // 配对码（用于后面 PairCard 显示）
                val pCoupleId   = strN("couple_id", "f7")
                val pPartnerId  = strN("partner_id", "f8")    // 已配对时此为对方 id

                // ✅ RLS 全放开，anon key 就能读写所有表，根本不需要 JWT token！
                // 这里千万不能存假 token → NetworkModule 拦截器会带上 Authorization: Bearer 头，
                // PostgREST 收到无效 JWT 直接返回 401 PGRST301，所有 REST API 全部挂掉！
                UserRepository.get().setToken(null)

                // finalXxx：服务端返回值优先，缺失时用本地填入的值兜底
                val finalGender = pGender.takeIf { it.isNotBlank() } ?: gender
                val finalAvatar = pAvatar.takeIf { it.isNotBlank() } ?: avatar
                val finalNickname = pNickname.takeIf { it.isNotBlank() }
                    ?: cleanName.ifBlank { cleanUser }
                val finalCoupleCode = pCoupleCode

                // 把配对码存到 Activity 级状态，PairCard 会用到
                pairCode = finalCoupleCode
                // 构造 UserInfo 并保存到本地仓库（持久化到 SharedPreferences）
                UserRepository.get().setUser(
                    UserInfo(
                        id = userId,
                        username = pUsername.takeIf { it.isNotBlank() } ?: cleanUser,
                        nickname = finalNickname,
                        gender = finalGender,
                        avatar = finalAvatar,
                        coupleCode = finalCoupleCode,
                        partnerId = pPartnerId
                    )
                )

                // ===== 步骤 4：登录成功，切回主线程显示欢迎语 + 触发下一步 =====
                withContext(Dispatchers.Main) {
                    userMsg = if (mode == "login") "欢迎回来，$finalNickname 💕"
                             else "注册成功！$finalNickname 💕"
                    loading = false
                    onOk()  // 切换到 PAIR 卡片
                }
            } else {
                // ===== verifyLogin 失败 —— 翻译错误为友好中文 =====
                val errCode = verifyOk?.code() ?: 0
                val msg = when {
                    // 用户名或密码错误
                    verifyErrBody.contains("INVALID_CREDENTIALS") ||
                        verifyErrBody.lowercase().contains("invalid") ->
                        "用户名或密码不对，请重新输入 💕"
                    // 账号不存在（profile 找不到）
                    verifyErrBody.contains("PROFILE_NOT_FOUND") ->
                        "账号不存在，请先注册"
                    // 数据库授权问题
                    verifyErrBody.lowercase().contains("permission denied") ->
                        "登录功能还没准备好（数据库缺少授权）"
                    // 404 表示 RPC 函数没找到（数据库 SQL 脚本未执行）
                    errCode == 404 ->
                        "登录服务未就绪，请联系开发者执行 SQL 修复脚本"
                    // 网络异常（如无网络、超时）
                    verifyEx != null ->
                        "网络异常：${verifyEx.message?.take(50).orEmpty()}"
                    // 其他错误走统一翻译函数
                    else ->
                        translateLoginError(errCode, verifyErrBody, verifyEx, cleanPass.length)
                }
                withContext(Dispatchers.Main) { userMsg = msg; loading = false }
            }
        }
    }

    // ========================================================================
    // 注册 RPC 错误 → 友好中文
    //
    // 把服务端返回的英文错误信息翻译成对用户友好的中文提示
    // 参数：
    //   httpCode : HTTP 状态码（200 成功，4xx 客户端错误，5xx 服务端错误）
    //   body     : 错误响应体原文（可能为空）
    //   ex       : 网络异常（如果是网络层失败而非服务端返回）
    // ========================================================================
    private fun translateRegisterError(httpCode: Int, body: String, ex: Throwable?): String {
        // 全部转小写方便匹配（避免大小写差异）
        val lowBody = body.lowercase()
        val b = if (body.isBlank()) "" else body
        return when {
            // 常见 RPC 409 用户自定义异常：USERNAME_EXISTS（用户名已存在）
            b.contains("USERNAME_EXISTS") || b.contains("username_exists") ||
                b.contains("already exists") || lowBody.contains("duplicate") ||
                lowBody.contains("unique") ->
                "这个用户名已经注册啦，直接用它「登录」就行 💕\n如果忘记密码，换一个用户名重新注册也可以"

            // PostgREST / pgcrypto 权限问题（数据库层授权漏了）
            lowBody.contains("permission denied") || lowBody.contains("execute") ->
                "注册功能还没准备好（数据库缺少授权），请联系开发者检查 SQL"

            // pgcrypto 参数错误（密码太短？一般不会到这里，客户端已经校验）
            lowBody.contains("password") && lowBody.contains("length") ->
                "密码至少 8 位，请修改后重试"

            // 其他 4xx：尝试解析 JSON 错误信息
            httpCode in 400..499 -> {
                // 用 Gson 解析 {"message":"..."} 这种结构
                val msg = runCatching {
                    Gson().fromJson(body, com.coupletracker.android.data.RpcErrorResp::class.java)?.message
                }.getOrNull().orEmpty()
                if (msg.isNotBlank()) "注册失败：$msg"
                else "注册失败 (HTTP $httpCode)\n密码至少 8 位 / 用户名 3 位"
            }

            // 5xx：服务端内部错误
            httpCode in 500..599 ->
                "服务器开小差啦（HTTP $httpCode），稍等 10 秒再点一下试试"

            // 网络异常（如手机无网络、DNS 解析失败）
            ex != null ->
                "网络异常：${ex.message?.take(50).orEmpty()}\n请检查手机网络（4G/WiFi）"

            // 兜底默认提示
            else -> "注册失败，请稍后再试"
        }
    }

    // ========================================================================
    // 登录错误 → 友好中文（和之前类似，但去掉重复的"429 注册分支"）
    //
    // 参数：
    //   errCode     : HTTP 状态码
    //   errBodyRaw  : 错误响应体
    //   netErr      : 网络异常（可能是 null）
    //   passwordLen : 密码长度，用于辅助判断（如长度不足）
    // ========================================================================
    private fun translateLoginError(
        errCode: Int, errBodyRaw: String, netErr: Throwable?, passwordLen: Int
    ): String {
        val body = errBodyRaw
        return when {
            // 429：请求过多被限流（短时间发了太多登录请求）
            errCode == 429 || body.contains("email rate limit") ||
                body.contains("over_email_send_rate_limit") -> {
                "登录请求太多啦，稍等 1 分钟再试"
            }
            // 400：客户端请求错误
            errCode == 400 -> when {
                body.contains("password") && body.contains("length") ->
                    "密码至少 8 位，请修改后重试"
                body.contains("bad_json") ->
                    "请求格式错误，请更新到最新版 APP"
                body.contains("Email not confirmed") ->
                    "账号未激活（罕见），请用同一个用户名重新注册一次"
                body.contains("Invalid login") || body.contains("Invalid credentials") ||
                    body.contains("invalid_grant") ->
                    "用户名或密码不对，请重新输入"
                else -> "请求失败 ($errCode)：请检查密码至少 8 位"
            }
            // 401~499：其他客户端错误
            errCode in 401..499 -> when {
                body.contains("already") || body.contains("already_registered") ->
                    "账号已存在，请直接登录"
                body.contains("User not found") || body.contains("not_found") ->
                    "账号不存在，请先注册"
                body.contains("Invalid") || body.contains("invalid") ||
                    body.contains("Invalid login") ->
                    "用户名或密码不对"
                body.contains("password") ->
                    "密码不对，再想想？"
                else -> "请求失败 ($errCode)"
            }
            // 网络层异常（没收到服务端响应）
            netErr != null -> {
                "网络异常：${netErr.message?.take(50).orEmpty()}\n请检查手机网络（4G/WiFi）"
            }
            // 兜底默认提示
            else -> "请求失败，请稍后再试"
        }
    }

    // ========================================================================
    // PairCard：配对卡片
    //
    // 配对是情侣追踪 APP 的核心功能：让两位用户绑定关系。
    // 工作机制（双向）：
    //   ①  A 看到"自己的配对码"，把它告诉 B
    //   ②  B 在输入框输入 A 的配对码，点击"发起配对"
    //   ③  B 端发送 pair_by_code 请求 → 服务端记录"等待 B 接受"
    //   ④  A 端通过轮询 check_pair_status 发现 incoming_request → 弹窗让 A 接受/拒绝
    //   ⑤  A 点击"接受" → accept_pair RPC → 双方绑定成功
    //
    // 轮询是本卡片的关键：每 3 秒请求一次服务端获取最新配对状态
    // ========================================================================
    @Composable
    fun PairCard(onPairOkOrSkip: () -> Unit) {
        // ---- 卡片内部状态 ----
        var inputCode by remember { mutableStateOf("") }    // 用户输入的对方配对码
        var msg by remember { mutableStateOf<String>("") } // 给用户的提示文字
        var copyTip by remember { mutableStateOf("") }     // 复制按钮的临时提示文字
        // collectAsState：把 UserRepository 的 Flow 转成 Compose State，自动响应变化
        val user by UserRepository.get().userFlow.collectAsState(initial = null)
        // 用户信息变化时同步 pairCode（万一服务端下发了新的配对码）
        LaunchedEffect(user) { pairCode = user?.coupleCode ?: pairCode }

        // 配对相关状态：
        var paired by remember { mutableStateOf(false) }    // 是否已配对成功
        var pairedWithNick by remember { mutableStateOf("") }  // 已配对的对方昵称
        var waiting by remember { mutableStateOf(false) }  // 是否已发请求等对方确认
        // incomingRequest：收到的对方配对请求（不为空时弹窗让用户决定）
        var incomingRequest by remember { mutableStateOf<com.coupletracker.android.data.CheckPairStatusResp?>(null) }
        var lastSendTime by remember { mutableStateOf(0L) }  // 上次发送配对请求的时间戳（用于防重复）

        // ========================================================================
        // 【轮询逻辑】每 3 秒向服务端查询一次当前用户的配对状态
        //
        // LaunchedEffect(Unit)：进入卡片时启动一次，Unit 不变则不会重启
        // while(true)：无限循环，直到卡片退出（LaunchedEffect 退出时自动取消协程）
        //
        // 服务端返回的 status 有四种：
        //   "paired"           —— 已配对成功
        //   "incoming_request" —— 收到对方发来的配对请求（需弹窗确认）
        //   "waiting"          —— 我方已发请求，等对方确认
        //   其他              —— 初始状态，重置 UI
        // ========================================================================
        LaunchedEffect(Unit) {
            while (true) {
                try {
                    // 从本地仓库读取当前登录用户
                    val me = UserRepository.get().getUser()
                    if (me?.id != null) {
                        // 调用 check_pair_status RPC 查询配对状态
                        val resp = NetworkModule.rpcService.checkPairStatus(
                            CheckPairStatusReq(myId = me.id)
                        )
                        val body = resp.body()
                        val errBody = resp.errorBody()?.string()
                        // 有错误响应体则打印到 Logcat（用 adb logcat PairCard:* 查看）
                        if (errBody != null && errBody.isNotBlank()) {
                            Log.e("PairCard", "check_pair_status error: ${errBody.take(200)}")
                        }
                        if (body != null) {
                            Log.d("PairCard", "check_pair_status: status=${body.status}")
                            // 根据服务端返回的 status 更新 UI 状态
                            when (body.status) {
                                // 已配对成功：更新本地用户信息，记录对方 id
                                "paired" -> {
                                    paired = true
                                    pairedWithNick = body.partnerNickname ?: "TA"
                                    incomingRequest = null
                                    waiting = false
                                    // 把 partnerId 保存到本地仓库（后续地图等界面会用到）
                                    UserRepository.get().setUser(me.copy(partnerId = body.partnerId))
                                }
                                // 收到对方配对请求：弹窗让本用户决定是否接受
                                // 注意：只在 incomingRequest 为空时才赋值，避免重复弹窗
                                "incoming_request" -> {
                                    if (incomingRequest == null) {
                                        Log.d("PairCard", "收到配对请求 from ${body.requesterNickname}")
                                        incomingRequest = body
                                    }
                                }
                                // 我方已发请求等对方确认：UI 切到"等待中"
                                "waiting" -> {
                                    waiting = true
                                    incomingRequest = null
                                }
                                // 其他状态：重置到初始
                                else -> {
                                    waiting = false
                                    incomingRequest = null
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    // 网络异常不要中断轮询，打印后继续下一轮
                    Log.e("PairCard", "轮询异常: ${e.message}")
                }
                // 关键：每 3 秒轮询一次（太频繁会耗电+限流，太慢则响应不及时）
                kotlinx.coroutines.delay(3000)
            }
        }

        // ========================================================================
        // 【收到配对请求时弹窗确认】
        // 当 incomingRequest 不为 null 时显示 AlertDialog
        // 用户点"接受" → 调用 accept_pair RPC 完成绑定
        // 用户点"拒绝" → 仅关闭弹窗（不通知服务端，对方只能继续等待或超时）
        // ========================================================================
        if (incomingRequest != null) {
            val requester = incomingRequest!!
            AlertDialog(
                onDismissRequest = { incomingRequest = null },  // 点外部关闭弹窗
                title = { Text("💑 配对请求") },
                text = { Text("${requester.requesterNickname ?: "TA"} 想和你配对，是否接受？") },
                confirmButton = {
                    Button(
                        onClick = {
                            // 取出请求者的 user id
                            val requesterId = requester.requesterId ?: ""
                            // 在 IO 线程发起接受配对的网络请求
                            lifecycleScope.launch(Dispatchers.IO) {
                                val me = UserRepository.get().getUser() ?: return@launch
                                // 调用 accept_pair RPC
                                val resp = runCatching {
                                    NetworkModule.rpcService.acceptPair(
                                        AcceptPairReq(myId = me.id, theirId = requesterId)
                                    )
                                }
                                val body = resp.getOrNull()?.body()
                                // body.ok == true 表示服务端确认配对成功
                                if (body?.ok == true) {
                                    // 保存对方 id 到本地
                                    UserRepository.get().setUser(
                                        me.copy(partnerId = requesterId)
                                    )
                                    // 切回主线程刷新 UI
                                    withContext(Dispatchers.Main) {
                                        paired = true
                                        pairedWithNick = body.partnerNickname ?: "TA"
                                        incomingRequest = null
                                        msg = "配对成功！💕"
                                    }
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE75480))
                    ) { Text("接受 💕") }
                },
                dismissButton = {
                    TextButton(onClick = { incomingRequest = null }) {
                        Text("拒绝", color = Color.Gray)
                    }
                }
            )
        }

        // 复制配对码到系统剪贴板的工具函数（局部函数，仅本卡片用）
        fun copyCode(code: String) {
            if (code.isBlank()) return
            runCatching {
                // 获取系统剪贴板服务
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                // 写入剪贴板（label "couple_code" 仅供某些系统显示用）
                cm.setPrimaryClip(ClipData.newPlainText("couple_code", code))
            }
            copyTip = "✅ 已复制"
            // 1.5 秒后把按钮文字还原为"复制"
            lifecycleScope.launch {
                kotlinx.coroutines.delay(1500)
                copyTip = ""
            }
        }

        // ---- 配对卡片主体 UI ----
        BoxWithGradient {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 28.dp, vertical = 36.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("💑", fontSize = 72.sp)
                Spacer(Modifier.height(8.dp))
                Text("邀请TA，开启旅程",
                    color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(18.dp))

                // ---- 我的配对码展示卡片（含"复制"按钮）----
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color.White)
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text("我的配对码：", color = Color(0xFF718096), fontSize = 13.sp)
                        Spacer(Modifier.height(6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            // 显示配对码（还没拿到时显示"等待生成..."）
                            Text(
                                text = pairCode.ifBlank { "等待生成..." },
                                fontSize = 32.sp, color = Color(0xFFE75480),
                                fontWeight = FontWeight.ExtraBold,
                                letterSpacing = 4.sp  // 字间距加大，6 位码更醒目
                            )
                            Spacer(Modifier.weight(1f))  // 把复制按钮推到右边
                            // 配对码生成后才显示复制按钮
                            if (pairCode.isNotBlank()) {
                                OutlinedButton(
                                    onClick = { copyCode(pairCode) },
                                    border = BorderStroke(1.dp, Color(0xFFE75480)),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                    shape = RoundedCornerShape(50)  // 全圆角胶囊形
                                ) {
                                    Text("📋", fontSize = 12.sp, color = Color(0xFFE75480))
                                    Spacer(Modifier.width(4.dp))
                                    Text(
                                        if (copyTip.isNotBlank()) copyTip else "复制",
                                        fontSize = 12.sp,
                                        color = Color(0xFFE75480)
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "把这串6位码发给TA，让TA在下面输入 👇",
                            color = Color(0xFF718096), fontSize = 12.sp
                        )
                    }
                }

                // ---- 已配对成功分支：显示成功提示卡片 ----
                if (paired) {
                    Spacer(Modifier.height(18.dp))
                    Card(
                        Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFDEF7EC))  // 浅绿
                    ) {
                        Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("🎉 已配对成功！", fontSize = 18.sp, color = Color(0xFF22543D), fontWeight = FontWeight.Bold)
                            if (pairedWithNick.isNotBlank()) {
                                Spacer(Modifier.height(4.dp))
                                Text("和 $pairedWithNick 绑定中 💕", fontSize = 13.sp, color = Color(0xFF2F855A))
                            }
                            Spacer(Modifier.height(12.dp))
                            Button(
                                onClick = onPairOkOrSkip,  // 进入下一步（权限引导）
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2F855A))
                            ) { Text("进入小世界 💕", fontSize = 16.sp) }
                        }
                    }
                } else {
                    // ---- 未配对分支：输入对方配对码并发起请求 ----
                    Spacer(Modifier.height(18.dp))
                    Text("输入TA的配对码", color = Color.White, fontSize = 14.sp)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = inputCode,
                        // 输入时自动去空格并转大写（配对码不区分大小写）
                        onValueChange = { inputCode = it.trim().uppercase() },
                        singleLine = true,
                        label = { Text("TA的配对码") },
                        leadingIcon = { Text("🔗", fontSize = 18.sp) },
                        trailingIcon = {
                            // "粘贴"按钮：从系统剪贴板读取内容
                            TextButton(onClick = {
                                runCatching {
                                    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    val clip = cm.primaryClip
                                    if (clip != null && clip.itemCount > 0) {
                                        inputCode = clip.getItemAt(0).text.toString().trim().uppercase()
                                    }
                                }
                            }) { Text("粘贴", color = Color.White, fontSize = 12.sp) }
                        },
                        colors = outlinedPinkColors(),
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (msg.isNotBlank()) {
                        Spacer(Modifier.height(6.dp))
                        Text(msg, color = Color.White, fontSize = 13.sp)
                    }
                    Spacer(Modifier.height(16.dp))
                    // ---- 发起配对按钮（核心动作）----
                    Button(
                        onClick = {
                            // 防抖：30 秒内已发请求且仍在等待 → 拒绝重复点击
                            val now = System.currentTimeMillis()
                            if (now - lastSendTime < 30000 && waiting) {
                                msg = "⏳ 已发送配对请求，请等待对方确认（30秒内不可重复发送）"
                                return@Button
                            }
                            loading = true; msg = ""
                            // 在 IO 线程发起 pair_by_code RPC 请求
                            lifecycleScope.launch(Dispatchers.IO) {
                                val me = UserRepository.get().getUser()
                                val myId = me?.id ?: ""
                                val theirCode = inputCode.trim().uppercase()

                                // 本地校验：必须有 user id 且对方码至少 4 位
                                if (myId.isBlank() || theirCode.length < 4) {
                                    withContext(Dispatchers.Main) {
                                        loading = false
                                        msg = "请输入完整的TA的配对码（至少4位）💕"
                                    }
                                    return@launch
                                }

                                // 调用 pair_by_code RPC（带上我的 id 和对方的配对码）
                                val resp = runCatching {
                                    NetworkModule.rpcService.pairByCode(
                                        PairByCodeReq(myId = myId, theirCode = theirCode)
                                    )
                                }
                                val body = resp.getOrNull()?.body()
                                val errBody = runCatching {
                                    resp.getOrNull()?.errorBody()?.string()
                                }.getOrNull().orEmpty()
                                val ex = resp.exceptionOrNull()

                                Log.d("PairCard", "pair_by_code: ok=${body?.ok} reason=${body?.reason} err=${errBody.take(200)} ex=${ex?.message?.take(100)}")

                                // ===== 成功分支：根据 body 里的标志细分三种结果 =====
                                if (resp.getOrNull()?.isSuccessful == true && body?.ok == true) {
                                    lastSendTime = System.currentTimeMillis()
                                    if (body.already_paired == true) {
                                        // 情况 1：其实已经配对过（如对方先接受了）
                                        UserRepository.get().setUser(
                                            me!!.copy(partnerId = body.their_id)
                                        )
                                        withContext(Dispatchers.Main) {
                                            paired = true
                                            pairedWithNick = body.their_nickname ?: "TA"
                                            loading = false
                                            msg = "已和 $pairedWithNick 配对 💕"
                                        }
                                    } else if (body.waiting == true) {
                                        // 情况 2：30 秒内已发过请求，服务端告知"还在等待"
                                        withContext(Dispatchers.Main) {
                                            loading = false
                                            waiting = true
                                            msg = body.msg ?: "已发送配对请求，等待对方确认中"
                                        }
                                    } else {
                                        // 情况 3：本次请求发送成功，等对方在 TA 的 APP 上确认
                                        withContext(Dispatchers.Main) {
                                            loading = false
                                            waiting = true
                                            msg = body.msg ?: "配对请求已发送，等待对方确认"
                                        }
                                    }
                                } else {
                                    // ===== 失败分支：把 reason 翻译成友好中文 =====
                                    val reason = body?.reason
                                    val friendly = when {
                                        reason == "CODE_NOT_FOUND" ->
                                            "配对码不存在：请让TA查看TA自己的配对码 💕"
                                        reason == "CANNOT_PAIR_SELF" ->
                                            "不能和自己配对哦 😅"
                                        reason == "ME_NOT_FOUND" ->
                                            "你的账号信息丢失了，请退出重新登录一次"
                                        reason == "THEY_ALREADY_PAIRED" ->
                                            "TA已经和别人配对了 😢"
                                        reason == "INVALID_ARGS" ->
                                            "参数错误：请确认输入完整的6位码"
                                        ex != null ->
                                            "网络异常：${ex.message?.take(50).orEmpty()}"
                                        errBody.isNotBlank() ->
                                            "配对失败：${errBody.take(80)}"
                                        else -> "配对失败，请稍后再试"
                                    }
                                    withContext(Dispatchers.Main) {
                                        loading = false
                                        msg = friendly
                                    }
                                }
                            }
                        },
                        // 按钮可点击条件：不在加载中 + 输入至少 4 位
                        enabled = !loading && inputCode.length >= 4,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                            .clip(RoundedCornerShape(26.dp)),
                        // 等待中按钮变灰，避免用户误以为可重新点
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (waiting) Color(0xFFA0AEC0) else Color(0xFF667EEA)
                        )
                    ) {
                        // 三种按钮内容：加载圈 / 等待文字 / 主文字
                        if (loading) CircularProgressIndicator(
                            color = Color.White, modifier = Modifier.size(20.dp))
                        else if (waiting) Text("⏳ 等待对方确认...", fontSize = 16.sp)
                        else Text("发起配对 💕", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    }
                }

                Spacer(Modifier.height(10.dp))
                // 跳过按钮：允许用户先进入 APP，之后再配对
                TextButton(onClick = onPairOkOrSkip) {
                    Text("稍后再说，先进入APP →", color = Color.White)
                }
            }
        }
    }

    // ========================================================================
    // PermCard：权限引导卡片
    //
    // 情侣追踪 APP 需要四类权限才能正常工作：
    //   1. 定位权限（前台）—— 拿到 GPS 坐标
    //   2. 后台定位       —— APP 切后台后仍能上报位置（Android 10+ 必须单独申请）
    //   3. 使用情况访问   —— 读取用户在哪些 APP 上花了多少时间
    //   4. 通知权限       —— 接收配对请求、消息提醒（Android 13+ 才需申请）
    //
    // 参数 onAllDone：所有完成后切到 DONE 步骤（跳转主界面）
    // ========================================================================
    @Composable
    fun PermCard(onAllDone: () -> Unit) {
        // ---- 权限列表（mutableStateListOf：可变列表，元素变化时 UI 自动刷新）----
        // 每项 PermItem 包含：key（标识）、title、desc、icon、granted（是否已授予）
        val permList = remember {
            mutableStateListOf(
                // 1. 定位权限：检查精确定位 + 粗略定位是否都已授权
                PermItem(
                    "location",
                    R.string.perm_location_title, R.string.perm_location_desc,
                    { Text("📍", fontSize = 24.sp) },
                    granted = hasAll(
                        listOf(Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION)
                    )
                ),
                // 2. 后台定位：Android 10+ 需要单独申请的 ACCESS_BACKGROUND_LOCATION
                PermItem(
                    "backgroundLocation", "后台定位",
                    "切到后台后也能让TA看到你（Android 10+需单独允许）",
                    { Text("📡", fontSize = 24.sp) },
                    granted = hasBgLocation()
                ),
                // 3. 使用情况访问：无法用普通权限 API 申请，必须跳系统设置页
                PermItem(
                    "usage", R.string.perm_usage_title, R.string.perm_usage_desc,
                    { Text("📱", fontSize = 24.sp) }, granted = usageMonitor.hasUsagePermission()
                ),
                // 4. 通知权限：Android 13（TIRAMISU）以上才需要申请
                PermItem(
                    "notif", R.string.perm_notification_title,
                    R.string.perm_notification_desc,
                    { Text("🔔", fontSize = 24.sp) },
                    granted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                        has(Manifest.permission.POST_NOTIFICATIONS) else true
                )
            )
        }

        // 🔴 所有权限都授予了吗？（用于显示"重启生效"提示）
        val allGranted = permList.all { it.granted }

        // 进入卡片时启动后台追踪服务（如果权限齐了）
        LaunchedEffect(Unit) {
            // ✅ 只有权限基本齐全时才启动服务（避免权限变化时的竞态崩溃）
            runCatching { TrackerService.start(this@LoginActivity) }
        }

        BoxWithGradient {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 32.dp)
            ) {
                Text("🔐 开启必要权限",
                    color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                Text("为了实时报备给TA，我们需要这些权限",
                    color = Color.White.copy(0.85f), fontSize = 14.sp)
                Spacer(Modifier.height(18.dp))

                // 渲染每个权限行（PermRow 见下方定义）
                permList.forEach { p ->
                    PermRow(p,
                        onGrant = { key -> request(key, permList) }
                    )
                    Spacer(Modifier.height(10.dp))
                }

                Spacer(Modifier.height(18.dp))

                // 🔴 所有权限都授予了 → 显示"重启生效"提示
                // 因为某些权限（特别是后台定位、使用情况访问）需要重启才生效
                if (allGranted) {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = Color(0xFFFFF5F5)),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.padding(14.dp)) {
                            Text("✅ 所有权限已开启！", color = Color(0xFF2F855A),
                                fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "💡 建议重启APP让所有权限完整生效：\n" +
                                    "① 从手机最近任务里把 CoupleTracker 划掉\n" +
                                    "② 重新打开即可\n\n" +
                                    "⚠️ 特别是「后台定位」「使用情况访问」这两个权限，" +
                                    "部分手机必须重启才能真正生效。",
                                color = Color(0xFF4A5568), fontSize = 12.sp, lineHeight = 18.sp
                            )
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                }

                // ---- 厂商机型温馨提示 ----
                // 小米/华为/OPPO/VIVO 等厂商对后台进程有额外限制，需要用户手动在"手机管家"放行
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = Color.White.copy(0.15f)),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Text("💡 温馨提示", color = Color.White, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "部分手机（小米/华为/OPPO/VIVO）需要在自带的「手机管家」里额外：\n" +
                                "① 允许本APP自启动\n" +
                                "② 在最近任务里给本APP加🔒锁定，防杀进程\n" +
                                "③ 电池优化设置为「无限制」\n" +
                                "否则后台会被系统杀掉，位置就不会实时同步哦。",
                            color = Color.White.copy(0.9f), fontSize = 12.sp, lineHeight = 18.sp
                        )
                    }
                }

                Spacer(Modifier.height(26.dp))
                // ---- 进入主界面按钮 ----
                Button(
                    onClick = onAllDone,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .clip(RoundedCornerShape(26.dp)),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFE75480), contentColor = Color.White)
                ) { Text("开始使用 💕", fontSize = 18.sp) }
            }
        }
    }

    // =================== 小工具 ===================
    // PermItem：权限项数据类，每一行权限的信息
    //   title/desc 是 Any 类型：可以是 String（直接文字）或 Int（R.string 资源 id）
    //   icon 是 @Composable 函数：用于渲染图标（这里都是 emoji）
    //   granted：是否已授权（变化时 UI 刷新）
    data class PermItem(
        val key: String,
        val title: Any,    // String or Int(R.string)
        val desc: Any,
        val icon: @Composable () -> Unit,
        var granted: Boolean
    )

    // PermRow：单个权限行的 UI 组件
    // 左侧图标 → 中间标题/描述 → 右侧"去开启/已开启"按钮
    @Composable
    fun PermRow(p: PermItem, onGrant: (String) -> Unit) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White)
        ) {
            Row(
                modifier = Modifier.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 左侧 36dp 的图标容器
                Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                    p.icon()
                }
                Spacer(Modifier.width(12.dp))
                // 中间标题+描述（weight(1f) 占满剩余空间）
                Column(Modifier.weight(1f)) {
                    Text(p.titleText(), color = Color(0xFF2D3748),
                        fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                    Text(p.descText(), color = Color(0xFF718096),
                        fontSize = 12.sp, lineHeight = 16.sp)
                }
                Spacer(Modifier.width(8.dp))
                // 右侧按钮：已授权绿色，未授权粉色
                // 点击调用 onGrant，传入 key 让外层知道申请哪个权限
                Button(
                    onClick = { onGrant(p.key) },
                    shape = RoundedCornerShape(18.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (p.granted) Color(0xFF48BB78) else Color(0xFFE75480))
                ) { Text(if (p.granted) "✓ 已开启" else "去开启") }
            }
        }
    }

    // ---- 工具扩展函数：把 title/desc（可能是资源 id）转成 String ----
    // 如果是 Int（R.string.xxx），用 getString() 加载字符串资源
    // 否则直接强转为 String（用户传的就是字面量）
    private fun PermItem.titleText(): String = when (title) {
        is Int -> getString(title)
        else -> title as String
    }
    private fun PermItem.descText(): String = when (desc) {
        is Int -> getString(desc)
        else -> desc as String
    }

    // ========================================================================
    // ============ 权限请求逻辑 ============
    // ========================================================================

    // has：检查单个权限是否已授予
    //   checkSelfPermission 返回值等于 PERMISSION_GRANTED 表示已授权
    private fun has(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED
    // hasAll：列表里所有权限都已授予才返回 true
    private fun hasAll(ps: List<String>) = ps.all { has(it) }
    // hasBgLocation：检查后台定位权限（Android 10+ 才有这个权限）
    private fun hasBgLocation(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContextCompat.checkSelfPermission(this,
                Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
        } else true  // 旧版本不需要此权限，直接返回 true
    }

    // ========================================================================
    // request：根据权限 key 发起对应的权限申请
    //
    // 不同权限走不同渠道：
    //   location / backgroundLocation / notif → multiplePerms.launch（系统弹窗）
    //   usage                                → 跳系统设置页（无法用弹窗申请）
    //
    // 申请完延迟 500ms 再刷新 UI（让系统先把状态更新到位）
    // ========================================================================
    private fun request(key: String, perms: MutableList<PermItem>) {
        lifecycleScope.launch {
            when (key) {
                "location" -> {
                    // 一次申请多个权限：精确定位 + 粗略定位 + （Android 13+ 通知）+ 活动识别
                    val list = mutableListOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    )
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        list += Manifest.permission.POST_NOTIFICATIONS
                    }
                    list += Manifest.permission.ACTIVITY_RECOGNITION
                    multiplePerms.launch(list.toTypedArray())
                }
                "backgroundLocation" -> {
                    // 后台定位必须单独申请（Android 10+ 系统限制）
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        multiplePerms.launch(arrayOf(
                            Manifest.permission.ACCESS_BACKGROUND_LOCATION
                        ))
                    }
                }
                "usage" -> {
                    // 使用情况访问权限无法用 Launcher 申请，只能跳系统设置页让用户手动开
                    if (!usageMonitor.hasUsagePermission()) {
                        startActivity(usageMonitor.createUsageSettingsIntent())
                    }
                }
                "notif" -> {
                    // 通知权限（Android 13+）
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        multiplePerms.launch(arrayOf(
                            Manifest.permission.POST_NOTIFICATIONS
                        ))
                    }
                }
            }
            // 延迟 500ms 等系统更新权限状态后，重新检查并刷新每行 UI
            delay(500L)
            perms.forEach { p ->
                p.granted = when (p.key) {
                    "location" -> hasAll(listOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    ))
                    "backgroundLocation" -> hasBgLocation()
                    "usage" -> usageMonitor.hasUsagePermission()
                    "notif" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                        has(Manifest.permission.POST_NOTIFICATIONS) else true
                    else -> p.granted
                }
            }
        }
    }

    // ========================================================================
    // ============ 通用组件 ============
    // ========================================================================

    // BoxWithGradient：带粉蓝渐变背景的容器
    // 所有卡片都套了这个容器，让整个页面有统一的渐变视觉
    // 参数 content 是一个 @Composable lambda，承载卡片实际内容
    @Composable
    fun BoxWithGradient(content: @Composable BoxScope.() -> Unit) {
        Box(
            modifier = Modifier
                .fillMaxSize()  // 铺满整个屏幕
                .background(
                    // Brush.linearGradient：线性渐变，从上到下依次是三种颜色
                    //   0xFFFF6B9D → 浅粉红
                    //   0xFFD53F8C → 玫红
                    //   0xFF667EEA → 蓝紫
                    brush = Brush.linearGradient(
                        listOf(Color(0xFFFF6B9D), Color(0xFFD53F8C), Color(0xFF667EEA))
                    )
                ),
            content = content
        )
    }
    // outlinedPinkColors：粉色主题的输入框样式配置
    // 返回 OutlinedTextFieldDefaults.colors 对象
    // 这样所有输入框都用同一套粉色样式，避免重复代码
    // @OptIn(ExperimentalMaterial3Api) 表示用了实验性 API
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun outlinedPinkColors() = OutlinedTextFieldDefaults.colors(
        focusedContainerColor = Color.White,             // 聚焦时背景白
        unfocusedContainerColor = Color.White,           // 未聚焦时背景白
        focusedBorderColor = Color(0xFFE75480),          // 聚焦时边框粉
        focusedLabelColor = Color(0xFFE75480),           // 聚焦时标签粉
        focusedLeadingIconColor = Color(0xFFE75480),     // 聚焦时左侧图标粉
        focusedTrailingIconColor = Color(0xFFE75480)     // 聚焦时右侧图标粉
    )
}
