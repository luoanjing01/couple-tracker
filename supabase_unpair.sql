-- ============================================================
--  取消配对函数 v1：单方面取消，双方 partner_id 都清空
-- ------------------------------------------------------------
--  使用场景：
--  - 任一方在 App「我的」页点「取消配对」按钮
--  - 双方 partner_id 都被清空，恢复到未配对状态
--  - 同时清空 pending_pair / pair_request_at，避免遗留脏数据
--
--  安全说明：
--  - SECURITY DEFINER 绕过 RLS，确保可以更新对方记录
--  - SET search_path = public 防止 schema 注入
--  - GRANT EXECUTE 让匿名/已登录用户均可调用
-- ============================================================

-- 取消配对函数
CREATE OR REPLACE FUNCTION public.unpair(p_my_id uuid)
RETURNS jsonb                       -- 返回 JSON 结果对象
LANGUAGE plpgsql
SECURITY DEFINER                    -- 绕过 RLS，确保可写两条 profiles 记录
SET search_path = public
AS $$
DECLARE
    v_partner_id uuid;              -- 当前用户的伴侣 ID
BEGIN
    -- 参数校验
    IF p_my_id IS NULL THEN
        RETURN jsonb_build_object('ok', false, 'reason', 'INVALID_ARGS');
    END IF;

    -- 查出自己的 partner_id
    SELECT partner_id INTO v_partner_id FROM public.profiles WHERE id = p_my_id;

    -- 若当前用户记录不存在或本就未配对，直接返回（无需取消）
    IF v_partner_id IS NULL THEN
        RETURN jsonb_build_object('ok', false, 'reason', 'NOT_PAIRED', 'msg', '当前未配对，无需取消');
    END IF;

    -- ① 清空自己的配对相关字段
    UPDATE public.profiles
       SET partner_id    = NULL,
           pending_pair  = NULL,
           pair_request_at = NULL
     WHERE id = p_my_id;

    -- ② 清空对方的配对相关字段（单方取消 -> 双方都解除）
    UPDATE public.profiles
       SET partner_id    = NULL,
           pending_pair  = NULL,
           pair_request_at = NULL
     WHERE id = v_partner_id;

    -- 返回成功结果
    RETURN jsonb_build_object(
        'ok', true,
        'msg', '配对已解除',
        'partner_id', v_partner_id::text
    );
END;
$$;

-- 授予执行权限，让 App 端可以调用此函数
GRANT EXECUTE ON FUNCTION public.unpair(uuid) TO anon, authenticated;
