-- ============================================================
--  配对逻辑 v4：简化版，确保可靠
-- ============================================================

-- 确保列存在
ALTER TABLE public.profiles ADD COLUMN IF NOT EXISTS pair_request_at timestamptz;
ALTER TABLE public.profiles ADD COLUMN IF NOT EXISTS pending_pair uuid;

-- ① pair_by_code：A 输入 B 的码 → 记录请求
CREATE OR REPLACE FUNCTION public.pair_by_code(p_my_id uuid, p_their_code text)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_their_row  record;
    v_me_row     record;
    v_their_nickname text;
    v_elapsed    interval;
BEGIN
    IF p_my_id IS NULL OR p_their_code IS NULL OR TRIM(p_their_code) = '' THEN
        RETURN jsonb_build_object('ok', false, 'reason', 'INVALID_ARGS');
    END IF;

    -- 查对方
    SELECT id, couple_code, partner_id, pending_pair, nickname INTO v_their_row
      FROM public.profiles WHERE lower(couple_code) = lower(TRIM(p_their_code)) LIMIT 1;

    IF v_their_row IS NULL OR v_their_row.id IS NULL THEN
        RETURN jsonb_build_object('ok', false, 'reason', 'CODE_NOT_FOUND');
    END IF;

    IF v_their_row.id = p_my_id THEN
        RETURN jsonb_build_object('ok', false, 'reason', 'CANNOT_PAIR_SELF');
    END IF;

    -- 查自己
    SELECT id, partner_id, pending_pair, pair_request_at INTO v_me_row
      FROM public.profiles WHERE id = p_my_id;

    IF v_me_row IS NULL OR v_me_row.id IS NULL THEN
        RETURN jsonb_build_object('ok', false, 'reason', 'ME_NOT_FOUND');
    END IF;

    -- 已配对
    IF v_me_row.partner_id IS NOT NULL THEN
        SELECT nickname INTO v_their_nickname FROM public.profiles WHERE id = v_me_row.partner_id;
        RETURN jsonb_build_object(
            'ok', true,
            'already_paired', true,
            'their_id', v_me_row.partner_id::text,
            'their_nickname', v_their_nickname
        );
    END IF;

    -- 对方已配对别人
    IF v_their_row.partner_id IS NOT NULL AND v_their_row.partner_id != p_my_id THEN
        RETURN jsonb_build_object('ok', false, 'reason', 'THEY_ALREADY_PAIRED');
    END IF;

    -- 30秒防重复
    IF v_me_row.pending_pair IS NOT NULL AND v_me_row.pair_request_at IS NOT NULL THEN
        v_elapsed := now() - v_me_row.pair_request_at;
        IF v_elapsed < interval '30 seconds' THEN
            SELECT nickname INTO v_their_nickname FROM public.profiles WHERE id = v_me_row.pending_pair;
            RETURN jsonb_build_object(
                'ok', true,
                'waiting', true,
                'reason', 'ALREADY_SENT',
                'their_id', v_me_row.pending_pair::text,
                'their_nickname', v_their_nickname,
                'msg', '已发送配对请求，等待对方确认中'
            );
        END IF;
    END IF;

    -- 记录配对请求
    UPDATE public.profiles
       SET pending_pair = v_their_row.id, pair_request_at = now()
     WHERE id = p_my_id;

    RETURN jsonb_build_object(
        'ok', true,
        'request_sent', true,
        'their_id', v_their_row.id::text,
        'their_nickname', v_their_row.nickname,
        'msg', '配对请求已发送给 ' || COALESCE(v_their_row.nickname, 'TA') || '，等待对方确认'
    );
END;
$$;
GRANT EXECUTE ON FUNCTION public.pair_by_code(uuid,text) TO anon, authenticated;

-- ② check_pair_status：轮询配对状态
CREATE OR REPLACE FUNCTION public.check_pair_status(p_my_id uuid)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_partner_id   uuid;
    v_partner_nick text;
    v_requester_id uuid;
    v_requester_nick text;
    v_requester_code text;
    v_requester_gender text;
    v_requester_avatar text;
BEGIN
    IF p_my_id IS NULL THEN
        RETURN jsonb_build_object('status', 'error', 'reason', 'INVALID_ARGS');
    END IF;

    -- 检查是否已配对
    SELECT partner_id INTO v_partner_id FROM public.profiles WHERE id = p_my_id;
    IF v_partner_id IS NOT NULL THEN
        SELECT nickname INTO v_partner_nick FROM public.profiles WHERE id = v_partner_id;
        RETURN jsonb_build_object(
            'status', 'paired',
            'partner_id', v_partner_id::text,
            'partner_nickname', v_partner_nick
        );
    END IF;

    -- 检查是否有人向我发起配对请求
    SELECT id, nickname, couple_code, gender, avatar
      INTO v_requester_id, v_requester_nick, v_requester_code, v_requester_gender, v_requester_avatar
      FROM public.profiles
     WHERE pending_pair = p_my_id
       AND partner_id IS NULL
     LIMIT 1;

    IF v_requester_id IS NOT NULL THEN
        RETURN jsonb_build_object(
            'status', 'incoming_request',
            'requester_id', v_requester_id::text,
            'requester_nickname', v_requester_nick,
            'requester_code', v_requester_code,
            'requester_gender', v_requester_gender,
            'requester_avatar', v_requester_avatar
        );
    END IF;

    -- 检查我是否已发送请求
    SELECT pending_pair INTO v_requester_id FROM public.profiles WHERE id = p_my_id;
    IF v_requester_id IS NOT NULL THEN
        SELECT nickname INTO v_requester_nick FROM public.profiles WHERE id = v_requester_id;
        RETURN jsonb_build_object(
            'status', 'waiting',
            'their_id', v_requester_id::text,
            'their_nickname', v_requester_nick
        );
    END IF;

    RETURN jsonb_build_object('status', 'idle');
END;
$$;
GRANT EXECUTE ON FUNCTION public.check_pair_status(uuid) TO anon, authenticated;

-- ③ accept_pair：B 确认配对 → 双方同时配对成功
CREATE OR REPLACE FUNCTION public.accept_pair(p_my_id uuid, p_their_id uuid)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_nick text;
BEGIN
    IF p_my_id IS NULL OR p_their_id IS NULL THEN
        RETURN jsonb_build_object('ok', false, 'reason', 'INVALID_ARGS');
    END IF;

    -- 验证对方确实向我发起过请求
    IF NOT EXISTS (
        SELECT 1 FROM public.profiles
         WHERE id = p_their_id AND pending_pair = p_my_id
    ) THEN
        RETURN jsonb_build_object('ok', false, 'reason', 'NO_PENDING_REQUEST');
    END IF;

    -- 双方同时设置 partner_id
    UPDATE public.profiles
       SET partner_id = p_their_id, pending_pair = NULL, pair_request_at = NULL
     WHERE id = p_my_id;

    UPDATE public.profiles
       SET partner_id = p_my_id, pending_pair = NULL, pair_request_at = NULL
     WHERE id = p_their_id;

    SELECT nickname INTO v_nick FROM public.profiles WHERE id = p_their_id;

    RETURN jsonb_build_object(
        'ok', true,
        'paired', true,
        'partner_id', p_their_id::text,
        'partner_nickname', v_nick,
        'msg', '配对成功！'
    );
END;
$$;
GRANT EXECUTE ON FUNCTION public.accept_pair(uuid, uuid) TO anon, authenticated;

SELECT '✅ 配对逻辑 v4 已部署' AS result;
