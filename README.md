# CoupleTracker · 情侣报备 APP 💕

> 一款专为情侣设计的安卓应用：自动报备位置、APP 使用情况、地图实时查看双方位置。
> 所有代码托管在 GitHub，任何设备克隆即可开发。

---

## 📍 快速开始（新设备开发指南）

### 1. 克隆项目

```bash
git clone https://github.com/luoanjing01/couple-tracker.git
cd couple-tracker/android-app
```

### 2. 环境要求

| 组件 | 版本 | 说明 |
|------|------|------|
| JDK | **21** | GitHub Actions CI 也是用 Temurin 21 |
| Android Studio | Hedgehog (2023.1.1+) 或更新 | Gradle 插件 8.2.2 要求 |
| Android SDK | compileSdk 34 / build-tools 34.0.0 | 最低 minSdk 26 (Android 8.0) |
| Kotlin | 1.9.22 | Compose Compiler 1.5.8 |

### 3. 本地构建 Debug APK

```bash
cd android-app
./gradlew :app:assembleDebug        # Linux/Mac
./gradlew.bat :app:assembleDebug    # Windows
```

产物位置：`android-app/app/build/outputs/apk/debug/app-debug.apk`

### 4. 云端自动构建（推荐）

push 到 `main` 分支会自动触发 GitHub Actions → [build-apk.yml](.github/workflows/build-apk.yml)
- 运行环境：Ubuntu Latest + JDK 21
- 构建时间：~2 分 30 秒
- APK 下载地址：`https://github.com/luoanjing01/couple-tracker/actions` → 最新 Run → Artifacts

---

## 🏗️ 项目结构

```
couple-tracker/
├── android-app/                          # ← 主要开发目录（安卓应用）
│   ├── app/
│   │   ├── src/main/
│   │   │   ├── AndroidManifest.xml       # 权限、Activity、Service 声明
│   │   │   ├── assets/www/
│   │   │   │   ├── index.html            # 地图 WebView（Leaflet 1.9.4 + 高德瓦片）
│   │   │   │   ├── leaflet.min.js        # Leaflet 地图库（离线）
│   │   │   │   └── leaflet.min.css
│   │   │   ├── java/com/coupletracker/android/
│   │   │   │   ├── TrackerApp.kt                 # Application 入口（清洗脏token）
│   │   │   │   ├── ui/
│   │   │   │   │   ├── SplashActivity.kt         # 启动页（判定登录态→跳登录或主页）
│   │   │   │   │   ├── LoginActivity.kt          # 登录/注册/配对/权限引导（单页多步骤）
│   │   │   │   │   └── MainActivity.kt           # 主界面（4 Tab：地图/应用/统计/我的）
│   │   │   │   ├── service/
│   │   │   │   │   └── TrackerService.kt         # 前台服务（常驻后台，采集+上报）
│   │   │   │   ├── location/
│   │   │   │   │   └── LocationTracker.kt        # 原生 LocationManager（GPS+NETWORK 双监听）
│   │   │   │   ├── appmonitor/
│   │   │   │   │   └── AppUsageMonitor.kt        # UsageStatsManager 前台APP检测
│   │   │   │   ├── data/
│   │   │   │   │   ├── NetworkModule.kt          # 3 个 Retrofit（authService/restService/rpcService）
│   │   │   │   │   ├── ApiService.kt             # Retrofit 接口定义 + 所有 data class
│   │   │   │   │   ├── UserRepository.kt         # DataStore 持久化（token/user/频率配置）
│   │   │   │   │   └── model/Models.kt           # 其他模型（登录请求等）
│   │   │   │   └── receiver/
│   │   │   │       └── BootReceiver.kt           # 开机自启 + 升级安装自动重启服务
│   │   │   └── res/                               # 标准资源（主题/颜色/字符串/图标）
│   │   └── build.gradle.kts                       # 应用级 Gradle（Compose/Material3/Supabase配置）
│   ├── build.gradle.kts                          # 根 Gradle（插件版本号）
│   ├── settings.gradle.kts
│   ├── gradle.properties
│   └── gradlew / gradlew.bat                      # Gradle Wrapper
│
├── .github/workflows/build-apk.yml               # CI 配置（push main 自动构建 APK）
│
├── supabase_schema.sql                            # 初始建表 SQL（参考用）
├── supabase_fix_v3.sql                            # ⚠️ 实际线上使用的 register_user / verify_login / pair_by_code 函数
├── supabase_cleanup_test_data.sql                 # 测试数据清理脚本
│
├── backend/                                       # 旧 Node.js 后端（已弃用，当前完全走 Supabase）
├── frontend/                                      # 旧 Web 前端（已弃用）
└── mobile-simulator/                              # 旧手机模拟器（已弃用）
```

---

## 🔧 后端：Supabase BaaS

项目**完全不使用自建后端**，所有数据直接通过 Supabase REST API 读写。

### Supabase 项目信息

| 项 | 值 |
|----|----|
| Dashboard | https://supabase.com/dashboard/project/gvytqbgangyjjurekyid |
| API URL | `https://gvytqbgangyjjurekyid.supabase.co` |
| Anon Key | `sb_publishable_TmlnyTou7Z7JGt3vNP3TTw_3-KkCiCM` |
| 数据库 | PostgreSQL（RLS **已关闭**，anon key 直接读写所有表） |

### 核心表结构

```sql
-- ① profiles（用户资料，配合 Supabase Auth）
profiles (
    id uuid PK → auth.users(id),
    username text UNIQUE,
    nickname text, avatar text, gender text,
    couple_code text UNIQUE,        -- 6位配对码（注册时自动生成）
    couple_id uuid → couples(id),   -- 配对后关联
    created_at timestamptz
)

-- ② couples（情侣组）
couples (
    id uuid PK, code text UNIQUE,
    user_a uuid → profiles(id),
    user_b uuid → profiles(id) NULL
)

-- ③ locations（位置上报，**couple_id 已改为可空**，未配对用户传 null 避 FK）
locations (
    id uuid PK,
    user_id uuid NOT NULL → profiles(id),
    couple_id uuid NULL,            -- ⚠️ 可空！已删除 FK 约束
    latitude double, longitude double,
    accuracy double, speed double,
    battery_level int, is_moving bool,
    created_at timestamptz
)

-- ④ app_usage（APP 使用时长，同样 couple_id 可空）
app_usage (
    id uuid PK,
    user_id uuid NOT NULL → profiles(id),
    couple_id uuid NULL,            -- ⚠️ 可空
    package_name text, app_name text, category text,
    usage_seconds int,
    window_start timestamptz,
    created_at timestamptz
)
```

### RPC 函数（SECURITY DEFINER，**不走 GoTrue，永不发邮件**）

所有函数参数名都带 `p_` 前缀，对应 Retrofit `@SerializedName("p_xxx")`：

| 函数 | 路径 | 功能 |
|------|------|------|
| `register_user(p_username, p_password, p_nickname, p_gender)` | `/rest/v1/rpc/register_user` | 注册：直接 INSERT auth.users + profiles，**永不触发 429** |
| `verify_login(p_username, p_password)` | `/rest/v1/rpc/verify_login` | 登录：`crypt()` 验证密码 → 返回 user_id + profile JSON |
| `pair_by_code(p_my_id, p_their_code)` | `/rest/v1/rpc/pair_by_code` | 配对：把两人 couple_code 改成同一个，返回对方昵称 |

---

## 🎨 技术栈 & 设计决策

### Android 原生

| 模块 | 方案 | 为什么 |
|------|------|--------|
| UI | Jetpack Compose + Material3 | 现代 Android 开发标准，比传统 XML 快 |
| 地图 | **WebView + 本地 Leaflet HTML** | 免 SDK，国内 CDN 可替换瓦片图源（高德） |
| 定位 | **原生 LocationManager**（GPS + NETWORK 双监听） | **FusedLocationProvider 在无 GMS 的国产手机（iQOO/VIVO/OPPO）上不触发回调** |
| 前台服务 | Foreground Service（location\|dataSync\|specialUse） | 后台持续采集，Android 14+ 必须声明 permission |
| 开机自启 | BOOT_COMPLETED + MY_PACKAGE_REPLACED | 升级安装后自动重启服务 |
| 持久化 | DataStore Preferences | 比 SharedPreferences 更现代、类型安全、协程友好 |
| 网络 | OkHttp + Retrofit + Gson | 标准 Android BaaS 方案，拦截器自动加 Supabase headers |

### 网络层架构（3 个 Retrofit 实例）

```
┌─────────────────────────────────────────────────────────────┐
│ NetworkModule.init()                                        │
│                                                             │
│  authClient  → Supabase /auth/v1/    （拦截器 addAuthHeader=false） │
│  restClient  → Supabase /rest/v1/    （拦截器 addAuthHeader=true）  │
│  rpcClient   → Supabase /rest/v1/rpc/ （拦截器 addAuthHeader=true） │
│                                                             │
│  拦截器统一加：apikey + Content-Type + （合法 JWT 才加 Authorization） │
└─────────────────────────────────────────────────────────────┘
```

### Token 安全策略（**三重防御**）

```
RLS 全放开 → anon key 就能读写所有表 → 根本不需要 JWT token！

防御1: TrackerApp.onCreate → 启动时扫描 DataStore，非 JWT 格式一律清掉
防御2: NetworkModule 拦截器 → 只有 isValidJwt(token) 才加 Authorization 头
防御3: UserRepository.isLoggedIn() → 发现脏 token 自动清洗，清洗后为 null 则视为未登录
```

> 为什么需要三重？因为旧版本存过假 token `"rpc_auth_xxx"`，升级安装时 DataStore 不会清空。只改 LoginActivity 不够——LoginActivity 可能没被执行过（用户已经"登录"了，被 SplashActivity 跳过登录页）。

### 地图 WebView 架构

```
MainActivity
  └─ WebView (离线 Leaflet index.html)
       ├─ 注入 JS: window.__SUPABASE_URL__, __AUTH_TOKEN__, __CURRENT_USER__
       ├─ onPageStarted / onPageFinished / Tab切回 → 重新注入 + invalidateSize()
       └─ 前端每 5 秒轮询 Supabase /rest/v1/locations（当前用户 + 配对用户的 couple_code）
            ├─ 高德矢量瓦片（国内可访问）
            └─ makeHeaders() 统一构造 headers（apikey + 有 JWT 才加 Authorization）
```

### 频率配置（可在"我的"页动态调整）

| 配置 | 默认值 | 范围 | 持久化 |
|------|--------|------|--------|
| 位置采集间隔 | 8 秒 | 3 - 60 秒 | DataStore `loc_interval_sec` |
| APP 采集间隔 | 4 秒 | 2 - 30 秒 | DataStore `app_interval_sec` |

TrackerService 用 `flow.combine` 监听两个 Flow 的变化 → 任意项改变时取消旧 Job 并重启 tracker。

---

## 📱 Activity / Service 启动流程

```
SplashActivity (500ms)
  │
  ├── UserRepository.isLoggedIn()
  │     ├─ sanitizeToken() ← 防御3：自动清洗脏 token
  │     └─ getToken() != null && getUser() != null ?
  │
  ├─ YES → TrackerService.start() + MainActivity（4 Tab 主页）
  │
  └─ NO  → LoginActivity（多步骤：登录/注册 → 配对 → 权限 → 完成）
              │
              └─ 完成后 → TrackerService.start() + MainActivity
```

### TrackerService（前台服务）

```
┌──────────────────────────────────────────────────┐
│  前台通知："💕 正在实时报备给TA"                     │
│                                                    │
│  ┌────────────────────────────────────┐            │
│  │  LocationTracker                   │            │
│  │  · 8 秒一次（可配置）               │            │
│  │  · GPS + NETWORK 双监听            │            │
│  │  · 强制上报机制                    │            │
│  │  · 失败 → 存 lastLocationReportStatus            │
│  └─────────────────┬──────────────────┘            │
│                    │ 上报 locations 表              │
│  ┌─────────────────▼──────────────────┐            │
│  │  AppUsageMonitor                  │            │
│  │  · 4 秒一次（可配置）               │            │
│  │  · UsageStatsManager.queryEvents  │            │
│  │  · 切换前台 APP → 立刻上报         │            │
│  └─────────────────┬──────────────────┘            │
│                    │ 上报 app_usage 表             │
└──────────────────────────────────────────────────┘
```

---

## 🧭 已知坑 & 经验教训

| # | 问题 | 原因 | 解决方案 |
|---|------|------|----------|
| 1 | 国产手机无 GMS 定位不触发 | FusedLocationProvider 依赖 Google Play Services | 改用原生 LocationManager |
| 2 | HTTP 401 PGRST301 | 旧版本假 token 残留 DataStore，NetworkModule 拦截器加无效 Authorization 头 | **三重防御**：启动清洗 + 拦截器校验 + 登录态清洗 |
| 3 | 配对码不存在 | Supabase PostgREST schema cache 保留旧函数签名 | 修改函数后必须 `NOTIFY pgrst, 'reload schema'` |
| 4 | Supabase 注册 429 | GoTrue /signup 触发邮件发送，免费套餐限流 | 彻底绕过 GoTrue → RPC register_user 直接 INSERT |
| 5 | 未配对用户 409 FK 冲突 | locations.app_usage 有 couples 表外键约束 | 删除 FK，couple_id 改为可空 |
| 6 | 地图显示"等待位置" | WebView 首次加载时容器尺寸为 0，Leaflet 无法渲染 | `map.invalidateSize()` + 尺寸为 0 死循环检测 |
| 7 | Icons.Extended 编不过 | 新版 material-icons-extended 解析错误 | 全部替换为纯 Emoji Text Composable |
| 8 | 构建假成功 | Gradle 命令后加 `\| tail -80` 吞掉了真实错误码 | CI 加 `set -euo pipefail` |
| 9 | Android 14 定位服务闪退 | 启动带 location 的 FGS 必须已有定位权限 + POST_NOTIFICATIONS | FGS 启动逻辑全 try-catch + nullable |

---

## 🚧 待完成功能 / Todo

- [ ] **统计 Tab 页面** — 日 APP 使用时长排行、位置轨迹回放、每日统计卡片
- [ ] **情侣头像/昵称更新** — 配对方的昵称/头像实时同步
- [ ] **消息/紧急提醒** — 一键 SOS 通知对方位置
- [ ] **地图双头像显示** — 区分"自己"和"TA"的不同图标/颜色
- [ ] **轨迹回放** — 按日期查看当天移动路线
- [ ] **省电模式** — 可配置关闭后台定位，仅前台定位
- [ ] **Release 签名** — 正式打包 keystore + ProGuard
- [ ] **加密隐私** — couples 表真正启用 RLS（目前全放开）

---

## 📦 如何在新设备继续开发

```bash
# 1. 克隆
git clone https://github.com/luoanjing01/couple-tracker.git
cd couple-tracker

# 2. 打开 Android Studio → File → Open → 选 android-app 目录
#    等待 Gradle Sync 完成（首次下载依赖几分钟）

# 3. 连接安卓手机（USB 调试开启）或启动模拟器

# 4. 点 Run 按钮 → 自动编译 + 安装到手机

# 5. 改完代码 → Run → 自动 push main → GitHub Actions 重新构建
#    新 APK 在 Actions Run 的 Artifacts 里下载
```

---

## 📋 Git 提交历史（关键版本）

| Commit | 说明 |
|--------|------|
| `2a5e915` | fix(auth): 三重防御彻底解决 HTTP 401 PGRST301 |
| `cde6d3a` | fix(auth): 移除 LoginActivity 假 token |
| `0f35398` | LocationManager 替换 FusedLocationProvider（国产机定位从死到活） |
| `9b6a335` | 换高德瓦片 + 重写 pair_by_code + couple_id 可空 |

---

## 🔑 敏感信息安全提醒

- Supabase anon key 是**公开的发布密钥**，可以硬编码在客户端（它本来就叫 `publishable`）
- Supabase **service_role key** 永远不能进客户端代码或 git（已被 .gitignore 保护）
- 当前 RLS 全放开，上线前必须加回行级安全策略（couples 表：只有 pair 的两人能看）
- 生产版本建议：注册仍走 RPC，但存储一个最小权限的 JWT（anon role），RLS 按 couple_id 过滤
