CREATE TABLE workflow_collaboration_identity (
    actor_id VARCHAR(255) NOT NULL PRIMARY KEY,
    subject_id VARCHAR(255) NOT NULL,
    display_name VARCHAR(120) NOT NULL,
    provenance VARCHAR(32) NOT NULL,
    account_handle VARCHAR(255),
    credential_hash VARCHAR(64) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);
