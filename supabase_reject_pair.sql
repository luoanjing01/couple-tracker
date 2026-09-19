-- ============================================================
--  拒绝配对函数 v1：清掉对方发起的 pending_pair 请求
-- ------------------------------------------------------------
--  使用场景：
--  - B 收到 A 的配对请求弹窗，点「拒绝」按钮
--  - 后端把 A 的 pending_pair / pair_request_at 清空
--  - B 下次轮询 check_pair_status 第②步再也查不到 A 的请求
--    （因为 A 的 pending_pair 已为 NULL），不再重复弹窗
--
--  成熟方案参考：
--  - 腾讯 IM `refuseFriendApplication`：拒绝后双方收到
--    onFriendApplicationListDeleted 回调，申请记录从列表删除
--  - CSDN 微服务好友管理：处理申请（同意/拒绝）后从
--    friend_apply 表删除该申请记录
--  共同点：拒绝 = 删掉 pending 记录，否则下次拉列表又会拉到
--
--  安全说明：
--  - SECURITY DEFINER 绕过 RLS，确保可写对方记录
--  - 校验 pending_pair = p_my_id，防止伪造他人请求
-- ============================================================

-- 拒绝配对函数
CREATE OR REPLACE FUNCTION public.reject_pair(p_my_id uuid, p_their_id uuid)
RETURNS jsonb                       -- 返回 JSON 结果对象
LANGUAGE plpgsql
SECURITY DEFINER                    -- 绕过 RLS，确保可写对方 profiles 记录
SET search_path = public
AS $$
BEGIN
    -- 参数校验
    IF p_my_id IS NULL OR p_their_id IS NULL THEN
        RETURN jsonb_build_object('ok', false, 'reason', 'INVALID_ARGS');
    END IF;

    -- 安全校验：对方确实向我发起过配对请求（pending_pair = 我的 id）
    -- 防止伪造他人请求
    IF NOT EXISTS (
        SELECT 1 FROM public.profiles
         WHERE id = p_their_id AND pending_pair = p_my_id
    ) THEN
        RETURN jsonb_build_object('ok', false, 'reason', 'NO_PENDING_REQUEST', 'msg', '没有待处理的配对请求');
    END IF;

    -- 清空对方的 pending_pair / pair_request_at
    -- （拒绝 = 删掉这条请求记录，A 下次轮询会显示 idle，B 下次轮询也查不到 incoming_request）
    UPDATE public.profiles
       SET pending_pair    = NULL,
           pair_request_at = NULL
     WHERE id = p_their_id;

    -- 返回成功结果
    RETURN jsonb_build_object(
        'ok', true,
        'msg', '已拒绝配对请求'
    );
END;
$$;

-- 授予执行权限，让 App 端可以调用此函数
GRANT EXECUTE ON FUNCTION public.reject_pair(uuid, uuid) TO anon, authenticated;
