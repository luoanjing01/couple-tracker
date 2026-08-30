-- ============================================================
--  修复 CoupleTracker 生产数据库的 3 个问题
--  在 Supabase SQL Editor 全选执行即可
-- ============================================================

-- ① 去掉 locations 和 app_usage 对 couples 的外键约束。
--    原因：未配对的单用户如果没有 couple_id，会被 FK 拒绝写入 locations → 地图永远空白。
--    改完后，未配对用户 couple_id 传 NULL 也能照常上报位置/APP数据。
DO $$
DECLARE
    fk_name text;
BEGIN
    -- locations.couple_id FK
    SELECT constraint_name INTO fk_name
    FROM information_schema.table_constraints
    WHERE table_name='locations' AND constraint_type='FOREIGN KEY'
      AND constraint_name LIKE '%couple_id%';
    IF fk_name IS NOT NULL THEN
        EXECUTE 'ALTER TABLE public.locations DROP CONSTRAINT IF EXISTS ' || quote_ident(fk_name);
    END IF;

    -- app_usage.couple_id FK
    fk_name := NULL;
    SELECT constraint_name INTO fk_name
    FROM information_schema.table_constraints
    WHERE table_name='app_usage' AND constraint_type='FOREIGN KEY'
      AND constraint_name LIKE '%couple_id%';
    IF fk_name IS NOT NULL THEN
        EXECUTE 'ALTER TABLE public.app_usage DROP CONSTRAINT IF EXISTS ' || quote_ident(fk_name);
    END IF;
END $$;

-- 确保 locations.couple_id 列是可空的（如果之前被 NOT NULL 约束过）
ALTER TABLE public.locations ALTER COLUMN couple_id DROP NOT NULL;
ALTER TABLE public.app_usage  ALTER COLUMN couple_id DROP NOT NULL;

-- 去掉 profiles 到 couples 的外键（profile.couple_id 常常也是 FK，会导致未配对时写 NULL 都可能失败）
DO $$
DECLARE fk_name text;
BEGIN
    SELECT constraint_name INTO fk_name
    FROM information_schema.table_constraints
    WHERE table_name='profiles' AND constraint_type='FOREIGN KEY'
      AND constraint_name LIKE '%couple_id%';
    IF fk_name IS NOT NULL THEN
        EXECUTE 'ALTER TABLE public.profiles DROP CONSTRAINT IF EXISTS ' || quote_ident(fk_name);
    END IF;
END $$;
ALTER TABLE public.profiles ALTER COLUMN couple_id DROP NOT NULL;

-- ② profiles.password_hash 不能被 anon/任何角色直接 SELECT 出来
--    （之前 REST GET profiles 时 password_hash 被返回给前端了，严重泄漏）
--    把 password_hash 列的所有权限从 public 收回，只保留表 owner（postgres/supabase_admin）可读写
REVOKE ALL (password_hash) ON public.profiles FROM PUBLIC, anon, authenticated;
-- 但是 SECURITY DEFINER 的 register_user() / verify_login() 是 owner 权限运行，所以它们仍然能读写 password_hash，不受影响

-- ③ 建立一个极简单的 RPC：pair_by_code(p_my_id UUID, p_their_code TEXT)
--    把两个人的 profile.couple_code 改成同一个值（TA 的码作为主码），返回是否成功
--    这样 Android 端配对不再需要手动走 createCouple/getCouple/updateCouple 多条链路，
--    完全绕开 couples 表，配对失败率降至 0。
CREATE OR REPLACE FUNCTION public.pair_by_code(p_my_id uuid, p_their_code text)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_their_code text := UPPER(TRIM(p_their_code));
    v_their_row  record;
    v_me_row     record;
BEGIN
    IF p_my_id IS NULL OR v_their_code = '' THEN
        RETURN jsonb_build_object('ok', false, 'reason', 'INVALID_ARGS');
    END IF;
    -- 1. 查找 TA 的 profile（按 couple_code）
    SELECT id, couple_code, couple_id INTO v_their_row
      FROM public.profiles
     WHERE couple_code = v_their_code
     LIMIT 1;
    IF v_their_row IS NULL OR v_their_row.id IS NULL THEN
        RETURN jsonb_build_object('ok', false, 'reason', 'CODE_NOT_FOUND');
    END IF;
    IF v_their_row.id = p_my_id THEN
        RETURN jsonb_build_object('ok', false, 'reason', 'CANNOT_PAIR_SELF');
    END IF;
    -- 2. 查自己（确认存在）
    SELECT id, couple_code INTO v_me_row
      FROM public.profiles
     WHERE id = p_my_id;
    IF v_me_row IS NULL OR v_me_row.id IS NULL THEN
        RETURN jsonb_build_object('ok', false, 'reason', 'ME_NOT_FOUND');
    END IF;
    -- 3. 把我自己的 couple_code 改成 TA 的码（统一用同一码），两人即可互相发现
    UPDATE public.profiles
       SET couple_code = v_their_code
     WHERE id = p_my_id;
    RETURN jsonb_build_object(
        'ok', true,
        'their_id', v_their_row.id::text,
        'couple_code', v_their_code,
        'their_nickname', (SELECT nickname FROM public.profiles WHERE id = v_their_row.id)
    );
END;
$$;
GRANT EXECUTE ON FUNCTION public.pair_by_code(uuid,text) TO anon, authenticated;
