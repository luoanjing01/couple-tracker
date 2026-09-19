-- ==============================================
-- 情侣报备系统 - Supabase 数据库 schema
-- 在 Supabase Dashboard → SQL Editor 里执行
-- ==============================================
-- 本文件创建情侣报备系统所需的所有数据库对象，包括：
--   ① 4 张业务表：profiles（用户）、couples（情侣）、locations（位置）、app_usage（APP 用时）
--   ② Realtime 实时推送配置
--   ③ RLS 行级安全策略
--   ④ 2 个触发器函数：自动生成配对码、新用户自动建 profile
--   ⑤ 索引（提升查询性能）
--
-- 关键概念（面向初学者）：
--   - uuid：128 位唯一标识，比自增 id 更适合分布式系统，不会泄露用户量
--   - timestamptz：带时区的时间戳，存 UTC 时间，读取时自动转本地时区
--   - references / 外键：建立表与表之间的引用关系，保证数据一致性
--   - on delete cascade：被引用的行删除时，引用它的行也自动删除
--   - on delete set null：被引用的行删除时，引用它的字段置为 NULL
--   - gen_random_uuid()：PostgreSQL 内置函数，生成随机 UUID v4
--   - publication supabase_realtime：Supabase 的实时推送通道，
--     加入此 publication 的表，其增删改会通过 WebSocket 推送到前端
--   - RLS (Row Level Security)：行级安全，决定"哪些行能被读/写"
--   - policy：RLS 的具体规则，using 控制读，with check 控制写
--   - trigger：在表上发生指定事件（INSERT/UPDATE/DELETE）时自动执行的函数
--   - security definer：函数以"所有者权限"而非"调用者权限"运行
-- ==============================================

-- ① 用户资料表（扩展 Supabase Auth 的 profiles）
-- Supabase 的 auth.users 存的是认证信息（邮箱、密码哈希等），
-- 业务字段（昵称、头像、配对码等）放在 public.profiles 里，
-- 通过 id 外键关联到 auth.users，保证一对一关系。
create table if not exists public.profiles (
  id uuid primary key references auth.users(id) on delete cascade,  -- 主键，同时是 auth.users 的外键；on delete cascade 表示账号删除时 profile 也删除
  username text unique not null,                                     -- 用户名，唯一，注册时必填
  nickname text default '',                                          -- 昵称，可空，默认空字符串
  avatar text default '',                                            -- 头像 URL
  gender text default 'unknown',                                     -- 性别：male/female/unknown
  couple_code text unique,                                           -- 配对码：6 位随机字符，用于情侣配对，唯一
  couple_id uuid references public.couples(id) on delete set null,   -- 所属情侣关系 id，未配对时为 NULL；情侣记录删除时此字段置 NULL
  created_at timestamptz default now()                                -- 创建时间，默认当前时间
);

-- ② 情侣表
-- 一条记录代表一对情侣关系。建立配对后，会插入一条 couple 记录，
-- 两个用户的 couple_id 都指向这条记录。
create table if not exists public.couples (
  id uuid primary key default gen_random_uuid(),                     -- 主键，自动生成随机 UUID
  code text unique not null,                                          -- 情侣唯一码，可作为分享标识
  user_a uuid not null references public.profiles(id) on delete cascade,  -- 发起者（A），必填；A 删除时此行也删除
  user_b uuid references public.profiles(id) on delete set null,     -- 另一方（B），可空；B 删除时此字段置 NULL
  created_at timestamptz default now()                                 -- 创建时间
);

-- ③ 位置上报表
-- 客户端定时（如每 30 秒）上报 GPS 位置，写入此表。
-- 通过 Realtime 订阅，伴侣端可以实时看到对方位置移动。
create table if not exists public.locations (
  id uuid primary key default gen_random_uuid(),                      -- 主键
  user_id uuid not null references public.profiles(id) on delete cascade,  -- 上报者 id；用户删除时其位置数据级联删除
  couple_id uuid not null references public.couples(id) on delete cascade, -- 所属情侣关系，用于按情侣维度查询轨迹
  latitude double precision not null,                                 -- 纬度，必填（GPS 核心字段）
  longitude double precision not null,                                -- 经度，必填
  accuracy double precision,                                          -- 定位精度（米），数值越小越准
  speed double precision,                                             -- 移动速度（米/秒）
  battery_level integer,                                              -- 电量百分比 0-100
  is_moving boolean default false,                                    -- 是否在移动中
  created_at timestamptz default now()                                  -- 上报时间
);

-- ④ APP 使用时长表（每 5 分钟汇总一次）
-- 客户端每隔 5 分钟汇总一次各 APP 的使用时长，写入此表。
-- 伴侣端可以查看对方今天用了哪些 APP、各用了多久。
create table if not exists public.app_usage (
  id uuid primary key default gen_random_uuid(),                      -- 主键
  user_id uuid not null references public.profiles(id) on delete cascade,  -- 使用者 id
  couple_id uuid not null references public.couples(id) on delete cascade, -- 所属情侣关系
  package_name text not null,                                         -- APP 包名（如 com.tencent.mm），用于唯一标识 APP
  app_name text,                                                      -- APP 显示名（如"微信"）
  category text,                                                      -- APP 分类（如"社交""游戏"）
  usage_seconds integer default 0,                                    -- 这段时间内使用秒数
  window_start timestamptz default now(),                             -- 统计窗口起始时间
  created_at timestamptz default now()                                  -- 记录创建时间
);

-- ==============================================
-- 开启 Realtime（让位置/APP变化实时推送）
-- ==============================================
-- Supabase 通过 PostgreSQL 的 publication 机制实现实时推送：
-- 把表加入 supabase_realtime publication 后，前端可以通过
-- .channel()/.on('postgres_changes') 订阅该表的增删改事件，
-- 无需轮询即可实时刷新 UI。
-- 注意：加入 Realtime 的表必须有主键，且建议开启 RLS，
-- 否则任何客户端订阅都能收到全表变更。
alter publication supabase_realtime add table public.locations;     -- 位置变化实时推送
alter publication supabase_realtime add table public.app_usage;     -- APP 用时变化实时推送

-- ==============================================
-- 关闭 RLS（开发阶段先放开，上线再加权限）
-- ==============================================
-- RLS (Row Level Security) 行级安全：控制"哪些行能被当前用户读/写"。
-- enable row level security 只是"开启 RLS 开关"，具体规则靠 policy 定义。
--
-- 这里采用"开发阶段全放开"的策略：
--   - using (true)   ：允许读所有行（无过滤条件）
--   - with check (true)：允许写所有行（无校验条件）
--   - for all         ：对 INSERT/UPDATE/DELETE 三种操作都生效
--
-- 上线前应改成更严格的策略，例如"用户只能读写自己 couple_id 的数据"。
-- drop policy if exists：先删旧策略避免重复创建报错。
alter table public.profiles enable row level security;             -- 开启 RLS 开关
drop policy if exists "profiles_all" on public.profiles;            -- 删除旧策略（若存在）
create policy "profiles_all" on public.profiles for all using (true) with check (true);  -- 允许所有读写

alter table public.couples enable row level security;
drop policy if exists "couples_all" on public.couples;
create policy "couples_all" on public.couples for all using (true) with check (true);

alter table public.locations enable row level security;
drop policy if exists "locations_all" on public.locations;
create policy "locations_all" on public.locations for all using (true) with check (true);

alter table public.app_usage enable row level security;
drop policy if exists "app_usage_all" on public.app_usage;
create policy "app_usage_all" on public.app_usage for all using (true) with check (true);

-- ==============================================
-- 自动生成 6 位配对码（注册时触发）
-- ==============================================
-- 这是一个 trigger 函数，在插入 profiles 之前自动执行。
-- 它会生成一个 6 位随机字符串作为配对码（couple_code）。
--
-- 字符集刻意去掉了容易混淆的字符：
--   - 没有 I、O、L：因为大写 I 像 1，O 像 0，L 像 1
--   - 没有 0、1：因为 0 像 O，1 像 I
--   只保留 ABCDEFGHJKMNPQRSTUVWXYZ23456789，减少用户输错概率。
--
-- 函数返回类型为 trigger：表示这是一个触发器函数，
-- 必须返回 NEW（要插入的行）或 NULL（阻止插入）。
create or replace function public.generate_couple_code()
returns trigger as $$
declare
  chars text := 'ABCDEFGHJKMNPQRSTUVWXYZ23456789';   -- 可用字符集（去掉了易混淆字符）
  result text := '';                                  -- 生成的配对码
begin
  -- 循环 6 次，每次随机取一个字符拼接
  -- floor(random() * length(chars))：产生 0 ~ length-1 的随机整数
  -- +1：PostgreSQL substr 下标从 1 开始
  -- ::int：把 numeric 转成整数
  for i in 1..6 loop
    result := result || substr(chars, (floor(random() * length(chars)) + 1)::int, 1);
  end loop;

  -- 如果生成的码已在表中存在（虽然概率极低），重新生成
  -- 这是一个 do-while 风格循环，保证最终拿到唯一码
  while exists (select 1 from public.profiles where couple_code = result) loop
    result := '';
    for i in 1..6 loop
      result := result || substr(chars, (floor(random() * length(chars)) + 1)::int, 1);
    end loop;
  end loop;

  new.couple_code := result;                           -- 把生成结果写入待插入行的字段
  return new;                                          -- 返回 NEW 表示继续插入
end;
$$ language plpgsql;

-- 触发器：在 profiles INSERT 之前触发
-- before insert：在数据真正写入表之前执行，可以修改 NEW
-- for each row：对每一行受影响的记录都触发一次
-- when (new.couple_code is null)：仅当用户没主动指定配对码时才生成
--   （用户也可以自己传配对码，那就尊重用户的值）
drop trigger if exists set_couple_code on public.profiles;   -- 先删旧触发器避免冲突
create trigger set_couple_code
  before insert on public.profiles
  for each row
  when (new.couple_code is null)
  execute function public.generate_couple_code();

-- ==============================================
-- 创建 Supabase Auth 注册时自动插入 profiles
-- ==============================================
-- Supabase 的 auth.users 表只管认证（账号、邮箱、密码），
-- 业务数据需要我们自己写入 public.profiles。
-- 这个 trigger 函数监听 auth.users 的 INSERT 事件，
-- 当新用户注册时，自动在 profiles 表插入一条对应记录。
--
-- security definer：以函数所有者（管理员）权限运行，
-- 因为普通用户对 auth.users 没有写权限，更对 profiles 没有写权限，
-- 必须提权才能自动插入。
create or replace function public.handle_new_user()
returns trigger as $$
begin
  -- 插入一条 profiles 记录，主键复用 auth.users 的 id
  insert into public.profiles (id, username, nickname, couple_code)
  values (
    new.id,                                                           -- 复用 auth.users 的新用户 id
    -- 用户名：优先用注册时传的 username，否则用 'user_' + id 前 8 位兜底
    coalesce(new.raw_user_meta_data->>'username', 'user_' || substr(new.id::text, 1, 8)),
    -- 昵称：优先用注册时传的 nickname，没传则空字符串
    coalesce(new.raw_user_meta_data->>'nickname', ''),
    -- raw_user_meta_data：Supabase 在 signUp 时可传入的元数据（JSON）
    -- ->>'username'：从 JSON 中提取 username 字段，返回 text 类型
    null  -- couple_code 留空，会由 set_couple_code 触发器自动生成
  );
  return new;                                                          -- 返回 NEW，让 INSERT 正常完成
end;
$$ language plpgsql security definer;                                  -- 提权运行

-- 触发器：监听 auth.users 表的新增事件
-- after insert：在用户记录写入 auth.users 之后触发
--   （用 after 而不是 before，因为需要 auth.users 的 id 已生成）
-- for each row：每插入一行（一个新用户）触发一次
drop trigger if exists on_auth_user_created on auth.users;
create trigger on_auth_user_created
  after insert on auth.users
  for each row execute function public.handle_new_user();

-- ==============================================
-- 创建索引（提升查询速度）
-- ==============================================
-- 索引（Index）类似书的目录：让数据库不必扫全表就能快速定位数据。
-- 如果没有索引，查询 WHERE user_id = 'xxx' 时会扫描整张表（全表扫描），
-- 数据量大时性能极差；有了索引，查询可以走"目录"快速定位。
--
-- if not exists：如果索引已存在则跳过，避免重复创建报错。
-- 复合索引 (a, b)：支持 a 查询、a+b 查询，但不支持单独 b 查询。
-- desc：按降序排列，配合 ORDER BY ... DESC 可避免反向扫描。
-- 使用规则：在常用于 WHERE / JOIN / ORDER BY 的列上建索引。

-- 单列索引：按 user_id 查询某用户的位置记录（最常见查询）
create index if not exists idx_locations_user on public.locations(user_id);

-- 复合索引：按 couple_id + created_at 查询某情侣最近的位置轨迹
-- 顺序很关键：先按 couple_id 过滤，再按 created_at desc 排序
-- 适配场景：SELECT * FROM locations WHERE couple_id=? ORDER BY created_at DESC
create index if not exists idx_locations_couple_time on public.locations(couple_id, created_at desc);

-- 复合索引：按 user_id + created_at 查询某用户某段时间的 APP 使用记录
create index if not exists idx_app_usage_user_time on public.app_usage(user_id, created_at desc);

-- 单列索引：按 couple_code 查询用户（配对时使用，配对码查询非常频繁）
-- 虽然 couple_code 上有 unique 约束（会自动建索引），但显式创建更清晰
create index if not exists idx_profiles_couple_code on public.profiles(couple_code);
