# 核心业务流程

## 1. App 启动路由

```
点击图标
  → TrackerApp.onCreate()
      ├─ 创建通知渠道
      ├─ UserRepository.init + NetworkModule.init
      └─ sanitizeToken()（清洗假 token）
  → SplashActivity（500ms）
      └─ UserRepository.isLoggedIn()
          ├─ true  → TrackerService.start() + MainActivity
          └─ false → LoginActivity（Step.LOGIN）
```

## 2. 注册 / 登录

```
LoginCard
  ├─ 注册：rpcService.registerUser(p_username, p_password, p_nickname, p_gender)
  │         → 服务端直插 auth.users（bcrypt 密码、邮箱标记已验证、自动发 6 位配对码）
  └─ 统一登录：rpcService.verifyLogin(p_username, p_password)
                → 返回 user_id + profile JSON
                → 兼容解析旧版 f1~f8 字段格式
                → UserRepository.setUser(UserInfo) 写入 DataStore
  → Step.PAIR
```

> 全程不触碰 GoTrue `/auth/v1/signup`，规避邮件发送与 429 限流；登录成功**不存 JWT**（verify_login 不签发 token，`auth_token` 保持空，登录态以 UserInfo 为准）。

## 3. 配对状态机

```
                pair_by_code(对方配对码)
   A 机：idle ───────────────────────────▶ waiting（pending_pair=B, pair_request_at=now）
                                           │
   B 机：每 3s 轮询 checkPairStatus ◀───────┘
              │
              ▼ status = incoming_request（携带 requester_* 信息）
        ┌─────┴─────┐
   accept_pair   reject_pair（清 A 的 pending）
        │               │
        ▼               ▼
     paired            idle
  （双向 partner_id）   （A 可重新发起）

paired ──unpair（任一方）──▶ idle（双方 partner_id/pending_pair/pair_request_at 全清）
```

- A 侧重复点击：服务端 30 秒窗口拦截。
- MainActivity 内建 5 秒轮询，配对成功/收到请求都会在主页响应；LoginActivity 的 PairCard 是 3 秒轮询。

## 4. 位置采集与上报

```
TrackerService.onCreate
  └─ combine(userFlow, locIntervalFlow, appIntervalFlow).collect
        └─ restartLocation(locSec × 1000)
              └─ LocationTracker.start(intervalMs)
                    ├─ pickBestLastKnown() → 2 分钟内缓存位置 → 立即强制上报
                    └─ registerProviders(当前间隔)
                          ├─ GPS_PROVIDER ──┐
                          ├─ NETWORK_PROVIDER┼→ onLocationChanged → report(loc)
                          └─ PASSIVE_PROVIDER ┘        │
                                                       ▼
                              精度过滤(>200m 丢弃) → 位移节流(2s/<3m 跳过)
                                                       │
                              静止检测（连续 3 次 <30m 且 <0.3m/s → 间隔拉到 5min；
                                        位移 >50m 或 >0.5m/s → 恢复高频）
                                                       │
                                                       ▼
                              pendingBatch += LocationInsert(user_id, couple_id=null, ...)
                                                       │
                    ┌──────────────────────────────────┼──────────────────────┐
                    │ 攒满 5 条                          │ 距上次 ≥3 分钟          │ 启动强制
                    ▼                                  ▼                      ▼
                              doFlush() → POST /rest/v1/locations（数组批量插入）
                                          失败 → 数据放回缓存，下个点触发重试
                                          成功 → lastLocationReportStatus 更新
```

- 电量：TrackerService 每 60s 读一次电量缓存进 LocationTracker，随下一条位置发出。
- 未配对：`couple_id = null`（数据库已去 FK），轨迹照常记录。

## 5. APP 使用采集与上报

```
AppUsageMonitor.start(pollMs ≈ 4s)
  每轮 checkAndReport():
    熄屏 → 暂停计时（恢复后扣除熄屏时长），不上报
    亮屏 → queryEvents(60s 窗口) 取最新 MOVE_TO_FOREGROUND 包名
           ├─ 包名变化 → 补报前一个 APP（≥10s）→ 重置会话
           ├─ 实时刷新 currentSessionSeconds 状态流
           └─ 距上次 ≥15s → reportOnce(当前 APP, 增量秒数)
                              → 噪音过滤（桌面/输入法/系统UI…）
                              → getAppMeta()（名称 + 中文分类）
                              → POST /rest/v1/app_usage
                              → lastAppReportStatus 更新
```

**双保险**：UI 层 `AppSessionTracker.setCurrentApp()` 在 APP 切换瞬间也会补报（同包 5s 去重防双报）；后台服务被国产 ROM 杀掉时 UI 活着就能续上。

## 6. 地图实时查看（WebView）

```
MainActivity 地图 Tab
  → WebView 加载 file:///android_asset/www/index.html
  → buildInjectionJs() 注入：
      window.__SUPABASE_URL__ / __SUPABASE_ANON_KEY__ / __AUTH_TOKEN__ / __CURRENT_USER__
      + localStorage 副本 + 调用 __applyAndroidInjection()
  → index.html：
      tryInitMap() → initMap()（Leaflet + 高德瓦片，GCJ-02）
      fetchPartnerUid()：profiles 表查配对方 id
      poll() 每 5s：
        ├─ locations?user_id=eq.我&order=created_at.desc&limit=1
        └─ locations?user_id=eq.TA&order=created_at.desc&limit=1
        → wgs2gcj 转换 → setOrUpdate() 更新 marker + 精度圆 → 首次 fit() 居中
```

- Tab 切回 / 页面加载完成 → 重新注入 + `map.invalidateSize()`（修容器尺寸 0 不渲染）。
- 瓦片连续失败 5 次自动切换 街道图 ↔ 卫星图。

## 7. 统计页数据流

```
StatsScreen（我/TA × 日期偏移）
  → getAppUsageInRange(user_id, created_at=and(gte.当日0点, lt.次日0点))
  → 客户端按日过滤 → 聚合：
      ├─ 24 小时柱状图（按小时汇总 usage_seconds）
      ├─ 总时长
      └─ APP 排行 + 占比
```

## 8. 退出登录 / 取消配对

- 退出登录：`UserRepository.logout()` — 清空 DataStore 全部键 + WebView 缓存/localStorage + cacheDir → userFlow 变 null → TrackerService combine 触发 `stopSelf()` → 回登录页。
- 取消配对：`unpair(p_my_id)` → 双方字段清空 → 双方轮询回到 `idle`。
