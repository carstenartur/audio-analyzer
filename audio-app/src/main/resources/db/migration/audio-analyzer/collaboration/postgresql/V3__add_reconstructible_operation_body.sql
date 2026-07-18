ALTER TABLE workflow_collaboration_operation
    ADD COLUMN operation_body_version INTEGER;

ALTER TABLE workflow_collaboration_operation
    ADD COLUMN operation_body TEXT;
