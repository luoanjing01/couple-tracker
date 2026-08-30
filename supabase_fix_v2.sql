-- ============================================================
-- 第二次修复 —— 去掉 auth.identities INSERT（完全绕过 GoTrue）
-- 我们只用 auth.users 存密码，verify_login RPC 直接查 auth.users 验证
-- ============================================================

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
    -- 1. 检查用户名重复
    SELECT EXISTS(SELECT 1 FROM public.profiles WHERE username = p_username) INTO v_exists;
    IF v_exists THEN
        RAISE EXCEPTION 'USERNAME_EXISTS';
    END IF;

    v_email  := p_username || '@coupletracker.local';
    v_avatar := CASE WHEN p_gender = 'female' THEN '💗' ELSE '💙' END;

    -- 2. 生成唯一 6 位 couple_code
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

    -- 3. instance_id
    BEGIN
        SELECT id INTO v_instance_id FROM auth.instances LIMIT 1;
    EXCEPTION WHEN OTHERS THEN
        v_instance_id := NULL;
    END;
    IF v_instance_id IS NULL THEN
        v_instance_id := '00000000-0000-0000-0000-000000000000'::uuid;
    END IF;

    -- 4. 只插 auth.users（bcrypt + email_confirmed_at = now()）
    --    不插 auth.identities —— 我们完全绕过 GoTrue signIn！
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

    -- 5. 插 public.profiles（带 couple_code）
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

-- 清理之前测试产生的半条数据（auth.users 成功了但 identities 插入失败）
DELETE FROM auth.users WHERE email = 'testfix2@coupletracker.local';
DELETE FROM public.profiles WHERE username = 'testfix2';
