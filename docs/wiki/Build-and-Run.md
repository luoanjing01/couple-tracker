# 构建与运行

## 环境要求

| 组件 | 版本 |
|------|------|
| JDK | **21**（CI 用 Temurin 21） |
| Android Studio | Hedgehog 2023.1.1+ |
| Android SDK | compileSdk 34 / build-tools 34.0.0 / minSdk 26 |
| Kotlin | 1.9.22（Compose Compiler 1.5.8） |
| AGP | 8.2.2 |

## 本地构建

```bash
git clone https://github.com/luoanjing01/couple-tracker.git
cd couple-tracker/android-app

./gradlew :app:assembleDebug      # Linux / macOS
./gradlew.bat :app:assembleDebug  # Windows
```

产物：`android-app/app/build/outputs/apk/debug/app-debug.apk`

> settings.gradle.kts 已配置阿里云镜像（google/public/gradle-plugin），国内克隆即可构建；gradle.properties 中默认 API 地址指向模拟器宿主机 `10.0.2.2`（历史遗留），运行时实际走 BuildConfig 注入的 Supabase 地址。

Android Studio 开发：File → Open → 选 `android-app/` → 等 Gradle Sync → 连接真机（USB 调试）或模拟器 → Run。

## GitHub Actions（主 CI）

`.github/workflows/build-apk.yml`：

- **触发**：push / PR 到 `main`、`master`，或手动 `workflow_dispatch`。
- **流程**（Ubuntu Latest + JDK 21 + SDK 34）：
  1. 计算新版本号：versionCode +1 / versionName +0.001
  2. `./gradlew :app:assembleDebug`（带 `-PversionCode/-PversionName` 注入）
  3. 上传 artifact `CoupleTracker-debug-apk`
  4. main 分支成功后创建 GitHub **Release**
  5. 把新版本号回写 `app/build.gradle.kts` 并提交（`chore: bump version [skip ci]`）
  6. 始终上传 `gradle-build-log` 便于排错
- **下载 APK**：仓库 Actions 页 → 最新 Run → Artifacts；或 Releases 页。
- 提交信息带 `[skip ci]` 可跳过构建（文档类提交适用）。
- CI 脚本使用 `set -euo pipefail` 防「构建假成功」（曾因 `| tail -80` 吞掉真实错误码踩坑）。

## CircleCI（备用）

`.circleci/config.yml`：`cimg/android:2024.08.2` 镜像（内置 JDK 21 + SDK 34），恢复 Gradle 缓存 → 构建 Debug + Release（未签名）APK → 归档 `CoupleTracker-debug.apk` / `CoupleTracker-release-unsigned.apk` / mapping / 构建报告。与 GitHub Actions 互为冗余。

## 首次安装后的必要授权

App 功能依赖以下敏感权限，LoginActivity 的 PermCard 会逐步引导：

| 权限 | 用途 | 缺失后果 |
|------|------|----------|
| 定位（精确/粗略） | LocationTracker | 无位置上报 |
| 后台定位 | 后台持续定位 | 切后台停报 |
| 使用情况访问（系统设置授权） | AppUsageMonitor | 无 APP 统计 |
| 通知（Android 13+） | 前台服务通知 | FGS 无法启动 |
| 电池优化白名单（建议手动加） | 防国产 ROM 杀服务 | 后台被清理 |

## 数据库初始化（自建 Supabase 时）

在 Supabase SQL Editor 按序执行：

```
supabase_schema.sql                 # 建表 + 触发器 + 索引
supabase_fix_v3.sql                 # register_user / verify_login
supabase_hotfix_20260830.sql        # 去 couple_id 强 FK + 收 password_hash 权限
supabase_pair_fix_v4.sql            # 配对状态机
supabase_unpair.sql                 # 取消配对
supabase_reject_pair.sql            # 拒绝配对
```

每个函数变更后记得 `NOTIFY pgrst, 'reload schema';`。

## 弃用模块的本地运行（仅考古）

```bash
npm run install:all     # 安装 backend/frontend/mobile-simulator 依赖
npm run dev:backend     # Express + Socket.IO 后端（render.yaml 同款 node server.js）
npm run dev:frontend    # React + Vite Web 版
npm run simulate        # 双手机模拟上报
```

当前客户端已不连接这些模块。
