# 整体架构

## 系统架构图

```
┌─────────────────────────────┐         ┌─────────────────────────────┐
│        手机 A（我）          │         │        手机 B（TA）          │
│  ┌───────────────────────┐  │         │  ┌───────────────────────┐  │
│  │ SplashActivity         │  │         │  │ （同一套 APK）          │  │
│  │ LoginActivity          │  │         │  └───────────────────────┘  │
│  │ MainActivity (4 Tab)   │  │         └──────────────┬──────────────┘
│  │  ├─ 地图: WebView+Leaflet│ │                        │
│  │  ├─ 应用: AppScreen     │  │                        │ HTTPS
│  │  ├─ 统计: StatsScreen   │  │                        ▼
│  │  └─ 我的: 设置/配对      │  │         ┌─────────────────────────────┐
│  ├───────────────────────┤  │         │         Supabase 云          │
│  │ TrackerService (前台服务)│ │         │  ┌─────────────────────────┐ │
│  │  ├─ LocationTracker    │──┼────────▶│  │ PostgREST /rest/v1/*     │ │
│  │  └─ AppUsageMonitor    │──┼────────▶│  │ RPC      /rest/v1/rpc/*  │ │
│  ├───────────────────────┤  │         │  │ GoTrue   /auth/v1/* (旁路)│ │
│  │ NetworkModule (Retrofit)│ │         │  ├─ PostgreSQL              │ │
│  │ UserRepository(DataStore)│ │         │  │  profiles / couples      │ │
│  └───────────────────────┘  │         │  │  locations / app_usage   │ │
└─────────────────────────────┘         │  │  7 个 RPC 函数            │ │
                                        │  └─────────────────────────┘ │
                                        └─────────────────────────────┘
```

## 核心架构决策

**纯 BaaS，零自建后端。** App 通过 HTTPS 直连 Supabase：
- 注册/登录/配对 → 自定义 **RPC 函数**（SECURITY DEFINER，绕过 GoTrue，永不发邮件、永不限流 429）
- 位置/APP 使用上报与查询 → **PostgREST REST API**（直接 INSERT/SELECT 表）
- RLS 全部关闭，anon key 即可读写；客户端做 token 合法性防御（见 [[Design-Decisions]]）

## 模块划分与职责

| 模块 | 路径 | 状态 | 职责 |
|------|------|------|------|
| android-app | `android-app/` | ✅ 活跃 | 唯一在维护的客户端 |
| 数据库脚本 | `supabase_*.sql` | ✅ 活跃 | 建表 + 历次修复 + RPC 函数定义 |
| CI | `.github/workflows/`, `.circleci/` | ✅ 活跃 | 自动构建 APK |
| backend | `backend/` | ❌ 弃用 | 早期 Express + Socket.IO + JSON 文件库，曾用 render.yaml 部署 Render |
| frontend | `frontend/` | ❌ 弃用 | 早期 React + Vite Web 版（LoginPage/MapPage/StatsPage/AppsPage） |
| mobile-simulator | `mobile-simulator/` | ❌ 弃用 | Node 脚本模拟两台手机上报，用于联调旧后端 |
| ios-app | `ios-app/` | ⚠️ 停滞 | Swift 雏形（MapViewController 等），未维护 |

## 技术栈

| 层 | 技术 | 版本 |
|----|------|------|
| 语言 | Kotlin | 1.9.22 |
| UI | Jetpack Compose + Material3 | BOM 2024.02.00 |
| 构建 | AGP / Gradle | 8.2.2 / Wrapper |
| 网络 | Retrofit + OkHttp + Gson | 2.9.0 / 4.12.0 / 2.10.1 |
| 持久化 | DataStore Preferences | 1.0.0 |
| 异步 | kotlinx-coroutines | 1.7.3 |
| 图片 | Coil | 2.5.0 |
| 地图 | WebView + 离线 Leaflet 1.9.4 + 高德瓦片（GCJ-02） | assets/www/index.html |
| 定位 | 原生 LocationManager（GPS + NETWORK + PASSIVE） | 不用 GMS FusedLocationProvider |
| CI | GitHub Actions（主）+ CircleCI（备） | JDK 21 / SDK 34 |

## 依赖关系（模块内）

```
TrackerApp (Application)
  ├─ init → UserRepository (DataStore 单例)
  ├─ init → NetworkModule (3×Retrofit 单例)
  └─ 创建通知渠道

SplashActivity → UserRepository.isLoggedIn() → MainActivity / LoginActivity
LoginActivity  → RpcService (register_user/verify_login/pair_by_code/...) → 完成后启动 TrackerService
MainActivity   → WebView(index.html) + AppScreen + StatsScreen + 配对轮询 → RpcService/RestService
TrackerService → LocationTracker ─┐
               → AppUsageMonitor ─┼→ NetworkModule.restService → Supabase
BootReceiver   → UserRepository → TrackerService.start()
AppSessionTracker (UI 层单例) → RestService（和后台服务互为上报双保险）
```

详细类/函数说明见 [[Android-App]]。
