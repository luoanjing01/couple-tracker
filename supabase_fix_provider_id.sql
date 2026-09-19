-- 快速修复：只需重新创建 register_user 函数（加 provider_id 列）
-- 在 Supabase SQL Editor 全选执行

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
    v_insert_ok   boolean := false;
    v_attempts    int := 0;
BEGIN
    SELECT EXISTS(SELECT 1 FROM public.profiles WHERE username = p_username) INTO v_exists;
    IF v_exists THEN
        RAISE EXCEPTION 'USERNAME_EXISTS';
    END IF;

    v_email  := p_username || '@coupletracker.local';
    v_avatar := CASE WHEN p_gender = 'female' THEN '💗' ELSE '💙' END;

    BEGIN
        SELECT id INTO v_instance_id FROM auth.instances LIMIT 1;
    EXCEPTION WHEN OTHERS THEN
        v_instance_id := NULL;
    END;
    IF v_instance_id IS NULL THEN
        v_instance_id := '00000000-0000-0000-0000-000000000000'::uuid;
    END IF;

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

    -- 修复：加了 provider_id 列
    INSERT INTO auth.identities (
        id, user_id, identity_data, provider, provider_id,
        last_sign_in_at, created_at, updated_at
    ) VALUES (
        v_user_id, v_user_id,
        jsonb_build_object('sub', v_user_id::text, 'email', v_email),
        'email', v_user_id::text,
        now(), now(), now()
    );

    WHILE NOT v_insert_ok AND v_attempts < 10 LOOP
        v_attempts := v_attempts + 1;
        BEGIN
            v_couple_code := substr(
                upper(translate(encode(gen_random_bytes(8), 'hex'), '0O1I', 'ABC8')),
                1, 6
            );
            IF char_length(v_couple_code) = 6 THEN
                INSERT INTO public.profiles (
                    id, username, nickname, gender, avatar, couple_code,
                    partner_id, pending_pair, created_at
                ) VALUES (
                    v_user_id, p_username,
                    COALESCE(p_nickname, p_username),
                    COALESCE(p_gender, 'unknown'),
                    v_avatar,
                    v_couple_code,
                    NULL, NULL,
                    now()
                );
                v_insert_ok := true;
            END IF;
        EXCEPTION WHEN unique_violation THEN
            v_insert_ok := false;
        END;
    END LOOP;

    IF NOT v_insert_ok THEN
        RAISE EXCEPTION 'COUPLE_CODE_GENERATE_FAILED';
    END IF;

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

SELECT '✅ register_user 函数已修复（加了 provider_id）' AS result;
