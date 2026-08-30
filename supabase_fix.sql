-- ============================================================
-- 修复脚本 — 在 Supabase SQL Editor 执行（按顺序全部 Run）
-- 解决：register_user 函数找不到 / 函数签名不匹配 / GoTrue 登录 500
-- ============================================================

-- ① 确保 uuid-ossp 扩展存在（提供 uuid_generate_v4）
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- ② 先删除旧的 register_user（如果存在），用正确版本重建
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

    -- 2. 生成唯一 6 位 couple_code（去除 0/O/1/I 歧义）
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

    -- 4. 插入 auth.users（bcrypt 哈希 + 直接 email_confirmed_at = now()，永不走邮件）
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

    -- 5. 插入 auth.identities（绕过 GoTrue 内部 identities 查询报错）
    INSERT INTO auth.identities (
        id, user_id, identity_data, provider,
        last_sign_in_at, created_at, updated_at
    ) VALUES (
        v_user_id, v_user_id,
        jsonb_build_object('sub', v_user_id::text, 'email', v_email),
        'email',
        now(), now(), now()
    );

    -- 6. 插入 public.profiles（带 couple_code）
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

    -- 7. 返回结果
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


-- ============================================================
-- verify_login RPC — 完全绕过 GoTrue signIn，手动验证密码
-- ============================================================
DROP FUNCTION IF EXISTS public.verify_login(text, text);

CREATE OR REPLACE FUNCTION public.verify_login(
    p_username text,
    p_password text
) RETURNS jsonb AS $$
DECLARE
    v_user_id uuid;
    v_email   text;
    v_profile jsonb;
BEGIN
    v_email := p_username || '@coupletracker.local';

    -- 手动验证密码（crypt 是 pgcrypto 扩展自带函数）
    SELECT u.id INTO v_user_id
    FROM auth.users u
    WHERE u.email = v_email
      AND u.encrypted_password = crypt(p_password, u.encrypted_password);

    IF v_user_id IS NULL THEN
        RAISE EXCEPTION 'INVALID_CREDENTIALS';
    END IF;

    -- 拉 profile
    SELECT row_to_json(row(
        p.id, p.username, p.nickname, p.avatar, p.gender,
        p.couple_code, p.couple_id, p.created_at
    ))::jsonb INTO v_profile
    FROM public.profiles p WHERE p.id = v_user_id;

    IF v_profile IS NULL THEN
        RAISE EXCEPTION 'PROFILE_NOT_FOUND';
    END IF;

    RETURN jsonb_build_object(
        'user_id', v_user_id,
        'profile', v_profile
    );
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

GRANT EXECUTE ON FUNCTION public.verify_login(text, text) TO postgres, anon, authenticated;
