# CoupleTracker · Code Wiki

> 情侣报备 / 情侣追踪 APP：自动上报位置与 APP 使用情况，地图实时查看双方动态。
> 本 Wiki 面向开发者，系统介绍仓库架构、模块职责、关键类与函数、依赖关系与运行方式。

## 项目一句话

安卓原生 App（Kotlin + Jetpack Compose）直连 Supabase BaaS（PostgreSQL + PostgREST + 自定义 RPC），**无自建后端**；前台服务常驻采集位置与 APP 使用数据，WebView 内嵌离线 Leaflet 地图（高德瓦片）展示双方实时位置。

## Supabase 项目

| 项 | 值 |
|----|----|
| Dashboard | https://supabase.com/dashboard/project/gvytqbgangyjjurekyid |
| API URL | `https://gvytqbgangyjjurekyid.supabase.co` |
| Anon Key | `sb_publishable_TmlnyTou7Z7JGt3vNP3TTw_3-KkCiCM`（公开发布密钥，允许硬编码） |
| RLS | 已关闭（anon key 可读写全部业务表） |

## Wiki 导航

| 页面 | 内容 |
|------|------|
| [[Architecture]] | 整体架构、模块划分、技术选型与依赖关系 |
| [[Android-App]] | 安卓端详解：Application / UI / Service / 采集器 / 数据层全部关键类与函数 |
| [[Backend-and-Database]] | Supabase 数据库表结构、7 个 RPC 函数、SQL 脚本演进史 |
| [[Data-Flow]] | 核心业务流程：启动、注册登录、配对状态机、位置上报、APP 使用统计 |
| [[Build-and-Run]] | 环境要求、本地构建、GitHub Actions / CircleCI 云端构建 |
| [[Design-Decisions]] | 关键技术决策、已知坑与经验教训、Token 三重防御 |

## 仓库速览

```
couple-tracker/
├── android-app/            # ✅ 唯一活跃模块：安卓 App（Kotlin + Compose）
├── .github/workflows/      # GitHub Actions：push main 自动构建 APK + Release
├── .circleci/              # CircleCI 备用构建配置
├── supabase_*.sql          # 数据库脚本（schema + 历次修复，详见 Backend-and-Database）
├── test_rpc*.js / test_supabase*.ps1  # RPC 联调测试脚本
├── backend/                # ❌ 已弃用：早期 Express + Socket.IO 自建后端
├── frontend/               # ❌ 已弃用：早期 React + Vite Web 版
├── mobile-simulator/       # ❌ 已弃用：双手机模拟上报脚本
├── ios-app/                # ⚠️ 停滞：iOS Swift 雏形，未维护
└── README.md / PROJECT_STRUCTURE.md
```

## 5 分钟上手

```bash
git clone https://github.com/luoanjing01/couple-tracker.git
cd couple-tracker/android-app
./gradlew :app:assembleDebug    # 产物：app/build/outputs/apk/debug/app-debug.apk
```

环境：JDK 21 + Android SDK 34（minSdk 26）。详见 [[Build-and-Run]]。
