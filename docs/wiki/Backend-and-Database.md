# 后端与数据库（Supabase）

项目**没有自建后端**，一切数据走 Supabase（PostgreSQL + PostgREST + GoTrue）。仓库根目录的 `supabase_*.sql` 是数据库的全部演进历史，需在 Supabase Dashboard → SQL Editor 手动执行。

| 项 | 值 |
|----|----|
| 项目 URL | `https://gvytqbgangyjjurekyid.supabase.co` |
| 数据接口 | `/rest/v1/*`（PostgREST） |
| 函数接口 | `/rest/v1/rpc/*` |
| 认证接口 | `/auth/v1/*`（GoTrue，仅遗留，实际注册登录走 RPC） |
| RLS | **全表关闭**（anon key 可读写；上线前必须恢复，见 [[Design-Decisions]]） |

## 表结构

### profiles（用户档案）

| 列 | 类型 | 说明 |
|----|------|------|
| id | uuid PK | 对应 `auth.users(id)` |
| username | text UNIQUE | 登录名 |
| nickname / avatar / gender | text | 昵称 / 头像 / 性别(male/female/unknown) |
| couple_code | text UNIQUE | 6 位配对码，注册时自动生成 |
| couple_id | uuid NULL | 所属情侣组（hotfix 后移除了对 couples 的强 FK） |
| partner_id | uuid NULL | 配对方 user id |
| pending_pair | uuid NULL | 待处理配对请求的对方 id |
| pair_request_at | timestamptz NULL | 配对请求时间（30 秒防重） |
| created_at / updated_at | timestamptz | 时间戳 |

### couples（情侣组）

`id uuid PK`、`code text UNIQUE`、`user_a uuid → profiles`、`user_b uuid NULL → profiles`、`created_at`。
> 注：当前配对链路主要走 profiles.partner_id，couples 表弱化使用。

### locations（位置上报）

`id uuid PK 默认 gen_random_uuid()`、`user_id uuid NOT NULL → profiles`、`couple_id uuid NULL`（**无外键**，未配对用户传 null）、`latitude/longitude double`、`accuracy double`、`speed double`、`battery_level int`、`is_moving bool`、`created_at timestamptz 默认 now()`。

### app_usage（APP 使用时长）

`id uuid PK`、`user_id uuid NOT NULL`、`couple_id uuid NULL`、`package_name text`、`app_name text`、`category text`、`usage_seconds int`、`window_start timestamptz`、`created_at`。

### 索引与触发器（supabase_schema.sql）

- 索引：`locations(user_id, created_at)`、`app_usage(user_id, created_at)`、`profiles(couple_code)` 等查询优化索引。
- `generate_couple_code()`：生成 6 位唯一配对码。
- `handle_new_user()`：auth.users 新增时自动建 profiles（GoTrue 路径用；当前注册走 RPC 直插，不依赖此触发器）。

## RPC 函数（当前线上生效版）

全部为 `SECURITY DEFINER`，参数带 `p_` 前缀；**修改函数后必须 `NOTIFY pgrst, 'reload schema'`**，否则 PostgREST 缓存旧签名报「参数不存在」。

### register_user(p_username, p_password, p_nickname, p_gender) → jsonb
定义于 `supabase_fix_v3.sql`：
1. 校验用户名不重复；
2. 生成唯一 couple_code；
3. 密码 `crypt(p_password, gen_salt('bf', 8))` bcrypt 加密；
4. 直接 INSERT `auth.users` + `auth.identities`（`email_confirmed_at = now()`，**永不触发邮件/429 限流**）+ `profiles`；
5. 返回 `user_id / couple_code / username / nickname / gender / avatar`。

### verify_login(p_username, p_password) → jsonb
查 profiles → `crypt(p_password, password_hash) = password_hash` 比对 → 返回 `user_id + profile(jsonb)`。完全不依赖 GoTrue。

### pair_by_code(p_my_id, p_their_code) → jsonb
定义于 `supabase_pair_fix_v4.sql`（最新版配对逻辑）：
1. 参数校验；按 couple_code 找对方（找不到 → `ok=false, reason`）；
2. 自己已配对 → `already_paired`；
3. 30 秒内重复请求拦截（防误触刷接口）；
4. 写入自己侧 `pending_pair = 对方id, pair_request_at = now()` → 返回 `request_sent / waiting / their_id / their_nickname`。

### check_pair_status(p_my_id) → jsonb
轮询用状态机：

| status | 含义 | 附带字段 |
|--------|------|----------|
| `paired` | 已配对 | partner_id / partner_nickname / partner_code / partner_gender / partner_avatar |
| `incoming_request` | 有人请求与我配对 | requester_id / requester_nickname / requester_code / requester_gender / requester_avatar |
| `waiting` | 我等对方确认 | their_id / their_nickname |
| `idle` | 空闲 | — |

### accept_pair(p_my_id, p_their_id) → jsonb
校验对方确有对我的 pending 请求 → 双向写 `partner_id` → 清空双方 `pending_pair / pair_request_at` → 返回 `ok, paired, partner_id, partner_nickname`。

### reject_pair(p_my_id, p_their_id) → jsonb
（`supabase_reject_pair.sql`）校验存在待处理请求 → 清掉请求方 A 的 `pending_pair / pair_request_at`，B 侧轮询不再弹窗。

### unpair(p_my_id) → jsonb
（`supabase_unpair.sql`）单方取消：`partner_id / pending_pair / pair_request_at` **双方全部清空**，恢复未配对状态，可重新配对。

## SQL 脚本演进史

| 文件 | 作用 | 状态 |
|------|------|------|
| `supabase_schema.sql` | 初始建 4 表 + 触发器 + 索引 | ✅ 已执行 |
| `supabase_fix.sql` / `fix_v2.sql` | 早期函数修复 | ✅ 已被取代 |
| `supabase_fix_v3.sql` | **现行 register_user / verify_login** + reload schema | ✅ 线上生效 |
| `supabase_clear_and_fix.sql` / `_v2` | 清库重建版修复 | ✅ 已执行 |
| `supabase_fix_provider_id.sql` | 修注册时 identities.provider 字段 | ✅ 已执行 |
| `supabase_fix_couplecode_unique.sql` | couple_code 唯一约束修复 | ✅ 已执行 |
| `supabase_fix_locations.sql` | locations 表小修 | ✅ 已执行 |
| `supabase_hotfix_20260830.sql` | **删除 locations/app_usage/profiles 对 couples 的强 FK**（修 409），回收 `profiles.password_hash` 查询权限 | ✅ 线上生效 |
| `supabase_pair_fix_v3.sql` / **`v4`** | 配对状态机；**v4 为现行版** | ✅ 线上生效 |
| `supabase_unpair.sql` | unpair 函数 | ✅ 线上生效 |
| `supabase_reject_pair.sql` | reject_pair 函数 | ✅ 线上生效 |
| `supabase_cleanup_test_data.sql` | 清理测试数据工具 | 工具脚本 |
| `supabase_clear_all_data.sql` | 清空全部业务数据（重写版，去掉会回滚事务的 audit_log_entries 删除） | 工具脚本 |

## 联调测试脚本

| 文件 | 用途 |
|------|------|
| `test_rpc.js` | 测旧版 `register_user_v2 / verify_login_v2` 及旧签名函数 |
| `test_rpc2.js` | 测带 `p_` 前缀的新版 RPC（注册/登录/重名场景） |
| `test_supabase.ps1` | PowerShell 调 `pair_by_code` 验证配对 |
| `test_supabase_fix.ps1` | 修复后集成测试：两用户互配 |

## 弃用后端（backend/）

早期自建方案：Express + Socket.IO（`server.js`）+ JSON 文件数据库（`database.js`，兼容 SQLite API 风格），`render.yaml` 曾用于部署 Render Web Service（健康检查 `/api/health`）。**已完全被 Supabase 取代，不再维护。**
