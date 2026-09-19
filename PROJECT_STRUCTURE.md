# 小世界（CoupleTracker）项目结构文档

> 本文档面向初学者，详细解释项目的每个文件和目录的作用。

---

## 一、项目概览

**小世界** 是一款情侣互助追踪 APP，核心功能：
- 实时位置共享与地图显示
- 每日活动统计（哪些应用用了多久）
- 配对码配对机制
- 安卓和 iOS 双平台支持

### 技术架构

```
用户手机 APP（安卓/iOS）
      ↕ HTTP 请求
Supabase 云服务（数据库 + API + 认证）
```

---

## 二、目录结构总览

```
couple-tracker/
├── android-app/          # 安卓应用主代码（Kotlin）
├── ios-app/              # iOS 应用代码（Swift）
├── backend/              # 后端服务（Node.js，备用）
├── frontend/             # 前端 Web 页面（React，备用）
├── .github/workflows/    # GitHub Actions 自动构建配置
├── supabase_*.sql        # 数据库 SQL 脚本（多个版本）
├── PROJECT_STRUCTURE.md  # 本文档
└── README.md             # 项目说明
```

---

## 三、Android 应用详解（android-app/）

### 3.1 项目配置文件

| 文件 | 作用 |
|------|------|
| `build.gradle.kts`（项目级） | Gradle 构建配置，定义仓库地址和镜像 |
| `app/build.gradle.kts` | 应用级配置：版本号、依赖库、SDK版本 |
| `settings.gradle.kts` | 项目名称和仓库配置 |
| `gradle.properties` | Gradle 参数（内存、阿里云镜像等） |
| `gradle/wrapper/gradle-wrapper.properties` | Gradle 版本和下载地址 |

### 3.2 源代码文件（app/src/main/java/com/coupletracker/android/）

#### 📁 data/ — 数据层（网络请求、数据存储）

| 文件 | 作用 | 通俗解释 |
|------|------|---------|
| `NetworkModule.kt` | 网络模块配置 | 配置 HTTP 客户端（Retrofit），设置 Supabase 地址和认证头 |
| `ApiService.kt` | API 接口定义 | 定义所有与服务器通信的接口：注册、登录、配对、位置上报等 |
| `UserRepository.kt` | 用户数据仓库 | 本地存储和读取用户信息（登录状态、用户ID等） |
| `model/Models.kt` | 数据模型 | 定义所有数据结构：用户信息、位置数据、应用使用记录等 |
| `AppSessionTracker.kt` | 应用使用追踪 | 记录用户打开了哪些应用、使用了多长时间 |

#### 📁 ui/ — 界面层（用户看到的页面）

| 文件 | 作用 | 通俗解释 |
|------|------|---------|
| `SplashActivity.kt` | 启动页 | APP 打开时显示的加载画面，自动判断是否已登录 |
| `LoginActivity.kt` | 登录/注册页 | 用户注册、登录、配对的界面 |
| `MainActivity.kt` | 主页面 | 底部导航栏的容器，管理地图、动态、统计三个标签页 |
| `AppScreen.kt` | 应用动态页 | 显示情侣双方的应用使用记录列表 |
| `StatsScreen.kt` | 统计页 | 显示每日使用时长的图表和统计 |

#### 📁 location/ — 定位服务

| 文件 | 作用 | 通俗解释 |
|------|------|---------|
| `LocationTracker.kt` | 位置追踪器 | 获取 GPS 位置、定时上报到服务器、获取对方位置 |

#### 📁 service/ — 后台服务

| 文件 | 作用 | 通俗解释 |
|------|------|---------|
| `TrackerService.kt` | 前台服务 | APP 在后台时保持位置上报和心跳，通知栏显示运行状态 |

#### 📁 appmonitor/ — 应用监控

| 文件 | 作用 | 通俗解释 |
|------|------|---------|
| `AppUsageMonitor.kt` | 应用使用监控 | 读取系统使用记录，统计各应用使用时长 |

#### 📁 receiver/ — 广播接收器

| 文件 | 作用 | 通俗解释 |
|------|------|---------|
| `BootReceiver.kt` | 开机启动 | 手机开机/重启后自动启动追踪服务 |

#### 根目录

| 文件 | 作用 | 通俗解释 |
|------|------|---------|
| `TrackerApp.kt` | Application 类 | APP 全局初始化，在所有 Activity 之前执行 |

### 3.3 资源文件（app/src/main/）

| 路径 | 作用 |
|------|------|
| `assets/www/index.html` | 地图网页 — 使用 Leaflet.js 显示高德地图，加载瓦片和轨迹 |
| `res/values/strings.xml` | 字符串资源 |
| `res/values/colors.xml` | 颜色定义 |
| `res/values/themes.xml` | 主题样式 |
| `res/xml/network_security_config.xml` | 网络安全配置（允许 HTTP 请求） |
| `AndroidManifest.xml` | APP 清单文件 — 声明权限、注册组件 |

### 3.4 构建输出

| 路径 | 作用 |
|------|------|
| `app/build/outputs/apk/debug/app-debug.apk` | 编译生成的安装包 |

---

## 四、iOS 应用详解（ios-app/）

| 文件 | 作用 |
|------|------|
| `AppDelegate.swift` | APP 生命周期管理 |
| `SceneDelegate.swift` | 界面场景管理 |
| `LoginViewController.swift` | 登录注册页 |
| `MainTabBarController.swift` | 底部导航栏 |
| `MapViewController.swift` | 地图页（WKWebView 加载 index.html） |
| `AppsViewController.swift` | 应用动态页 |
| `StatsViewController.swift` | 统计页 |
| `ProfileViewController.swift` | 个人信息页 |
| `APIClient.swift` | API 客户端（网络请求封装） |
| `LocationManager.swift` | 定位管理 |
| `UserStore.swift` | 用户数据存储 |
| `Resources/www/index.html` | 地图网页（同安卓） |

---

## 五、后端服务（backend/，备用）

| 文件 | 作用 |
|------|------|
| `server.js` | Express + Socket.IO 后端服务 |
| `database.js` | JSON 文件数据库（演示用） |

> 注意：当前项目主要使用 Supabase 作为后端，backend/ 目录为早期备用方案。

---

## 六、前端 Web（frontend/，备用）

| 文件 | 作用 |
|------|------|
| `src/App.tsx` | React 路由入口 |
| `src/api/index.ts` | API 客户端封装 |

> 注意：当前项目以原生 APP 为主，frontend/ 为早期 Web 版本。

---

## 七、数据库 SQL 脚本

| 文件 | 作用 | 使用状态 |
|------|------|---------|
| `supabase_schema.sql` | 初始建表脚本 | ✅ 已执行 |
| `supabase_fix.sql` | 第一次修复 | ✅ 已执行 |
| `supabase_fix_v2.sql` | 第二次修复 | ✅ 已执行 |
| `supabase_fix_v3.sql` | 第三次修复 | ✅ 已执行 |
| `supabase_clear_and_fix.sql` | 清空数据+修复 | ✅ 已执行 |
| `supabase_clear_and_fix_v2.sql` | 清空+修复v2 | ✅ 已执行 |
| `supabase_fix_provider_id.sql` | 修复注册 provider_id | ✅ 已执行 |
| `supabase_pair_fix_v4.sql` | 配对逻辑v4（最新） | ✅ 已执行 |

### 数据库表结构

```
profiles（用户表）
├── id          — 用户 UUID（主键）
├── username    — 用户名
├── nickname    — 昵称
├── couple_code  — 配对码（6位）
├── partner_id   — 配对方的用户ID
├── pending_pair — 待配对方的用户ID
├── pair_request_at — 配对请求发送时间
├── gender      — 性别
├── avatar      — 头像URL
├── created_at  — 注册时间
└── updated_at  — 更新时间

locations（位置表）
├── user_id     — 用户ID
├── latitude    — 纬度
├── longitude   — 经度
├── accuracy     — 精度（米）
├── speed       — 速度
├── heading     — 方向
├── created_at  — 记录时间

app_usage（应用使用表）
├── user_id     — 用户ID
├── package_name — 应用包名
├── app_name    — 应用名称
├── usage_minutes — 使用时长（分钟）
├── date        — 日期
```

### 数据库函数

| 函数名 | 作用 |
|--------|------|
| `register_user` | 注册新用户（创建auth账户+profiles记录） |
| `verify_login` | 验证登录（检查密码是否正确） |
| `pair_by_code` | 发起配对请求（A输入B的码） |
| `check_pair_status` | 查询配对状态（轮询用） |
| `accept_pair` | 确认配对（B同意A的请求） |

---

## 八、自动构建（.github/workflows/）

| 文件 | 作用 |
|------|------|
| `build-apk.yml` | GitHub Actions 配置 — 推送代码后自动编译 APK |

### 构建流程

```
git push → GitHub 检测到代码更新 → 启动 Ubuntu 虚拟机
→ 安装 JDK → 编译 Android 项目 → 上传 APK 到 Artifacts
→ 你在 GitHub Actions 页面下载 APK
```

---

## 九、APP 运行流程

### 9.1 启动流程

```
用户点击 APP 图标
  → SplashActivity（闪屏页）
    → 检查是否已登录
      → 已登录 → MainActivity（主页）
      → 未登录 → LoginActivity（登录页）
```

### 9.2 注册登录流程

```
输入用户名+密码 → 调用 register_user RPC
  → 成功 → 保存用户信息 → 进入配对页
  → 失败 → 提示错误原因
```

### 9.3 配对流程

```
A 输入 B 的配对码 → pair_by_code RPC → 设置 pending_pair
  → B 的 APP 每3秒轮询 check_pair_status
    → 检测到 incoming_request → 弹窗"XX想和你配对"
      → B 点"接受" → accept_pair RPC
        → 双方 partner_id 互相设置 → 配对成功
```

### 9.4 位置共享流程

```
TrackerService 后台运行
  → 每5秒获取 GPS 位置 → 上报到 locations 表
  → 同时查询对方最新位置 → 在地图上显示
```

### 9.5 应用统计流程

```
AppUsageMonitor 读取系统使用记录
  → 过滤桌面/输入法等噪音应用
  → 计算前台使用时长 → 上报到 app_usage 表
  → AppScreen/StatsScreen 展示统计结果
```

---

## 十、关键配置说明

### Supabase 配置
- 项目地址：在 `NetworkModule.kt` 中的 `SUPABASE_URL`
- API 密钥：在 `NetworkModule.kt` 中的 `SUPABASE_KEY`
- 数据库：PostgreSQL（Supabase 托管）

### 地图配置
- 图源：高德地图（街道图+卫星图）
- 缩放范围：3-19 级
- 坐标系：GCJ-02（火星坐标）
- 离线支持：纯离线模式，零网络依赖

### 定位配置
- 上报间隔：5 秒
- 位置过期：2 分钟（超过2分钟的旧位置丢弃）
- 精度过滤：>200m 的位置点丢弃

---

## 十一、常用操作

### 编译 APK
```bash
cd android-app
./gradlew assembleDebug
# 输出：app/build/outputs/apk/debug/app-debug.apk
```

### 执行 SQL 修复
1. 打开 Supabase Dashboard → SQL Editor
2. 粘贴 SQL 文件内容 → Run

### 推送代码触发自动构建
```bash
git add .
git commit -m "描述改了什么"
git push origin main
# GitHub Actions 自动构建
```
