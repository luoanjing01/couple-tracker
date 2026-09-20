# Android App 详解

包名 `com.coupletracker.android`，applicationId 同名。minSdk 26 / targetSdk 34 / compileSdk 34。

## 源码地图

```
app/src/main/java/com/coupletracker/android/
├── TrackerApp.kt                # Application 入口
├── ui/
│   ├── SplashActivity.kt        # 启动页（500ms → 登录态路由）
│   ├── LoginActivity.kt         # 登录/注册/配对/权限引导（单 Activity 多步骤）
│   ├── MainActivity.kt          # 主界面（4 Tab + 配对轮询 + WebView 地图）
│   ├── AppScreen.kt             # 应用动态 Tab
│   └── StatsScreen.kt           # 统计 Tab
├── service/
│   └── TrackerService.kt        # 前台服务（采集调度中枢）
├── location/
│   └── LocationTracker.kt       # 原生定位 + 省电策略 + 批量上报
├── appmonitor/
│   └── AppUsageMonitor.kt       # 前台 APP 监控（UsageStatsManager）
├── data/
│   ├── NetworkModule.kt         # 3 个 Retrofit + 2 个拦截器
│   ├── ApiService.kt            # AuthService / RpcService / RestService 接口 + DTO
│   ├── UserRepository.kt        # DataStore 持久化（token/用户/频率配置）
│   ├── AppSessionTracker.kt     # UI 层上报双保险 + 心情状态
│   └── model/Models.kt          # UserInfo / CoupleInfo 等业务模型
└── receiver/
    └── BootReceiver.kt          # 开机/升级自启

app/src/main/assets/www/
└── index.html                   # 离线 Leaflet 地图页（高德瓦片，5s 轮询）
```

---

## 1. TrackerApp（Application）

进程入口，`onCreate()` 依次执行：
1. `createNotificationChannels()` — 创建前台服务通知渠道（`IMPORTANCE_LOW`，不弹不响），渠道 ID 来自 `R.string.tracker_channel_id`，已存在则跳过。
2. `UserRepository.init(this)` → `NetworkModule.init()` — 先仓库后网络。
3. `runBlocking(IO) { UserRepository.get().sanitizeToken() }` — **Token 三重防御第 1 层**：启动时清洗旧版本残留的假 token（如 `rpc_auth_xxx`），防止升级后全部请求 401。

`companion object { lateinit var instance }` 提供全局 Context。

## 2. data 层

### NetworkModule（object 单例）

网络总管。`init()` 构建 3 个 Retrofit 实例：

| 实例 | baseUrl | 拦截器 | 用途 |
|------|---------|--------|------|
| `authService` (AuthService) | `/auth/v1/` | apikey only | GoTrue 登录/登出（遗留路径） |
| `restService` (RestService) | `/rest/v1/` | postgrestFilter → 日志 → apikey+JWT | 表增删改查 |
| `rpcService` (RpcService) | `/rest/v1/rpc/` | 日志 → apikey+JWT | 调用 SECURITY DEFINER 函数 |

要点：
- `supabaseHeaderInterceptor(addAuthHeader)` — 必加 `apikey` / `Content-Type` / `Accept`；**仅当 `isValidJwt(token)` 为真才加 `Authorization`**（三重防御第 2 层）。
- `postgrestFilterInterceptor` — 仅挂在 restClient：对白名单列名（`id/username/couple_code/code/user_a/user_b/user_id/couple_id/package_name/created_at`）的裸值过滤参数自动补 `eq.` 前缀；已带操作符（eq./gt./in.…）或 `and(...)/or(...)` 逻辑语法的原样放行（幂等）。
- restRetrofit 使用 `GsonBuilder().serializeNulls()` — PATCH 需要显式发 `{"partner_id":null}` 清空字段。
- 状态流：`lastLocationReportStatus` / `lastAppReportStatus`（MutableStateFlow + 只读 Flow），UI「我的」页展示最近上报结果。

### ApiService.kt — 接口与 DTO

**AuthService**（遗留，登录实际走 RPC）：
- `signIn(SignInBody)` → `POST token?grant_type=password`
- `logout()` → `POST logout`

**RpcService**（核心认证与配对，参数一律 `p_` 前缀）：

| 方法 | 路径 | 功能 |
|------|------|------|
| `registerUser(RegisterUserReq)` | `register_user` | 注册（直接插 auth.users，见 [[Backend-and-Database]]） |
| `verifyLogin(VerifyLoginReq)` | `verify_login` | 登录（crypt 校验密码） |
| `pairByCode(PairByCodeReq)` | `pair_by_code` | 发起配对（输入对方配对码） |
| `checkPairStatus(CheckPairStatusReq)` | `check_pair_status` | 轮询配对状态 |
| `acceptPair(AcceptPairReq)` | `accept_pair` | 接受配对 |
| `unpair(UnpairReq)` | `unpair` | 单方取消配对（双方都解除） |
| `rejectPair(RejectPairReq)` | `reject_pair` | 拒绝配对请求 |

**RestService**（PostgREST 表操作）：
- profiles：`getProfile(select, id, username, couple_code)` / `updateProfile(id, Map<String, Any?>)`
- couples：`createCouple` / `getCouple(code, user_a)` / `updateCouple`
- locations：`reportLocation(LocationInsert)` / `reportLocationsBatch(List<LocationInsert>)`（批量，PostgREST 数组插入）/ `getCoupleLocations(couple_id, order, limit)` / `getUserLocations(user_id, order, limit)`
- app_usage：`reportAppUsage(AppUsageInsert)` / `getAppUsage(user_id, …)` / `getAppUsageInRange(user_id, created_at=and(gte.x,lt.y), …)`

**DTO 设计**：查询用 `LocationRow` / `AppUsageRow`（含 id/created_at）；上报用 `LocationInsert` / `AppUsageInsert`（**不含服务端生成字段**）。原因：serializeNulls 会把 `"id":null` 显式发出，导致 PostgreSQL 默认值 `gen_random_uuid()` 失效 → 23502 主键冲突。

### UserRepository（单例，DataStore）

存储文件 `couple_tracker_prefs`，键：

| Key | 类型 | 说明 |
|-----|------|------|
| `auth_token` | String? | JWT（可为空，登录态以 user 为准） |
| `user_info_json` | String? | UserInfo 的 Gson JSON |
| `server_api_base` / `server_web_base` | String | 可动态改后端地址，默认 BuildConfig |
| `loc_interval_sec` | Int | 位置采集间隔，默认 8s，约束 [3, 60] |
| `app_interval_sec` | Int | APP 采集间隔，默认 4s，约束 [2, 30] |

关键函数：
- `isValidJwt(tok)` — 正则 `^[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+$` 三段式校验。
- `sanitizeToken()` — 非 JWT 即清除（三重防御第 3 层在 `isLoggedIn()` 内调用）。
- `isLoggedIn()` = sanitizeToken() 后 `getUser() != null`。
- `logout()` — 清空整个 DataStore + WebView 缓存/历史/表单 + `cacheDir.deleteRecursively()`。
- 频率读写均带 `coerceIn` 范围约束。

### AppSessionTracker（object 单例，UI 层双保险）

解决两个 Compose 痛点：Tab 切换 `remember` 状态丢失（用 object 持有状态）；后台服务可能被国产 ROM 杀掉（UI 层也直接上报）。
- `mood: StateFlow<String>` — 心情 emoji。
- `setCurrentApp(pkg, name)` — APP 切换时补报前一个 APP（≥10 秒才报），**不做定时上报**（避免与 AppUsageMonitor 双报导致时长翻倍）。
- `uploadUsage(pkg, name, seconds)` — 噪音过滤 → 同包 5 秒去重（`ConcurrentHashMap lastUploadedAt`）→ POST app_usage。
- 内置 `isSystemNoisePkg` 与 `categorizePkg`（包名关键词分类）。

### model/Models.kt

`UserInfo`（含 `displayName` = nickname 兜底 username；`color` = 女性 #FF6B9D / 其他 #4C9AFF）、`CoupleInfo(user, partner)`、`LoginRequest`、`RegisterRequest`、`PairRequest`、`LocationRequest`、`ForegroundAppRequest`。

## 3. UI 层

### SplashActivity
启动屏 500ms → `UserRepository.isLoggedIn()`：已登录 → `TrackerService.start()` + MainActivity；否则 → LoginActivity。

### LoginActivity（单 Activity 多步骤）
`private enum class Step { LOGIN, PAIR, PERMS, DONE }`，按步切换 Composable：
- **LoginCard** → `doAuth()`：注册走 `rpcService.registerUser`，随后统一 `verifyLogin`，解析 profile（兼容旧版 f1~f8 编号字段与新版命名字段），写入 UserRepository。
- **PairCard** → 展示/复制自己的配对码；输入对方码调 `pairByCode`；每 3 秒轮询 `checkPairStatus`，收到请求弹窗 → `acceptPair`；可跳过。
- **PermCard** → 引导授权：定位 → 后台定位 → 使用情况访问（跳系统设置 `ACTION_USAGE_ACCESS_SETTINGS`）→ 通知权限；完成后 `TrackerService.start()`。
- **DONE** → 跳 MainActivity。

### MainActivity（4 Tab）
底部导航：**地图 / 应用 / 统计 / 我的**。
- **地图 Tab**：WebView 加载 `file:///android_asset/www/index.html`。`buildInjectionJs()` 注入 `window.__SUPABASE_URL__` / `__SUPABASE_ANON_KEY__` / `__AUTH_TOKEN__` / `__CURRENT_USER__`（同时写 localStorage），并调用页面端 `__applyAndroidInjection()`；onPageStarted/onPageFinished/Tab 切回时重复注入并 `map.invalidateSize()`（修复 WebView 尺寸为 0 不渲染的坑）。
- **内建配对轮询**：每 5 秒 `checkPairStatus`，处理 `paired` / `incoming_request`（接受 → `acceptPair`，拒绝 → 清对方 `pending_pair`）。
- **我的 Tab**：采集频率滑杆（写 UserRepository）、最近上报状态（订阅 NetworkModule 状态流）、取消配对（`unpair`）、退出登录（`logout()`）。

### AppScreen（应用动态 Tab）
- 可切换查看「我 / TA」（按 myCode/myId 查对方 profile 得 subjectId）。
- 自己：每 3 秒读本机前台 APP（AppUsageMonitor 状态流）；对方：每 15 秒查 `app_usage` 表最新记录。
- 展示当前正在使用 + 历史打开记录。

### StatsScreen（统计 Tab）
- 支持日期切换（dayOffset）、我/TA 切换、下拉刷新。
- 数据：`getAppUsageInRange` 按 `created_at=and(gte.起始,lt.结束)` 拉取后客户端按日过滤。
- 聚合：24 小时柱状图（按小时）、总时长、APP 排行与占比。

## 4. 采集层

### TrackerService（前台服务，调度中枢）
- `foregroundServiceType="location|dataSync|specialUse"`，通知 ID 10086，文案如「💕 已登录 · 昵称」。
- 协程架构：`serviceScope = SupervisorJob + Dispatchers.Default`；所有可能抛异常处一律 `runCatching` 包裹（缺权限/网络差/SDK 报错均静默降级，绝不崩进程）。
- 核心：`flow.combine(userFlow, locationIntervalSecFlow, appIntervalSecFlow).collect` — 任意配置变化即取消旧 Job、按新频率重启采集器；未登录 → `stopSelf()`。
- `onStartCommand`：`ACTION_STOP` → 停止；权限后补授予时补调 `startForeground()`；兜底硬启动 AppUsageMonitor（4s）。返回 `START_STICKY`。
- 电量监控协程：每 60 秒读电量（BatteryManager 优先，粘性广播兜底）→ `LocationTracker.setBatteryCache()`，随位置上报顺带发出。
- companion：`canStartForeground(ctx)`（Android 13+ 需 POST_NOTIFICATIONS；14+ location 型 FGS 需已有定位权限）、`start(ctx)`（8.0+ 用 startForegroundService）、`stop(ctx)`。

### LocationTracker（定位追踪器）
**为什么用原生 LocationManager**：FusedLocationProvider 依赖 GMS，国产机（iQOO/VIVO/OPPO/华为/小米）注册成功但回调永不触发。

- **三 Provider**：GPS_PROVIDER + NETWORK_PROVIDER + PASSIVE_PROVIDER（被动定位，零耗电复用其他 App 结果），共用一个 LocationListener。
- **动态调频**（对标 Life360）：连续 3 次位移 <30m 且速度 <0.3m/s → 判静止，系统定位间隔拉到 5 分钟；单次位移 >50m 或速度 >0.5m/s → 立即恢复用户设定频率。改间隔需 `removeUpdates` 后重新 `requestLocationUpdates`（`registerProviders`）。
- **批量上报**：位置点先入 `CopyOnWriteArrayList pendingBatch`，攒满 5 条或距上次 ≥3 分钟 → `reportLocationsBatch` 一次性 POST；失败数据加回缓存重试；缓存上限 50 条防爆内存；`stop()` 时用 GlobalScope 兜底补传。
- **精度过滤**：accuracy >200m 丢弃；30 秒内已有更准位置且新精度差 2 倍以上 → 丢弃。
- **启动优化**：`pickBestLastKnown()` 读三 provider 缓存位置，<2 分钟内的最新一条强制立即上报（地图秒开）。
- 节流：2 秒内位移 <3m 跳过。

### AppUsageMonitor（APP 使用监控）
- 基于 `UsageStatsManager.queryEvents`（60 秒窗口）取最新 `MOVE_TO_FOREGROUND` 事件判定前台包名。
- 轮询间隔由服务传入（默认 4s）；**每 15 秒**上报一次当前 APP 增量时长；APP 切换时补报前一个（≥10 秒）。
- 熄屏暂停计时（`PowerManager.isInteractive`），亮屏后把 sessionStartAt 顺延扣除熄屏时长。
- `hasUsagePermission()` 用 AppOpsManager 检查「使用情况访问」；`createUsageSettingsIntent()` 引导授权。
- `getAppMeta(pkg)`：PackageManager 取名称 + 系统 category 映射中文分类（游戏/社交/视频/音乐…），未标分类按包名关键词兜底。
- 噪音过滤 `isSystemNoisePkg`：桌面/输入法/系统 UI/安装器/权限弹窗一律不上报。
- 状态流：`currentApp`、`currentSessionSeconds` 供 UI 实时显示。

### BootReceiver
监听 `BOOT_COMPLETED` / `LOCKED_BOOT_COMPLETED` / `MY_PACKAGE_REPLACED`，IO 协程中确认已登录后拉起 TrackerService（onReceive 在主线程，不能做磁盘 IO）。

## 5. 地图页 index.html（assets/www）

- 纯离线 Leaflet 1.9.4；等待安卓注入 `__CURRENT_USER__` 等变量后初始化。
- **瓦片源（均为高德，GCJ-02）**：
  - 街道图 `https://webrd0{s}.is.autonavi.com/appmaptile?lang=zh_cn&size=1&scale=1&style=8&x={x}&y={y}&z={z}`，sub `1234`
  - 卫星图 `https://webst0{s}.is.autonavi.com/appmaptile?style=6&x={x}&y={y}&z={z}`
  - 连续 5 张瓦片加载失败自动切换备用图源（`loadTileLayer` 全局函数）。
- 坐标系：WGS-84（GPS）→ GCJ-02 转换（`wgs2gcj`），标记与精度圆按当前图源坐标系放置。
- `poll()`：每 5 秒并发查 `locations` 表拉「我」和「TA」最新位置；`fetchPartnerUid()` 查 `profiles` 表得配对方 id；`fit()` 首次有标记自动居中。
- 内置调试面板与容器尺寸为 0 的自愈检测。

## 6. AndroidManifest 要点

权限：`INTERNET`、`ACCESS_NETWORK_STATE`、`ACCESS_FINE/COARSE/BACKGROUND_LOCATION`、`ACTIVITY_RECOGNITION`、`NEARBY_WIFI_DEVICES`、`FOREGROUND_SERVICE`(+`_LOCATION`/`_DATA_SYNC`/`_SPECIAL_USE`)、`WAKE_LOCK`、`POST_NOTIFICATIONS`、`PACKAGE_USAGE_STATS`、`QUERY_ALL_PACKAGES`、`RECEIVE_BOOT_COMPLETED`。

组件：`TrackerApp`（application name）、`SplashActivity`（LAUNCHER）、`LoginActivity`、`MainActivity`、`TrackerService`（foregroundServiceType + SPECIAL_USE_FGS_SUBTYPE property）、`BootReceiver`（三种开机/升级 action）。

## 7. 构建配置（app/build.gradle.kts）

- `versionCode 182 / versionName "0.182"`（CI 每次自动 +1 / +0.001 并回写）。
- BuildConfig 注入：`SUPABASE_URL`、`SUPABASE_ANON_KEY`、`DEFAULT_API_BASE`（…/rest/v1）、`DEFAULT_WEB_BASE`。
- 依赖：`core-ktx 1.12.0`、`lifecycle-runtime-ktx 2.7.0`、`activity-compose 1.8.2`、`compose-bom 2024.02.00`（ui/material/material3/foundation）、`okhttp 4.12.0`(+logging)、`retrofit 2.9.0`(+converter-gson)、`socket.io-client 2.1.0`、`kotlinx-coroutines-android 1.7.3`、`datastore-preferences 1.0.0`、`coil-compose 2.5.0`、`gson 2.10.1`。
- release 未启用 minify；settings.gradle.kts 配置阿里云镜像加速依赖下载。
