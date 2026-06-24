create table embedding_tasks
(
    id          bigserial    not null primary key,
    entity_type varchar(20)  not null,
    entity_id   bigint       not null,
    status      varchar(20)  not null default 'pending',
    attempts    int          not null default 0,
    created_at  timestamptz  not null default now(),
    updated_at  timestamptz  not null default now()
);

-- Fast poll: only pending rows, ordered by age
create index embedding_tasks_pending_idx
    on embedding_tasks (created_at)
    where status = 'pending';

-- Prevent double-queueing the same entity while it's still pending or in flight
create unique index embedding_tasks_active_unique
    on embedding_tasks (entity_type, entity_id)
    where status in ('pending', 'processing');
