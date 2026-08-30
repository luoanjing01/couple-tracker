package com.coupletracker.android.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Bundle
import android.webkit.*
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import com.coupletracker.android.BuildConfig
import com.coupletracker.android.data.NetworkModule
import com.coupletracker.android.data.UserRepository
import com.coupletracker.android.service.TrackerService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
        val icon: ImageVector
    ) {
        MAP("/map", "地图", Icons.Default.LocationOn),
        APPS("/apps", "应用", Icons.Default.Apps),
        STATS("/stats", "统计", Icons.Default.BarChart),
        ME("/me", "我的", Icons.Default.Person)
    }

    private var webView: WebView? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runCatching { TrackerService.start(this) }

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
                                    onClick = {
                                        selected = t
                                        if (t != Tab.ME) navigate(t.path)
                                    },
                                    icon = { Icon(t.icon, null) },
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
                        if (selected == Tab.ME) {
                            SettingsScreen(onBackToMap = {
                                selected = Tab.MAP
                                navigate("/map")
                            })
                        } else {
                            // 用 NetworkModule 里用户可改的 Web 地址，而非 BuildConfig 写死的
                            val webBase by produceState(initialValue = BuildConfig.DEFAULT_WEB_BASE) {
                                value = NetworkModule.getWebBase()
                            }
                            WebPage(webBase + selected.path)
                        }
                    }
                }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Composable
    fun WebPage(url: String) {
        var loading by remember { mutableStateOf(true) }
        Box(Modifier.fillMaxSize()) {
            AndroidView(factory = { ctx ->
                WebView(ctx).apply {
                    layoutParams = android.view.ViewGroup.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        databaseEnabled = true
                        useWideViewPort = true
                        loadWithOverviewMode = true
                        allowContentAccess = true
                        allowFileAccess = true
                        mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                        cacheMode = WebSettings.LOAD_DEFAULT
                        mediaPlaybackRequiresUserGesture = false
                    }
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            v: WebView?, req: WebResourceRequest?
                        ): Boolean = false

                        override fun onPageStarted(
                            view: WebView?, u: String?, favicon: Bitmap?
                        ) {
                            loading = true
                            lifecycleScope.launch {
                                val token = UserRepository.get().getToken() ?: ""
                                val js = """
                                (function(){
                                  try {
                                    localStorage.setItem('token', '$token');
                                    localStorage.setItem('coupleTracker_token', '$token');
                                    window.__ANDROID_TOKEN__ = '$token';
                                    var d = new Date(); d.setFullYear(d.getFullYear()+1);
                                    document.cookie = 'token=$token; expires='+d.toUTCString()+'; path=/';
                                  } catch(e) {}
                                })();""".trimIndent()
                                view?.evaluateJavascript(js, null)
                            }
                        }

                        override fun onPageFinished(view: WebView?, u: String?) {
                            super.onPageFinished(view, u)
                            lifecycleScope.launch {
                                val token = UserRepository.get().getToken() ?: ""
                                view?.evaluateJavascript(
                                    "(function(){localStorage.setItem('token','$token');})();",
                                    null
                                )
                            }
                            loading = false
                        }
                    }
                    webChromeClient = object : WebChromeClient() {
                        override fun onPermissionRequest(request: PermissionRequest?) {
                            request?.grant(request.resources)
                        }
                    }
                    // 必须先把 WebView 引用挂到 Activity 上再初始化 CookieManager
                    this@MainActivity.webView = this
                    CookieManager.getInstance().apply {
                        setAcceptCookie(true)
                        setAcceptThirdPartyCookies(this@MainActivity.webView, true)
                    }
                    loadUrl(url)
                }
            }, update = { v ->
                if (v.url?.trimEnd('/') != url.trimEnd('/')) v.loadUrl(url)
            })
            if (loading) {
                Column(
                    Modifier
                        .align(Alignment.Center)
                        .background(Color.White.copy(alpha = 0.92f))
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CircularProgressIndicator(color = Color(0xFFE75480))
                    Spacer(Modifier.height(10.dp))
                    Text("加载中...", color = Color(0xFF718096), fontSize = 13.sp)
                }
            }
        }
    }

    private fun navigate(path: String) {
        lifecycleScope.launch {
            val webBase = NetworkModule.getWebBase()
            val url = webBase + path
            webView?.loadUrl(url)
        }
    }

    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    fun SettingsScreen(onBackToMap: () -> Unit) {
        val user by UserRepository.get().userFlow.collectAsState(initial = null)
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

            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White)
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("配对码", color = Color(0xFF718096), fontSize = 12.sp)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        (user?.coupleCode ?: "暂无").uppercase(),
                        fontSize = 30.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color(0xFFE75480),
                        letterSpacing = 4.sp
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "把这串发给TA，在登录页「配对」输入即可绑定",
                        color = Color(0xFF718096), fontSize = 12.sp
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
                    Icon(Icons.Default.LocationOn, null)
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
                    Icon(Icons.Default.Refresh, null)
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
