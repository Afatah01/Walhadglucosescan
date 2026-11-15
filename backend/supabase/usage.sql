-- The table that counts the questions asked through the AI door.
-- Run this once in the SQL editor of your Supabase project.

create table if not exists public.walhad_ai_usage (
  id         bigint generated always as identity primary key,
  app_key    text        not null,          -- a short fingerprint, never the key itself
  model      text        not null,
  asked_at   timestamptz not null default now()
);

create index if not exists walhad_ai_usage_day
  on public.walhad_ai_usage (app_key, asked_at desc);

-- Nobody can read or write this table from the app: the function uses the
-- service-role key, which is not given out to phones.
alter table public.walhad_ai_usage enable row level security;

-- How many questions were asked, per day and per model:
--   select date_trunc('day', asked_at) as day, model, count(*)
--   from public.walhad_ai_usage group by 1, 2 order by 1 desc;
