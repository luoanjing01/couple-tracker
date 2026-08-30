package com.coupletracker.android.ui

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import com.coupletracker.android.R
import kotlinx.coroutines.delay
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.coupletracker.android.appmonitor.AppUsageMonitor
import com.coupletracker.android.data.NetworkModule
import com.coupletracker.android.data.SignUpBody
import com.coupletracker.android.data.SignUpMetadata
import com.coupletracker.android.data.UserRepository
import com.coupletracker.android.data.model.*
import com.coupletracker.android.service.TrackerService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 登录/注册/配对/权限引导 Activity（单页多步骤Compose）
 */
class LoginActivity : ComponentActivity() {

    private enum class Step { LOGIN, PAIR, PERMS, DONE }

    private var pairCode by mutableStateOf("")
    private var userMsg by mutableStateOf("")
    private var loading by mutableStateOf(false)

    private val usageMonitor by lazy { AppUsageMonitor(this, lifecycleScope) }

    // 权限请求 Launcher
    private val multiplePerms =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ ->
            // 请求完后，请求使用情况访问（需跳系统设置）
            if (!usageMonitor.hasUsagePermission()) {
                startActivity(usageMonitor.createUsageSettingsIntent())
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(
                primary = Color(0xFFE75480), secondary = Color(0xFF667EEA),
                background = Color(0xFFFDF2F8)
            )) {
                Surface(modifier = Modifier.fillMaxSize()) { LoginScreen() }
            }
        }
    }

    @Composable
    fun LoginScreen() {
        var step by remember { mutableStateOf(Step.LOGIN) }
        when (step) {
            Step.LOGIN   -> LoginCard(onLoginOk = { step = Step.PAIR })
            Step.PAIR    -> PairCard(onPairOkOrSkip = { step = Step.PERMS })
            Step.PERMS   -> PermCard(onAllDone = { step = Step.DONE })
            Step.DONE    -> {
                LaunchedEffect(Unit) {
                    startActivity(android.content.Intent(this@LoginActivity, MainActivity::class.java))
                    finish()
                }
            }
        }
    }

    // ======= 登录/注册卡片 =======
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun LoginCard(onLoginOk: () -> Unit) {
        var mode by remember { mutableStateOf("login") }  // login | register
        var username by remember { mutableStateOf("") }
        var password by remember { mutableStateOf("") }
        var displayName by remember { mutableStateOf("") }
        var genderIdx by remember { mutableStateOf(0) }
        var pwdVisible by remember { mutableStateOf(false) }

        BoxWithGradient {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 28.dp, vertical = 36.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("💕", fontSize = 72.sp)
                Spacer(Modifier.height(8.dp))
                Text("情侣空间", fontSize = 32.sp,
                    color = Color.White, fontWeight = FontWeight.Bold)
                Text("实时位置 · APP使用 · 每日陪伴",
                    color = Color.White.copy(alpha = 0.85f), fontSize = 14.sp)
                Spacer(Modifier.height(30.dp))

                // 模式切换
                val tabs = listOf("登录" to "login", "注册" to "register")
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color.White.copy(0.15f))
                ) {
                    tabs.forEach { (label, value) ->
                        val sel = mode == value
                        TextButton(
                            onClick = { mode = value },
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(20.dp))
                                .background(if (sel) Color.White else Color.Transparent),
                            colors = ButtonDefaults.textButtonColors(
                                contentColor = if (sel) Color(0xFFE75480) else Color.White
                            )
                        ) { Text(label, fontWeight = FontWeight.SemiBold) }
                    }
                }
                Spacer(Modifier.height(20.dp))

                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("用户名") },
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Person, null) },
                    colors = outlinedPinkColors(),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("密码") },
                    singleLine = true,
                    visualTransformation =
                        if (pwdVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { pwdVisible = !pwdVisible }) {
                            Icon(
                                if (pwdVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                null
                            )
                        }
                    },
                    leadingIcon = { Icon(Icons.Default.Lock, null) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    colors = outlinedPinkColors(),
                    modifier = Modifier.fillMaxWidth()
                )

                if (mode == "register") {
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = displayName,
                        onValueChange = { displayName = it },
                        label = { Text("昵称（在地图上显示）") },
                        singleLine = true,
                        leadingIcon = { Icon(Icons.Default.Favorite, null) },
                        colors = outlinedPinkColors(),
                        modifier = Modifier.fillMaxWidth()
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
                                    selectedContainerColor = Color(0xFFE75480),
                                    selectedLabelColor = Color.White
                                ),
                                modifier = Modifier.padding(end = 8.dp)
                            )
                        }
                    }
                }

                Spacer(Modifier.height(26.dp))
                Button(
                    onClick = {
                        doAuth(mode, username, password, displayName, genderIdx, onLoginOk)
                    },
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
                    if (loading) CircularProgressIndicator(
                        color = Color.White, modifier = Modifier.size(20.dp))
                    else Text(if (mode == "login") "登录" else "创建账号", fontSize = 18.sp)
                }

                Spacer(Modifier.height(10.dp))
                if (userMsg.isNotBlank()) {
                    Text(userMsg, color = Color.White, fontSize = 13.sp)
                }
            }
        }
    }

    private fun doAuth(
        mode: String, username: String, password: String, displayName: String,
        genderIdx: Int, onOk: () -> Unit
    ) {
        userMsg = ""; loading = true
        lifecycleScope.launch(Dispatchers.IO) {
            // 用 username 当 email（Supabase 要求 email 格式）
            val email = if (username.contains("@")) username else "$username@coupletracker.local"
            val gender = if (genderIdx == 0) "female" else "male"
            val avatar = if (genderIdx == 0) "💗" else "💙"

            // 1. 调 Supabase Auth
            val authResult = runCatching {
                if (mode == "login") {
                    NetworkModule.authService.signIn(email, password)
                } else {
                    NetworkModule.authService.signUp(
                        SignUpBody(
                            email = email,
                            password = password,
                            data = SignUpMetadata(
                                username = username,
                                nickname = displayName
                            )
                        )
                    )
                }
            }

            val ok = authResult.getOrNull()
            val authBody = ok?.body()
            val authToken = authBody?.access_token
            val authUserId = authBody?.user?.id

            // 2. 登录/注册成功 → 存 token + 从 REST API 拉 profile
            if (ok?.isSuccessful == true && authToken != null && authUserId != null) {
                UserRepository.get().setToken(authToken)
                UserRepository.get().setUser(
                    com.coupletracker.android.data.model.UserInfo(
                        id = authUserId,
                        username = username,
                        nickname = displayName,
                        gender = gender,
                        avatar = avatar,
                        coupleCode = ""
                    )
                )
                // 3. 查 profile，拿 couple_code
                val profileResp = runCatching {
                    NetworkModule.restService.getProfile(id = authUserId)
                }
                val profile = profileResp.getOrNull()?.body()?.firstOrNull()
                if (profile != null) {
                    pairCode = profile.couple_code
                    // 更新本地 user 信息（带 couple_code）
                    UserRepository.get().setUser(
                        com.coupletracker.android.data.model.UserInfo(
                            id = profile.id,
                            username = profile.username,
                            nickname = profile.nickname.ifBlank { displayName },
                            gender = gender,
                            avatar = avatar,
                            coupleCode = profile.couple_code
                        )
                    )
                }
                withContext(Dispatchers.Main) {
                    userMsg = if (mode == "login") "欢迎回来，$displayName 💕"
                             else "注册成功！$displayName 💕"
                    loading = false
                    onOk()
                }
            } else {
                // Supabase 错误信息
                val errBody = authResult.getOrNull()?.errorBody()?.string()
                val netErr = authResult.exceptionOrNull()
                val err = when {
                    errBody?.contains("already") == true -> "账号已存在，请直接登录"
                    errBody?.contains("Invalid") == true -> "账号或密码错误"
                    errBody?.contains("User not found") == true -> "账号不存在，请先注册"
                    ok != null -> "请求失败 (${ok.code()})"
                    netErr != null -> "网络异常：${netErr.message}\n请检查手机网络连接"
                    else -> "未知错误"
                }
                withContext(Dispatchers.Main) {
                    userMsg = err; loading = false
                }
            }
        }
    }

    // ======= 配对卡片 =======
    @Composable
    fun PairCard(onPairOkOrSkip: () -> Unit) {
        var inputCode by remember { mutableStateOf("") }
        var msg by remember { mutableStateOf<String>("") }
        val user by UserRepository.get().userFlow.collectAsState(initial = null)
        LaunchedEffect(user) { pairCode = user?.coupleCode ?: pairCode }

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

                // 我的配对码
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color.White)
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text("我的配对码：", color = Color(0xFF718096), fontSize = 13.sp)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = pairCode.ifBlank { "等待生成..." },
                            fontSize = 32.sp, color = Color(0xFFE75480),
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = 4.sp
                        )
                        Spacer(Modifier.height(6.dp))
                        Text("把这串6位码发给TA，让TA在下面输入 👇",
                            color = Color(0xFF718096), fontSize = 12.sp)
                    }
                }
                Spacer(Modifier.height(18.dp))

                Text("输入TA的配对码", color = Color.White, fontSize = 14.sp)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = inputCode,
                    onValueChange = { inputCode = it.uppercase() },
                    singleLine = true,
                    label = { Text("TA的配对码") },
                    leadingIcon = { Icon(Icons.Default.Link, null) },
                    colors = outlinedPinkColors(),
                    modifier = Modifier.fillMaxWidth()
                )
                if (msg.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(msg, color = Color.White, fontSize = 13.sp)
                }
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = {
                        loading = true; msg = ""
                        lifecycleScope.launch(Dispatchers.IO) {
                            val user = UserRepository.get().getUser()
                            val myId = user?.id ?: ""
                            val theirCode = inputCode.trim().uppercase()

                            // 1. 查 TA 的 profile（按 couple_code）
                            val theirProfileResp = runCatching {
                                NetworkModule.restService.getProfile(coupleCode = theirCode)
                            }
                            val theirProfile = theirProfileResp.getOrNull()?.body()?.firstOrNull()

                            if (theirProfile == null) {
                                withContext(Dispatchers.Main) {
                                    loading = false
                                    msg = "配对码不存在，请检查是否输错 💕"
                                }
                                return@launch
                            }
                            if (theirProfile.id == myId) {
                                withContext(Dispatchers.Main) {
                                    loading = false
                                    msg = "不能和自己配对哦 😅"
                                }
                                return@launch
                            }

                            // 2. TA 如果已有 couple → 直接用那个
                            val coupleId: String = if (!theirProfile.couple_id.isNullOrBlank()) {
                                theirProfile.couple_id
                            } else {
                                // TA 还没建 couple → 创建
                                val createResp = runCatching {
                                    NetworkModule.restService.createCouple(
                                        mapOf(
                                            "code" to theirCode,
                                            "user_a" to theirProfile.id
                                        )
                                    )
                                }
                                createResp.getOrNull()?.body()?.id ?: ""
                            }

                            if (coupleId.isBlank()) {
                                withContext(Dispatchers.Main) {
                                    loading = false
                                    msg = "配对失败：无法创建情侣关系"
                                }
                                return@launch
                            }

                            // 3. 把自己加入 couple（如果 TA 是 user_a 则我是 user_b；反之亦然）
                            val coupleResp = runCatching {
                                NetworkModule.restService.getCouple(code = theirCode)
                            }
                            val couple = coupleResp.getOrNull()?.body()?.firstOrNull()

                            if (couple != null) {
                                // 更新 couple，把自己加进去
                                if (couple.user_a == myId) {
                                    // 我是 user_a，TA 是 user_b（反过来也行，看谁先创建的）
                                    val update = runCatching {
                                        NetworkModule.restService.updateCouple(
                                            id = couple.id,
                                            body = mapOf("user_b" to theirProfile.id)
                                        )
                                    }
                                    val update2 = runCatching {
                                        NetworkModule.restService.updateCouple(
                                            id = couple.id,
                                            body = mapOf("user_b" to myId)
                                        )
                                    }
                                } else {
                                    runCatching {
                                        NetworkModule.restService.updateCouple(
                                            id = couple.id,
                                            body = mapOf("user_b" to myId)
                                        )
                                    }
                                }
                                // 更新自己的 profile.couple_id
                                runCatching {
                                    NetworkModule.restService.updateProfile(
                                        id = myId,
                                        body = mapOf("couple_id" to couple.id)
                                    )
                                }
                                // 刷新本地用户信息
                                UserRepository.get().setUser(
                                    user!!.copy(coupleCode = theirCode)
                                )
                                pairCode = theirCode

                                withContext(Dispatchers.Main) {
                                    loading = false
                                    msg = "配对成功！💕 你们现在是一对啦"
                                    onPairOkOrSkip()
                                }
                            } else {
                                withContext(Dispatchers.Main) {
                                    loading = false
                                    msg = "配对失败：情侣关系未找到"
                                }
                            }
                        }
                    },
                    enabled = !loading && inputCode.length >= 4,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .clip(RoundedCornerShape(26.dp)),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF667EEA))
                ) {
                    if (loading) CircularProgressIndicator(
                        color = Color.White, modifier = Modifier.size(20.dp))
                    else Text("立即配对 💕", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                }

                Spacer(Modifier.height(10.dp))
                TextButton(onClick = onPairOkOrSkip) {
                    Text("稍后再说，先进入APP →", color = Color.White)
                }
            }
        }
    }

    // ======= 权限引导卡片 =======
    @Composable
    fun PermCard(onAllDone: () -> Unit) {
        val permList = remember {
            mutableStateListOf(
                PermItem(
                    "location",
                    R.string.perm_location_title, R.string.perm_location_desc,
                    Icons.Default.LocationOn,
                    granted = hasAll(
                        listOf(Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION)
                    )
                ),
                PermItem(
                    "backgroundLocation", "后台定位",
                    "切到后台后也能让TA看到你（Android 10+需单独允许）",
                    Icons.Default.MyLocation,
                    granted = hasBgLocation()
                ),
                PermItem(
                    "usage", R.string.perm_usage_title, R.string.perm_usage_desc,
                    Icons.Default.Apps, granted = usageMonitor.hasUsagePermission()
                ),
                PermItem(
                    "notif", R.string.perm_notification_title,
                    R.string.perm_notification_desc,
                    Icons.Default.Notifications,
                    granted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                        has(Manifest.permission.POST_NOTIFICATIONS) else true
                )
            )
        }

        LaunchedEffect(Unit) {
            // 启动服务（权限不齐也先启动，获取到权限后服务内自己检测会生效）
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

                permList.forEach { p ->
                    PermRow(p,
                        onGrant = { key -> request(key, permList) }
                    )
                    Spacer(Modifier.height(10.dp))
                }

                Spacer(Modifier.height(18.dp))
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
    data class PermItem(
        val key: String,
        val title: Any,    // String or Int(R.string)
        val desc: Any,
        val icon: ImageVector,
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
                Icon(p.icon, null, tint = Color(0xFFE75480), modifier = Modifier.size(36.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(p.titleText(), color = Color(0xFF2D3748),
                        fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                    Text(p.descText(), color = Color(0xFF718096),
                        fontSize = 12.sp, lineHeight = 16.sp)
                }
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = { onGrant(p.key) },
                    shape = RoundedCornerShape(18.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (p.granted) Color(0xFF48BB78) else Color(0xFFE75480))
                ) { Text(if (p.granted) "✓ 已开启" else "去开启") }
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

    // ============ 权限请求逻辑 ============
    private fun has(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED
    private fun hasAll(ps: List<String>) = ps.all { has(it) }
    private fun hasBgLocation(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContextCompat.checkSelfPermission(this,
                Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
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
                        multiplePerms.launch(arrayOf(
                            Manifest.permission.ACCESS_BACKGROUND_LOCATION
                        ))
                    }
                }
                "usage" -> {
                    // 无法运行时请求，必须跳设置页
                    if (!usageMonitor.hasUsagePermission()) {
                        startActivity(usageMonitor.createUsageSettingsIntent())
                    }
                }
                "notif" -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        multiplePerms.launch(arrayOf(
                            Manifest.permission.POST_NOTIFICATIONS
                        ))
                    }
                }
            }
            delay(500L)
            // 刷新UI
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

    // ============ 通用组件 ============
    @Composable
    fun BoxWithGradient(content: @Composable BoxScope.() -> Unit) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    brush = Brush.linearGradient(
                        listOf(Color(0xFFFF6B9D), Color(0xFFD53F8C), Color(0xFF667EEA))
                    )
                ),
            content = content
        )
    }
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun outlinedPinkColors() = OutlinedTextFieldDefaults.colors(
        focusedContainerColor = Color.White,
        unfocusedContainerColor = Color.White,
        focusedBorderColor = Color(0xFFE75480),
        focusedLabelColor = Color(0xFFE75480),
        focusedLeadingIconColor = Color(0xFFE75480),
        focusedTrailingIconColor = Color(0xFFE75480)
    )
}
