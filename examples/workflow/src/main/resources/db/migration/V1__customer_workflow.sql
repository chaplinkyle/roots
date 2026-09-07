CREATE TABLE workflow_customers (
    customer_id CHAR(36) PRIMARY KEY,
    company VARCHAR(120) NOT NULL,
    contact_name VARCHAR(120) NOT NULL,
    email VARCHAR(254) NOT NULL,
    company_search VARCHAR(240) NOT NULL,
    version BIGINT NOT NULL,
    created_at BIGINT NOT NULL
);
CREATE INDEX workflow_customers_page ON workflow_customers(created_at, customer_id);
CREATE INDEX workflow_customers_search ON workflow_customers(company_search);
CREATE TABLE workflow_drafts (
    draft_id CHAR(36) PRIMARY KEY,
    owner_name VARCHAR(256) NOT NULL,
    customer_id CHAR(36),
    base_version BIGINT NOT NULL,
    company VARCHAR(120) NOT NULL,
    contact_name VARCHAR(120) NOT NULL,
    email VARCHAR(254) NOT NULL,
    version BIGINT NOT NULL,
    completed BOOLEAN NOT NULL,
    updated_at BIGINT NOT NULL
);
CREATE INDEX workflow_drafts_owner ON workflow_drafts(owner_name, updated_at, draft_id);
CREATE TABLE workflow_audit (
    operation_id CHAR(36) PRIMARY KEY,
    customer_id CHAR(36) NOT NULL,
    actor VARCHAR(256) NOT NULL,
    customer_version BIGINT NOT NULL,
    committed_at BIGINT NOT NULL
);
