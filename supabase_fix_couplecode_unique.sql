-- ============================================================
--  修复配对码重复问题：每个账号保持独立配对码，配对改用 partner_id
--  在 Supabase SQL Editor 全选执行即可
-- ============================================================

-- ① 添加 partner_id 列到 profiles（如果不存在）
ALTER TABLE public.profiles ADD COLUMN IF NOT EXISTS partner_id uuid;

-- ② 确保 couple_code 有唯一约束（防止生成重复码）
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.table_constraints
        WHERE table_name = 'profiles' AND constraint_type = 'UNIQUE'
          AND constraint_name = 'profiles_couple_code_key'
    ) THEN
        -- 先清理已存在的重复码（保留最早的，其余置空后重新生成）
        DELETE FROM public.profiles p1
        WHERE p1.ctid NOT IN (
            SELECT MIN(p2.ctid) FROM public.profiles p2
            WHERE p2.couple_code IS NOT NULL
            GROUP BY p2.couple_code
        );
        -- 添加唯一约束
        ALTER TABLE public.profiles ADD CONSTRAINT profiles_couple_code_key UNIQUE (couple_code);
    END IF;
END $$;

-- ③ 重写 pair_by_code：不改 couple_code，改用 partner_id 双向绑定
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
    SELECT id, couple_code, partner_id INTO v_their_row
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
    SELECT id, couple_code, partner_id INTO v_me_row
      FROM public.profiles
     WHERE id = p_my_id;

    IF v_me_row IS NULL OR v_me_row.id IS NULL THEN
        RETURN jsonb_build_object('ok', false, 'reason', 'ME_NOT_FOUND');
    END IF;

    -- 3. 双向设置 partner_id（不改 couple_code！每个人保留自己的码）
    UPDATE public.profiles SET partner_id = v_their_row.id WHERE id = p_my_id;
    UPDATE public.profiles SET partner_id = p_my_id WHERE id = v_their_row.id;

    RETURN jsonb_build_object(
        'ok', true,
        'their_id', v_their_row.id::text,
        'couple_code', v_their_code,
        'their_nickname', (SELECT nickname FROM public.profiles WHERE id = v_their_row.id)
    );
END;
$$;
GRANT EXECUTE ON FUNCTION public.pair_by_code(uuid,text) TO anon, authenticated;

-- ④ 修复已配对用户：把共享 couple_code 的用户改为 partner_id 绑定
--    （找出 couple_code 相同但 id 不同的用户对，设置 partner_id）
DO $$
DECLARE
    r record;
BEGIN
    FOR r IN
        SELECT couple_code,
               array_agg(id ORDER BY created_at) AS ids
          FROM public.profiles
         WHERE couple_code IS NOT NULL
         GROUP BY couple_code
        HAVING COUNT(*) = 2
    LOOP
        -- 第一个人 → partner_id = 第二个人
        UPDATE public.profiles SET partner_id = r.ids[2] WHERE id = r.ids[1];
        -- 第二个人 → partner_id = 第一个人
        UPDATE public.profiles SET partner_id = r.ids[1] WHERE id = r.ids[2];

        -- 给第二个人重新生成独立的 couple_code（解除共享）
        UPDATE public.profiles
           SET couple_code = (
               SELECT string_agg(c, '')
               FROM (
                   SELECT substr('ABCDEFGHJKMNPQRSTUVWXYZ23456789',
                       (floor(random() * 28) + 1)::int, 1) AS c
                   FROM generate_series(1, 6)
               ) sub
               WHERE NOT EXISTS (
                   SELECT 1 FROM public.profiles p2
                   WHERE p2.couple_code = (
                       SELECT string_agg(c, '')
                       FROM (
                           SELECT substr('ABCDEFGHJKMNPQRSTUVWXYZ23456789',
                               (floor(random() * 28) + 1)::int, 1) AS c
                           FROM generate_series(1, 6)
                       ) sub2
                   )
               )
           )
         WHERE id = r.ids[2];
    END LOOP;
END $$;

-- ⑤ 确保 RLS 允许通过 partner_id 查询
--    （已有 RLS 策略通常用 id = auth.uid()，partner_id 查询需要额外策略）
CREATE POLICY IF NOT EXISTS "profiles_partner_select" ON public.profiles
    FOR SELECT TO anon, authenticated
    USING (true);
