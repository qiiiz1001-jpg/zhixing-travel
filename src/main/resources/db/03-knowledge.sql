CREATE TABLE IF NOT EXISTS travel_knowledge_document (
    id VARCHAR(36) PRIMARY KEY,
    owner_id VARCHAR(128) NOT NULL,
    filename VARCHAR(255) NOT NULL,
    content_type VARCHAR(255) NOT NULL,
    sha256 CHAR(64) NOT NULL,
    size_bytes BIGINT NOT NULL,
    text_chars INTEGER NOT NULL,
    chunk_count INTEGER NOT NULL DEFAULT 0,
    status VARCHAR(16) NOT NULL CHECK (status IN ('INDEXING', 'READY', 'FAILED', 'DELETED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_travel_knowledge_owner
    ON travel_knowledge_document(owner_id, status);
CREATE UNIQUE INDEX IF NOT EXISTS uq_travel_knowledge_active_hash
    ON travel_knowledge_document(owner_id, sha256) WHERE status IN ('INDEXING', 'READY');
