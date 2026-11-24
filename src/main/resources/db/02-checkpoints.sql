-- LangGraph4j 1.8.0 schema, made idempotent without deleting any checkpoint.
CREATE TABLE IF NOT EXISTS lg4jthread (
    thread_id UUID PRIMARY KEY,
    thread_name VARCHAR(255),
    is_released BOOLEAN DEFAULT FALSE NOT NULL
);
CREATE TABLE IF NOT EXISTS lg4jcheckpoint (
    checkpoint_id UUID PRIMARY KEY,
    parent_checkpoint_id UUID,
    thread_id UUID NOT NULL REFERENCES lg4jthread(thread_id) ON DELETE CASCADE,
    node_id VARCHAR(255),
    next_node_id VARCHAR(255),
    state_data JSONB NOT NULL,
    state_content_type VARCHAR(100) NOT NULL,
    saved_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_lg4jcheckpoint_thread_id ON lg4jcheckpoint(thread_id);
CREATE INDEX IF NOT EXISTS idx_lg4jcheckpoint_thread_id_saved_at_desc ON lg4jcheckpoint(thread_id, saved_at DESC);
CREATE UNIQUE INDEX IF NOT EXISTS idx_unique_lg4jthread_thread_name_unreleased
    ON lg4jthread(thread_name) WHERE is_released=FALSE;
