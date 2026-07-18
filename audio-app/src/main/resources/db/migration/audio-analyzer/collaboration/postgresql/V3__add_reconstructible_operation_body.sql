ALTER TABLE workflow_collaboration_operation
    ADD COLUMN operation_body_version INTEGER;

ALTER TABLE workflow_collaboration_operation
    ADD COLUMN operation_body TEXT;

ALTER TABLE workflow_collaboration_operation
    ADD COLUMN command_kind VARCHAR(32);

ALTER TABLE workflow_collaboration_operation
    ADD COLUMN command_id VARCHAR(255);

ALTER TABLE workflow_collaboration_operation
    ADD COLUMN target_operation_id VARCHAR(255);
