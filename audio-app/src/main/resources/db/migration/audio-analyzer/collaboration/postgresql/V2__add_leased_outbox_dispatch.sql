-- Add durable lease ownership and optimistic concurrency for at-least-once dispatch.

alter table workflow_collaboration_outbox
    add column entity_version bigint default 0 not null;
alter table workflow_collaboration_outbox
    add column lease_owner varchar(255);
alter table workflow_collaboration_outbox
    add column lease_token varchar(255);
alter table workflow_collaboration_outbox
    add column lease_expires_at timestamp(6) with time zone;

drop index idx_workflow_outbox_pending;
create index idx_workflow_outbox_pending
    on workflow_collaboration_outbox (published_at, next_attempt_at, lease_expires_at);
