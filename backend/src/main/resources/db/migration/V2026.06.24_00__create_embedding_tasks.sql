create table embedding_tasks
(
    id          bigserial    not null primary key,
    entity_type varchar(20)  not null,
    entity_id   bigint       not null,
    status      varchar(20)  not null default 'pending',
    attempts    int          not null default 0,
    claim_token uuid,
    created_at  timestamptz  not null default now(),
    updated_at  timestamptz  not null default now()
);

-- Fast poll: only pending rows, ordered by age
create index embedding_tasks_pending_idx
    on embedding_tasks (created_at)
    where status = 'pending';

-- At most one pending task per entity; enqueueIfAbsent relies on this via ON CONFLICT DO NOTHING.
-- Pending-only (not 'starting'), so an update arriving mid-processing can still enqueue a new task.
create unique index embedding_tasks_pending_unique
    on embedding_tasks (entity_type, entity_id)
    where status = 'pending';

-- Fast NOT EXISTS probe in claimPending: skip entities already being processed
create index embedding_tasks_starting_idx
    on embedding_tasks (entity_type, entity_id)
    where status = 'starting';
