-- ==============================================
-- 修复 locations 表：允许 couple_id 为 null
-- 在 Supabase Dashboard -> SQL Editor 执行
-- ==============================================
-- 问题：locations 表 couple_id 列是 NOT NULL
-- 未配对用户 couple_id 为 null，导致位置上报失败 (HTTP 400, 23502 not_null_violation)
-- 修复：允许 couple_id 为 null，未配对用户也能上报位置轨迹
-- ==============================================

ALTER TABLE public.locations ALTER COLUMN couple_id DROP NOT NULL;
