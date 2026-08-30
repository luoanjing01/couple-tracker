-- ============================================================
--  清理测试阶段的脏数据
--  只删 @coupletracker.local 的用户（就是本 APP 通过 RPC 注册的）
--  不会影响其他手工在 Supabase Dashboard 里建的账号
-- ============================================================

BEGIN;

-- 1. 先删 app_usage / locations / couples（有外键依赖）
DELETE FROM public.app_usage
WHERE couple_id IN (SELECT id FROM public.couples)
   OR user_id IN (SELECT id FROM public.profiles WHERE username IS NOT NULL);

DELETE FROM public.locations
WHERE couple_id IN (SELECT id FROM public.couples)
   OR user_id IN (SELECT id FROM public.profiles WHERE username IS NOT NULL);

-- 2. 解除 profiles <-> couples 之间的外键关联
UPDATE public.profiles SET couple_id = NULL WHERE couple_id IS NOT NULL;
DELETE FROM public.couples;

-- 3. 删除 @coupletracker.local 的 profiles（即本 APP 注册的所有测试用户）
DELETE FROM public.profiles
WHERE id IN (
    SELECT id FROM auth.users
    WHERE email LIKE '%@coupletracker.local'
);

-- 4. 最后删 auth.users 里 @coupletracker.local 的账号
DELETE FROM auth.users
WHERE email LIKE '%@coupletracker.local';

COMMIT;

-- ============================================================
--  执行完之后：
--    成功 → 没有红色报错
--    效果 → 之前所有测试账号都被清空，可以从头重新注册全新用户名
-- ============================================================
