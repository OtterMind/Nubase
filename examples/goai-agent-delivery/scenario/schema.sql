create table if not exists public.delivery_tasks (
  id uuid primary key default gen_random_uuid(),
  owner_id uuid not null references auth.users (id) on delete cascade,
  title text not null check (char_length(title) between 1 and 200),
  status text not null default 'planned' check (status in ('planned', 'building', 'verifying', 'approved', 'rejected')),
  run_id text not null,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

alter table public.delivery_tasks enable row level security;

create policy "delivery task owners can read"
  on public.delivery_tasks
  for select
  using (auth.uid() = owner_id);

create policy "delivery task owners can insert"
  on public.delivery_tasks
  for insert
  with check (auth.uid() = owner_id);

create policy "delivery task owners can update"
  on public.delivery_tasks
  for update
  using (auth.uid() = owner_id)
  with check (auth.uid() = owner_id);
