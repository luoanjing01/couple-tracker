package com.coupletracker.android.ui

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.os.Bundle
import android.webkit.*
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import com.coupletracker.android.BuildConfig
import com.coupletracker.android.data.NetworkModule
import com.coupletracker.android.data.PairByCodeReq
import com.coupletracker.android.data.UserRepository
import com.coupletracker.android.service.TrackerService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * 主界面：底部3 Tab + WebView直接加载前端
 *   - 地图 Tab -> 访问 /map
 *   - 应用 Tab -> 访问 /apps
 *   - 统计 Tab -> 访问 /stats
 *   - 我的 Tab -> 原生设置页（配对码/退出登录/重启服务）
 *
 * Token注入：页面加载前写入 localStorage + Cookie，让Web端直接读取
 */
class MainActivity : ComponentActivity() {

    private enum class Tab(
        val path: String,
        val label: String,
        val icon: @Composable () -> Unit
    ) {
        MAP("/map",   "地图", { Text("🗺️", fontSize = 20.sp) }),
        APPS("/apps", "应用", { Text("📱", fontSize = 20.sp) }),
        STATS("/stats","统计",{ Text("📊", fontSize = 20.sp) }),
        ME("/me",     "我的", { Text("👤", fontSize = 20.sp) })
    }

    private var webView: WebView? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ✅ 闪退兜底：启动前台服务前再做一次权限检查，缺少权限则不启动
        // （Service 内部也会再次检查，这里双保险）
        runCatching {
            if (com.coupletracker.android.service.TrackerService.canStartForeground(this)) {
                com.coupletracker.android.service.TrackerService.start(this)
            }
        }

        setContent {
            MaterialTheme(colorScheme = lightColorScheme(
                primary = Color(0xFFE75480),
                secondary = Color(0xFF667EEA),
                background = Color(0xFFFDF2F8)
            )) {
                var selected by remember { mutableStateOf(Tab.MAP) }
                Scaffold(
                    bottomBar = {
                        NavigationBar(containerColor = Color.White) {
                            Tab.values().forEach { t ->
                                NavigationBarItem(
                                    selected = selected == t,
                                    onClick = { selected = t },
                                    icon = t.icon,
                                    label = { Text(t.label) },
                                    colors = NavigationBarItemDefaults.colors(
                                        selectedIconColor = Color(0xFFE75480),
                                        selectedTextColor = Color(0xFFE75480),
                                        indicatorColor = Color(0xFFFDF2F8)
                                    )
                                )
                            }
                        }
                    }
                ) { pad ->
                    Box(Modifier.padding(pad).fillMaxSize()) {
                        when (selected) {
                            Tab.MAP   -> PlaceholderScreen(
                                icon = { Text("🗺️", fontSize = 40.sp) },
                                title = "实时地图",
                                desc = "地图页面已接入 💕\n\n当前功能状态：\n✅ 位置已采集（后台按设置频率上报到云端）\n✅ 云端已保存所有位置记录\n✅ 两台手机同一个账号配对后即可互相查看\n✅ 已支持 WebView 本地地图 + Supabase 实时同步",
                                accent = Color(0xFFE75480),
                                useMapWebView = true
                            )
                            Tab.APPS  -> PlaceholderScreen(
                                icon = { Text("📱", fontSize = 40.sp) },
                                title = "应用使用",
                                desc = "应用使用页面将在正式版上线 💕\n\n当前功能状态：\n✅ APP 使用已采集（每 2 秒检查前台）\n✅ 每 60 秒上报一次使用时长到云端\n✅ 已自动识别微信/抖音/王者等常用APP分类",
                                accent = Color(0xFF667EEA)
                            )
                            Tab.STATS -> StatsScreen()
                            Tab.ME    -> SettingsScreen(onBackToMap = { selected = Tab.MAP })
                        }
                    }
                }
            }
        }
    }

    /** 构造注入脚本（每次同步读取最新 user/token，保证值不陈旧） */
    private fun buildInjectionJs(): String {
        // DataStore 读本地文件极快，但 API 是 suspend，用 runBlocking 包一层保证这里能同步取值
        val token = runCatching { kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) { UserRepository.get().getToken() } }.getOrNull()
        val u = runCatching { kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) { UserRepository.get().getUser() } }.getOrNull()
        val userJson = u?.let { org.json.JSONObject().apply {
            put("id", it.id)
            put("username", it.username)
            put("nickname", it.nickname)
            put("avatar", it.avatar ?: "")
            put("gender", it.gender ?: "")
            put("coupleCode", it.coupleCode ?: "")
        }.toString() } ?: "null"
        val tokenJs = if (token.isNullOrBlank()) "null" else "\"${token.replace("\"","\\\"")}\""
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
        val user by UserRepository.get().userFlow.collectAsState(initial = null)
        val locSec by UserRepository.get().locationIntervalSecFlow.collectAsState(
            initial = UserRepository.DEFAULT_LOC_INTERVAL_SEC
        )
        val appSec by UserRepository.get().appIntervalSecFlow.collectAsState(
            initial = UserRepository.DEFAULT_APP_INTERVAL_SEC
        )

        if (useMapWebView) {
            Box(Modifier.fillMaxSize()) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        WebView(ctx).apply {
                            // ✅ 显式 LayoutParams：Compose AndroidView 有时不会自动给 match_parent
                            layoutParams = android.view.ViewGroup.LayoutParams(
                                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                                android.view.ViewGroup.LayoutParams.MATCH_PARENT
                            )
                            setBackgroundColor(0x00000000) // 透明背景，避免 WebView 默认白色闪烁
                            overScrollMode = android.view.View.OVER_SCROLL_NEVER
                            isScrollContainer = false

                            // ✅ onSizeChanged：最可靠的尺寸变化回调（WebView 原生提供）
                            override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
                                super.onSizeChanged(w, h, oldw, oldh)
                                if (w != oldw || h != oldh) {
                                    runCatching {
                                        evaluateJavascript(
                                            ""try{ if(typeof kickSize==='function') kickSize(); if(typeof map!=='undefined'&&map) map.invalidateSize(true); }catch(e){}"",
                                            null
                                        )
                                    }
                                }
                            }

                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.databaseEnabled = true
                            settings.allowFileAccess = true
                            settings.allowContentAccess = true
                            settings.useWideViewPort = true
                            settings.loadWithOverviewMode = true
                            settings.setSupportZoom(true)
                            settings.builtInZoomControls = true
                            settings.displayZoomControls = false
                            settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                            webViewClient = object : WebViewClient() {

                                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                    super.onPageStarted(view, url, favicon)
                                    // 🚨 必须在主线程立刻注入：onPageStarted 时 evaluateJavascript 对 file:// 页面基本是同步生效的
                                    // 之前放在 lifecycleScope.launch(IO) 会延迟几十~几百毫秒，刚好错过 HTML <script> 的 readUser() 20次重试窗口，导致 me 永远 null，永远"等待位置"
                                    view ?: return
                                    runCatching { view.evaluateJavascript(buildInjectionJs(), null) }
                                }

                                override fun onPageFinished(view: WebView?, url: String?) {
                                    super.onPageFinished(view, url)
                                    // 双保险：onPageFinished 再注入一次 + 触发前端刷新回调
                                    // （HTML里的 Leaflet 初始化可能早于 onPageStarted，需要再手动通知）
                                    view ?: return
                                    runCatching {
                                        view.evaluateJavascript(buildInjectionJs(), null)
                                        // 100ms 后再发一次"信号"（如果前端在轮询用户，就当再踢一次）
                                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                            runCatching { view.evaluateJavascript("(function(){try{window.__applyAndroidInjection&&window.__applyAndroidInjection();}catch(e){}})();", null) }
                                        }, 120)
                                    }
                                }
                            }
                            val webViewRef = this
                            CookieManager.getInstance().apply {
                                setAcceptCookie(true)
                                setAcceptThirdPartyCookies(webViewRef, true)
                            }
                            webChromeClient = object : WebChromeClient() {}
                            // 优先加载本地离线地图（assets/www/index.html）
                            // 若该资源不存在，回退到加载占位网页（不会崩）
                            runCatching {
                                val list = ctx.assets.list("www")
                                if (!list.isNullOrEmpty()) {
                                    loadUrl("file:///android_asset/www/index.html#/map")
                                } else {
                                    loadDataWithBaseURL(
                                        null, buildFallbackMapHtml(),
                                        "text/html", "UTF-8", null
                                    )
                                }
                            }.getOrElse {
                                loadDataWithBaseURL(
                                    null, buildFallbackMapHtml(),
                                    "text/html", "UTF-8", null
                                )
                            }
                            this@MainActivity.webView = this
                        }
                    },
                    update = { wv ->
                        // 🚨 Tab 切换回来时（PlaceholderScreen 重组会触发 update）
                        //    ① 重新注入用户信息：防止刚登录/刚配对后切回地图页，前端仍用旧数据
                        //    ② 踢一下地图尺寸：防止 WebView 在后台状态中尺寸被清零
                        val js = buildInjectionJs() + "; try{ var m = (typeof map !== 'undefined' && map); if (m) { m.invalidateSize(true); setTimeout(function(){m.invalidateSize(true);},300);} } catch(e){}"
                        runCatching { wv.evaluateJavascript(js, null) }
                    }
                )
                // 右下角悬浮卡片：显示采集频率，避免遮挡地图关键区域
                Column(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 14.dp, bottom = 18.dp)
                ) {
                    Card(
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.96f))
                    ) {
                        Column(Modifier.padding(10.dp)) {
                            Text("采集频率", fontSize = 10.sp, color = Color(0xFF718096), fontWeight = FontWeight.Medium)
                            Spacer(Modifier.height(2.dp))
                            Text("📍 位置 每${locSec}秒", fontSize = 11.sp, color = Color(0xFF2D3748))
                            Text("📱 APP 每${appSec}秒", fontSize = 11.sp, color = Color(0xFF2D3748))
                        }
                    }
                }
            }
            return
        }
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 28.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                Modifier
                    .size(88.dp)
                    .background(accent.copy(alpha = 0.12f), RoundedCornerShape(28.dp)),
                contentAlignment = Alignment.Center
            ) {
                icon()
            }
            Spacer(Modifier.height(18.dp))
            Text(title, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color(0xFF2D3748))
            Spacer(Modifier.height(10.dp))
            Text(
                "正在为 ${"@" + (user?.username ?: "-")} 准备中...",
                color = Color(0xFF718096), fontSize = 13.sp
            )
            Spacer(Modifier.height(22.dp))

            Card(
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White)
            ) {
                Column(Modifier.padding(20.dp)) {
                    Text(
                        "💕 后台采集状态",
                        fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color(0xFF2D3748)
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(10.dp).background(Color(0xFF48BB78), RoundedCornerShape(50)))
                        Spacer(Modifier.width(8.dp))
                        Text("位置上报", color = Color(0xFF2D3748), fontSize = 13.sp)
                        Spacer(Modifier.weight(1f))
                        Text("每 ${locSec} 秒", color = Color(0xFF718096), fontSize = 12.sp)
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(10.dp).background(Color(0xFF48BB78), RoundedCornerShape(50)))
                        Spacer(Modifier.width(8.dp))
                        Text("APP 使用", color = Color(0xFF2D3748), fontSize = 13.sp)
                        Spacer(Modifier.weight(1f))
                        Text("每 ${appSec} 秒检测", color = Color(0xFF718096), fontSize = 12.sp)
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(10.dp).background(Color(0xFF48BB78), RoundedCornerShape(50)))
                        Spacer(Modifier.width(8.dp))
                        Text("数据存储", color = Color(0xFF2D3748), fontSize = 13.sp)
                        Spacer(Modifier.weight(1f))
                        Text("云端 Supabase", color = Color(0xFF718096), fontSize = 12.sp)
                    }
                }
            }

            Spacer(Modifier.height(18.dp))
            Card(
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White)
            ) {
                Column(Modifier.padding(20.dp)) {
                    Text("📝 功能说明", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color(0xFF2D3748))
                    Spacer(Modifier.height(12.dp))
                    Text(desc, color = Color(0xFF4A5568), fontSize = 13.sp, lineHeight = 22.sp)
                }
            }

            Spacer(Modifier.height(30.dp))
            Text(
                "所有数据已安全保存到云端 ✅",
                color = Color(0xFF48BB78), fontSize = 12.sp, fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "版本 v${BuildConfig.VERSION_NAME}（测试版）",
                color = Color(0xFFA0AEC0), fontSize = 11.sp
            )
        }
    }

    /** assets/www 读不到时的最小兜底页：带样式提示 + 1s 后自动重试跳 assets */
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


    private fun navigate(path: String) { /* 占位，暂时不用 WebView */ }

    @OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
    @Composable
    fun SettingsScreen(onBackToMap: () -> Unit) {
        val user by UserRepository.get().userFlow.collectAsState(initial = null)
        val locSec by UserRepository.get().locationIntervalSecFlow.collectAsState(
            initial = UserRepository.DEFAULT_LOC_INTERVAL_SEC
        )
        val appSec by UserRepository.get().appIntervalSecFlow.collectAsState(
            initial = UserRepository.DEFAULT_APP_INTERVAL_SEC
        )
        // Slider 的临时值（拖动时实时显示，松手写仓库）
        val tmpLoc = remember(locSec) { mutableFloatStateOf(locSec.toFloat()) }
        val tmpApp = remember(appSec) { mutableFloatStateOf(appSec.toFloat()) }
        // 确保仓库值变化时同步回临时值
        LaunchedEffect(locSec) { tmpLoc.floatValue = locSec.toFloat() }
        LaunchedEffect(appSec) { tmpApp.floatValue = appSec.toFloat() }
        Column(
            Modifier
                .fillMaxSize()
                .background(Color(0xFFFDF2F8))
                .padding(horizontal = 20.dp, vertical = 24.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("💕 ", fontSize = 48.sp)
                Column {
                    Text(
                        user?.displayName ?: "未登录",
                        fontSize = 22.sp,
                        color = Color(0xFF2D3748),
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "@" + (user?.username ?: "-"),
                        color = Color(0xFF718096), fontSize = 13.sp
                    )
                }
            }

            Spacer(Modifier.height(20.dp))

            val code = (user?.coupleCode ?: "").uppercase()
            var pairInput by rememberSaveable { mutableStateOf("") }
            var pairMsg by rememberSaveable { mutableStateOf("") }
            var pairLoading by rememberSaveable { mutableStateOf(false) }
            var copyTip by remember { mutableStateOf("") }
            // 是否已配对：同 couple_code 存在其他 profile。每次显示/切到「我的」时查一次
            var hasPartner by remember { mutableStateOf<Boolean?>(null) }
            var partnerName by remember { mutableStateOf("") }
            LaunchedEffect(code) {
                hasPartner = null
                partnerName = ""
                if (code.isBlank()) return@LaunchedEffect
                withContext(Dispatchers.IO) {
                    val myId = user?.id ?: ""
                    runCatching {
                        NetworkModule.restService.getProfile(
                            coupleCode = code
                        )
                    }.getOrNull()?.body()?.filter { it.id != myId }?.firstOrNull()?.let { partner ->
                        hasPartner = true
                        partnerName = partner.nickname.ifBlank { partner.username }
                    } ?: run { hasPartner = false }
                }
            }

            fun copyCoupleCode() {
                if (code.isBlank()) return
                runCatching {
                    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("couple_code", code))
                }
                copyTip = "已复制"
                lifecycleScope.launch { delay(1500); copyTip = "" }
            }

            // 🎯 已配对状态 → 配对码 + 配对按钮 全部消失，只显示"已与 TA 绑定"状态卡
            //    （完全按用户要求：「配对上之后配对码和配对按钮才消失」）
            if (hasPartner == true && partnerName.isNotBlank()) {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFF0FFF4))
                ) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("❤️", fontSize = 28.sp)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(
                                "💞 已与 $partnerName 绑定",
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 18.sp,
                                color = Color(0xFF2F855A)
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                "去地图页查看彼此的实时位置吧 💕",
                                fontSize = 12.sp,
                                color = Color(0xFF38A169)
                            )
                        }
                    }
                }
            } else {
                // —— 未配对 / 加载中：显示我的配对码（含复制按钮）——
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White)
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text("配对码", color = Color(0xFF718096), fontSize = 12.sp)
                        Spacer(Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                if (code.isBlank()) "暂无" else code,
                                fontSize = 30.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = Color(0xFFE75480),
                                letterSpacing = 4.sp
                            )
                            Spacer(Modifier.weight(1f))
                            if (code.isNotBlank()) {
                                OutlinedButton(
                                    onClick = { copyCoupleCode() },
                                    border = BorderStroke(1.dp, Color(0xFFE75480)),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                    shape = RoundedCornerShape(50)
                                ) {
                                    Text(
                                        "📋",
                                        color = Color(0xFFE75480),
                                        fontSize = 12.sp
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Text(
                                        if (copyTip.isNotBlank()) copyTip else "复制",
                                        color = Color(0xFFE75480), fontSize = 12.sp
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            if (hasPartner == false)
                                "把这串码发给TA，让TA在下面或登录页「配对」输入即可绑定"
                            else "正在加载绑定状态...",
                            color = Color(0xFF718096),
                            fontSize = 12.sp
                        )
                    }
                }
            }

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
                            color = Color(0xFF2D3748)
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = pairInput,
                            onValueChange = { pairInput = it.trim().uppercase() },
                            singleLine = true,
                            label = { Text("TA 的配对码（6 位）") },
                            leadingIcon = { Text("🔗", fontSize = 18.sp) },
                            trailingIcon = {
                                TextButton(onClick = {
                                    runCatching {
                                        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        val clip = cm.primaryClip
                                        if (clip != null && clip.itemCount > 0) {
                                            pairInput = clip.getItemAt(0).text.toString().trim().uppercase()
                                        }
                                    }
                                }) { Text("粘贴", fontSize = 12.sp, color = Color(0xFF667EEA)) }
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                        if (pairMsg.isNotBlank()) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                pairMsg,
                                color = if (pairMsg.contains("成功")) Color(0xFF2F855A) else Color(0xFFE53E3E),
                                fontSize = 12.sp
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                        Button(
                            onClick = {
                                val me = user
                                if (me == null) { pairMsg = "账号信息丢失，请重登"; return@Button }
                                pairLoading = true; pairMsg = ""
                                lifecycleScope.launch(Dispatchers.IO) {
                                    val resp = runCatching {
                                        NetworkModule.rpcService.pairByCode(
                                            com.coupletracker.android.data.PairByCodeReq(
                                                myId = me.id,
                                                theirCode = pairInput.trim().uppercase()
                                            )
                                        )
                                    }
                                    val body = resp.getOrNull()?.body()
                                    val err = runCatching { resp.getOrNull()?.errorBody()?.string() }.getOrNull().orEmpty()
                                    val ex = resp.exceptionOrNull()
                                    withContext(Dispatchers.Main) {
                                        pairLoading = false
                                        when {
                                            resp.getOrNull()?.isSuccessful == true && body?.ok == true -> {
                                                val newCode = body.couple_code ?: pairInput.trim().uppercase()
                                                UserRepository.get().setUser(me.copy(coupleCode = newCode))
                                                hasPartner = true
                                                partnerName = body.their_nickname?.takeIf { it.isNotBlank() } ?: "TA"
                                                pairMsg = "✅ 配对成功！已和 $partnerName 绑定"
                                                pairInput = ""
                                            }
                                            body?.reason == "CODE_NOT_FOUND" ->
                                                pairMsg = "❌ 配对码不存在：让TA打开「我的」页确认TA的码"
                                            body?.reason == "CANNOT_PAIR_SELF" ->
                                                pairMsg = "😅 不能和自己配对哦"
                                            body?.reason == "ME_NOT_FOUND" ->
                                                pairMsg = "账号信息丢失，请退出后重新登录"
                                            ex != null ->
                                                pairMsg = "网络异常：${ex.message?.take(40).orEmpty()}"
                                            err.isNotBlank() ->
                                                pairMsg = "配对失败：${err.take(60)}"
                                            else -> pairMsg = "配对失败，请稍后再试"
                                        }
                                    }
                                }
                            },
                            enabled = !pairLoading && pairInput.length >= 4,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .clip(RoundedCornerShape(24.dp)),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF667EEA))
                        ) {
                            if (pairLoading) CircularProgressIndicator(
                                color = Color.White, modifier = Modifier.size(18.dp))
                            else Text("立即配对 💕", fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }

            Spacer(Modifier.height(14.dp))

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
                            color = Color(0xFF2D3748)
                        )
                    }
                    Spacer(Modifier.height(14.dp))

                    // —— 位置采集频率 Slider ——
                    Text("📍 位置上报", color = Color(0xFF4A5568), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(2.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "每 ${tmpLoc.floatValue.toInt()} 秒",
                            color = Color(0xFFE75480),
                            fontSize = 18.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            "范围 ${UserRepository.MIN_LOC_INTERVAL_SEC}-${UserRepository.MAX_LOC_INTERVAL_SEC}s",
                            color = Color(0xFFA0AEC0), fontSize = 10.sp
                        )
                    }
                    Slider(
                        value = tmpLoc.floatValue,
                        onValueChange = { tmpLoc.floatValue = it.roundToInt().toFloat() },
                        onValueChangeFinished = {
                            val sec = tmpLoc.floatValue.toInt()
                            lifecycleScope.launch(Dispatchers.IO) {
                                UserRepository.get().setLocationIntervalSec(sec)
                            }
                        },
                        valueRange = UserRepository.MIN_LOC_INTERVAL_SEC.toFloat()..UserRepository.MAX_LOC_INTERVAL_SEC.toFloat(),
                        steps = UserRepository.MAX_LOC_INTERVAL_SEC - UserRepository.MIN_LOC_INTERVAL_SEC - 1,
                        colors = SliderDefaults.colors(thumbColor = Color(0xFFE75480), activeTrackColor = Color(0xFFE75480))
                    )
                    Spacer(Modifier.height(10.dp))

                    // —— APP 使用采集频率 Slider ——
                    Text("📱 APP 使用检测", color = Color(0xFF4A5568), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(2.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "每 ${tmpApp.floatValue.toInt()} 秒",
                            color = Color(0xFF667EEA),
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
                        colors = SliderDefaults.colors(thumbColor = Color(0xFF667EEA), activeTrackColor = Color(0xFF667EEA))
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "✅ 调整后立即生效，无需重启APP",
                        color = Color(0xFF48BB78), fontSize = 11.sp, fontWeight = FontWeight.Medium
                    )
                    // 上报状态（方便用户排查"为什么地图没显示"）
                    Spacer(Modifier.height(10.dp))
                    val locStatus by NetworkModule.lastLocationReportStatusFlow.collectAsState()
                    val appStatus by NetworkModule.lastAppReportStatusFlow.collectAsState()
                    fun colorOf(s: String) = when {
                        s.contains("成功") -> Color(0xFF2F855A)
                        s.contains("失败") || s.contains("异常") -> Color(0xFFE53E3E)
                        else -> Color(0xFF718096)
                    }
                    Divider(color = Color(0xFFEDF2F7))
                    Spacer(Modifier.height(8.dp))
                    Text("🛰️ 上报状态 · 供排查参考", color = Color(0xFF4A5568), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text("📍 $locStatus", color = colorOf(locStatus), fontSize = 10.sp, lineHeight = 14.sp)
                    Spacer(Modifier.height(2.dp))
                    Text("📱 $appStatus", color = colorOf(appStatus), fontSize = 10.sp, lineHeight = 14.sp)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "如果「位置上报」连续失败：打开系统设置 → 应用权限 → 允许定位（允许始终允许）→ 再打开一次本APP",
                        color = Color(0xFFA0AEC0), fontSize = 10.sp, lineHeight = 14.sp
                    )
                }
            }

            Spacer(Modifier.height(14.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
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
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF667EEA))
                ) {
                    Text("🔄", fontSize = 16.sp)
                    Spacer(Modifier.width(4.dp))
                    Text("重启服务")
                }
            }

            Spacer(Modifier.height(26.dp))

            // ====== 云端服务信息（Supabase BaaS） ======
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White)
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("☁️ 云端服务", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color(0xFF2D3748))
                    Spacer(Modifier.height(8.dp))
                    Text("Supabase", color = Color(0xFF718096), fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    Text("Auth: ${NetworkModule.getApiBase().replace("/rest/v1", "/auth/v1")}", color = Color(0xFF718096), fontSize = 11.sp)
                    Text("REST: ${NetworkModule.getApiBase()}", color = Color(0xFF718096), fontSize = 11.sp)
                }
            }

            Spacer(Modifier.height(16.dp))
            OutlinedButton(
                onClick = {
                    lifecycleScope.launch(Dispatchers.IO) {
                        UserRepository.get().logout()
                        TrackerService.stop(this@MainActivity)
                        withContext(Dispatchers.Main) { finish() }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(26.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = Color(0xFFE53E3E)
                ),
                border = ButtonDefaults.outlinedButtonBorder.copy(
                    brush = SolidColor(Color(0xFFE53E3E))
                )
            ) { Text("退出登录") }

            Spacer(Modifier.height(20.dp))
            Text(
                "版本 v${BuildConfig.VERSION_NAME}",
                color = Color(0xFF718096), fontSize = 12.sp,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
            Text(
                "后端 ${NetworkModule.getApiBase()}\n前端 ${BuildConfig.DEFAULT_WEB_BASE}",
                color = Color(0xFF718096), fontSize = 10.sp,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
        }
    }

    override fun onBackPressed() {
        if (webView?.canGoBack() == true) webView?.goBack()
        else super.onBackPressed()
    }
}
