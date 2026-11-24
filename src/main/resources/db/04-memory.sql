-- 摘要覆盖使用服务端消息序号，不用随机消息 UUID 判断先后。
ALTER TABLE message ADD COLUMN IF NOT EXISTS memory_seq BIGSERIAL;
CREATE UNIQUE INDEX IF NOT EXISTS uq_message_memory_seq ON message(memory_seq);
CREATE INDEX IF NOT EXISTS idx_message_memory_history
    ON message(conversation_id, memory_seq) WHERE is_delete=0;
-- 偏好、操作回执、会话摘要复用既有 store(namespace,key,value)，无需新建记忆表。
