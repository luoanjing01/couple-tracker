-- ============================================================================
-- 清除「情侣追踪」App 服务器上的所有用户数据（测试期专用）
-- ----------------------------------------------------------------------------
-- 修复说明（为什么旧版脚本执行后数据还在）：
--   旧版脚本里有一行 `DELETE FROM auth.audit_log_entries WHERE user_id = ...`，
--   而 Supabase 的 audit_log_entries 表根本没有 user_id 这一列，
--   导致整个事务报错回滚 —— 前面的 DELETE 全部作废，所以数据一条都没删掉。
--
-- 本版本修复：
--   ① 去掉了会报错的 audit_log_entries 删除（auth.users 删除时会自动级联清理会话等）
--   ② 按外键依赖顺序删除：子表 → 父表
--   ③ 最后附带验证查询，所有表都应该返回 0 行
--
-- 使用方法：
--   打开 Supabase Dashboard → 左侧 SQL Editor → New query
--   把本文件全部内容粘贴进去 → 点右下角 Run（或 Ctrl+Enter）
--   ⚠️ 此操作不可恢复！所有测试账号、位置轨迹、配对关系、使用统计都会被清空
-- ============================================================================

BEGIN;

-- ① 删 APP 使用记录（外键引用 profiles.id）
DELETE FROM public.app_usage;

-- ② 删位置轨迹（外键引用 profiles.id）
DELETE FROM public.locations;

-- ③ 删用户档案（配对关系 partner_id / pending_pair / couple_code 都在这里）
DELETE FROM public.profiles;

-- ④ 删情侣关系表（当前版本配对不创建 couples 行，一般为空，兜底删一下）
DELETE FROM public.couples;

-- ⑤ 删认证用户（auth.users 删除会自动级联删除 auth.identities / sessions /
--    refresh_tokens 等；public.profiles 也有 ON DELETE CASCADE 兜底）
DELETE FROM auth.users;

COMMIT;

-- ============================================================================
-- 验证：以下查询应该全部返回 0
-- ============================================================================
SELECT 'profiles'   AS 表名, COUNT(*) AS 剩余行数 FROM public.profiles
UNION ALL
SELECT 'locations',  COUNT(*) FROM public.locations
UNION ALL
SELECT 'app_usage',  COUNT(*) FROM public.app_usage
UNION ALL
SELECT 'couples',    COUNT(*) FROM public.couples
UNION ALL
SELECT 'auth.users', COUNT(*) FROM auth.users;
