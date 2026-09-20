# 设计决策与已知坑

## 关键技术决策

### 1. 为什么绕过 GoTrue 注册/登录？
Supabase 免费套餐的 GoTrue `/signup` 会触发验证邮件并有限流（429）。自定义 `register_user`（SECURITY DEFINER）直插 `auth.users` 并标记 `email_confirmed_at = now()`，永不发信、永不限流；`verify_login` 用 `crypt()` 自验密码，登录链路完全自控。

### 2. 为什么 RLS 全关闭？
开发期简化：anon key 即可读写全部表，客户端无需 JWT 生命周期管理。**上线前必须恢复** couples/profiles 的行级安全（只有配对的两人能互相看）。

### 3. Token 三重防御（修 HTTP 401 PGRST301）
旧版本曾在 DataStore 存假 token（`rpc_auth_xxx`），升级安装后残留导致拦截器带上无效 Authorization 头 → 全部请求 401。防御：
1. `TrackerApp.onCreate` 启动即 `sanitizeToken()`；
2. `NetworkModule` 拦截器仅对 `isValidJwt()` 通过的 token 加头；
3. `UserRepository.isLoggedIn()` 内再次清洗。
> 只改 LoginActivity 不够：已"登录"用户会被 SplashActivity 直接放行，根本不进登录页。

### 4. 为什么定位用原生 LocationManager？
FusedLocationProvider 依赖 GMS，国产手机（iQOO/VIVO/OPPO/华为/小米）注册成功但回调永不触发 → locations 表 0 记录 → 地图永远「等待位置」。原生 LocationManager 三 provider（GPS/NETWORK/PASSIVE）100% 兼容国产机。

### 5. 省电轻量化（对标 Life360）
- 动态调频：静止（3 次确认）→ 5 分钟间隔；移动恢复高频。
- PASSIVE_PROVIDER 白嫖其他 App 的定位结果，零额外耗电。
- 批量上报：5 条 / 3 分钟聚合一次 POST，网络唤醒降为 1/5；失败重试 + 50 条缓存上限。

### 6. 请求/响应 DTO 分离
restRetrofit 开启 `serializeNulls()`（PATCH 清字段需要），副作用是 `id: null` 会覆盖数据库默认值 `gen_random_uuid()` → 23502。故上报用 `LocationInsert` / `AppUsageInsert`（无 id/created_at），查询用 `LocationRow` / `AppUsageRow`。

### 7. PostgREST 过滤器自动规范化
历史上裸值过滤（`?id=<uuid>` 无 `eq.`）报 400 PGRST100，曾导致「取消配对提示网络异常」「伴侣信息加载失败误显示未配对」。`postgrestFilterInterceptor` 在请求出口对白名单列统一补 `eq.`，幂等可叠加。

### 8. 地图为什么用 WebView + 离线 Leaflet？
免集成地图 SDK（无 Key、无合规流程）；瓦片走高德公开服务（国内直连）；Leaflet 离线打包进 assets，断网也能开图（瓦片需网络）。

## 已知坑速查表

| # | 症状 | 根因 | 解法 |
|---|------|------|------|
| 1 | 国产机定位不触发 | FusedLocationProvider 依赖 GMS | 原生 LocationManager |
| 2 | HTTP 401 PGRST301 | 旧假 token 残留 | 三重防御 |
| 3 | RPC 报参数不存在 | PostgREST schema cache 旧签名 | `NOTIFY pgrst, 'reload schema'` |
| 4 | 注册 429 | GoTrue 邮件限流 | RPC register_user 直插 |
| 5 | 未配对上报 409 | couple_id 强 FK | hotfix 去 FK、列可空 |
| 6 | 地图「等待位置」 | WebView 容器尺寸 0 | `map.invalidateSize()` + 自愈检测 |
| 7 | material-icons-extended 编译失败 | 新版解析错误 | 全面改用 Emoji Text |
| 8 | CI 构建假成功 | 管道吞错误码 | `set -euo pipefail` |
| 9 | Android 14 FGS 闪退 | 启动 location FGS 需已有定位权限 + 通知权限 | 启动全 try-catch + canStartForeground 预检 |
| 10 | APP 时长翻倍 | UI 层与后台双上报 | UI 层只在切换时补报，定时上报归 AppUsageMonitor |
| 11 | 插入报 23502 | serializeNulls 发出 id:null | Insert/Row DTO 分离 |

## 安全提醒

- `anon key`（`sb_publishable_...`）是公开发布密钥，可硬编码。
- `service_role` key 永远不进客户端/git（.gitignore 已防护）。
- `profiles.password_hash` 查询权限已在 hotfix 中回收。
- 待办：恢复 RLS（按 couple_id/partner_id 过滤）、Release 签名 + ProGuard。

## 待完成路线图

统计页增强（轨迹回放/每日卡片）、伴侣资料实时同步、SOS 一键通知、地图双头像样式、省电模式开关、Release 签名、RLS 加密。
