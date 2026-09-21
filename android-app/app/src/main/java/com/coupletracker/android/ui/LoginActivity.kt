// ============================================================================
// LoginActivity.kt —— 登录 / 注册 / 配对 / 权限引导（潮汐卡片设计版）
//
// 流程：LOGIN 登录/注册 → PAIR 配对 → PERMS 权限引导 → DONE 跳转主界面
// 网络逻辑（doAuth / 配对轮询 / 权限申请）与旧版完全一致，仅 UI 换成
// concept-d-splash-login.html 的设计：珊瑚渐变 hero + 奶油色圆角表单。
// ============================================================================
package com.coupletracker.android.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.coupletracker.android.R
import com.coupletracker.android.appmonitor.AppUsageMonitor
import com.coupletracker.android.data.AcceptPairReq
import com.coupletracker.android.data.CheckPairStatusReq
import com.coupletracker.android.data.NetworkModule
import com.coupletracker.android.data.PairByCodeReq
import com.coupletracker.android.data.RegisterUserReq
import com.coupletracker.android.data.UserRepository
import com.coupletracker.android.data.VerifyLoginReq
import com.coupletracker.android.data.model.UserInfo
import com.coupletracker.android.service.TrackerService
import com.coupletracker.android.ui.theme.CardBg
import com.coupletracker.android.ui.theme.ChipBg
import com.coupletracker.android.ui.theme.ChipBgCool
import com.coupletracker.android.ui.theme.Coral
import com.coupletracker.android.ui.theme.CoralDeep
import com.coupletracker.android.ui.theme.CoralSoft
import com.coupletracker.android.ui.theme.Cream
import com.coupletracker.android.ui.theme.FieldBg
import com.coupletracker.android.ui.theme.Ink
import com.coupletracker.android.ui.theme.InkSoft
import com.coupletracker.android.ui.theme.Line
import com.coupletracker.android.ui.theme.Mint
import com.coupletracker.android.ui.theme.MintDeep
import com.coupletracker.android.ui.theme.Muted
import com.coupletracker.android.ui.theme.Peach
import com.coupletracker.android.ui.theme.StatusGreen
import com.coupletracker.android.ui.theme.TidalTheme
import com.coupletracker.android.ui.tidal.TidalLogoMark
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LoginActivity : ComponentActivity() {

    private enum class Step { LOGIN, PAIR, PERMS, DONE }

    private var pairCode by mutableStateOf("")
    private var userMsg by mutableStateOf("")
    private var loading by mutableStateOf(false)

    private val usageMonitor by lazy { AppUsageMonitor(this, lifecycleScope) }

    private val multiplePerms =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ ->
            if (!usageMonitor.hasUsagePermission()) {
                startActivity(usageMonitor.createUsageSettingsIntent())
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent {
            TidalTheme(darkStatusBarIcons = false) {
                LoginScreen()
            }
        }
    }

    @Composable
    fun LoginScreen() {
        var step by remember { mutableStateOf(Step.LOGIN) }
        when (step) {
            Step.LOGIN -> LoginCard(onLoginOk = { step = Step.PAIR })
            Step.PAIR -> PairCard(onPairOkOrSkip = { step = Step.PERMS })
            Step.PERMS -> PermCard(onAllDone = { step = Step.DONE })
            Step.DONE -> {
                LaunchedEffect(Unit) {
                    startActivity(android.content.Intent(this@LoginActivity, MainActivity::class.java))
                    finish()
                }
            }
        }
    }

    // ========================================================================
    // 登录 / 注册
    // ========================================================================
    @Composable
    fun LoginCard(onLoginOk: () -> Unit) {
        var mode by remember { mutableStateOf("login") }      // login | register
        var username by remember { mutableStateOf("") }
        var password by remember { mutableStateOf("") }
        var displayName by remember { mutableStateOf("") }
        var genderIdx by remember { mutableStateOf(0) }       // 0=女生 1=男生
        var pwdVisible by remember { mutableStateOf(false) }

        Box(Modifier.fillMaxSize().background(Cream)) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {

                // ===== 顶部珊瑚渐变 Hero =====
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(250.dp)
                        .background(
                            Brush.linearGradient(
                                listOf(CoralDeep, CoralSoft, Color(0xFFFFD4C4))
                            )
                        )
                ) {
                    // 装饰圆环 + 光斑
                    Box(Modifier.offset(x = 240.dp, y = (-100).dp).size(280.dp)
                        .border(1.5.dp, Color.White.copy(alpha = 0.16f), CircleShape))
                    Box(Modifier.offset(x = 290.dp, y = 40.dp).size(180.dp)
                        .border(1.5.dp, Color.White.copy(alpha = 0.10f), CircleShape))
                    Box(Modifier.offset(x = 300.dp, y = 20.dp).size(140.dp)
                        .alpha(0.5f).clip(CircleShape)
                        .background(Color(0xFFFFD4C4).copy(alpha = 0.5f)))

                    // 品牌区：mini logo + 欢迎语
                    Row(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(start = 26.dp, end = 26.dp, bottom = 34.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(Color.White.copy(alpha = 0.22f))
                                .border(1.5.dp, Color.White.copy(alpha = 0.35f), RoundedCornerShape(14.dp)),
                            contentAlignment = Alignment.Center
                        ) { TidalLogoMark(size = 30.dp) }
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(
                                if (mode == "login") "欢迎回来" else "很高兴见到你",
                                color = Color.White, fontSize = 22.sp,
                                fontWeight = FontWeight.ExtraBold
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                if (mode == "login") "登录潮汐，继续你们的旅程"
                                else "创建一个账号，邀请 TA 加入",
                                color = Color.White.copy(alpha = 0.9f), fontSize = 12.sp,
                                letterSpacing = 0.5.sp
                            )
                        }
                    }
                }

                // ===== 奶油色表单卡（上叠 22dp，顶部 28dp 圆角）=====
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .offset(y = (-22).dp)
                        .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                        .background(Cream)
                        .padding(horizontal = 24.dp)
                ) {
                    Spacer(Modifier.height(24.dp))

                    // ---- Pill Tabs：登录 / 注册 ----
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(999.dp))
                            .background(Ink.copy(alpha = 0.06f))
                            .padding(3.dp)
                    ) {
                        listOf("登录" to "login", "注册" to "register").forEach { (label, value) ->
                            val sel = mode == value
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(999.dp))
                                    .background(if (sel) Color.White else Color.Transparent)
                                    .clickableNoRipple { mode = value }
                                    .padding(vertical = 9.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                                    color = if (sel) Coral else InkSoft
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(18.dp))

                    // ---- 用户名 ----
                    TidalField(
                        value = username,
                        onValueChange = { username = it },
                        label = "用户名",
                        leading = { Text("👤", fontSize = 16.sp) }
                    )
                    Spacer(Modifier.height(12.dp))

                    // ---- 密码 ----
                    TidalField(
                        value = password,
                        onValueChange = { password = it },
                        label = "密码",
                        leading = { Text("🔒", fontSize = 16.sp) },
                        trailing = {
                            IconButton(onClick = { pwdVisible = !pwdVisible }) {
                                Text(if (pwdVisible) "👁️" else "🙈", fontSize = 15.sp)
                            }
                        },
                        visualTransformation =
                            if (pwdVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardType = KeyboardType.Password
                    )

                    // ---- 注册额外字段 ----
                    if (mode == "register") {
                        Spacer(Modifier.height(12.dp))
                        TidalField(
                            value = displayName,
                            onValueChange = { displayName = it },
                            label = "昵称（在地图上显示给 TA）",
                            leading = { Text("💗", fontSize = 16.sp) }
                        )
                        Spacer(Modifier.height(12.dp))
                        Row {
                            listOf("女生", "男生").forEachIndexed { idx, lbl ->
                                val sel = genderIdx == idx
                                FilterChip(
                                    selected = sel,
                                    onClick = { genderIdx = idx },
                                    label = { Text(lbl) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = Coral,
                                        selectedLabelColor = Color.White,
                                        containerColor = FieldBg,
                                        labelColor = InkSoft
                                    ),
                                    border = FilterChipDefaults.filterChipBorder(
                                        enabled = true, selected = sel,
                                        borderColor = Line,
                                        selectedBorderColor = Coral
                                    ),
                                    modifier = Modifier.padding(end = 8.dp)
                                )
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        // 账号要求提示
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(ChipBgCool)
                                .padding(horizontal = 14.dp, vertical = 10.dp)
                        ) {
                            Text(
                                "• 用户名 ≥ 3 位（字母/数字，推荐 6 位以上）\n" +
                                    "• 密码 ≥ 8 位，建议同时包含字母和数字\n" +
                                    "• 昵称将显示在地图上，给对方看的",
                                color = MintDeep, fontSize = 11.sp, lineHeight = 16.sp
                            )
                        }
                    }

                    Spacer(Modifier.height(20.dp))

                    // ---- 主按钮（渐变珊瑚）----
                    Button(
                        onClick = { doAuth(mode, username, password, displayName, genderIdx, onLoginOk) },
                        enabled = !loading && username.isNotBlank() && password.isNotBlank()
                                && (mode == "login" || displayName.isNotBlank()),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Coral,
                            contentColor = Color.White,
                            disabledContainerColor = Coral.copy(alpha = 0.45f),
                            disabledContentColor = Color.White.copy(alpha = 0.8f)
                        )
                    ) {
                        if (loading) CircularProgressIndicator(
                            color = Color.White, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        else Text(
                            if (mode == "login") "登录 →" else "创建账号 →",
                            fontSize = 15.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp
                        )
                    }

                    // ---- 提示信息 ----
                    if (userMsg.isNotBlank()) {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            userMsg, color = InkSoft, fontSize = 12.sp,
                            textAlign = TextAlign.Center, lineHeight = 17.sp,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    Spacer(Modifier.height(24.dp))
                    Text(
                        "登录即代表同意《用户协议》与《隐私政策》",
                        color = Muted, fontSize = 10.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(20.dp))
                }
            }
        }
    }

    // ========================================================================
    // doAuth：注册 + 登录核心网络逻辑（RPC 化，与旧版一致）
    // ========================================================================
    private fun doAuth(
        mode: String, username: String, password: String, displayName: String,
        genderIdx: Int, onOk: () -> Unit
    ) {
        val cleanUser = username.trim()
        val cleanPass = password.trim()
        val cleanName = displayName.trim()

        when {
            cleanUser.length < 3 -> {
                userMsg = "❌ 用户名至少 3 位（字母/数字，推荐 6 位以上）"; loading = false; return
            }
            cleanPass.length < 8 -> {
                userMsg = "❌ 密码至少 8 位，建议同时包含字母和数字"; loading = false; return
            }
            mode == "register" && cleanName.isEmpty() -> {
                userMsg = "❌ 请填写昵称（会在地图上显示给TA）"; loading = false; return
            }
        }

        userMsg = ""; loading = true
        lifecycleScope.launch(Dispatchers.IO) {
            val gender = if (genderIdx == 0) "female" else "male"
            val avatar = if (genderIdx == 0) "💗" else "💙"

            if (mode == "register") {
                val rpcResp = runCatching {
                    NetworkModule.rpcService.registerUser(
                        RegisterUserReq(
                            username = cleanUser,
                            password = cleanPass,
                            nickname = cleanName.ifBlank { cleanUser },
                            gender = gender
                        )
                    )
                }
                val regOk = rpcResp.getOrNull()
                val regErrBody = runCatching { regOk?.errorBody()?.string() }.getOrNull().orEmpty()
                val regEx = rpcResp.exceptionOrNull()
                if (regOk == null || !regOk.isSuccessful) {
                    val msg = translateRegisterError(regOk?.code() ?: 0, regErrBody, regEx)
                    withContext(Dispatchers.Main) { userMsg = msg; loading = false }
                    return@launch
                }
            }

            val verifyResp = runCatching {
                NetworkModule.rpcService.verifyLogin(
                    VerifyLoginReq(username = cleanUser, password = cleanPass)
                )
            }
            val verifyOk = verifyResp.getOrNull()
            val verifyBody = verifyOk?.body()
            val verifyErrBody = runCatching { verifyOk?.errorBody()?.string() }.getOrNull().orEmpty()
            val verifyEx = verifyResp.exceptionOrNull()

            if (verifyOk?.isSuccessful == true && verifyBody != null) {
                val rawProfile = verifyBody.profile
                val userId = verifyBody.user_id
                    ?: rawProfile?.get("f1")?.asString
                    ?: rawProfile?.get("id")?.asString

                if (userId.isNullOrBlank()) {
                    withContext(Dispatchers.Main) {
                        userMsg = "登录返回数据异常，请联系开发者"
                        loading = false
                    }
                    return@launch
                }

                fun str(key: String, fKey: String): String {
                    val v1 = runCatching { rawProfile?.get(key)?.asString }.getOrNull()
                    if (!v1.isNullOrBlank() && v1 != "null") return v1
                    val v2 = runCatching { rawProfile?.get(fKey)?.asString }.getOrNull()
                    return if (!v2.isNullOrBlank() && v2 != "null") v2 else ""
                }
                fun strN(key: String, fKey: String): String? {
                    val v1 = runCatching { rawProfile?.get(key)?.asString }.getOrNull()
                    if (!v1.isNullOrBlank() && v1 != "null") return v1
                    val v2 = runCatching { rawProfile?.get(fKey)?.asString }.getOrNull()
                    return if (!v2.isNullOrBlank() && v2 != "null") v2 else null
                }

                val pUsername   = str("username", "f2")
                val pNickname   = str("nickname", "f3")
                val pAvatar     = str("avatar", "f4")
                val pGender     = str("gender", "f5")
                val pCoupleCode = str("couple_code", "f6")
                val pPartnerId  = strN("partner_id", "f8")

                UserRepository.get().setToken(null)

                val finalGender = pGender.takeIf { it.isNotBlank() } ?: gender
                val finalAvatar = pAvatar.takeIf { it.isNotBlank() } ?: avatar
                val finalNickname = pNickname.takeIf { it.isNotBlank() }
                    ?: cleanName.ifBlank { cleanUser }

                pairCode = pCoupleCode
                UserRepository.get().setUser(
                    UserInfo(
                        id = userId,
                        username = pUsername.takeIf { it.isNotBlank() } ?: cleanUser,
                        nickname = finalNickname,
                        gender = finalGender,
                        avatar = finalAvatar,
                        coupleCode = pCoupleCode,
                        partnerId = pPartnerId
                    )
                )

                withContext(Dispatchers.Main) {
                    userMsg = if (mode == "login") "欢迎回来，$finalNickname 💕"
                             else "注册成功！$finalNickname 💕"
                    loading = false
                    onOk()
                }
            } else {
                val errCode = verifyOk?.code() ?: 0
                val msg = when {
                    verifyErrBody.contains("INVALID_CREDENTIALS") ||
                        verifyErrBody.lowercase().contains("invalid") ->
                        "用户名或密码不对，请重新输入 💕"
                    verifyErrBody.contains("PROFILE_NOT_FOUND") ->
                        "账号不存在，请先注册"
                    verifyErrBody.lowercase().contains("permission denied") ->
                        "登录功能还没准备好（数据库缺少授权）"
                    errCode == 404 ->
                        "登录服务未就绪，请联系开发者执行 SQL 修复脚本"
                    verifyEx != null ->
                        "网络异常：${verifyEx.message?.take(50).orEmpty()}"
                    else ->
                        translateLoginError(errCode, verifyErrBody, verifyEx, cleanPass.length)
                }
                withContext(Dispatchers.Main) { userMsg = msg; loading = false }
            }
        }
    }

    // ========================================================================
    // 注册错误翻译
    // ========================================================================
    private fun translateRegisterError(httpCode: Int, body: String, ex: Throwable?): String {
        val lowBody = body.lowercase()
        val b = if (body.isBlank()) "" else body
        return when {
            b.contains("USERNAME_EXISTS") || b.contains("username_exists") ||
                b.contains("already exists") || lowBody.contains("duplicate") ||
                lowBody.contains("unique") ->
                "这个用户名已经注册啦，直接用它「登录」就行 💕\n如果忘记密码，换一个用户名重新注册也可以"
            lowBody.contains("permission denied") || lowBody.contains("execute") ->
                "注册功能还没准备好（数据库缺少授权），请联系开发者检查 SQL"
            lowBody.contains("null value") || lowBody.contains("not-null") ->
                "注册信息不完整（数据库字段非空约束），请截图反馈给开发者"
            lowBody.contains("password") && lowBody.contains("crypt") ->
                "密码加密失败（数据库 pgcrypto 扩展问题），请联系开发者"
            lowBody.contains("username") && lowBody.contains("length") ->
                "用户名长度不符合要求（3-20 位字母/数字）"
            httpCode == 400 ->
                "注册请求格式不对：${b.take(80)}\n请检查输入是否符合要求"
            httpCode == 404 ->
                "注册服务未就绪（找不到函数），请联系开发者执行 SQL 修复脚本"
            httpCode == 429 || lowBody.contains("rate limit") || lowBody.contains("too many") ->
                "操作太频繁啦，请稍等几秒再试 💕"
            httpCode in 500..599 ->
                "服务器开小差了（$httpCode），请稍后再试"
            ex != null ->
                "网络异常：${ex.message?.take(50).orEmpty()}\n请检查网络连接后重试"
            else ->
                "注册失败：${if (b.isNotBlank()) b.take(80) else "未知错误 ($httpCode)"}"
        }
    }

    // ========================================================================
    // 登录错误翻译
    // ========================================================================
    private fun translateLoginError(httpCode: Int, body: String, ex: Throwable?, pwdLen: Int): String {
        val lowBody = body.lowercase()
        return when {
            httpCode == 400 && pwdLen < 8 ->
                "密码至少 8 位哦（当前只有 $pwdLen 位）"
            httpCode == 400 ->
                "用户名或密码格式不对，请检查后重试"
            httpCode == 401 || httpCode == 403 ->
                "用户名或密码不对，请重新输入 💕"
            httpCode == 404 ->
                "登录服务未就绪，请联系开发者执行 SQL 修复脚本"
            httpCode == 429 || lowBody.contains("rate limit") || lowBody.contains("too many") ->
                "操作太频繁啦，请稍等几秒再试 💕"
            httpCode in 500..599 ->
                "服务器开小差了（$httpCode），请稍后再试"
            ex != null ->
                "网络异常：${ex.message?.take(50).orEmpty()}\n请检查网络连接后重试"
            else ->
                "登录失败：${if (body.isNotBlank()) body.take(80) else "未知错误 ($httpCode)"}"
        }
    }

    // ========================================================================
    // 配对卡片
    // ========================================================================
    @Composable
    fun PairCard(onPairOkOrSkip: () -> Unit) {
        var inputCode by remember { mutableStateOf("") }
        var msg by remember { mutableStateOf("") }
        var copyTip by remember { mutableStateOf("") }
        val user by UserRepository.get().userFlow.collectAsState(initial = null)
        LaunchedEffect(user) { pairCode = user?.coupleCode ?: pairCode }

        var paired by remember { mutableStateOf(false) }
        var pairedWithNick by remember { mutableStateOf("") }
        var waiting by remember { mutableStateOf(false) }
        var incomingRequest by remember {
            mutableStateOf<com.coupletracker.android.data.CheckPairStatusResp?>(null)
        }
        var lastSendTime by remember { mutableStateOf(0L) }

        // 每 3 秒轮询配对状态
        LaunchedEffect(Unit) {
            while (true) {
                try {
                    val me = UserRepository.get().getUser()
                    if (me?.id != null) {
                        val resp = NetworkModule.rpcService.checkPairStatus(
                            CheckPairStatusReq(myId = me.id)
                        )
                        val body = resp.body()
                        val errBody = resp.errorBody()?.string()
                        if (!errBody.isNullOrBlank()) {
                            Log.e("PairCard", "check_pair_status error: ${errBody.take(200)}")
                        }
                        if (body != null) {
                            when (body.status) {
                                "paired" -> {
                                    paired = true
                                    pairedWithNick = body.partnerNickname ?: "TA"
                                    incomingRequest = null
                                    waiting = false
                                    UserRepository.get().setUser(me.copy(partnerId = body.partnerId))
                                }
                                "incoming_request" -> {
                                    if (incomingRequest == null) incomingRequest = body
                                }
                                "waiting" -> { waiting = true; incomingRequest = null }
                                else -> { waiting = false; incomingRequest = null }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e("PairCard", "轮询异常: ${e.message}")
                }
                delay(3000)
            }
        }

        // 收到配对请求 → 弹窗确认
        if (incomingRequest != null) {
            val requester = incomingRequest!!
            AlertDialog(
                onDismissRequest = { incomingRequest = null },
                title = { Text("💑 配对请求", color = Ink, fontWeight = FontWeight.Bold) },
                text = { Text("${requester.requesterNickname ?: "TA"} 想和你配对，是否接受？", color = InkSoft) },
                confirmButton = {
                    Button(
                        onClick = {
                            val requesterId = requester.requesterId ?: ""
                            lifecycleScope.launch(Dispatchers.IO) {
                                val me = UserRepository.get().getUser() ?: return@launch
                                val resp = runCatching {
                                    NetworkModule.rpcService.acceptPair(
                                        AcceptPairReq(myId = me.id, theirId = requesterId)
                                    )
                                }
                                val body = resp.getOrNull()?.body()
                                if (body?.ok == true) {
                                    UserRepository.get().setUser(me.copy(partnerId = requesterId))
                                    withContext(Dispatchers.Main) {
                                        paired = true
                                        pairedWithNick = body.partnerNickname ?: "TA"
                                        incomingRequest = null
                                        msg = "配对成功！💕"
                                    }
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Coral)
                    ) { Text("接受 💕") }
                },
                dismissButton = {
                    TextButton(onClick = { incomingRequest = null }) {
                        Text("拒绝", color = Muted)
                    }
                },
                containerColor = Color.White
            )
        }

        fun copyCode(code: String) {
            if (code.isBlank()) return
            runCatching {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("couple_code", code))
            }
            copyTip = "✅ 已复制"
            lifecycleScope.launch {
                delay(1500)
                copyTip = ""
            }
        }

        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(Cream, Peach)))
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 40.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(Modifier.height(20.dp))
                Text("💑", fontSize = 64.sp)
                Spacer(Modifier.height(8.dp))
                Text("邀请TA，开启旅程", color = Ink, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
                Spacer(Modifier.height(4.dp))
                Text("配对成功后，就能实时看到彼此啦", color = InkSoft, fontSize = 13.sp)
                Spacer(Modifier.height(22.dp))

                // ---- 我的配对码卡片 ----
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White)
                ) {
                    Column(Modifier.padding(18.dp)) {
                        Text("我的配对码", color = Muted, fontSize = 11.sp,
                            fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = pairCode.ifBlank { "等待生成..." },
                                fontSize = 32.sp, color = Coral,
                                fontWeight = FontWeight.ExtraBold, letterSpacing = 4.sp
                            )
                            Spacer(Modifier.weight(1f))
                            if (pairCode.isNotBlank()) {
                                OutlinedButton(
                                    onClick = { copyCode(pairCode) },
                                    border = BorderStroke(1.5.dp, Coral),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                    shape = RoundedCornerShape(999.dp)
                                ) {
                                    Text(
                                        if (copyTip.isNotBlank()) copyTip else "📋 复制",
                                        fontSize = 12.sp, color = Coral, fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Text("把这串 6 位码发给TA，让TA在下方输入 👇",
                            color = InkSoft, fontSize = 12.sp)
                    }
                }

                if (paired) {
                    // ---- 已配对成功 ----
                    Spacer(Modifier.height(18.dp))
                    Card(
                        Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(18.dp),
                        colors = CardDefaults.cardColors(containerColor = ChipBgCool)
                    ) {
                        Column(
                            Modifier.padding(18.dp).fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text("🎉 已配对成功！", fontSize = 18.sp, color = MintDeep,
                                fontWeight = FontWeight.Bold)
                            if (pairedWithNick.isNotBlank()) {
                                Spacer(Modifier.height(4.dp))
                                Text("和 $pairedWithNick 绑定中 💕", fontSize = 13.sp, color = MintDeep)
                            }
                            Spacer(Modifier.height(14.dp))
                            Button(
                                onClick = onPairOkOrSkip,
                                modifier = Modifier.fillMaxWidth().height(50.dp),
                                shape = RoundedCornerShape(14.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = MintDeep)
                            ) { Text("进入潮汐 💕", fontSize = 15.sp, fontWeight = FontWeight.Bold) }
                        }
                    }
                } else {
                    // ---- 输入对方配对码 ----
                    Spacer(Modifier.height(22.dp))
                    Text("输入TA的配对码", color = Ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(10.dp))
                    TidalField(
                        value = inputCode,
                        onValueChange = { inputCode = it.trim().uppercase() },
                        label = "TA的配对码",
                        leading = { Text("🔗", fontSize = 16.sp) },
                        trailing = {
                            TextButton(onClick = {
                                runCatching {
                                    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    val clip = cm.primaryClip
                                    if (clip != null && clip.itemCount > 0) {
                                        inputCode = clip.getItemAt(0).text.toString().trim().uppercase()
                                    }
                                }
                            }) { Text("粘贴", color = Coral, fontSize = 12.sp, fontWeight = FontWeight.SemiBold) }
                        }
                    )
                    if (msg.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text(msg, color = InkSoft, fontSize = 12.sp, lineHeight = 17.sp,
                            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                    }
                    Spacer(Modifier.height(16.dp))
                    Button(
                        onClick = {
                            val now = System.currentTimeMillis()
                            if (now - lastSendTime < 30000 && waiting) {
                                msg = "⏳ 已发送配对请求，请等待对方确认（30秒内不可重复发送）"
                                return@Button
                            }
                            loading = true; msg = ""
                            lifecycleScope.launch(Dispatchers.IO) {
                                val me = UserRepository.get().getUser()
                                val myId = me?.id ?: ""
                                val theirCode = inputCode.trim().uppercase()

                                if (myId.isBlank() || theirCode.length < 4) {
                                    withContext(Dispatchers.Main) {
                                        loading = false
                                        msg = "请输入完整的TA的配对码（至少4位）💕"
                                    }
                                    return@launch
                                }

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

                                Log.d("PairCard", "pair_by_code: ok=${body?.ok} reason=${body?.reason}")

                                if (resp.getOrNull()?.isSuccessful == true && body?.ok == true) {
                                    lastSendTime = System.currentTimeMillis()
                                    when {
                                        body.already_paired == true -> {
                                            UserRepository.get().setUser(
                                                me!!.copy(partnerId = body.their_id)
                                            )
                                            withContext(Dispatchers.Main) {
                                                paired = true
                                                pairedWithNick = body.their_nickname ?: "TA"
                                                loading = false
                                                msg = "已和 $pairedWithNick 配对 💕"
                                            }
                                        }
                                        body.waiting == true -> {
                                            withContext(Dispatchers.Main) {
                                                loading = false
                                                waiting = true
                                                msg = body.msg ?: "已发送配对请求，等待对方确认中"
                                            }
                                        }
                                        else -> {
                                            withContext(Dispatchers.Main) {
                                                loading = false
                                                waiting = true
                                                msg = body.msg ?: "配对请求已发送，等待对方确认"
                                            }
                                        }
                                    }
                                } else {
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
                        enabled = !loading && inputCode.length >= 4,
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (waiting) Muted else Coral,
                            contentColor = Color.White,
                            disabledContainerColor = Coral.copy(alpha = 0.45f)
                        )
                    ) {
                        when {
                            loading -> CircularProgressIndicator(
                                color = Color.White, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            waiting -> Text("⏳ 等待对方确认...", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                            else -> Text("发起配对 💕", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))
                TextButton(onClick = onPairOkOrSkip) {
                    Text("稍后再说，先进入APP →", color = InkSoft, fontSize = 13.sp)
                }
            }
        }
    }

    // ========================================================================
    // 权限引导卡片
    // ========================================================================
    @Composable
    fun PermCard(onAllDone: () -> Unit) {
        val permList = remember {
            mutableStateListOf(
                PermItem(
                    "location",
                    R.string.perm_location_title, R.string.perm_location_desc,
                    { Text("📍", fontSize = 22.sp) },
                    granted = hasAll(
                        listOf(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                        )
                    )
                ),
                PermItem(
                    "backgroundLocation", "后台定位",
                    "切到后台后也能让TA看到你（Android 10+需单独允许）",
                    { Text("📡", fontSize = 22.sp) },
                    granted = hasBgLocation()
                ),
                PermItem(
                    "usage", R.string.perm_usage_title, R.string.perm_usage_desc,
                    { Text("📱", fontSize = 22.sp) }, granted = usageMonitor.hasUsagePermission()
                ),
                PermItem(
                    "notif", R.string.perm_notification_title,
                    R.string.perm_notification_desc,
                    { Text("🔔", fontSize = 22.sp) },
                    granted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                        has(Manifest.permission.POST_NOTIFICATIONS) else true
                )
            )
        }
        val allGranted = permList.all { it.granted }

        LaunchedEffect(Unit) {
            runCatching { TrackerService.start(this@LoginActivity) }
        }

        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(Cream, Peach)))
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 40.dp)
            ) {
                Spacer(Modifier.height(12.dp))
                Text("🔐 开启必要权限", color = Ink, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
                Spacer(Modifier.height(4.dp))
                Text("为了实时报备给TA，我们需要这些权限", color = InkSoft, fontSize = 13.sp)
                Spacer(Modifier.height(20.dp))

                permList.forEach { p ->
                    PermRow(p, onGrant = { key -> request(key, permList) })
                    Spacer(Modifier.height(10.dp))
                }

                Spacer(Modifier.height(10.dp))

                if (allGranted) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = ChipBgCool),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.padding(14.dp)) {
                            Text("✅ 所有权限已开启！", color = MintDeep, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "💡 建议重启APP让所有权限完整生效：\n" +
                                    "① 从手机最近任务里把本APP划掉\n" +
                                    "② 重新打开即可\n\n" +
                                    "⚠️ 特别是「后台定位」「使用情况访问」这两个权限，" +
                                    "部分手机必须重启才能真正生效。",
                                color = InkSoft, fontSize = 12.sp, lineHeight = 18.sp
                            )
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                }

                // 厂商提示
                Card(
                    colors = CardDefaults.cardColors(containerColor = CardBg),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Text("💡 温馨提示", color = Ink, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "部分手机（小米/华为/OPPO/VIVO）需要在自带的「手机管家」里额外：\n" +
                                "① 允许本APP自启动\n" +
                                "② 在最近任务里给本APP加🔒锁定，防杀进程\n" +
                                "③ 电池优化设置为「无限制」\n" +
                                "否则后台会被系统杀掉，位置就不会实时同步哦。",
                            color = InkSoft, fontSize = 12.sp, lineHeight = 18.sp
                        )
                    }
                }

                Spacer(Modifier.height(24.dp))
                Button(
                    onClick = onAllDone,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Coral, contentColor = Color.White)
                ) { Text("开始使用 💕", fontSize = 16.sp, fontWeight = FontWeight.Bold) }
                Spacer(Modifier.height(20.dp))
            }
        }
    }

    // =================== 权限小工具 ===================
    data class PermItem(
        val key: String,
        val title: Any,
        val desc: Any,
        val icon: @Composable () -> Unit,
        var granted: Boolean
    )

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
                Box(
                    Modifier
                        .size(42.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Peach),
                    contentAlignment = Alignment.Center
                ) { p.icon() }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(p.titleText(), color = Ink, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                    Text(p.descText(), color = InkSoft, fontSize = 12.sp, lineHeight = 16.sp)
                }
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = { onGrant(p.key) },
                    shape = RoundedCornerShape(999.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (p.granted) StatusGreen else Coral
                    )
                ) {
                    Text(
                        if (p.granted) "✓ 已开启" else "去开启",
                        fontSize = 12.sp, fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }

    private fun PermItem.titleText(): String = when (title) {
        is Int -> getString(title)
        else -> title as String
    }
    private fun PermItem.descText(): String = when (desc) {
        is Int -> getString(desc)
        else -> desc as String
    }

    private fun has(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED
    private fun hasAll(ps: List<String>) = ps.all { has(it) }
    private fun hasBgLocation(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        } else true
    }

    private fun request(key: String, perms: MutableList<PermItem>) {
        lifecycleScope.launch {
            when (key) {
                "location" -> {
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
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        multiplePerms.launch(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION))
                    }
                }
                "usage" -> {
                    if (!usageMonitor.hasUsagePermission()) {
                        startActivity(usageMonitor.createUsageSettingsIntent())
                    }
                }
                "notif" -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        multiplePerms.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
                    }
                }
            }
            delay(500L)
            perms.forEach { p ->
                p.granted = when (p.key) {
                    "location" -> hasAll(
                        listOf(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                        )
                    )
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
    // 通用组件：潮汐风格输入框（白底 70% + 聚焦珊瑚边框）
    // ========================================================================
    @Composable
    fun TidalField(
        value: String,
        onValueChange: (String) -> Unit,
        label: String,
        leading: @Composable (() -> Unit)? = null,
        trailing: @Composable (() -> Unit)? = null,
        visualTransformation: VisualTransformation = VisualTransformation.None,
        keyboardType: KeyboardType = KeyboardType.Text
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label, fontSize = 13.sp) },
            singleLine = true,
            leadingIcon = leading,
            trailingIcon = trailing,
            visualTransformation = visualTransformation,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            shape = RoundedCornerShape(14.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = Color.White,
                unfocusedContainerColor = FieldBg,
                focusedBorderColor = Coral,
                unfocusedBorderColor = Color.Transparent,
                focusedLabelColor = Coral,
                unfocusedLabelColor = Muted,
                cursorColor = Coral,
                focusedTextColor = Ink,
                unfocusedTextColor = Ink
            ),
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/** 无波纹点击（用于 pill tab，避免默认水波纹破坏设计稿观感） */
private fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier = composed {
    val interactionSource = remember { MutableInteractionSource() }
    clickable(
        interactionSource = interactionSource,
        indication = null,
        onClick = onClick
    )
}
