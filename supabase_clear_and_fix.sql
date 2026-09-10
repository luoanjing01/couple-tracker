-- ============================================================
--  清除所有数据 + 双向配对逻辑 + 配对码唯一（在 Supabase SQL Editor 全选执行）
--  ⚠️ 会删除所有用户、位置、使用记录！请确认后再执行！
-- ============================================================

-- ① 清除所有业务数据（按外键依赖顺序）
DELETE FROM public.app_usage;
DELETE FROM public.locations;
DELETE FROM public.profiles;
DELETE FROM auth.identities;
DELETE FROM auth.users;

-- ② 确保 partner_id 和 pending_pair 列存在
ALTER TABLE public.profiles ADD COLUMN IF NOT EXISTS partner_id uuid;
ALTER TABLE public.profiles ADD COLUMN IF NOT EXISTS pending_pair uuid;

-- ③ 清除可能已存在的约束，重新添加
ALTER TABLE public.profiles DROP CONSTRAINT IF EXISTS profiles_couple_code_key;
ALTER TABLE public.profiles ADD CONSTRAINT profiles_couple_code_key UNIQUE (couple_code);

-- ④ 重写 register_user：插入时如果 couple_code 冲突则重试
CREATE OR REPLACE FUNCTION public.register_user(
    p_username text,
    p_password text,
    p_nickname text,
    p_gender text
) RETURNS jsonb AS $$
DECLARE
    v_user_id     uuid  := uuid_generate_v4();
    v_couple_code text;
    v_avatar      text;
    v_email       text;
    v_instance_id uuid;
    v_exists      boolean;
    v_bytes       bytea;
    v_insert_ok   boolean := false;
    v_attempts    int := 0;
BEGIN
    -- 1. 检查用户名重复
    SELECT EXISTS(SELECT 1 FROM public.profiles WHERE username = p_username) INTO v_exists;
    IF v_exists THEN
        RAISE EXCEPTION 'USERNAME_EXISTS';
    END IF;

    v_email  := p_username || '@coupletracker.local';
    v_avatar := CASE WHEN p_gender = 'female' THEN '💗' ELSE '💙' END;

    -- 2. instance_id
    BEGIN
        SELECT id INTO v_instance_id FROM auth.instances LIMIT 1;
    EXCEPTION WHEN OTHERS THEN
        v_instance_id := NULL;
    END;
    IF v_instance_id IS NULL THEN
        v_instance_id := '00000000-0000-0000-0000-000000000000'::uuid;
    END IF;

    -- 3. 插入 auth.users
    INSERT INTO auth.users (
        id, instance_id, aud, role, email,
        email_confirmed_at, encrypted_password,
        raw_app_meta_data, raw_user_meta_data,
        is_super_admin, created_at, updated_at, last_sign_in_at
    ) VALUES (
        v_user_id, v_instance_id, 'authenticated', 'authenticated', v_email,
        now(), crypt(p_password, gen_salt('bf', 8)),
        '{"provider":"email","providers":["email"]}'::jsonb,
        jsonb_build_object('username', p_username, 'nickname', COALESCE(p_nickname, p_username)),
        false, now(), now(), now()
    );

    -- 4. 插入 auth.identities
    INSERT INTO auth.identities (
        id, user_id, identity_data, provider,
        last_sign_in_at, created_at, updated_at
    ) VALUES (
        v_user_id, v_user_id,
        jsonb_build_object('sub', v_user_id::text, 'email', v_email),
        'email', now(), now(), now()
    );

    -- 5. 插入 profiles（带唯一 couple_code，冲突则重试，最多10次）
    WHILE NOT v_insert_ok AND v_attempts < 10 LOOP
        v_attempts := v_attempts + 1;
        BEGIN
            v_bytes := gen_random_bytes(8);
            v_couple_code := substr(
                upper(translate(encode(v_bytes, 'hex'), '0O1I', 'ABC8')),
                1, 6
            );
            IF char_length(v_couple_code) = 6 THEN
                INSERT INTO public.profiles (
                    id, username, nickname, gender, avatar, couple_code, created_at
                ) VALUES (
                    v_user_id, p_username,
                    COALESCE(p_nickname, p_username),
                    COALESCE(p_gender, 'unknown'),
                    v_avatar,
                    v_couple_code,
                    now()
                );
                v_insert_ok := true;
            END IF;
        EXCEPTION WHEN unique_violation THEN
            -- couple_code 冲突，继续循环重试
            v_insert_ok := false;
        END;
    END LOOP;

    IF NOT v_insert_ok THEN
        RAISE EXCEPTION 'COUPLE_CODE_GENERATE_FAILED';
    END IF;

    -- 6. 返回结果
    RETURN jsonb_build_object(
        'user_id',     v_user_id,
        'couple_code', v_couple_code,
        'email',       v_email,
        'username',    p_username,
        'nickname',    COALESCE(p_nickname, p_username),
        'gender',      COALESCE(p_gender, 'unknown'),
        'avatar',      v_avatar
    );
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;
GRANT EXECUTE ON FUNCTION public.register_user(text, text, text, text) TO postgres, anon, authenticated;

-- ⑤ 双向配对逻辑：
--    A 输入 B 的码 → A.pending_pair = B.id
--    B 输入 A 的码 → B.pending_pair = A.id → 检查 A.pending_pair == B → 双向确认！
--    两人 partner_id 互设 → pending_pair 清空
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
    v_their_nickname text;
BEGIN
    IF p_my_id IS NULL OR v_their_code = '' THEN
        RETURN jsonb_build_object('ok', false, 'reason', 'INVALID_ARGS');
    END IF;

    -- 查对方
    SELECT id, couple_code, partner_id, pending_pair INTO v_their_row
      FROM public.profiles WHERE lower(couple_code) = lower(v_their_code) LIMIT 1;

    IF v_their_row IS NULL OR v_their_row.id IS NULL THEN
        RETURN jsonb_build_object('ok', false, 'reason', 'CODE_NOT_FOUND');
    END IF;

    IF v_their_row.id = p_my_id THEN
        RETURN jsonb_build_object('ok', false, 'reason', 'CANNOT_PAIR_SELF');
    END IF;

    -- 如果我已经配对了
    SELECT id, partner_id, pending_pair INTO v_me_row
      FROM public.profiles WHERE id = p_my_id;

    IF v_me_row IS NULL OR v_me_row.id IS NULL THEN
        RETURN jsonb_build_object('ok', false, 'reason', 'ME_NOT_FOUND');
    END IF;

    -- 如果已经有 partner_id，说明已配对
    IF v_me_row.partner_id IS NOT NULL THEN
        SELECT nickname INTO v_their_nickname FROM public.profiles WHERE id = v_me_row.partner_id;
        RETURN jsonb_build_object(
            'ok', true,
            'their_id', v_me_row.partner_id::text,
            'already_paired', true,
            'their_nickname', v_their_nickname
        );
    END IF;

    -- 记录我的配对意图
    UPDATE public.profiles SET pending_pair = v_their_row.id WHERE id = p_my_id;

    -- 检查对方是否也输入了我的码（双向确认）
    IF v_their_row.pending_pair = p_my_id THEN
        -- ✅ 双向确认！设置 partner_id
        UPDATE public.profiles SET partner_id = v_their_row.id, pending_pair = NULL WHERE id = p_my_id;
        UPDATE public.profiles SET partner_id = p_my_id, pending_pair = NULL WHERE id = v_their_row.id;

        SELECT nickname INTO v_their_nickname FROM public.profiles WHERE id = v_their_row.id;
        RETURN jsonb_build_object(
            'ok', true,
            'their_id', v_their_row.id::text,
            'paired', true,
            'their_nickname', v_their_nickname
        );
    ELSE
        -- ⏳ 等待对方也输入我的码
        RETURN jsonb_build_object(
            'ok', true,
            'paired', false,
            'waiting', true,
            'their_id', v_their_row.id::text,
            'their_nickname', (SELECT nickname FROM public.profiles WHERE id = v_their_row.id),
            'msg', '已发起配对请求，等待TA也输入你的配对码'
        );
    END IF;
END;
$$;
GRANT EXECUTE ON FUNCTION public.pair_by_code(uuid,text) TO anon, authenticated;

-- ⑥ RLS：允许通过 partner_id 查询
CREATE POLICY IF NOT EXISTS "profiles_partner_select" ON public.profiles
    FOR SELECT TO anon, authenticated USING (true);

-- 完成
SELECT '✅ 数据已清除，双向配对逻辑已启用，配对码唯一约束已启用' AS result;
