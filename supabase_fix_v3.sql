-- ============================================================
-- 第三次修复 —— 彻底解决 identities + schema cache 问题
-- ============================================================

-- ① 先 DROP handle_new_user trigger（避免和 register_user 手动插 profiles 重复）
DROP TRIGGER IF EXISTS on_auth_user_created ON auth.users;
DROP FUNCTION IF EXISTS public.handle_new_user();

-- ② 重建 register_user，正确插 auth.identities（带 provider_id）
DROP FUNCTION IF EXISTS public.register_user(text, text, text, text);

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
BEGIN
    -- 检查用户名重复
    SELECT EXISTS(SELECT 1 FROM public.profiles WHERE username = p_username) INTO v_exists;
    IF v_exists THEN
        RAISE EXCEPTION 'USERNAME_EXISTS';
    END IF;

    v_email  := p_username || '@coupletracker.local';
    v_avatar := CASE WHEN p_gender = 'female' THEN '💗' ELSE '💙' END;

    -- 生成 6 位 couple_code
    WHILE true LOOP
        v_bytes := gen_random_bytes(8);
        v_couple_code := substr(
            upper(translate(encode(v_bytes, 'hex'), '0O1I', 'ABC8')),
            1, 6
        );
        IF char_length(v_couple_code) = 6 AND NOT EXISTS(
            SELECT 1 FROM public.profiles WHERE couple_code = v_couple_code
        ) THEN
            EXIT;
        END IF;
    END LOOP;

    -- instance_id
    BEGIN
        SELECT id INTO v_instance_id FROM auth.instances LIMIT 1;
    EXCEPTION WHEN OTHERS THEN
        v_instance_id := NULL;
    END;
    IF v_instance_id IS NULL THEN
        v_instance_id := '00000000-0000-0000-0000-000000000000'::uuid;
    END IF;

    -- 插 auth.users
    INSERT INTO auth.users (
        id, instance_id, aud, role, email,
        email_confirmed_at,
        encrypted_password,
        raw_app_meta_data, raw_user_meta_data,
        is_super_admin,
        created_at, updated_at, last_sign_in_at
    ) VALUES (
        v_user_id, v_instance_id, 'authenticated', 'authenticated', v_email,
        now(),
        crypt(p_password, gen_salt('bf', 8)),
        '{"provider":"email","providers":["email"]}'::jsonb,
        jsonb_build_object('username', p_username, 'nickname', COALESCE(p_nickname, p_username)),
        false,
        now(), now(), now()
    );

    -- 插 auth.identities（带 provider_id = 'email'，NOT NULL 约束）
    INSERT INTO auth.identities (
        id, user_id, provider_id, identity_data, provider,
        email, last_sign_in_at, created_at, updated_at
    ) VALUES (
        v_user_id, v_user_id, 'email',
        jsonb_build_object('sub', v_user_id::text, 'email', v_email, 'email_verified', true),
        'email',
        v_email,
        now(), now(), now()
    );

    -- 插 profiles
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

-- ③ 刷新 PostgREST schema cache（关键！不然它还在用旧版函数签名）
NOTIFY pgrst, 'reload schema';

-- ④ 清理之前测试残留的半条数据
DELETE FROM auth.users WHERE email IN ('testfix2@coupletracker.local', 'testfix3@coupletracker.local');
DELETE FROM public.profiles WHERE username IN ('testfix2', 'testfix3');

-- ⑤ 验证函数签名（应该显示参数名带 p_ 前缀）
SELECT proname, pronargs, 
       pg_get_function_identity_arguments(oid) as arg_signature
FROM pg_proc 
WHERE proname = 'register_user';
