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
import androidx.compose.runtime.saveable.rememberSaveable
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
                        when (selected) {
                            Tab.MAP   -> PlaceholderScreen(
                                icon = Icons.Default.LocationOn,
                                title = "实时地图",
                                desc = "地图页面已接入 💕\n\n当前功能状态：\n✅ 位置已采集（后台按设置频率上报到云端）\n✅ 云端已保存所有位置记录\n✅ 两台手机同一个账号配对后即可互相查看\n✅ 已支持 WebView 本地地图 + Supabase 实时同步",
                                accent = Color(0xFFE75480),
                                useMapWebView = true
                            )
                            Tab.APPS  -> PlaceholderScreen(
                                icon = Icons.Default.Apps,
                                title = "应用使用",
                                desc = "应用使用页面将在正式版上线 💕\n\n当前功能状态：\n✅ APP 使用已采集（每 2 秒检查前台）\n✅ 每 60 秒上报一次使用时长到云端\n✅ 已自动识别微信/抖音/王者等常用APP分类",
                                accent = Color(0xFF667EEA)
                            )
                            Tab.STATS -> PlaceholderScreen(
                                icon = Icons.Default.BarChart,
                                title = "每日统计",
                                desc = "每日使用统计页面将在正式版上线 💕\n\n当前功能状态：\n✅ 每日使用数据已完整记录到云端\n✅ 支持按日期/按APP/按分类统计查询\n✅ 打开时长、移动轨迹、打开次数全记录",
                                accent = Color(0xFF48BB78)
                            )
                            Tab.ME    -> SettingsScreen(onBackToMap = { selected = Tab.MAP })
                        }
                    }
                }
            }
        }
    }

    // ========================================================================
    //  原生占位页 + WebView 地图（前端 dist 部署到 assets/www 后直接离线加载）
    // ========================================================================
    @OptIn(ExperimentalMaterial3Api::class)
    @SuppressLint("SetJavaScriptEnabled")
    @Composable
    fun PlaceholderScreen(
        icon: ImageVector,
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
                                    // 注入 token + user + supabase 配置，让前端直接读取
                                    lifecycleScope.launch(Dispatchers.IO) {
                                        val token = UserRepository.get().getToken()
                                        val u = UserRepository.get().getUser()
                                        val userJson = u?.let { org.json.JSONObject().apply {
                                            put("id", it.id)
                                            put("username", it.username)
                                            put("nickname", it.nickname)
                                            put("avatar", it.avatar)
                                            put("gender", it.gender ?: "")
                                            put("coupleCode", it.coupleCode ?: "")
                                        }.toString() } ?: "null"
                                        val js = """
                                            window.__SUPABASE_URL__ = "${BuildConfig.SUPABASE_URL}";
                                            window.__SUPABASE_ANON_KEY__ = "${BuildConfig.SUPABASE_ANON_KEY}";
                                            window.__AUTH_TOKEN__ = ${if (token == null) "null" else "\"$token\""};
                                            window.__CURRENT_USER__ = $userJson;
                                            localStorage.setItem('token', window.__AUTH_TOKEN__ || '');
                                            localStorage.setItem('user', window.__CURRENT_USER__ || 'null');
                                        """.trimIndent()
                                        withContext(Dispatchers.Main) {
                                            runCatching { view?.evaluateJavascript(js, null) }
                                        }
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
                        // 每次重组都不用重新加载，避免 WebView 闪烁
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
                Icon(icon, null, tint = accent, modifier = Modifier.size(44.dp))
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

    /** 本地 assets 没有 dist 时的兜底离线地图 HTML，直接用 Leaflet CDN + Supabase REST 拉位置 */
    private fun buildFallbackMapHtml(): String = """
        <!doctype html>
        <html>
        <head>
        <meta charset="utf-8">
        <meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no">
        <title>情侣地图</title>
        <link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css"/>
        <script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"></script>
        <style>
          html,body{margin:0;padding:0;height:100%;font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,"PingFang SC","Microsoft YaHei",sans-serif;background:#fdf2f8;}
          #map{width:100%;height:100%;}
          .topbar{position:absolute;top:10px;left:10px;right:10px;z-index:500;background:rgba(255,255,255,0.95);border-radius:14px;padding:10px 14px;box-shadow:0 4px 18px rgba(0,0,0,0.08);}
          .topbar .row{display:flex;justify-content:space-between;align-items:center;gap:10px;}
          .nick{font-weight:700;color:#2d3748;font-size:14px;}
          .meta{font-size:11px;color:#718096;margin-top:2px;}
          .status{font-size:12px;}
          .dot{display:inline-block;width:8px;height:8px;border-radius:50%;background:#48bb78;margin-right:4px;vertical-align:middle;}
          .distance{font-size:13px;color:#e75480;font-weight:700;}
          .loading{position:absolute;inset:0;display:flex;align-items:center;justify-content:center;z-index:999;background:rgba(253,242,248,0.8);color:#e75480;font-weight:700;}
          .popup{text-align:center;}
          .popup .avatar{font-size:26px;}
          .popup .name{font-weight:700;font-size:14px;margin:2px 0;}
          .popup .badge{display:inline-block;padding:2px 8px;border-radius:10px;font-size:11px;margin-top:4px;}
          .badge-move{background:#fff4db;color:#b7791f;}
          .badge-stop{background:#def7ec;color:#22543d;}
          .popup .sub{font-size:11px;color:#718096;margin-top:4px;}
        </style>
        </head>
        <body>
        <div id="map"></div>
        <div class="topbar">
          <div class="row">
            <div>
              <div class="nick" id="nickRow">💕 情侣地图</div>
              <div class="meta" id="metaRow">加载位置中...</div>
            </div>
            <div>
              <div class="status"><span class="dot"></span><span id="statusText">已连接</span></div>
              <div class="distance" id="distRow">—</div>
            </div>
          </div>
        </div>
        <div class="loading" id="loading">正在初始化地图 🗺️...</div>
        <script>
          var me = null, partner = null;
          try {
            if (window.__CURRENT_USER__) me = window.__CURRENT_USER__;
            else {
              var u = localStorage.getItem('user');
              if (u) me = JSON.parse(u);
            }
          } catch(e) {}
          var SUPABASE_URL = window.__SUPABASE_URL__ || (location.origin);
          var ANON_KEY = window.__SUPABASE_ANON_KEY__ || '';
          var TOKEN = window.__AUTH_TOKEN__ || localStorage.getItem('token') || ANON_KEY;
          var meMarker = null, partnerMarker = null, meAcc = null, partnerAcc = null, fitDone = false;
          var lastMeAt = 0, lastPartnerAt = 0;
          var map;

          function initMap() {
            map = L.map('map', { zoomControl: true }).setView([39.9042, 116.4074], 11);
            L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
              maxZoom: 19,
              attribution: '&copy; OpenStreetMap'
            }).addTo(map);
            setTimeout(function(){ document.getElementById('loading').style.display='none'; }, 400);
            poll();
            setInterval(poll, 5000);
          }

          function createIcon(emoji, color, pulse) {
            return L.divIcon({
              className: 'custom-marker',
              html: (pulse ?
                '<div style="position:absolute;width:48px;height:48px;border-radius:50%;background:'+color+';opacity:.3;top:-6px;left:-6px;animation:pulse 1.5s ease-out infinite;"></div>' : '') +
                '<div style="position:relative;width:36px;height:36px;border-radius:50%;background:'+color+';border:3px solid #fff;box-shadow:0 4px 12px rgba(0,0,0,0.25);display:flex;align-items:center;justify-content:center;font-size:18px;z-index:2;">' + (emoji || '👤') + '</div>' +
                '<div style="position:absolute;bottom:-4px;left:50%;transform:translateX(-50%);width:0;height:0;border-left:6px solid transparent;border-right:6px solid transparent;border-top:8px solid #fff;"></div>' +
                '<style>@keyframes pulse{0%{transform:scale(1);opacity:.4}100%{transform:scale(2);opacity:0}}</style>',
              iconSize: [36,36], iconAnchor: [18,36], popupAnchor: [0,-32]
            });
          }

          function haversine(a, b) {
            if (!a || !b) return null;
            var R = 6371000;
            var toRad = function(x){return x*Math.PI/180;};
            var dLat = toRad(b[0]-a[0]);
            var dLon = toRad(b[1]-a[1]);
            var la1 = toRad(a[0]), la2 = toRad(b[0]);
            var h = Math.sin(dLat/2)**2 + Math.cos(la1)*Math.cos(la2)*Math.sin(dLon/2)**2;
            return 2*R*Math.asin(Math.sqrt(h));
          }
          function fmtDist(m){
            if (m == null) return '—';
            if (m < 1000) return '相距 ' + Math.round(m) + ' 米';
            return '相距 ' + (m/1000).toFixed(2) + ' 公里';
          }
          function fmtTime(iso){
            if (!iso) return '';
            try {
              var d = new Date(iso);
              if (isNaN(d.getTime())) d = new Date(iso.replace(' ','T'));
              var p = function(n){return (n<10?'0':'')+n;};
              return p(d.getMonth()+1)+'-'+p(d.getDate())+' '+p(d.getHours())+':'+p(d.getMinutes());
            } catch(e){return '';}
          }

          function setOrUpdate(markerRef, lat, lng, emoji, color, pulse, row, isMe) {
            var ll = [lat, lng];
            if (!markerRef.value) {
              markerRef.value = L.marker(ll, { icon: createIcon(emoji, color, pulse) }).addTo(map);
            } else {
              markerRef.value.setLatLng(ll);
              markerRef.value.setIcon(createIcon(emoji, color, pulse));
            }
            if (isMe) meMarker = markerRef.value;
            else partnerMarker = markerRef.value;
            // 精度圈
            var accCircle = (isMe ? meAcc : partnerAcc);
            if (accCircle) { accCircle.setLatLng(ll); accCircle.setRadius(Math.max(30, row.accuracy||20)); }
            else {
              var c = L.circle(ll, { radius: Math.max(30, row.accuracy||20), color: color, fillColor: color, fillOpacity: 0.12, weight: 1 }).addTo(map);
              if (isMe) meAcc = c; else partnerAcc = c;
            }
            // popup
            var badgeClass = row.is_moving ? 'badge-move' : 'badge-stop';
            var badgeText = row.is_moving ? '🏃 移动中' : '🧎 静止';
            var bat = row.battery_level != null ? '🔋 '+row.battery_level+'%  ' : '';
            var speed = row.speed != null ? '🚶 '+row.speed.toFixed(1)+' m/s' : '';
            var html = '<div class="popup"><div class="avatar">'+(emoji||'👤')+'</div>'+
              '<div class="name">'+row.nickname+(isMe?' (我)':' (TA)')+'</div>'+
              '<span class="badge '+badgeClass+'">'+badgeText+'</span>'+
              '<div class="sub">'+bat+speed+'</div>'+
              '<div class="sub">更新于 '+fmtTime(row.timestamp)+'</div></div>';
            markerRef.value.bindPopup(html);
          }

          function fit() {
            var pts = [];
            if (meMarker) pts.push(meMarker.getLatLng());
            if (partnerMarker) pts.push(partnerMarker.getLatLng());
            if (pts.length === 0) return;
            if (pts.length === 1) map.setView(pts[0], 14, {animate:true});
            else map.fitBounds(L.latLngBounds(pts), {padding:[80,80], maxZoom:15, animate:true});
          }

          function updateTopbar() {
            var nickRow = '💕 情侣地图';
            var metaRow = '';
            if (me) {
              nickRow = '@'+(me.username||'') + ' · ' + (me.nickname||'');
              metaRow += '配对码：'+(me.coupleCode||'未绑定').toUpperCase();
            }
            document.getElementById('nickRow').textContent = nickRow;
            document.getElementById('metaRow').textContent = metaRow || '等待位置...';
            var my = meMarker ? meMarker.getLatLng() : null;
            var pt = partnerMarker ? partnerMarker.getLatLng() : null;
            var dist = null;
            if (my && pt) dist = haversine([my.lat,my.lng],[pt.lat,pt.lng]);
            document.getElementById('distRow').textContent = fmtDist(dist);
            var age = Math.max(lastMeAt, lastPartnerAt);
            var secs = age ? Math.round((Date.now()-age)/1000) : 0;
            var t = '已连接';
            if (!age) t = '等待位置...';
            else if (secs < 60) t = secs + ' 秒前更新';
            else if (secs < 3600) t = Math.floor(secs/60) + ' 分钟前更新';
            else t = Math.floor(secs/3600) + ' 小时前更新';
            document.getElementById('statusText').textContent = t;
          }

          function poll() {
            var headers = { 'apikey': ANON_KEY, 'Authorization': 'Bearer ' + (TOKEN || ANON_KEY) };
            var myLat = null, myLng = null, ptLat = null, ptLng = null;
            // 我的位置：按 user_id 过滤，按 created_at 倒序取 1 条
            var uid = me && me.id ? me.id : null;
            var reqsDone = 0;
            function done() {
              reqsDone++;
              if (reqsDone === 2) {
                if (!fitDone && (meMarker || partnerMarker)) { fit(); fitDone = true; }
                else fit();
                updateTopbar();
              }
            }
            if (uid) {
              fetch(SUPABASE_URL + '/rest/v1/locations?select=*&user_id=eq.'+encodeURIComponent(uid)+'&order=created_at.desc&limit=1', { headers: headers })
                .then(function(r){return r.json();}).then(function(rows){
                  if (Array.isArray(rows) && rows.length) {
                    var row = rows[0];
                    lastMeAt = new Date(row.timestamp || row.created_at).getTime();
                    setOrUpdate({value: meMarker}, row.latitude, row.longitude, (me&&me.avatar)||'👤', '#ff6b9d', !!row.is_moving, Object.assign({}, row, {nickname: (me&&me.nickname)||'我'}), true);
                    myLat = row.latitude; myLng = row.longitude;
                  }
                  done();
                }).catch(function(){done();});
            } else done();

            // TA 的位置：先找同一 couple_code 的 profile，当作 partner
            fetchPartnerUid(function(partnerUid){
              if (!partnerUid) { done(); return; }
              fetch(SUPABASE_URL + '/rest/v1/locations?select=*&user_id=eq.'+encodeURIComponent(partnerUid)+'&order=created_at.desc&limit=1', { headers: headers })
                .then(function(r){return r.json();}).then(function(rows){
                  if (Array.isArray(rows) && rows.length) {
                    var row = rows[0];
                    lastPartnerAt = new Date(row.timestamp || row.created_at).getTime();
                    // 找 partner 的 nickname/avatar
                    fetchNickAvatar(partnerUid, function(pa){
                      setOrUpdate({value: partnerMarker}, row.latitude, row.longitude, (pa&&pa.avatar)||'💞', '#667eea', !!row.is_moving, Object.assign({}, row, {nickname: (pa&&pa.nickname)||'TA'}), false);
                      ptLat = row.latitude; ptLng = row.longitude;
                    });
                  }
                  done();
                }).catch(function(){done();});
            });
          }

          var __partnerNickCache = null;
          function fetchNickAvatar(uid, cb) {
            if (__partnerNickCache && __partnerNickCache.id === uid) { cb(__partnerNickCache); return; }
            fetch(SUPABASE_URL + '/rest/v1/profiles?select=id,nickname,avatar,couple_code&limit=1&id=eq.'+encodeURIComponent(uid), {
              headers: { 'apikey': ANON_KEY, 'Authorization': 'Bearer ' + (TOKEN||ANON_KEY) }
            }).then(function(r){return r.json();}).then(function(rows){
              var p = (Array.isArray(rows) && rows[0]) ? rows[0] : {id:uid, nickname:'TA', avatar:'💞'};
              __partnerNickCache = p;
              cb(p);
            }).catch(function(){cb(null);});
          }

          var __partnerUid = {v:null, at:0};
          function fetchPartnerUid(cb) {
            var now = Date.now();
            if (__partnerUid.v && (now - __partnerUid.at) < 15000) { cb(__partnerUid.v); return; }
            var code = me && me.coupleCode ? me.coupleCode : null;
            var uid = me && me.id ? me.id : null;
            if (!code) { cb(null); return; }
            var headers = { 'apikey': ANON_KEY, 'Authorization': 'Bearer ' + (TOKEN||ANON_KEY) };
            // 查同一 couple_code 的其他用户
            fetch(SUPABASE_URL + '/rest/v1/profiles?select=id&couple_code=eq.'+encodeURIComponent(code.toUpperCase())+'&id=not.eq.'+encodeURIComponent(uid)+'&limit=1', { headers: headers })
              .then(function(r){return r.json();}).then(function(rows){
                var pu = (Array.isArray(rows) && rows[0]) ? rows[0].id : null;
                __partnerUid = {v:pu, at: now};
                cb(pu);
              }).catch(function(){cb(null);});
          }

          if (document.readyState === 'complete') initMap();
          else window.addEventListener('load', initMap);
        </script>
        </body>
        </html>
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
