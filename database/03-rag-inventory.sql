-- 只读盘点实际向量库；本地文档存在不代表已经入库。
SELECT count(*) AS stored_chunks FROM agent_embeddings;
SELECT coalesce(metadata->>'source', metadata->>'file_name', 'unknown') AS source,
       metadata->>'chunk_type' AS chunk_type,
       count(*) AS chunks,
       count(DISTINCT metadata->>'parent_id') AS parents
FROM agent_embeddings
GROUP BY 1, 2 ORDER BY 1, 2;

-- 私人资料只允许 READY 且 owner_id、document_id 匹配的向量参与检索。
SELECT status, count(*) AS documents
FROM travel_knowledge_document GROUP BY status ORDER BY status;
SELECT count(*) AS private_searchable_chunks
FROM agent_embeddings e JOIN travel_knowledge_document d
  ON e.metadata->>'document_id'=d.id AND e.metadata->>'owner_id'=d.owner_id
WHERE d.status='READY';
