-- ============================================================
--  配对逻辑 v4：简化版，确保可靠
-- ------------------------------------------------------------
--  本文件实现情侣 APP 的"配对"功能：
--  1. 用户 A 输入 用户 B 的配对码，向 B 发起配对请求
--  2. 用户 B 轮询查询自己是否被请求
--  3. 用户 B 同意后，双方正式建立配对关系
--
--  关键概念说明（面向初学者）：
--  - SECURITY DEFINER：函数以"函数所有者（通常是数据库管理员）"的
--    权限执行，而不是调用者权限。这样普通用户即使没有直接读写表
--    的权限，也能通过调用这些函数完成业务操作。常用于绕过 RLS。
--  - GRANT EXECUTE：把函数的执行权限授予指定角色。anon 表示未登录
--    的匿名用户，authenticated 表示已登录用户。
--  - RLS (Row Level Security)：行级安全策略，控制哪些行可以被
--    读/写。本系统通过 SECURITY DEFINER 绕过 RLS，保证配对流程安全。
--  - jsonb_build_object：PostgreSQL 内置函数，用于构造一个 JSON 对象
--    并返回 jsonb 类型。例如 jsonb_build_object('ok', true) 会生成
--    {"ok": true}，方便前端直接解析。
--  - search_path = public：指定函数执行时默认的 schema 查找路径，
--    避免被恶意 schema 覆盖（安全加固）。
-- ============================================================

-- 确保列存在
-- ADD COLUMN IF NOT EXISTS：如果列已存在则跳过，避免重复执行报错
-- timestamptz：带时区的时间戳类型，推荐使用，能正确处理跨时区时间
ALTER TABLE public.profiles ADD COLUMN IF NOT EXISTS pair_request_at timestamptz;  -- 记录配对请求发起时间，用于 30 秒防重复
ALTER TABLE public.profiles ADD COLUMN IF NOT EXISTS pending_pair uuid;            -- 记录"我"向谁发起了配对请求（存对方 user id）

-- ============================================================
-- ① 函数：pair_by_code
-- ------------------------------------------------------------
--  功能：用户 A 输入用户 B 的配对码（couple_code），向 B 发起配对请求。
--
--  参数：
--    - p_my_id       ：调用者（A）的 user id
--    - p_their_code  ：对方（B）的配对码
--
--  返回：jsonb 对象，包含 ok / reason / msg 等字段，前端据此渲染 UI。
--
--  业务流程：
--    1) 校验参数非空
--    2) 根据配对码找到对方记录（不区分大小写）
--    3) 拒绝自己配自己
--    4) 检查是否已经配对过（幂等返回）
--    5) 检查对方是否已被别人配走
--    6) 30 秒内重复请求 → 返回"已发送，等待中"
--    7) 写入 pending_pair + pair_request_at，表示请求已发起
--
--  关键点：
--    - SECURITY DEFINER 让函数绕过 RLS 直接访问 profiles 表
--    - lower(...) 让配对码大小写不敏感，提升用户体验
--    - 30 秒防重复：避免用户狂点按钮导致请求风暴
-- ============================================================
CREATE OR REPLACE FUNCTION public.pair_by_code(p_my_id uuid, p_their_code text)
RETURNS jsonb                       -- 返回 JSON 对象，前端可直接解析
LANGUAGE plpgsql                    -- 使用 PL/pgSQL 过程语言
SECURITY DEFINER                    -- 以函数所有者权限运行，绕过 RLS
SET search_path = public            -- 固定 schema 查找路径，安全加固
AS $$
DECLARE
    v_their_row  record;            -- 存对方的整行数据
    v_me_row     record;            -- 存自己的整行数据
    v_their_nickname text;          -- 存对方昵称
    v_elapsed    interval;          -- 距上次请求经过的时间
BEGIN
    -- 参数校验：任何一个为空或配对码全是空格都拒绝
    IF p_my_id IS NULL OR p_their_code IS NULL OR TRIM(p_their_code) = '' THEN
        RETURN jsonb_build_object('ok', false, 'reason', 'INVALID_ARGS');  -- 返回 {"ok": false, "reason": "INVALID_ARGS"}
    END IF;

    -- 查对方：lower() 做大小写不敏感匹配，TRIM() 去掉首尾空格
    -- LIMIT 1 防止意外有多条匹配（理论上 couple_code 是 unique 的）
    SELECT id, couple_code, partner_id, pending_pair, nickname INTO v_their_row
      FROM public.profiles WHERE lower(couple_code) = lower(TRIM(p_their_code)) LIMIT 1;

    -- 没找到对方：返回 CODE_NOT_FOUND
    IF v_their_row IS NULL OR v_their_row.id IS NULL THEN
        RETURN jsonb_build_object('ok', false, 'reason', 'CODE_NOT_FOUND');
    END IF;

    -- 防止自己配自己（输入了自己的码）
    IF v_their_row.id = p_my_id THEN
        RETURN jsonb_build_object('ok', false, 'reason', 'CANNOT_PAIR_SELF');
    END IF;

    -- 查自己：取自己的 partner_id / pending_pair / pair_request_at 用于后续判断
    SELECT id, partner_id, pending_pair, pair_request_at INTO v_me_row
      FROM public.profiles WHERE id = p_my_id;

    -- 自己不存在（理论不该发生，但防御性处理）
    IF v_me_row IS NULL OR v_me_row.id IS NULL THEN
        RETURN jsonb_build_object('ok', false, 'reason', 'ME_NOT_FOUND');
    END IF;

    -- 已配对：直接返回当前伴侣信息（幂等，多次调用结果一致）
    IF v_me_row.partner_id IS NOT NULL THEN
        SELECT nickname INTO v_their_nickname FROM public.profiles WHERE id = v_me_row.partner_id;
        RETURN jsonb_build_object(
            'ok', true,
            'already_paired', true,                                     -- 标记：已经配对过
            'their_id', v_me_row.partner_id::text,                      -- ::text 把 uuid 转字符串方便 JSON
            'their_nickname', v_their_nickname
        );
    END IF;

    -- 对方已配对别人：拒绝，因为对方已不是"可配对"状态
    IF v_their_row.partner_id IS NOT NULL AND v_their_row.partner_id != p_my_id THEN
        RETURN jsonb_build_object('ok', false, 'reason', 'THEY_ALREADY_PAIRED');
    END IF;

    -- 30秒防重复：如果 30 秒内已经发过请求，不再重复写入，直接返回"等待中"
    -- 目的：防止用户疯狂点击导致 pending_pair 被反复覆盖
    IF v_me_row.pending_pair IS NOT NULL AND v_me_row.pair_request_at IS NOT NULL THEN
        v_elapsed := now() - v_me_row.pair_request_at;                  -- 计算时间差
        IF v_elapsed < interval '30 seconds' THEN                        -- 不足 30 秒
            SELECT nickname INTO v_their_nickname FROM public.profiles WHERE id = v_me_row.pending_pair;
            RETURN jsonb_build_object(
                'ok', true,
                'waiting', true,                                         -- 标记：等待对方确认中
                'reason', 'ALREADY_SENT',
                'their_id', v_me_row.pending_pair::text,
                'their_nickname', v_their_nickname,
                'msg', '已发送配对请求，等待对方确认中'
            );
        END IF;
    END IF;

    -- 记录配对请求：把"我要配谁"写进自己的行
    -- pending_pair = 对方的 id；pair_request_at = 当前时间戳
    UPDATE public.profiles
       SET pending_pair = v_their_row.id, pair_request_at = now()
     WHERE id = p_my_id;

    -- 返回成功：告知前端"请求已发送"
    -- COALESCE(v_their_row.nickname, 'TA')：如果对方没设昵称，显示"TA"
    RETURN jsonb_build_object(
        'ok', true,
        'request_sent', true,                                            -- 标记：请求已发出
        'their_id', v_their_row.id::text,
        'their_nickname', v_their_row.nickname,
        'msg', '配对请求已发送给 ' || COALESCE(v_their_row.nickname, 'TA') || '，等待对方确认'
    );
END;
$$;
-- 把执行权限授予 anon（匿名用户）和 authenticated（已登录用户）
-- 这样前端可以直接通过 Supabase RPC 调用此函数
GRANT EXECUTE ON FUNCTION public.pair_by_code(uuid,text) TO anon, authenticated;

-- ============================================================
-- ② 函数：check_pair_status
-- ------------------------------------------------------------
--  功能：查询"我"当前的配对状态，前端通过轮询（如每 3 秒调用一次）
--       来获取最新状态并更新 UI。
--
--  参数：
--    - p_my_id ：调用者（我）的 user id
--
--  返回：jsonb 对象，status 字段取值如下：
--    - 'paired'           ：已配对成功，返回伴侣信息
--    - 'incoming_request' ：有人向我发起了配对请求，等待我同意
--    - 'waiting'          ：我已发出请求，正在等对方同意
--    - 'idle'             ：无任何配对相关状态
--    - 'error'            ：参数错误
--
--  关键点：
--    - 按优先级顺序判断：已配对 > 有人找我 > 我等对方 > 空闲
--    - 查询"谁向我发起请求"用 pending_pair = p_my_id 反向匹配
--      （因为 pair_by_code 中对方把我的 id 写入了他自己的 pending_pair）
-- ============================================================
CREATE OR REPLACE FUNCTION public.check_pair_status(p_my_id uuid)
RETURNS jsonb                       -- 返回 JSON 状态对象
LANGUAGE plpgsql
SECURITY DEFINER                    -- 绕过 RLS，保证可读到 profiles
SET search_path = public
AS $$
DECLARE
    v_partner_id   uuid;             -- 我的伴侣 id
    v_partner_nick text;             -- 伴侣昵称
    v_requester_id uuid;             -- 向我发起请求的人的 id
    v_requester_nick text;           -- 请求者昵称
    v_requester_code text;           -- 请求者的配对码
    v_requester_gender text;         -- 请求者性别
    v_requester_avatar text;         -- 请求者头像 URL
BEGIN
    -- 参数校验
    IF p_my_id IS NULL THEN
        RETURN jsonb_build_object('status', 'error', 'reason', 'INVALID_ARGS');
    END IF;

    -- ① 检查是否已配对（最高优先级）
    -- 如果 partner_id 不为空，说明已经和别人建立配对关系
    SELECT partner_id INTO v_partner_id FROM public.profiles WHERE id = p_my_id;
    IF v_partner_id IS NOT NULL THEN
        SELECT nickname INTO v_partner_nick FROM public.profiles WHERE id = v_partner_id;
        RETURN jsonb_build_object(
            'status', 'paired',
            'partner_id', v_partner_id::text,
            'partner_nickname', v_partner_nick
        );
    END IF;

    -- ② 检查是否有人向我发起配对请求
    -- 反向查找：谁的 pending_pair 等于我的 id，且对方还没和别人配对
    -- LIMIT 1：只取第一个请求者（如果有多人请求，按入库顺序）
    SELECT id, nickname, couple_code, gender, avatar
      INTO v_requester_id, v_requester_nick, v_requester_code, v_requester_gender, v_requester_avatar
      FROM public.profiles
     WHERE pending_pair = p_my_id
       AND partner_id IS NULL         -- 对方当前还单身
     LIMIT 1;

    -- 找到请求者：返回完整信息，前端可展示"XXX 想和你配对，是否同意？"
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

    -- ③ 检查我是否已发送请求（在等对方同意）
    -- 复用 v_requester_id 变量存"我发起请求的对象"
    SELECT pending_pair INTO v_requester_id FROM public.profiles WHERE id = p_my_id;
    IF v_requester_id IS NOT NULL THEN
        SELECT nickname INTO v_requester_nick FROM public.profiles WHERE id = v_requester_id;
        RETURN jsonb_build_object(
            'status', 'waiting',
            'their_id', v_requester_id::text,
            'their_nickname', v_requester_nick
        );
    END IF;

    -- ④ 以上都不满足：无任何配对状态，空闲
    RETURN jsonb_build_object('status', 'idle');
END;
$$;
-- 授予执行权限，让前端可以调用此函数做状态轮询
GRANT EXECUTE ON FUNCTION public.check_pair_status(uuid) TO anon, authenticated;

-- ============================================================
-- ③ 函数：accept_pair
-- ------------------------------------------------------------
--  功能：用户 B 同意 A 的配对请求，双方正式建立配对关系。
--       建立后 A 和 B 互相成为对方的 partner，并清空临时请求字段。
--
--  参数：
--    - p_my_id     ：同意者（B）的 user id
--    - p_their_id  ：请求者（A）的 user id
--
--  返回：jsonb 对象，包含 ok / paired / partner_id / partner_nickname / msg
--
--  关键点：
--    - 必须先验证对方确实向我发起过请求（pending_pair = p_my_id）
--      这是防止伪造请求的安全校验
--    - 两次 UPDATE 在同一事务内自动提交，不会出现"一边配上一边没配上"
--    - 配对完成后清空 pending_pair 和 pair_request_at，避免脏数据残留
-- ============================================================
CREATE OR REPLACE FUNCTION public.accept_pair(p_my_id uuid, p_their_id uuid)
RETURNS jsonb                       -- 返回 JSON 结果对象
LANGUAGE plpgsql
SECURITY DEFINER                    -- 绕过 RLS，确保可写两条 profiles 记录
SET search_path = public
AS $$
DECLARE
    v_nick text;                    -- 对方昵称
BEGIN
    -- 参数校验：两个 id 都必须有
    IF p_my_id IS NULL OR p_their_id IS NULL THEN
        RETURN jsonb_build_object('ok', false, 'reason', 'INVALID_ARGS');
    END IF;

    -- 安全校验：对方确实向我发起过配对请求
    -- 即对方记录里 pending_pair = 我的 id；否则拒绝（防止伪造）
    IF NOT EXISTS (
        SELECT 1 FROM public.profiles
         WHERE id = p_their_id AND pending_pair = p_my_id
    ) THEN
        RETURN jsonb_build_object('ok', false, 'reason', 'NO_PENDING_REQUEST');
    END IF;

    -- ① 更新自己：把 partner_id 设为对方，并清空临时字段
    UPDATE public.profiles
       SET partner_id = p_their_id, pending_pair = NULL, pair_request_at = NULL
     WHERE id = p_my_id;

    -- ② 更新对方：把 partner_id 设为我，并清空对方记录里的请求字段
    --   （对方的 pending_pair 原本就指向我，这里清掉）
    UPDATE public.profiles
       SET partner_id = p_my_id, pending_pair = NULL, pair_request_at = NULL
     WHERE id = p_their_id;

    -- 取对方昵称用于返回
    SELECT nickname INTO v_nick FROM public.profiles WHERE id = p_their_id;

    -- 返回成功结果
    RETURN jsonb_build_object(
        'ok', true,
        'paired', true,                                                  -- 标记：已成功配对
        'partner_id', p_their_id::text,
        'partner_nickname', v_nick,
        'msg', '配对成功！'
    );
END;
$$;
-- 授予执行权限，让 B 用户可以调用此函数同意请求
GRANT EXECUTE ON FUNCTION public.accept_pair(uuid, uuid) TO anon, authenticated;

-- 部署成功后输出一条结果消息，可在 SQL Editor 中看到
-- 用于确认整个脚本已执行完成
SELECT '✅ 配对逻辑 v4 已部署' AS result;
