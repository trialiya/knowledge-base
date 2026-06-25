-- Add claim_token to track ownership across concurrent poll batches and stuck-task reaper.
alter table embedding_tasks
    add column claim_token uuid;

-- Old unique index covered pending + processing; replace with pending-only so a new pending row
-- can be inserted while the same entity is in the 'starting' state.
drop index if exists embedding_tasks_active_unique;

create unique index embedding_tasks_pending_unique
    on embedding_tasks (entity_type, entity_id)
    where status = 'pending';

-- Fast NOT EXISTS probe in claimPending: skip entities already being processed.
create index embedding_tasks_starting_idx
    on embedding_tasks (entity_type, entity_id)
    where status = 'starting';
