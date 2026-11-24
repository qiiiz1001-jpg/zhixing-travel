package cn.zhixing.travel.knowledge;

import java.time.OffsetDateTime;

public record KnowledgeDocument(
        String id,
        String ownerId,
        String filename,
        String contentType,
        String sha256,
        long sizeBytes,
        int textChars,
        int chunkCount,
        String status,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {}
