-- Walhad Glucose Scan: the online copy of the glucose values.
--
-- Run this once in the "SQL Editor" of your Supabase project.
-- Running it again does no harm: nothing that is there is removed.

create table if not exists public.glucose_values (
    user_id  uuid        not null default auth.uid() references auth.users (id) on delete cascade,
    source   smallint    not null,            -- 0 = read from the sensor by the app, 1 = from LibreLinkUp
    time_ms  bigint      not null,            -- milliseconds since 1970 (UTC), the start of a minute
    mgdl     integer     not null,            -- glucose in mg/dL
    kind     smallint    not null default 0,  -- 0 = one-minute value, 1 = 15-minute value
    change   real,                            -- mg/dL per minute against the value before, or empty
    sensor   text,                            -- serial number of the sensor, or empty
    saved_at timestamptz not null default now(),
    primary key (user_id, source, time_ms)
);

-- Each row belongs to the person who is logged in. Nobody else can read or change it.
alter table public.glucose_values enable row level security;

revoke all on table public.glucose_values from anon;
grant select, insert, update, delete on table public.glucose_values to authenticated;

drop policy if exists "own rows: read" on public.glucose_values;
create policy "own rows: read" on public.glucose_values
    for select to authenticated using ((select auth.uid()) = user_id);

drop policy if exists "own rows: add" on public.glucose_values;
create policy "own rows: add" on public.glucose_values
    for insert to authenticated with check ((select auth.uid()) = user_id);

drop policy if exists "own rows: change" on public.glucose_values;
create policy "own rows: change" on public.glucose_values
    for update to authenticated using ((select auth.uid()) = user_id) with check ((select auth.uid()) = user_id);

drop policy if exists "own rows: remove" on public.glucose_values;
create policy "own rows: remove" on public.glucose_values
    for delete to authenticated using ((select auth.uid()) = user_id);
