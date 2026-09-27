-- ============================================================================
-- 迁移：device_status 表新增 is_moving / foreground_package 字段
-- ----------------------------------------------------------------------------
-- 【背景】
-- 状态卡精细化：移动优先、小世界前台=在线、其他前台=亮屏、熄屏、离线。
-- 云端需要知道对方"是否在移动"和"前台 App 包名"，才能正确分级显示。
-- ============================================================================

alter table public.device_status
  add column if not exists is_moving boolean default false,          -- 是否正在移动（速度>0.5m/s 或位移>50m）
  add column if not exists foreground_package text;                   -- 前台 App 包名（小世界自身在前台时=本包名）

-- 让 PostgREST 立即重新加载 schema
notify pgrst, 'reload schema';
