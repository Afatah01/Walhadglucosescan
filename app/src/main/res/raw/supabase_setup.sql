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

-- Walhad Glucose Scan: monitoring records (alert events, sync events, sessions, health).
-- Added for app version 5.10. Run this once in the "SQL Editor" of your Supabase project,
-- after the section above. Running it again does no harm: nothing that is there is removed.

create table if not exists public.alert_events (
    user_id     uuid        not null default auth.uid() references auth.users (id) on delete cascade,
    event_id    text        not null,     -- kind + the minute of the reading; the same reading yields the same id
    kind        text        not null,     -- VERY_LOW, LOW, HIGH, VERY_HIGH, RAPID_RISE, RAPID_FALL, NO_DATA, TEST
    value_mgdl  integer,                  -- the measured value the alert is about (empty for TEST with no reading)
    measured_at timestamptz,              -- when the reading was measured
    fired_at    timestamptz not null default now(),   -- when the alert fired
    status      text        not null default 'posted',-- posted | failed | simulated
    detail      text,
    unique (user_id, event_id)
);

create table if not exists public.forecast_predictions (
    user_id     uuid        not null default auth.uid() references auth.users (id) on delete cascade,
    computed_at timestamptz not null,
    at_time     timestamptz not null,
    horizon     smallint    not null check (horizon in (15,30,60)),
    value_mgdl  real        not null,
    confidence  smallint    not null check (confidence in (1,2,3)),
    actual      real,
    error       real,
    evaluated_at timestamptz,
    unique (user_id, computed_at, horizon)
);

create table if not exists public.monitoring_sessions (
    id          bigint      generated always as identity primary key,
    user_id     uuid        not null default auth.uid() references auth.users (id) on delete cascade,
    started_at  timestamptz not null default now(),
    ended_at    timestamptz,
    source      text        not null default 'librelinkup',
    end_reason  text
);

create table if not exists public.sync_events (
    id          bigint      generated always as identity primary key,
    user_id     uuid        not null default auth.uid() references auth.users (id) on delete cascade,
    at          timestamptz not null default now(),
    kind        text        not null,     -- sync | backup | workmanager
    ok          boolean     not null default true,
    detail      text,
    client_id   text
);

create unique index if not exists sync_events_user_client_key on public.sync_events (user_id, client_id);

create table if not exists public.notification_events (
    id          bigint      generated always as identity primary key,
    user_id     uuid        not null default auth.uid() references auth.users (id) on delete cascade,
    at          timestamptz not null default now(),
    kind        text        not null,
    delivered   boolean     not null default true,
    detail      text
);

create table if not exists public.device_registrations (
    id          bigint      generated always as identity primary key,
    user_id     uuid        not null default auth.uid() references auth.users (id) on delete cascade,
    device_id   text        not null,
    app_version text,
    updated_at  timestamptz not null default now(),
    unique (user_id, device_id)
);

create table if not exists public.app_health (
    id                  bigint      generated always as identity primary key,
    user_id             uuid        not null default auth.uid() references auth.users (id) on delete cascade,
    at                  timestamptz not null default now(),
    foreground_service  boolean,
    workmanager_ok      boolean,
    linkup_state        text,
    network             text,
    detail              text
);

create table if not exists public.diagnostic_logs (
    id          bigint      generated always as identity primary key,
    user_id     uuid        not null default auth.uid() references auth.users (id) on delete cascade,
    at          timestamptz not null default now(),
    level       text        not null default 'info',
    tag         text,
    message     text
);

-- Each row belongs to the person who is logged in. Nobody else can read or change it.
alter table public.alert_events          enable row level security;
alter table public.forecast_predictions  enable row level security;
alter table public.monitoring_sessions   enable row level security;
alter table public.sync_events           enable row level security;
alter table public.notification_events   enable row level security;
alter table public.device_registrations  enable row level security;
alter table public.app_health            enable row level security;
alter table public.diagnostic_logs       enable row level security;

revoke all on table public.alert_events          from anon;
revoke all on table public.forecast_predictions  from anon;
revoke all on table public.monitoring_sessions   from anon;
revoke all on table public.sync_events           from anon;
revoke all on table public.notification_events   from anon;
revoke all on table public.device_registrations  from anon;
revoke all on table public.app_health            from anon;
revoke all on table public.diagnostic_logs       from anon;

grant select, insert, update, delete on table public.alert_events          to authenticated;
grant select, insert, update, delete on table public.forecast_predictions  to authenticated;
grant select, insert, update, delete on table public.monitoring_sessions   to authenticated;
grant select, insert, update, delete on table public.sync_events           to authenticated;
grant select, insert, update, delete on table public.notification_events   to authenticated;
grant select, insert, update, delete on table public.device_registrations  to authenticated;
grant select, insert, update, delete on table public.app_health            to authenticated;
grant select, insert, update, delete on table public.diagnostic_logs       to authenticated;

drop policy if exists "own rows" on public.alert_events;
create policy "own rows" on public.alert_events
    for all using ((select auth.uid()) = user_id) with check ((select auth.uid()) = user_id);

drop policy if exists "own rows" on public.forecast_predictions;
create policy "own rows" on public.forecast_predictions
    for all using ((select auth.uid()) = user_id) with check ((select auth.uid()) = user_id);

drop policy if exists "own rows" on public.monitoring_sessions;
create policy "own rows" on public.monitoring_sessions
    for all using ((select auth.uid()) = user_id) with check ((select auth.uid()) = user_id);

drop policy if exists "own rows" on public.sync_events;
create policy "own rows" on public.sync_events
    for all using ((select auth.uid()) = user_id) with check ((select auth.uid()) = user_id);

drop policy if exists "own rows" on public.notification_events;
create policy "own rows" on public.notification_events
    for all using ((select auth.uid()) = user_id) with check ((select auth.uid()) = user_id);

drop policy if exists "own rows" on public.device_registrations;
create policy "own rows" on public.device_registrations
    for all using ((select auth.uid()) = user_id) with check ((select auth.uid()) = user_id);

drop policy if exists "own rows" on public.app_health;
create policy "own rows" on public.app_health
    for all using ((select auth.uid()) = user_id) with check ((select auth.uid()) = user_id);

drop policy if exists "own rows" on public.diagnostic_logs;
create policy "own rows" on public.diagnostic_logs
    for all using ((select auth.uid()) = user_id) with check ((select auth.uid()) = user_id);

-- Walhad Glucose Scan 5.11: sync columns for forecasts, sessions and diagnostics.
-- Run this once in the SQL Editor after the section above. Safe to run twice.

alter table public.forecast_predictions add column if not exists input_value real;
alter table public.forecast_predictions add column if not exists input_time timestamptz;
alter table public.forecast_predictions add column if not exists model text;
alter table public.forecast_predictions add column if not exists freshness_minutes integer;
alter table public.forecast_predictions add column if not exists band text;
alter table public.forecast_predictions add column if not exists ci_half_width real;
alter table public.monitoring_sessions add column if not exists client_id text;
create unique index if not exists monitoring_sessions_user_client_key on public.monitoring_sessions (user_id, client_id);
alter table public.diagnostic_logs add column if not exists client_id text;
create unique index if not exists diagnostic_logs_user_client_key on public.diagnostic_logs (user_id, client_id);
create index if not exists forecast_predictions_user_time on public.forecast_predictions (user_id, computed_at);
create index if not exists sync_events_user_time on public.sync_events (user_id, at desc);

-- The three horizons of one prediction are unique per person, so copies may be re-sent safely.
create unique index if not exists forecast_predictions_user_computed_horizon_key on public.forecast_predictions (user_id, computed_at, horizon);
