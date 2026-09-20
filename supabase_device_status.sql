-- ============================================================================
-- 迁移：新增 device_status 设备状态心跳表
-- ----------------------------------------------------------------------------
-- 【背景】
-- 旧版「应用页-手机状态」通过 locations 表最后一条记录的时间戳推断对方是否在线：
--   - 后台定位服务被国产 ROM 杀掉 / 手机静止不上报位置 → 超过 5 分钟就误显示"关机"
--   - 云端没有网络类型 / WiFi SSID 字段 → 对方网络永远显示"云端未记录"
--
-- 【行业做法】（Life360 / Zenly 等情侣/家庭定位 App 的通用方案）
-- 设备状态与位置上报解耦：客户端以 60 秒心跳 + 关键状态变化（充电插拔、
-- 网络切换、亮熄屏）即时上报的方式，向 device_status 表 upsert 自己的
-- 最新状态（每用户一行）。查询端按 updated_at 距今时长分级显示：
--   < 2 分钟 → 在线；< 30 分钟 → X 分钟前在线；否则 → 离线。
-- ============================================================================

-- ① 设备状态表：每个用户一行（user_id 主键），客户端 upsert 覆盖最新状态
create table if not exists public.device_status (
  user_id uuid primary key references public.profiles(id) on delete cascade, -- 用户 id，同时作主键（一人一行）
  battery_level integer,                                  -- 电量百分比 0-100
  is_charging boolean default false,                      -- 是否在充电
  network_type text,                                      -- 网络类型：wifi / cellular / none
  wifi_ssid text,                                         -- WiFi 名称（蜂窝/无网络时为 null）
  screen_on boolean default true,                         -- 屏幕是否点亮
  updated_at timestamptz default now()                    -- 客户端心跳时间（客户端填 UTC 时间）
);

-- ② RLS 策略：与项目现有表保持一致（开发阶段全放开，上线前再收紧）
alter table public.device_status enable row level security;
drop policy if exists "device_status_all" on public.device_status;
create policy "device_status_all" on public.device_status
  for all using (true) with check (true);

-- ③ 让 PostgREST 立即重新加载 schema（不执行这一步，新表接口要等缓存过期才可用）
notify pgrst, 'reload schema';
