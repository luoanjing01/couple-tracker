-- ==============================================
-- 情侣报备系统 - Supabase 数据库 schema
-- 在 Supabase Dashboard → SQL Editor 里执行
-- ==============================================

-- ① 用户资料表（扩展 Supabase Auth 的 profiles）
create table if not exists public.profiles (
  id uuid primary key references auth.users(id) on delete cascade,
  username text unique not null,
  nickname text default '',
  avatar text default '',
  gender text default 'unknown',
  couple_code text unique,
  couple_id uuid references public.couples(id) on delete set null,
  created_at timestamptz default now()
);

-- ② 情侣表
create table if not exists public.couples (
  id uuid primary key default gen_random_uuid(),
  code text unique not null,
  user_a uuid not null references public.profiles(id) on delete cascade,
  user_b uuid references public.profiles(id) on delete set null,
  created_at timestamptz default now()
);

-- ③ 位置上报表
create table if not exists public.locations (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references public.profiles(id) on delete cascade,
  couple_id uuid not null references public.couples(id) on delete cascade,
  latitude double precision not null,
  longitude double precision not null,
  accuracy double precision,
  speed double precision,
  battery_level integer,
  is_moving boolean default false,
  created_at timestamptz default now()
);

-- ④ APP 使用时长表（每 5 分钟汇总一次）
create table if not exists public.app_usage (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references public.profiles(id) on delete cascade,
  couple_id uuid not null references public.couples(id) on delete cascade,
  package_name text not null,
  app_name text,
  category text,
  usage_seconds integer default 0,
  window_start timestamptz default now(),
  created_at timestamptz default now()
);

-- ==============================================
-- 开启 Realtime（让位置/APP变化实时推送）
-- ==============================================
alter publication supabase_realtime add table public.locations;
alter publication supabase_realtime add table public.app_usage;

-- ==============================================
-- 关闭 RLS（开发阶段先放开，上线再加权限）
-- ==============================================
alter table public.profiles enable row level security;
drop policy if exists "profiles_all" on public.profiles;
create policy "profiles_all" on public.profiles for all using (true) with check (true);

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
create or replace function public.generate_couple_code()
returns trigger as $$
declare
  chars text := 'ABCDEFGHJKMNPQRSTUVWXYZ23456789';
  result text := '';
begin
  for i in 1..6 loop
    result := result || substr(chars, (floor(random() * length(chars)) + 1)::int, 1);
  end loop;
  -- 如果已存在则重试
  while exists (select 1 from public.profiles where couple_code = result) loop
    result := '';
    for i in 1..6 loop
      result := result || substr(chars, (floor(random() * length(chars)) + 1)::int, 1);
    end loop;
  end loop;
  new.couple_code := result;
  return new;
end;
$$ language plpgsql;

drop trigger if exists set_couple_code on public.profiles;
create trigger set_couple_code
  before insert on public.profiles
  for each row
  when (new.couple_code is null)
  execute function public.generate_couple_code();

-- ==============================================
-- 创建 Supabase Auth 注册时自动插入 profiles
-- ==============================================
create or replace function public.handle_new_user()
returns trigger as $$
begin
  insert into public.profiles (id, username, nickname, couple_code)
  values (
    new.id,
    coalesce(new.raw_user_meta_data->>'username', 'user_' || substr(new.id::text, 1, 8)),
    coalesce(new.raw_user_meta_data->>'nickname', ''),
    null  -- trigger 会自动生成
  );
  return new;
end;
$$ language plpgsql security definer;

drop trigger if exists on_auth_user_created on auth.users;
create trigger on_auth_user_created
  after insert on auth.users
  for each row execute function public.handle_new_user();

-- ==============================================
-- 创建索引（提升查询速度）
-- ==============================================
create index if not exists idx_locations_user on public.locations(user_id);
create index if not exists idx_locations_couple_time on public.locations(couple_id, created_at desc);
create index if not exists idx_app_usage_user_time on public.app_usage(user_id, created_at desc);
create index if not exists idx_profiles_couple_code on public.profiles(couple_code);
